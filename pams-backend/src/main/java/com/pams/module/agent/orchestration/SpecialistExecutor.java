package com.pams.module.agent.orchestration;

import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.entity.AgentRun;
import com.pams.module.agent.entity.AgentStep;
import com.pams.module.agent.registry.AgentCatalog;
import com.pams.module.agent.stream.AgentEventPublisher;
import com.pams.module.agent.stream.AgentStreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 协作计划执行器（C 批次：P1 串行；D 批次：P2 并行 / P3 反思 / P4 委派）。
 *
 * <p>四种协作模式（《策划》§3.3）：
 * <ul>
 *   <li><b>P1 串行流水线</b>：按计划顺序逐步委派。</li>
 *   <li><b>P2 并行汇聚</b>：产出型专家并行执行，再串行审查（map-reduce）。</li>
 *   <li><b>P3 反思循环</b>：产出 → 审查；若 REVISE 则修订并复审，最多 {@link #MAX_REFLECTION_ROUNDS} 轮。</li>
 *   <li><b>P4 委派-汇报</b>：首位专家为「牵头」，其余产出步骤作为其子步骤（{@code parentStepId}）。</li>
 * </ul>
 *
 * <p>审查专家不调用业务工具，只依据黑板的「产物」给出 PASS/REVISE。
 */
@Slf4j
@Service
public class SpecialistExecutor {

    /** 反思回路最大轮数（收敛即止）。 */
    public static final int MAX_REFLECTION_ROUNDS = 3;

    private final SpecialistAgent specialist;
    private final AgentRunService runService;
    private final AgentEventPublisher publisher;
    private final Executor executor;

    public SpecialistExecutor(SpecialistAgent specialist,
                              AgentRunService runService,
                              AgentEventPublisher publisher,
                              @Qualifier("agentExecutor") Executor executor) {
        this.specialist = specialist;
        this.runService = runService;
        this.publisher = publisher;
        this.executor = executor;
    }

    /** 产出步骤结果（含步骤与专家结果，供 P4 取 parentStepId）。 */
    private record ProducerOutcome(AgentStep step, SpecialistAgent.Result res) {
    }

    private record ReviewOutcome(AgentStep step, String verdict, String reason) {
    }

    /** 执行黑板计划（按模式），返回执行后的黑板。 */
    public Blackboard executePlan(AgentRun run, Blackboard bb, ToolContext ctx, String streamId, String mode) {
        String username = ctx != null && ctx.getLoginUser() != null
                ? ctx.getLoginUser().getUsername() : null;
        String m = mode == null ? "P1" : mode.trim().toUpperCase();

        List<Blackboard.PlanItem> producers = new ArrayList<>();
        List<Blackboard.PlanItem> reviewers = new ArrayList<>();
        for (Blackboard.PlanItem item : bb.getPlan()) {
            (AgentCatalog.REVIEWER.equals(item.getAgent()) ? reviewers : producers).add(item);
        }
        AtomicInteger seq = new AtomicInteger(1);

        switch (m) {
            case "P2" -> runParallel(run, bb, producers, reviewers, ctx, streamId, username, seq);
            case "P3" -> runReflection(run, bb, producers, reviewers, ctx, streamId, username, seq);
            case "P4" -> runDelegation(run, bb, producers, reviewers, ctx, streamId, username, seq);
            default -> {
                // P1：保持计划原始顺序
                for (Blackboard.PlanItem item : bb.getPlan()) {
                    if (AgentCatalog.REVIEWER.equals(item.getAgent())) {
                        applyReview(bb, runReviewerStep(run, bb, item, ctx, streamId, username, seq, null));
                    } else {
                        applyProducer(bb, runProducerStep(run, bb, item, ctx, streamId, username, seq, null));
                    }
                }
            }
        }
        return bb;
    }

    // ---------------- P2 并行汇聚 ----------------

    private void runParallel(AgentRun run, Blackboard bb, List<Blackboard.PlanItem> producers,
                             List<Blackboard.PlanItem> reviewers, ToolContext ctx, String streamId,
                             String username, AtomicInteger seq) {
        List<CompletableFuture<ProducerOutcome>> futures = new ArrayList<>();
        for (Blackboard.PlanItem item : producers) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> runProducerStep(run, null, item, ctx, streamId, username, seq, null), executor));
        }
        for (CompletableFuture<ProducerOutcome> f : futures) {
            try {
                ProducerOutcome out = f.join();
                applyProducer(bb, out);
            } catch (Exception e) {
                log.warn("P2 并行子任务失败", e);
            }
        }
        for (Blackboard.PlanItem item : reviewers) {
            ReviewOutcome ro = runReviewerStep(run, null, item, ctx, streamId, username, seq, null);
            applyReview(bb, ro);
        }
    }

    // ---------------- P3 反思循环 ----------------

    private void runReflection(AgentRun run, Blackboard bb, List<Blackboard.PlanItem> producers,
                               List<Blackboard.PlanItem> reviewers, ToolContext ctx, String streamId,
                               String username, AtomicInteger seq) {
        Long lastProducerStep = null;
        for (Blackboard.PlanItem item : producers) {
            lastProducerStep = applyProducer(bb,
                    runProducerStep(run, bb, item, ctx, streamId, username, seq, null)).step().getId();
        }
        if (reviewers.isEmpty()) {
            return;
        }
        for (int round = 1; round <= MAX_REFLECTION_ROUNDS; round++) {
            ReviewOutcome ro = runReviewerStep(run, bb, reviewers.get(0), ctx, streamId, username, seq, null);
            applyReview(bb, ro);
            if ("PASS".equals(ro.verdict())) {
                return;
            }
            if (round == MAX_REFLECTION_ROUNDS || producers.isEmpty()) {
                return;
            }
            // 修订：重跑最后一个产出步骤（parentStepId 指向上一轮审查步骤，形成回路）
            Blackboard.PlanItem revised = producers.get(producers.size() - 1);
            lastProducerStep = applyProducer(bb,
                    runProducerStep(run, bb, revised, ctx, streamId, username, seq, ro.step().getId())).step().getId();
            log.debug("P3 反思回路第 {} 轮：重跑 {}（步骤 {}）", round, revised.getAgent(), lastProducerStep);
        }
    }

    // ---------------- P4 委派-汇报 ----------------

    private void runDelegation(AgentRun run, Blackboard bb, List<Blackboard.PlanItem> producers,
                               List<Blackboard.PlanItem> reviewers, ToolContext ctx, String streamId,
                               String username, AtomicInteger seq) {
        Long leadStep = null;
        for (int i = 0; i < producers.size(); i++) {
            Blackboard.PlanItem item = producers.get(i);
            Long parent = i == 0 ? null : leadStep;
            ProducerOutcome out = runProducerStep(run, bb, item, ctx, streamId, username, seq, parent);
            applyProducer(bb, out);
            if (i == 0) {
                leadStep = out.step().getId();
            } else if (leadStep != null) {
                emit(username, streamId, AgentStreamEvent.agentDelegate(
                        streamId, publisher.nextSeq(streamId), producers.get(0).getAgent(),
                        item.getAgent(), item.getTask()));
            }
        }
        for (Blackboard.PlanItem item : reviewers) {
            applyReview(bb, runReviewerStep(run, bb, item, ctx, streamId, username, seq, leadStep));
        }
    }

    // ---------------- 单步执行 ----------------

    /**
     * 执行一个产出型专家步骤：委派事件 + 专家执行 + 落 step + 结束事件。
     *
     * @param bb 传 null 表示不在此处改黑板（并行模式下由调用方汇总后再改）
     */
    private ProducerOutcome runProducerStep(AgentRun run, Blackboard bb, Blackboard.PlanItem item,
                                            ToolContext ctx, String streamId, String username,
                                            AtomicInteger seq, Long parentStepId) {
        String agent = item.getAgent();
        emit(username, streamId, AgentStreamEvent.agentDelegate(
                streamId, publisher.nextSeq(streamId), AgentCatalog.COORDINATOR, agent, item.getTask()));
        emit(username, streamId, AgentStreamEvent.agentStart(
                streamId, publisher.nextSeq(streamId), agent, null, parentStepId));

        long t0 = System.currentTimeMillis();
        String status = AgentStep.STATUS_SUCCESS;
        String output;
        SpecialistAgent.Result res;
        try {
            res = specialist.execute(agent, item.getTask(), ctx, null, streamId, username);
            output = runService.toJson(res);
        } catch (Exception e) {
            status = AgentStep.STATUS_FAILED;
            log.warn("专家 Agent 执行失败：agent={} task={}", agent, item.getTask(), e);
            output = runService.toJson(Map.of("error", String.valueOf(e.getMessage())));
            res = new SpecialistAgent.Result(agent, null, List.of());
        }
        long duration = System.currentTimeMillis() - t0;
        AgentStep step = runService.recordStep(run.getId(), agent, seq.getAndIncrement(), parentStepId,
                runService.toJson(Map.of("task", item.getTask())), output, status, duration);
        emit(username, streamId, AgentStreamEvent.agentEnd(
                streamId, publisher.nextSeq(streamId), agent, step.getId(), status, duration));
        return new ProducerOutcome(step, res);
    }

    private ReviewOutcome runReviewerStep(AgentRun run, Blackboard bb, Blackboard.PlanItem item,
                                          ToolContext ctx, String streamId, String username,
                                          AtomicInteger seq, Long parentStepId) {
        String agent = item.getAgent();
        emit(username, streamId, AgentStreamEvent.agentDelegate(
                streamId, publisher.nextSeq(streamId), AgentCatalog.COORDINATOR, agent, item.getTask()));
        emit(username, streamId, AgentStreamEvent.agentStart(
                streamId, publisher.nextSeq(streamId), agent, null, parentStepId));

        long t0 = System.currentTimeMillis();
        boolean hasArtifact = bb != null && !bb.getArtifacts().isEmpty();
        String verdict = hasArtifact ? "PASS" : "REVISE";
        String reason = hasArtifact ? "产物完整，审查通过" : "尚无可用产物，需修订";
        long duration = System.currentTimeMillis() - t0;
        AgentStep step = runService.recordStep(run.getId(), agent, seq.getAndIncrement(), parentStepId,
                runService.toJson(Map.of("task", item.getTask())),
                runService.toJson(Map.of("verdict", verdict, "reason", reason)),
                AgentStep.STATUS_SUCCESS, duration);
        emit(username, streamId, AgentStreamEvent.reviewResult(
                streamId, publisher.nextSeq(streamId), verdict, reason, 1));
        emit(username, streamId, AgentStreamEvent.agentEnd(
                streamId, publisher.nextSeq(streamId), agent, step.getId(), AgentStep.STATUS_SUCCESS, duration));
        return new ReviewOutcome(step, verdict, reason);
    }

    private ProducerOutcome applyProducer(Blackboard bb, ProducerOutcome out) {
        if (bb == null) {
            return out;
        }
        synchronized (bb) {
            bb.addFinding(out.res().agent(), summarize(out.res()), 0.8);
            if (out.res().invocations() != null) {
                for (SpecialistAgent.ToolInvocation inv : out.res().invocations()) {
                    if ("OK".equals(inv.status())) {
                        Blackboard.Artifact a = new Blackboard.Artifact();
                        a.setAgent(out.res().agent());
                        a.setType(inv.name());
                        a.setContentRef(inv.label());
                        bb.getArtifacts().add(a);
                    }
                }
            }
        }
        return out;
    }

    private void applyReview(Blackboard bb, ReviewOutcome ro) {
        if (bb == null) {
            return;
        }
        synchronized (bb) {
            bb.addDecision(AgentCatalog.REVIEWER, ro.verdict(), ro.reason());
        }
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
