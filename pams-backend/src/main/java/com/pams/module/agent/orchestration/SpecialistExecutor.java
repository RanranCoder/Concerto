package com.pams.module.agent.orchestration;

import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.entity.AgentRun;
import com.pams.module.agent.entity.AgentStep;
import com.pams.module.agent.registry.AgentCatalog;
import com.pams.module.agent.service.AgentLlmCaller;
import com.pams.module.agent.stream.AgentEventPublisher;
import com.pams.module.agent.stream.AgentStreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 协作计划的逐步执行器（C 批次）。
 *
 * <p>按 P1 串行流水线执行黑板上的计划：每个计划项委派给对应专家 Agent，
 * 落 {@code agent_step}、写黑板、推 {@code AGENT_START/AGENT_END/AGENT_DELEGATE/REVIEW_RESULT}。
 * <b>审查专家不调用业务工具</b>，只依据黑板给出 PASS/REVISE。
 */
@Slf4j
@Service
public class SpecialistExecutor {

    private final SpecialistAgent specialist;
    private final AgentRunService runService;
    private final AgentEventPublisher publisher;

    public SpecialistExecutor(SpecialistAgent specialist,
                              AgentRunService runService,
                              AgentEventPublisher publisher) {
        this.specialist = specialist;
        this.runService = runService;
        this.publisher = publisher;
    }

    /** 执行黑板计划，返回执行后的黑板。 */
    public Blackboard executePlan(AgentRun run, Blackboard bb, ToolContext ctx, String streamId) {
        String username = ctx != null && ctx.getLoginUser() != null
                ? ctx.getLoginUser().getUsername() : null;
        for (Blackboard.PlanItem item : bb.getPlan()) {
            String agent = item.getAgent();
            emit(username, streamId, AgentStreamEvent.agentDelegate(
                    streamId, publisher.nextSeq(streamId), AgentCatalog.COORDINATOR, agent, item.getTask()));
            emit(username, streamId, AgentStreamEvent.agentStart(
                    streamId, publisher.nextSeq(streamId), agent, null, null));

            long t0 = System.currentTimeMillis();
            String status = AgentStep.STATUS_SUCCESS;
            String output;
            try {
                if (AgentCatalog.REVIEWER.equals(agent)) {
                    output = runReviewer(bb, streamId, username);
                } else {
                    SpecialistAgent.Result res = specialist.execute(agent, item.getTask(), ctx, null, streamId, username);
                    output = runService.toJson(res);
                    bb.addFinding(agent, summarize(res), 0.8);
                    if (res.invocations() != null) {
                        for (SpecialistAgent.ToolInvocation inv : res.invocations()) {
                            if ("OK".equals(inv.status())) {
                                Blackboard.Artifact a = new Blackboard.Artifact();
                                a.setAgent(agent);
                                a.setType(inv.name());
                                a.setContentRef(inv.label());
                                bb.getArtifacts().add(a);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                status = AgentStep.STATUS_FAILED;
                log.warn("专家 Agent 执行失败：agent={} task={}", agent, item.getTask(), e);
                output = runService.toJson(Map.of("error", String.valueOf(e.getMessage())));
            }
            long duration = System.currentTimeMillis() - t0;

            AgentStep step = runService.recordStep(run.getId(), agent, runService.nextSeq(run.getId()), null,
                    runService.toJson(Map.of("task", item.getTask())), output, status, duration);
            emit(username, streamId, AgentStreamEvent.agentEnd(
                    streamId, publisher.nextSeq(streamId), agent, step.getId(), status, duration));
        }
        return bb;
    }

    /** 审查专家：不调工具，依据黑板证据给出 PASS/REVISE。 */
    private String runReviewer(Blackboard bb, String streamId, String username) {
        boolean hasEvidence = !bb.getFindings().isEmpty();
        String verdict = hasEvidence ? "PASS" : "REVISE";
        String reason = hasEvidence
                ? "已核对 " + bb.getFindings().size() + " 条证据，未发现明显问题"
                : "缺少证据，无法通过审查";
        bb.addDecision(AgentCatalog.REVIEWER, verdict, reason);
        emit(username, streamId, AgentStreamEvent.reviewResult(
                streamId, publisher.nextSeq(streamId), verdict, reason, 1));
        return runService.toJson(Map.of("verdict", verdict, "reason", reason));
    }

    private String summarize(SpecialistAgent.Result res) {
        if (res.text() != null && !res.text().isBlank()) {
            return res.text();
        }
        if (res.invocations() == null || res.invocations().isEmpty()) {
            return "（" + res.agent() + " 未产生工具调用）";
        }
        StringBuilder sb = new StringBuilder();
        for (SpecialistAgent.ToolInvocation inv : res.invocations()) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(inv.label()).append('[').append(inv.status()).append(']');
        }
        return sb.toString();
    }

    private void emit(String username, String streamId, AgentStreamEvent e) {
        if (streamId != null && username != null) {
            publisher.publish(username, e);
        }
    }
}
