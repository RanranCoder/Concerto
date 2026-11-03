package com.pams.module.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.*;
import com.pams.module.agent.core.*;
import com.pams.module.agent.service.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

/** Server-owned proposals. Clients submit only an opaque handle, never a graph or a tool grant. */
@Service
public class ControlledPlanStore {
    private final JdbcTemplate db;private final ControlledPlanningService planner;private final AgentRunService runs;
    private final WorkItemState state;private final com.pams.module.agent.config.AgentProperties props;
    private final ObjectMapper json=new ObjectMapper();
    public ControlledPlanStore(JdbcTemplate db,ControlledPlanningService planner,AgentRunService runs,WorkItemState state,com.pams.module.agent.config.AgentProperties props){this.db=db;this.planner=planner;this.runs=runs;this.state=state;this.props=props;}
    public record Proposal(String planRef,ControlledPlanningService.Preview preview){}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=600000,initialDelay=600000)
    @Transactional public void purgeUnused(){db.update("DELETE FROM agent_controlled_plan WHERE run_id IS NULL AND expires_at<?",LocalDateTime.now().minusDays(1));}
    public Proposal prepare(String goal,ToolContext ctx){
        var preview=planner.preview(goal,ctx);if(!"MODEL_PLAN_VALIDATED".equals(preview.status()))return new Proposal(null,preview);
        var caps=planner.capabilities(ctx);var bb=preview.plan();
        for(var node:bb.getPlan()) {
            var step=preview.steps().stream().filter(s->s.id()==node.getId()).findFirst().orElseThrow();
            var cap=caps.get(ControlledPlanValidator.key(step.agent(),step.capability()));if(cap==null)throw new BizException(409,"能力已撤销");
            var contract=new LinkedHashMap<String,Object>();contract.put("version","controlled-node/v1");contract.put("goal",bb.getGoal());
            contract.put("capability",step.capability());contract.put("schema",cap.tool().parametersSchema());contract.put("write",cap.tool().writeOperation());
            contract.put("inputBindings",step.inputBindings());contract.put("completionCriteria",step.completionCriteria());
            contract.put("completionPolicy",cap.tool().writeOperation()?"CONFIRMED_BUSINESS_RECEIPT":"TOOL_RESULT_OK");
            contract.put("promptSha256",preview.promptSha256());contract.put("capabilitySha256",preview.capabilitySha256());
            node.setContract(Collections.unmodifiableMap(contract));
        }
        String ref=UUID.randomUUID().toString();
        db.update("INSERT INTO agent_controlled_plan(id,org_id,user_id,conversation_id,tool_mode,goal,payload_json,expires_at,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                ref,ctx.getLoginUser().getOrgId(),ctx.getUserId(),ctx.getConversationId(),ctx.getMode(),bb.getGoal(),encode(bb),LocalDateTime.now().plusMinutes(15),LocalDateTime.now());
        return new Proposal(ref,preview);
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private Map<String,Object> require(String ref,ToolContext ctx){
        if(ctx==null || ctx.getLoginUser()==null || !Objects.equals(OrgContext.get(),ctx.getLoginUser().getOrgId()))throw new BizException(403,"组织上下文无效");
        var rows=db.queryForList("SELECT * FROM agent_controlled_plan WHERE id=? AND org_id=? AND user_id=? FOR UPDATE",ref,OrgContext.get(),ctx.getUserId());
        if(rows.isEmpty())throw new BizException(404,"规划不存在或不可见");var row=rows.getFirst();
        if(!Objects.equals(row.get("conversation_id"),ctx.getConversationId()) || !Objects.equals(row.get("tool_mode"),ctx.getMode()))throw new BizException(409,"规划会话或模式不匹配");
        if(row.get("run_id")==null && AgentPreparedOperations.dateTime(row.get("expires_at")).isBefore(LocalDateTime.now()))throw new BizException(409,"规划已过期");return row;
    }
    private Blackboard decode(Map<String,Object> row){try{return json.readValue(row.get("payload_json").toString(),Blackboard.class);}catch(Exception e){throw new BizException(409,"规划数据损坏");}}
    private void enabled(ToolContext ctx){if(ctx==null||ctx.getLoginUser()==null)throw new BizException(403,"缺少登录身份");if(!props.isControlledExecutionEnabled() || !props.isControlledPlanningEnabled() || !props.getControlledPlanningOrgIds().contains(ctx.getLoginUser().getOrgId()))throw new BizException(409,"受控执行尚未在本组织启用");}
    @Transactional
    public Long start(String ref,ToolContext ctx){
        enabled(ctx);var row=require(ref,ctx);if(row.get("run_id")!=null)return ((Number)row.get("run_id")).longValue();
        var bb=decode(row);verify(bb,ctx);bb.setMode("P1");
        var run=runs.startRun(ctx.getConversationId(),ctx.getUserId(),bb.getGoal(),"P1");
        state.initialize(run.getId(),bb,ctx.withRunId(run.getId()));db.update("UPDATE agent_controlled_plan SET run_id=? WHERE id=?",run.getId(),ref);return run.getId();
    }
    private void verify(Blackboard bb,ToolContext ctx){
        var caps=planner.capabilities(ctx);
        for(var node:bb.getPlan()) {var c=node.getContract();var cap=caps.get(ControlledPlanValidator.key(node.getAgent(),c.get("capability").toString()));
            if(cap==null || cap.tool().writeOperation()!=Boolean.TRUE.equals(c.get("write")) || !json.valueToTree(cap.tool().parametersSchema()).equals(json.valueToTree(c.get("schema"))))throw new BizException(409,"规划能力或契约已变化，请重新规划");}
    }
    /** Fixed graph local replan. The changed branch is invalidated by the existing revision/token rules. */
    @Transactional
    public void replace(Long runId,Long nodeId,int revision,String ref,ToolContext ctx){
        enabled(ctx);var run=state.owned(runId,ctx.getUserId());var row=require(ref,ctx);
        if(!Objects.equals(run.getConversationId(),ctx.getConversationId())||!Objects.equals(run.getToolMode(),ctx.getMode()))throw new BizException(409,"重规划须保持原任务会话和模式");
        if(row.get("run_id")!=null)throw new BizException(409,"该规划已使用");var bb=decode(row);verify(bb,ctx);
        if(!Objects.equals(run.getGoal(),bb.getGoal()))throw new BizException(409,"局部重规划不得改变原始目标");
        var node=state.nodes(runId).stream().filter(n->n.getId().equals(nodeId)).findFirst().orElseThrow(()->new BizException(404,"节点不存在"));
        if(node.getContractJson()==null)throw new BizException(409,"规则节点不能升级为模型授权");
        var replacement=bb.getPlan().stream().filter(p->p.getId()==node.getNodeKey()).findFirst().orElseThrow(()->new BizException(409,"规划缺少该节点"));
        if(!Objects.equals(node.getAgentName(),replacement.getAgent()) || !state.dependencies(node).equals(replacement.getDependsOn()))throw new BizException(409,"局部重规划必须保留专家与依赖结构；结构变更请新建任务");
        state.reviseContract(runId,nodeId,ctx.getUserId(),revision,replacement);
        db.update("UPDATE agent_controlled_plan SET run_id=? WHERE id=?",runId,ref);
    }
}
