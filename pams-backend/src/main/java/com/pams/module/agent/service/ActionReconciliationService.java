package com.pams.module.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.BizException;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.AgentTask;
import com.pams.module.agent.repository.AgentTaskRepository;
import com.pams.module.agent.orchestration.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

/** Reconcile receipts, never retry writes. Unsupported or absent receipts stay explicitly unknown. */
@Service
public class ActionReconciliationService {
    private final AgentTaskRepository tasks;
    private final ToolRegistry registry;
    private final TaskGate gate;
    private final PlanRevisionService revisions;
    private final AgentRunService runs;
    private final ObjectMapper json=new ObjectMapper();
    @org.springframework.beans.factory.annotation.Autowired private TaskProgressActionService progress;
    @org.springframework.beans.factory.annotation.Autowired private AgentTaskPlanService taskPlans;
    @org.springframework.beans.factory.annotation.Autowired private AgentNotificationActionService notifications;
    @org.springframework.beans.factory.annotation.Autowired private AgentEditorialService editorial;
    @org.springframework.beans.factory.annotation.Autowired private AgentSchedulePublicationService publications;
    private boolean supported(String tool){return AgentSchedulePublicationService.KIND.equals(tool) || AgentEditorialService.WRITES.contains(tool) || Set.of("preparePlanRevision","prepareTaskProgressUpdate","prepareTaskPlanChange","sendNotification","generateActivityTasks").contains(tool);}
    private Optional<WriteAgentTool.CommitResult> receipt(String tool,ToolContext ctx,AgentActionPlan action) throws Exception {
        if(AgentEditorialService.WRITES.contains(tool))return editorial.receipt(tool,ctx,action);
        if(AgentSchedulePublicationService.KIND.equals(tool))return publications.receipt(ctx,action);
        if(Set.of("sendNotification","generateActivityTasks").contains(tool)) {
            boolean modern=false;for(String line:action.detailLines())if(line.startsWith("__args__:") && json.readTree(line.substring(9)).has("operationId"))modern=true;
            if(!modern)return Optional.empty();
        }
        return switch(tool){case "prepareTaskProgressUpdate"->progress.confirmedReceipt(ctx,action);case "prepareTaskPlanChange","generateActivityTasks"->taskPlans.confirmedReceipt(ctx,action,tool);case "sendNotification"->notifications.confirmedReceipt(ctx,action);default->revisions.confirmedReceipt(ctx,action);};
    }
    public ActionReconciliationService(AgentTaskRepository tasks,ToolRegistry registry,TaskGate gate,PlanRevisionService revisions,AgentRunService runs) {
        this.tasks=tasks;this.registry=registry;this.gate=gate;this.revisions=revisions;this.runs=runs;
    }
    @Transactional
    public Map<String,Object> reconcile(Long id,ToolContext ctx) throws Exception {
        var task=tasks.findByIdAndUserId(id,ctx.getUserId()).filter(t->ctx.getLoginUser()!=null && Objects.equals(t.getOrgId(),ctx.getLoginUser().getOrgId()))
                .orElseThrow(()->new BizException(404,"任务不存在或不可见"));
        var tool=registry.find(task.getToolName()).orElseThrow(()->new BizException(409,"工具已下线，需人工核对"));
        if(!registry.visibleTo(tool,ctx)) throw new BizException(403,"当前权限或模式不允许核对该操作");
        if(AgentTask.SUCCESS.equals(task.getStatus())) {
            if(supported(task.getToolName()) && receipt(task.getToolName(),ctx.derive(task.getConversationId(),task.getStreamId()),gate.readPlan(task)).isEmpty())
                return response(task,false,"任务保存过成功结果，但当前业务回执无法核实，请人工核对");
            return response(task,true,"已保存实际成功回执");
        }
        boolean interrupted=AgentTask.RUNNING.equals(task.getStatus()) && task.getConfirmedAt()!=null
                && task.getConfirmedAt().isBefore(RuntimeIdentity.STARTED) && !RuntimeIdentity.ID.equals(task.getExecutionOwner());
        if(!"RESULT_UNKNOWN".equals(task.getStatus()) && !interrupted) throw new BizException(409,"仅可核对结果未知或前次进程中断的已提交动作");
        if(interrupted){
            if(tasks.markInterruptedUnknown(id,ctx.getUserId(),task.getRowVersion())!=1)throw new BizException(409,"任务状态已变化，请刷新后核对");
            task=tasks.findByIdAndUserId(id,ctx.getUserId()).orElseThrow();
        }
        if(!supported(task.getToolName())) return response(task,false,"该业务尚无可靠回执适配器，请在原生业务页面核对；不会重做操作");
        var receipt=receipt(task.getToolName(),ctx.derive(task.getConversationId(),task.getStreamId()),gate.readPlan(task));
        if(receipt.isEmpty()) return response(task,false,"尚未查到可证明成功的业务回执；保持待核对，不推断未执行，不重复提交");
        var result=receipt.get();
        var payload=new LinkedHashMap<String,Object>();payload.put("status","SUCCESS");payload.put("affectedCount",result.affectedCount());
        payload.put("failedCount",result.failedCount());payload.put("message",result.message());payload.put("detail",result.detail());
        payload.put("references",result.references());payload.put("skipped",gate.readPlan(task).skipped());
        int changed=tasks.reconcileSuccess(id,ctx.getUserId(),task.getStatus(),task.getRowVersion(),json.writeValueAsString(payload),LocalDateTime.now());
        var fresh=tasks.findByIdAndUserId(id,ctx.getUserId()).orElseThrow();
        if(changed==1 && fresh.getRunId()!=null) runs.actionSettled(fresh,"SUCCESS");
        return response(fresh,AgentTask.SUCCESS.equals(fresh.getStatus()),changed==1?"已核对实际成功回执；下游步骤将按权限与依赖条件接续":"任务状态已变化，已返回最新结果");
    }
    private Map<String,Object> response(AgentTask task,boolean verified,String message) {
        var out=new LinkedHashMap<>(gate.detail(task.getId(),task.getUserId()));
        out.put("reconciled",verified);out.put("reconciliationState",verified?"VERIFIED_SUCCESS":"RESULT_UNKNOWN");out.put("reconciliationMessage",message);
        return out;
    }
}
