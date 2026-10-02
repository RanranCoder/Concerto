package com.pams.module.agent.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.entity.AgentBenchmark;
import com.pams.module.agent.entity.AgentRun;
import com.pams.module.agent.entity.AgentStep;
import com.pams.module.agent.registry.AgentCatalog;
import com.pams.module.agent.repository.AgentBenchmarkRepository;
import com.pams.module.agent.stream.AgentEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对比评估实验（E 批次）：单 Agent vs 多 Agent 协作。
 *
 * <p><b>实验设计</b>：自变量 = 是否拆分协作；因变量 = 完成率 / 步骤数 / 参与 Agent 数 /
 * 工具调用数 / 审查通过率 / 耗时。两条臂共用同一引擎（{@link SpecialistAgent} +
 * {@code ToolRegistry} + LLM），唯一变量是「单 Agent 一把梭」vs「多 Agent 分工」。
 *
 * <p>单 Agent 臂 = {@link AgentCatalog#GENERALIST}（工具不限）；
 * 多 Agent 臂 = {@link CoordinatorAgent} 规划 + 专家 + 审查。
 *
 * <p>⚠️ 离线（Stub 无 Key）下专家不产出工具调用，内容质量类指标退化；
 * 真实对比需在有 LLM Key 的环境运行。
 */
@Slf4j
@Service
public class BenchmarkService {

    /** 内置任务集（覆盖检索 / 策划 / 文书 / 排班 / 事务五类意图）。 */
    public record TaskDef(String code, String text) {
    }

    public static final List<TaskDef> TASKS = List.of(
            new TaskDef("T1_QUERY", "查一下最近有哪些活动，各自进度怎么样"),
            new TaskDef("T2_PLAN", "为迎新晚会做一份活动策划方案"),
            new TaskDef("T3_WRITE", "给迎新晚会写一篇报道推文"),
            new TaskDef("T4_SCHEDULE", "为控烟值班生成排班"),
            new TaskDef("T5_DATA", "给参加迎新晚会的新生批量加素拓分"));

    private final CoordinatorAgent coordinator;
    private final SpecialistExecutor specialistExecutor;
    private final AgentRunService runService;
    private final AgentBenchmarkRepository repository;
    private final AgentEventPublisher publisher;
    private final ObjectMapper om = new ObjectMapper();

    public BenchmarkService(CoordinatorAgent coordinator,
                            SpecialistExecutor specialistExecutor,
                            AgentRunService runService,
                            AgentBenchmarkRepository repository,
                            AgentEventPublisher publisher) {
        this.coordinator = coordinator;
        this.specialistExecutor = specialistExecutor;
        this.runService = runService;
        this.repository = repository;
        this.publisher = publisher;
    }

    /** 运行一次批量实验。modes 为空时默认 P1。 */
    public Map<String, Object> runBenchmark(List<String> modes) {
        List<String> multiModes = (modes == null || modes.isEmpty()) ? List.of("P1") : modes;
        String batchId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Long userId = currentUserId();

        List<AgentBenchmark> rows = new ArrayList<>();
        for (TaskDef task : TASKS) {
            rows.add(runSingle(batchId, task, userId));
            for (String mode : multiModes) {
                rows.add(runMulti(batchId, task, mode, userId));
            }
        }
        repository.saveAll(rows);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", batchId);
        out.put("tasks", TASKS.size());
        out.put("rows", rows);
        out.put("summary", summarize(rows));
        return out;
    }

    // ---------------- 两条臂 ----------------

    private AgentBenchmark runSingle(String batchId, TaskDef task, Long userId) {
        Blackboard bb = new Blackboard();
        bb.setGoal(task.text());
        bb.setMode("SINGLE");
        Blackboard.PlanItem item = new Blackboard.PlanItem();
        item.setId(1);
        item.setAgent(AgentCatalog.GENERALIST);
        item.setTask(task.text());
        bb.getPlan().add(item);

        AgentRun run = runService.startRun(null, userId, task.text(), "SINGLE");
        String streamId = publisher.newStream("bench", userId);
        ToolContext ctx = ToolContext.current(null, streamId);
        specialistExecutor.executePlan(run, bb, ctx, streamId, "P1");
        runService.finishRun(run.getId(), AgentRun.STATUS_SUCCESS, bb);
        return toRow(batchId, task, AgentBenchmark.ARM_SINGLE, "P1", run, bb);
    }

    private AgentBenchmark runMulti(String batchId, TaskDef task, String mode, Long userId) {
        String streamId = publisher.newStream("bench", userId);
        ToolContext ctx = ToolContext.current(null, streamId);
        AgentRun run = coordinator.run(task.text(), ctx, null, mode, streamId);
        Blackboard bb = parseBb(run.getBlackboardJson());
        return toRow(batchId, task, AgentBenchmark.ARM_MULTI, mode, run, bb);
    }

    // ---------------- 指标 ----------------

    private AgentBenchmark toRow(String batchId, TaskDef task, String arm, String mode,
                                 AgentRun run, Blackboard bb) {
        List<AgentStep> steps = runService.steps(run.getId());
        AgentBenchmark row = new AgentBenchmark();
        row.setBatchId(batchId);
        row.setTaskCode(task.code());
        row.setTaskText(task.text());
        row.setArm(arm);
        row.setMode(mode);
        row.setRunId(run.getId());
        row.setStatus(run.getStatus());
        row.setSteps(steps.size());
        row.setAgents((int) steps.stream().map(AgentStep::getAgentName).distinct().count());
        row.setToolCalls(countInvocations(steps));
        row.setArtifacts(bb == null ? 0 : bb.getArtifacts().size());
        row.setReviewPass(reviewPass(bb));
        row.setDurationMs(durationMs(run));
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    private int countInvocations(List<AgentStep> steps) {
        int n = 0;
        for (AgentStep s : steps) {
            String out = s.getOutputJson();
            if (out == null || !out.contains("invocations")) {
                continue;
            }
            try {
                JsonNode inv = om.readTree(out).get("invocations");
                if (inv != null && inv.isArray()) {
                    n += inv.size();
                }
            } catch (Exception ignored) {
                // 非专家结果（如审查 JSON / 计划数组）→ 跳过
            }
        }
        return n;
    }

    private Integer reviewPass(Blackboard bb) {
        if (bb == null || bb.getDecisions().isEmpty()) {
            return null;
        }
        var last = bb.getDecisions().get(bb.getDecisions().size() - 1);
        return "PASS".equals(last.getVerdict()) ? 1 : 0;
    }

    private Integer durationMs(AgentRun run) {
        if (run.getStartedAt() == null || run.getFinishedAt() == null) {
            return null;
        }
        return (int) Duration.between(run.getStartedAt(), run.getFinishedAt()).toMillis();
    }

    private Map<String, Object> summarize(List<AgentBenchmark> rows) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String arm : List.of(AgentBenchmark.ARM_SINGLE, AgentBenchmark.ARM_MULTI)) {
            List<AgentBenchmark> sub = rows.stream().filter(r -> arm.equals(r.getArm())).toList();
            if (sub.isEmpty()) {
                continue;
            }
            long success = sub.stream().filter(r -> AgentRun.STATUS_SUCCESS.equals(r.getStatus())).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("samples", sub.size());
            m.put("completionRate", round(success * 1.0 / sub.size()));
            m.put("avgSteps", avg(sub.stream().map(AgentBenchmark::getSteps).toList()));
            m.put("avgAgents", avg(sub.stream().map(AgentBenchmark::getAgents).toList()));
            m.put("avgToolCalls", avg(sub.stream().map(AgentBenchmark::getToolCalls).toList()));
            m.put("avgArtifacts", avg(sub.stream().map(AgentBenchmark::getArtifacts).toList()));
            m.put("avgDurationMs", avg(sub.stream().map(r -> r.getDurationMs() == null ? 0 : r.getDurationMs()).toList()));
            if (AgentBenchmark.ARM_MULTI.equals(arm)) {
                long decisive = sub.stream().filter(r -> r.getReviewPass() != null).count();
                long pass = sub.stream().filter(r -> Integer.valueOf(1).equals(r.getReviewPass())).count();
                m.put("reviewPassRate", decisive == 0 ? null : round(pass * 1.0 / decisive));
            }
            summary.put(arm, m);
        }
        return summary;
    }

    private double avg(List<Integer> xs) {
        return xs.isEmpty() ? 0 : round(xs.stream().mapToInt(Integer::intValue).average().orElse(0));
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private Blackboard parseBb(String json) {
        if (json == null || json.isBlank()) {
            return new Blackboard();
        }
        try {
            return om.readValue(json, Blackboard.class);
        } catch (Exception e) {
            return new Blackboard();
        }
    }

    private Long currentUserId() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.pams.security.LoginUser u) {
            return u.getId();
        }
        return null;
    }
}
