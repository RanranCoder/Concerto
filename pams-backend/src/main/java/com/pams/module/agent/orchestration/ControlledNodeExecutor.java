package com.pams.module.agent.orchestration;

import com.fasterxml.jackson.databind.*;
import com.pams.common.BizException;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.AgentWorkItem;
import com.pams.module.agent.repository.*;
import com.pams.module.agent.service.*;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.registry.AgentRegistry;
import org.springframework.stereotype.Service;
import java.util.*;

/** Exactly one resolved capability per node. No executor model can invent facts or additional calls. */
@Service
public class ControlledNodeExecutor {
    @org.springframework.beans.factory.annotation.Autowired private com.pams.module.agent.config.AgentProperties properties;
    private final ToolRegistry tools;private final ToolOutcomeHandler outcomes;private final AgentRegistry agents;
    private final AgentStepRepository steps;private final AgentTaskRepository actions;private final WorkItemState state;
    private final AuditService audit;private final ObjectMapper json=AgentPreparedOperations.mapper();
    public ControlledNodeExecutor(ToolRegistry tools,ToolOutcomeHandler outcomes,AgentRegistry agents,AgentStepRepository steps,AgentTaskRepository actions,WorkItemState state,AuditService audit){this.tools=tools;this.outcomes=outcomes;this.agents=agents;this.steps=steps;this.actions=actions;this.state=state;this.audit=audit;}
    public record Execution(SpecialistAgent.Result result,String receipt){}
    public Execution execute(AgentWorkItem node,ToolContext ctx){
        if(!properties.isControlledExecutionEnabled() || !properties.isControlledPlanningEnabled() || !properties.getControlledPlanningOrgIds().contains(ctx.getLoginUser().getOrgId()))throw new BizException(409,"本组织受控执行已关闭");
        var c=ControlledBindings.tree(node.getContractJson());String name=c.path("capability").asText();
        var tool=tools.find(name).orElseThrow(()->new BizException(409,"能力已下线"));
        var agent=agents.byName(node.getAgentName()).filter(a->Integer.valueOf(1).equals(a.getEnabled())).orElseThrow(()->new BizException(409,"专家已停用"));
        var scope=agents.toolScopeOf(agent);if(scope!=null&&!scope.contains(name))throw new BizException(403,"专家能力范围已撤销");
        var receipts=new HashMap<Integer,JsonNode>();
        for(var upstream:state.nodes(node.getRunId()))if(state.dependencies(node).contains(upstream.getNodeKey())&&"SUCCESS".equals(upstream.getStatus())) {
            if(upstream.getActionTaskId()!=null){var action=actions.findById(upstream.getActionTaskId()).orElseThrow();if("SUCCESS".equals(action.getStatus())&&WorkItemState.sensitivity(action)==Sensitivity.NORMAL&&action.getResultJson()!=null)receipts.put(upstream.getNodeKey(),ControlledBindings.tree(action.getResultJson()));}
            else if(upstream.getResultJson()!=null)receipts.put(upstream.getNodeKey(),ControlledBindings.tree(upstream.getResultJson()));
        }
        var args=ControlledBindings.resolve(json.convertValue(c,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}),receipts);
        var step=steps.findByWorkItemIdAndRevisionAndAttempt(node.getId(),node.getRevision(),node.getAttempt()).orElseThrow();
        var input=(com.fasterxml.jackson.databind.node.ObjectNode)ControlledBindings.tree(step.getInputJson());input.set("resolvedArguments",json.valueToTree(args));step.setInputJson(input.toString());steps.saveAndFlush(step);
        long start=System.currentTimeMillis();ToolResult result=tools.dispatch(name,ctx,args);
        // A native FORM would let a later HTTP edit replace the immutable bound arguments.
        // Keep ordinary FORM support, but do not issue an unusable/editable form from this executor.
        if(result.needsInput())result=ToolResult.failed("受控步骤仍缺必要字段；请补充明确目标后重新规划，或使用普通办事流程填写表单。当前没有可提交的确认清单。");
        audit.log(ctx,name,args,tool.writeOperation()?"PREVIEW":"READ",tool.sensitivity(),result.status());
        var pending=outcomes.prepare(ctx,tool,result,new LlmProvider.ToolCallRequest("controlled-"+node.getId()+"-"+node.getRevision(),name,args));
        String status=pending.waiting()?pending.form()!=null?"WAITING_INPUT":"WAITING_CONFIRM":result.isOk()&&!tool.writeOperation()?"SUCCESS":"FAILED";
        String text=new PolicyGuard().egressText(tool.sensitivity(),result.summary());
        var invocation=new SpecialistAgent.ToolInvocation(name,tool.label(),result.status(),text,System.currentTimeMillis()-start);
        var outcome=new SpecialistAgent.Result(node.getAgentName(),text,List.of(invocation),status,"controlled-binding",false,tool.sensitivity(),pending.plan(),pending.form(),result.references(),List.of());
        String receipt=tool.sensitivity()==Sensitivity.NORMAL&&"SUCCESS".equals(status)?json.valueToTree(Map.of("payload",result.payload())).toString():null;
        if(receipt!=null && receipt.length()>16000)receipt=null;
        return new Execution(outcome,receipt);
    }
}
