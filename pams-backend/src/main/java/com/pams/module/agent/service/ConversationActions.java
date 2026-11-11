package com.pams.module.agent.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.BizException;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.AgentTask;
import com.pams.module.agent.repository.AgentTaskRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import java.util.*;

/** Conversation-scoped interaction adapter, reusing native task validation and authorization. */
@Service
public class ConversationActions {
    private final AgentTaskRepository tasks;
    private final TaskGate gate;
    private final ToolRegistry registry;
    private final ObjectMapper json = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    public ConversationActions(AgentTaskRepository tasks, @Lazy TaskGate gate, @Lazy ToolRegistry registry) {
        this.tasks=tasks; this.gate=gate; this.registry=registry;
    }
    private void requireContext(ToolContext ctx) {
        if(ctx==null || ctx.getUserId()==null || ctx.getConversationId()==null || ctx.getLoginUser()==null)
            throw new BizException(403,"需要当前用户的会话上下文");
    }
    private boolean visible(AgentTask task, ToolContext ctx) {
        return Objects.equals(task.getConversationId(),ctx.getConversationId())
            && "NORMAL".equalsIgnoreCase(task.getDataSensitivity())
            && Objects.equals(task.getUserId(),ctx.getUserId())
            && Objects.equals(task.getOrgId(),ctx.getLoginUser().getOrgId())
            && registry.find(task.getToolName()).map(t->registry.visibleTo(t,ctx)).orElse(false);
    }
    public ToolResult list(ToolContext ctx) {
        requireContext(ctx);
        var page=tasks.findByConversationIdAndUserIdAndStatusInOrderByIdDesc(ctx.getConversationId(),ctx.getUserId(),
            List.of("WAITING_INPUT","PENDING_CONFIRM","REVIEW_REQUIRED"),PageRequest.of(0,20));
        var rows=new ArrayList<Map<String,Object>>();
        for(var task:page) {
            if(!visible(task,ctx) || gate.isExpired(task))continue;
            var row=new LinkedHashMap<String,Object>();
            row.put("taskId",task.getId());row.put("tool",task.getToolName());row.put("status",task.getStatus());row.put("summary",task.getSummary());
            var detail=gate.detail(task.getId(),ctx.getUserId());
            if(detail.get("form") instanceof Map<?,?> form) {
                row.put("fields",form.get("fields"));row.put("values",form.get("values"));
            }
            rows.add(row);
        }
        return ToolResult.ok("当前会话待处理事项；补充信息可通过聊天继续，预览不代表业务已提交。",
            Map.of("tasks",rows,"hasMore",page.hasNext(),"complete",!page.hasNext()));
    }
    public ToolResult supply(ToolContext ctx,Long taskId,Map<String,Object> values) {
        requireContext(ctx);
        if(ctx.isReadonly() || ctx.isKb() || ctx.getWorkItemId()!=null)throw new BizException(403,"仅会话主助手可补充当前任务");
        var task=tasks.findByIdAndUserId(taskId,ctx.getUserId()).orElseThrow(()->new BizException(404,"任务不存在或不可见"));
        if(!visible(task,ctx))throw new BizException(403,"任务不属于当前会话或当前权限已变化");
        if(!AgentTask.WAITING_INPUT.equals(task.getStatus()) || gate.isExpired(task))throw new BizException(409,"任务已变化，请重新查询当前待处理事项");
        var detail=gate.detail(taskId,ctx.getUserId());
        var allowed=new HashSet<String>();
        if(detail.get("form") instanceof Map<?,?> form && form.get("fields") instanceof List<?> fields)
            for(var field:fields)if(field instanceof Map<?,?> f && f.get("name") instanceof String name)allowed.add(name);
        if(values==null || values.isEmpty() || !allowed.containsAll(values.keySet()))throw new BizException(400,"只能补充该任务声明的输入字段");
        var result=gate.submitInput(taskId,values,ctx);
        if("PLAN".equals(result.get("status")))return ToolResult.plan("已补齐信息并更新同一任务的预览，尚未提交业务。",
            json.convertValue(result.get("plan"),AgentActionPlan.class));
        if("FORM".equals(result.get("status")))return new ToolResult("FORM","仍有信息需要补充。",List.of(),null,
            Map.of("pendingForm",result.get("form")),null,null);
        if("ERROR".equals(result.get("status")))return ToolResult.failed(Objects.toString(result.get("error"),"输入校验未通过"));
        return ToolResult.ok(Objects.toString(result.get("message"),"该待处理任务已结束，未提交业务。"));
    }
}
