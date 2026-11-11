package com.pams.module.agent.service;

import com.pams.module.agent.core.*;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.stream.AgentEventPublisher;
import com.pams.module.agent.stream.AgentStreamEvent;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Persists interaction pauses and optionally completes a recoverable draft. */
@Service
public class ToolOutcomeHandler {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ToolOutcomeHandler.class);

    public static ToolResult execute(ToolRegistry registry, ToolContext ctx, LlmProvider.ToolCallRequest call) {
        try {
            return registry.dispatch(call.name(), ctx, call.arguments());
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (AccessDeniedException denied) {
            return ToolResult.denied(denied.getMessage());
        } catch (Exception failure) {
            LOG.warn("Agent tool failed: tool={} stream={}", call.name(), ctx.getStreamId(), failure);
            return ToolResult.failed(failure.getMessage() == null ? "执行异常" : failure.getMessage());
        }
    }
    public record PendingAction(AgentActionPlan plan, Map<String,Object> form, Map<String,Object> receipt) {
        public PendingAction(AgentActionPlan plan, Map<String,Object> form) { this(plan,form,null); }
        public boolean waiting() { return plan != null || form != null; }
        public String inputQuestion() {
            if(form==null)return "";
            var missing=new java.util.ArrayList<String>();
            var values=form.get("values") instanceof Map<?,?> v?v:Map.of();
            if(form.get("fields") instanceof java.util.List<?> fields)for(var raw:fields) {
                if(!(raw instanceof Map<?,?> field) || !Boolean.TRUE.equals(field.get("required")))continue;
                Object value=values.get(field.get("name"));
                if(value==null || value.toString().isBlank())missing.add(java.util.Objects.toString(field.get("label"),java.util.Objects.toString(field.get("name"),"必要信息")));
            }
            return (missing.isEmpty()?"还需要核对任务信息。":"还需要你补充："+String.join("、",missing)+"。")
                +"直接在聊天中告诉我即可，我会接着处理；也可以使用下方表单。";
        }
    }
    private final TaskGate gate;
    private final AgentEventPublisher publisher;
    public ToolOutcomeHandler(TaskGate gate, AgentEventPublisher publisher) {
        this.gate = gate;
        this.publisher = publisher;
    }
    public PendingAction prepare(ToolContext ctx, AgentTool tool, ToolResult result,
                                 LlmProvider.ToolCallRequest call) {
        if (tool instanceof PersistedInteractionTool) {
            @SuppressWarnings("unchecked") var form=result.payload().get("pendingForm") instanceof Map<?,?> value ? (Map<String,Object>)value : null;
            return new PendingAction(result.plan(),form);
        }
        if (!(tool instanceof WriteAgentTool writer)) return new PendingAction(null,null);
        PendingAction pending;
        if (result.needsInput()) {
            pending = new PendingAction(null,gate.createForm(ctx,tool,result.form(),call.arguments()));
        } else if (result.plan() != null) {
            AgentActionPlan raw = result.plan();
            pending = new PendingAction(gate.createPlan(ctx,tool,raw.summary(),raw.total(),raw.items(),
                raw.skipped(),raw.detailLines(),raw.reversible(),raw.preview()),null);
        } else return new PendingAction(null,null);
        if (pending.plan() != null && ctx.getRunId() == null && ctx.getWorkItemId() == null
                && !ctx.isReadonly() && !ctx.isKb() && pending.plan().confirmToken() != null
                && !pending.plan().confirmToken().isBlank()) {
            if (writer.saveDraftInTurn(call.arguments())) {
                return new PendingAction(null, null, gate.completeTurnDraft(pending.plan(), ctx));
            }
            var receipt = gate.completeDelegatedTurnAction(tool.name(), pending.plan(), ctx);
            if (receipt != null) return new PendingAction(null, null, receipt);
        }
        // Collaboration publishes only after its run snapshot and assistant message are durable.
        if (ctx.getRunId() == null && ctx.getStreamId() != null && ctx.getLoginUser() != null) {
            String stream = ctx.getStreamId();
            publisher.publish(ctx.getLoginUser().getUsername(), pending.plan() != null
                ? AgentStreamEvent.planReady(stream,publisher.nextSeq(stream),pending.plan().toJson())
                : AgentStreamEvent.inputNeeded(stream,publisher.nextSeq(stream),pending.form()));
        }
        return pending;
    }
}
