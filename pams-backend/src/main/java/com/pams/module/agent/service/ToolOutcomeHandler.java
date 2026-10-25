package com.pams.module.agent.service;

import com.pams.module.agent.core.*;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.stream.AgentEventPublisher;
import com.pams.module.agent.stream.AgentStreamEvent;
import org.springframework.stereotype.Service;
import java.util.Map;

/** One confirmation boundary shared by the ordinary loop and specialists. Never commits. */
@Service
public class ToolOutcomeHandler {
    public record PendingAction(AgentActionPlan plan, Map<String,Object> form) {
        public boolean waiting() { return plan != null || form != null; }
    }
    private final TaskGate gate;
    private final AgentEventPublisher publisher;
    public ToolOutcomeHandler(TaskGate gate, AgentEventPublisher publisher) {
        this.gate = gate;
        this.publisher = publisher;
    }
    public PendingAction prepare(ToolContext ctx, AgentTool tool, ToolResult result,
                                 LlmProvider.ToolCallRequest call) {
        if (!(tool instanceof WriteAgentTool)) return new PendingAction(null,null);
        PendingAction pending;
        if (result.needsInput()) {
            pending = new PendingAction(null,gate.createForm(ctx,tool,result.form(),call.arguments()));
        } else if (result.plan() != null) {
            AgentActionPlan raw = result.plan();
            pending = new PendingAction(gate.createPlan(ctx,tool,raw.summary(),raw.total(),raw.items(),
                raw.skipped(),raw.detailLines(),raw.reversible(),raw.preview()),null);
        } else return new PendingAction(null,null);
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
