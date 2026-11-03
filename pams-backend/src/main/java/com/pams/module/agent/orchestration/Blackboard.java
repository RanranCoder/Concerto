package com.pams.module.agent.orchestration;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 黑板书（Blackboard）—— 多 Agent 协作的单一事实来源。
 *
 * <p>每个 Agent「读黑板 + 写黑板」；黑板可整体序列化落库（{@code agent_run.blackboard_json}），
 * 支持协作过程回放。结构见《通用化与多Agent协作改造策划》§3.4。
 */
@Data
public class Blackboard {

    private String goal;
    /** P1 串行 / P2 并行 / P3 反思 / P4 委派 */
    private String mode;

    private List<PlanItem> plan = new ArrayList<>();
    private List<Finding> findings = new ArrayList<>();
    private List<Artifact> artifacts = new ArrayList<>();
    private List<Decision> decisions = new ArrayList<>();
    private List<String> openQuestions = new ArrayList<>();
    private boolean cancelled;
    private String persistedState;
    /** Live handoff only: raw confirmation tokens belong to TaskGate, not the run snapshot. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private List<SpecialistAgent.Result> results = new ArrayList<>();

    public String executionState() {
        if (persistedState != null) return persistedState;
        if (cancelled) return "CANCELLED";
        if (results.stream().anyMatch(r -> "CANCELLED".equals(r.state()))) return "CANCELLED";
        if (results.stream().anyMatch(r -> "WAITING_INPUT".equals(r.state()))) return "WAITING_INPUT";
        if (results.stream().anyMatch(r -> "WAITING_CONFIRM".equals(r.state()))) return "WAITING_CONFIRM";
        if (results.isEmpty()) return "FAILED";
        boolean completed = results.stream().anyMatch(r -> "SUCCESS".equals(r.state()));
        boolean failed = results.stream().anyMatch(r -> !"SUCCESS".equals(r.state()));
        boolean revise = !decisions.isEmpty() && "REVISE".equals(decisions.get(decisions.size()-1).getVerdict());
        return failed || revise ? completed || results.stream().anyMatch(r -> "PARTIAL".equals(r.state())) ? "PARTIAL" : "FAILED" : "SUCCESS";
    }

    public void addFinding(String agent, String summary, Double confidence) {
        Finding f = new Finding();
        f.setAgent(agent);
        f.setSummary(summary);
        f.setConfidence(confidence);
        findings.add(f);
    }

    public void addDecision(String by, String verdict, String reason) {
        Decision d = new Decision();
        d.setBy(by);
        d.setVerdict(verdict);
        d.setReason(reason);
        decisions.add(d);
    }

    /** 计划项：交由哪个 Agent 做什么。 */
    @Data
    public static class PlanItem {
        private int id;
        private String agent;
        private String task;
        /** Server-produced immutable capability/binding contract for this revision. Null for legacy rules. */
        private java.util.Map<String,Object> contract;
        private String status = "QUEUED";
        private Long durationMs;
        private Long workItemId;
        private int revision = 1;
        private int attempt;
        private List<Integer> dependsOn = new ArrayList<>();
    }

    /** 证据/发现：某 Agent 产出的结论与置信度。 */
    @Data
    public static class Finding {
        private String agent;
        private String summary;
        private Double confidence;
    }

    /** 产物引用：如推文草稿、策划书章节。 */
    @Data
    public static class Artifact {
        private String agent;
        private String type;
        private String contentRef;
    }

    /** 裁决：审查/协调的结论。 */
    @Data
    public static class Decision {
        private String by;
        private String verdict;
        private String reason;
    }
}
