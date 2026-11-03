package com.pams.module.agent.service;

import com.pams.common.BizException;
import com.pams.module.activity.entity.*;
import com.pams.module.activity.repository.*;
import com.pams.module.activity.service.TaskService;
import com.pams.module.agent.core.*;
import com.pams.module.agent.repository.AgentTaskRepository;
import com.pams.module.agent.tool.ToolArgs;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class TaskProgressActionService {
    private final TaskRepository tasks;
    private final ActivityRepository activities;
    private final TaskService service;
    private final AgentTaskRepository actions;
    private final JdbcTemplate db;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
    public TaskProgressActionService(TaskRepository tasks,ActivityRepository activities,TaskService service,AgentTaskRepository actions,JdbcTemplate db) {
        this.tasks=tasks;this.activities=activities;this.service=service;this.actions=actions;this.db=db;
    }
    private Task permitted(Long id,ToolContext ctx) {
        return permitted(id,ctx,true);
    }
    private Task permitted(Long id,ToolContext ctx,boolean write) {
        if(ctx==null || ctx.getLoginUser()==null || ctx.isReadonly() || ctx.isKb() || !ctx.hasAnyPermission("task:progress","task:manage"))
            throw new BizException(403,"无权修改任务进度");
        var task=tasks.findById(id).filter(t->Objects.equals(t.getOrgId(),ctx.getLoginUser().getOrgId()))
                .orElseThrow(()->new BizException(404,"任务不存在或不可见"));
        if(!ctx.hasPermission("task:manage") && !Objects.equals(task.getAssigneeUserId(),ctx.getUserId())) throw new BizException(403,"仅可修改本人负责的任务");
        var activity=activities.findById(task.getActivityId()).filter(a->Objects.equals(a.getOrgId(),ctx.getLoginUser().getOrgId()))
                .orElseThrow(()->new BizException(404,"活动不存在或不可见"));
        if(write && activity.getStatus()==ActivityStatus.ARCHIVED) throw new BizException(409,"活动已归档，任务只读");
        return task;
    }
    @Transactional
    public ToolResult prepare(ToolContext ctx,Map<String,Object> args) {
        Long id=integer(args,"taskId"),revision=integer(args,"expectedRevision"),progress=integer(args,"progress");
        if(id==null || id<=0 || revision==null || revision<0 || progress==null || progress<0 || progress>100)
            return ToolResult.failed("需要明确 taskId、expectedRevision 和0–100整数 progress");
        var task=permitted(id,ctx);
        if(!Objects.equals(revision,task.getRevision())) return ToolResult.failed("STALE_BASELINE：任务已变化，请重新读取");
        if(Objects.equals(task.getProgress(),progress.intValue())) return ToolResult.failed("进度没有变化，无需提交");
        String operation=UUID.randomUUID().toString();
        db.update("INSERT INTO agent_task_progress_operation(id,org_id,user_id,business_task_id,baseline_revision,before_progress,after_progress,created_at) VALUES(?,?,?,?,?,?,?,?)",
                operation,ctx.getLoginUser().getOrgId(),ctx.getUserId(),id,revision,task.getProgress(),progress.intValue(),LocalDateTime.now());
        String change=Objects.toString(task.getProgress(),"未设置")+"% → "+progress+"%";
        return ToolResult.plan("已准备任务进度修改，尚未写入。",new AgentActionPlan(null,"任务「"+task.getName()+"」进度修改",1,
                List.of(new AgentActionPlan.PlanItem(id,task.getName(),change)),List.of(),
                List.of("任务ID："+id+"；活动ID："+task.getActivityId()+"；基线revision："+revision,
                        "仅修改进度；业务状态由现有规则计算，不改负责人、时间或依赖。","__args__:{\"operationId\":\""+operation+"\"}"),false,null,
                "任务ID："+id+"\n\n进度："+change+"\n\n确认后保存；状态由业务规则更新，相关通知沿用业务服务。"));
    }
    private Long integer(Map<String,Object> args,String key) {
        String value=ToolArgs.str(args,key);
        if(value==null || !value.matches("[0-9]{1,18}")) return null;
        try{return Long.valueOf(value);}catch(NumberFormatException e){return null;}
    }
    private Map<String,Object> operation(ToolContext ctx,AgentActionPlan action,boolean lock) throws Exception {
        String id=null;
        for(String line:action.detailLines()) if(line.startsWith("__args__:")) id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(line.substring(9)).path("operationId").asText();
        if(id==null || !id.matches("[a-f0-9-]{36}")) throw new BizException(409,"进度操作标识无效");
        var rows=db.queryForList("SELECT * FROM agent_task_progress_operation WHERE id=? AND org_id=? AND user_id=?"+(lock?" FOR UPDATE":""),id,ctx.getLoginUser().getOrgId(),ctx.getUserId());
        if(rows.isEmpty()) throw new BizException(404,"进度操作不存在或不可见");return rows.getFirst();
    }
    @Transactional(rollbackFor=Exception.class)
    public WriteAgentTool.CommitResult commit(ToolContext ctx,AgentActionPlan action) throws Exception {
        if(ctx==null || ctx.getLoginUser()==null || action==null || action.planId()==null) throw new BizException(403,"必须通过确认门禁提交");
        var pending=actions.findByIdAndUserId(action.planId(),ctx.getUserId()).orElseThrow(()->new BizException(404,"确认任务不可见"));
        if(!"prepareTaskProgressUpdate".equals(pending.getToolName()) || !"RUNNING".equals(pending.getStatus()) || !Objects.equals(pending.getOrgId(),ctx.getLoginUser().getOrgId())) throw new BizException(403,"确认任务状态无效");
        var op=operation(ctx,action,true);Long id=((Number)op.get("business_task_id")).longValue();var task=permitted(id,ctx);
        if(op.get("result_revision")!=null) return receipt(op,task);
        var locked=activities.lockById(task.getActivityId()).orElseThrow();em.refresh(locked);em.refresh(task);permitted(id,ctx);
        Long baseline=((Number)op.get("baseline_revision")).longValue();
        if(!Objects.equals(baseline,task.getRevision())) throw new BizException(409,"STALE_BASELINE：任务已变化，请重新预览");
        var saved=service.updateProgress(id,((Number)op.get("after_progress")).intValue(),baseline);
        db.update("UPDATE agent_task_progress_operation SET result_revision=? WHERE id=?",saved.getRevision(),op.get("id"));
        op.put("result_revision",saved.getRevision());return receipt(op,saved);
    }
    @Transactional(readOnly=true)
    public Optional<WriteAgentTool.CommitResult> confirmedReceipt(ToolContext ctx,AgentActionPlan action) throws Exception {
        var op=operation(ctx,action,false);var task=permitted(((Number)op.get("business_task_id")).longValue(),ctx,false);
        return op.get("result_revision")==null?Optional.empty():Optional.of(receipt(op,task));
    }
    private WriteAgentTool.CommitResult receipt(Map<String,Object> op,Task task) {
        return new WriteAgentTool.CommitResult("SUCCESS",1,0,"已保存任务进度；回执记录本次操作，当前进度请重新查询。",
                ToolArgs.map("taskId",task.getId(),"activityId",task.getActivityId(),"progress",op.get("after_progress"),"revision",op.get("result_revision"),"operationId",op.get("id")),
                List.of(ToolArgs.map("type","task","id",task.getId(),"label",task.getName(),"link","/activities/"+task.getActivityId()+"?tab=tasks")));
    }
}
