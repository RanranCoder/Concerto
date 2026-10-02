package com.pams.module.agent.stream;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 过程事件契约（业务设计方案 D7 / 灵思 §3.5.3）。
 *
 * <p>{@code seq} 单调递增，前端据此去重排序（乱序到达不错位）；
 * 断连后用 {@code GET /api/agent/streams/{streamId}?since={lastSeq}} 补齐缺口。
 *
 * <p><b>⚠️ 与现有通知推送的 payload 策略有意不同</b>：现有 {@code /user/queue/notifications}
 * 的 payload 是<b>刻意做薄的</b>（只推 type + unreadCount，前端回拉 REST）。
 * 过程事件流<b>不能照抄这个约定</b>——它必须携带真实内容（工具名、耗时、状态），
 * 否则前端无从渲染时间轴。
 *
 * @param streamId 本次回答的事件流 ID
 * @param seq      流内序号（从 1 开始单调递增）
 * @param type     事件类型
 * @param payload  事件载荷（<b>厚载荷</b>，含真实内容）
 * @param ts       事件时间戳（毫秒）
 */
public record AgentStreamEvent(String streamId, int seq, String type,
                               Map<String, Object> payload, long ts) {

    // —— 一期实发的事件类型 ——
    /** 进入编排的第一时间推送。**必须在意图分类之前**，否则 TTFE 不达标（灵思 §3.5.3 规则 5） */
    public static final String THINKING = "THINKING";
    public static final String TOOL_START = "TOOL_START";
    public static final String TOOL_END = "TOOL_END";
    public static final String PLAN_READY = "PLAN_READY";
    /**
     * 表单补全请求（D2.5 Form Elicitation）：写类工具关键参数不全时发出，
     * payload = 表单 schema + 预填值 + taskId。前端收到后弹出结构化表单。
     * 前端对未知事件类型默认忽略（mergeTimeline 的 default 分支），故该事件向后兼容。
     */
    public static final String INPUT_NEEDED = "INPUT_NEEDED";
    public static final String MESSAGE_DONE = "MESSAGE_DONE";
    public static final String ERROR = "ERROR";
    public static final String ABORTED = "ABORTED";

    /**
     * 二期字符流预留（D7）——现已启用（UI v2）。
     * <p>payload = {@code {round, text}}：{@code round} 是本轮问答内的第几次 LLM 调用
     * （从 0 起）。前端遇到 <b>round 变化即清空已拼装的回复再追加</b>——
     * 这个约定让"工具调用轮 + 重试 + 降级"三类场景都不需要额外的重置事件：
     * 只要换个 round，前端就自然从空开始。
     */
    public static final String MESSAGE_DELTA = "MESSAGE_DELTA";

    // —— 多 Agent 协作事件（B 批次） ——
    /** 某专家 Agent 开始执行 */
    public static final String AGENT_START = "AGENT_START";
    /** 某专家 Agent 执行结束 */
    public static final String AGENT_END = "AGENT_END";
    /** Agent 间委派（from → to） */
    public static final String AGENT_DELEGATE = "AGENT_DELEGATE";
    /** 审查结论（PASS / REVISE） */
    public static final String REVIEW_RESULT = "REVIEW_RESULT";

    public static AgentStreamEvent agentStart(String streamId, int seq, String agent,
                                              Long stepId, Long parentStepId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("agent", agent);
        p.put("stepId", stepId);
        if (parentStepId != null) p.put("parentStepId", parentStepId);
        return of(streamId, seq, AGENT_START, p);
    }

    public static AgentStreamEvent agentEnd(String streamId, int seq, String agent, Long stepId,
                                            String status, long durationMs) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("agent", agent);
        p.put("stepId", stepId);
        p.put("status", status);
        p.put("durationMs", durationMs);
        return of(streamId, seq, AGENT_END, p);
    }

    public static AgentStreamEvent agentDelegate(String streamId, int seq, String from, String to,
                                                 String reason) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("from", from);
        p.put("to", to);
        if (reason != null) p.put("reason", reason);
        return of(streamId, seq, AGENT_DELEGATE, p);
    }

    public static AgentStreamEvent reviewResult(String streamId, int seq, String verdict,
                                                String reason, int round) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("verdict", verdict);
        p.put("reason", reason == null ? "" : reason);
        p.put("round", round);
        return of(streamId, seq, REVIEW_RESULT, p);
    }

    public static AgentStreamEvent messageDelta(String streamId, int seq, int round, String text) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("round", round);
        p.put("text", text == null ? "" : text);
        return of(streamId, seq, MESSAGE_DELTA, p);
    }

    public static AgentStreamEvent of(String streamId, int seq, String type, Map<String, Object> payload) {
        return new AgentStreamEvent(streamId, seq, type,
                payload == null ? Map.of() : payload, System.currentTimeMillis());
    }

    public static AgentStreamEvent thinking(String streamId, int seq, String note) {
        return of(streamId, seq, THINKING, Map.of("note", note == null ? "" : note));
    }

    public static AgentStreamEvent toolStart(String streamId, int seq, String tool, String label) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tool", tool);
        p.put("label", label == null ? tool : label);
        return of(streamId, seq, TOOL_START, p);
    }

    public static AgentStreamEvent toolEnd(String streamId, int seq, String tool, String label,
                                           String status, long durationMs, String error) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tool", tool);
        p.put("label", label == null ? tool : label);
        p.put("status", status);
        p.put("durationMs", durationMs);
        if (error != null) p.put("error", error);
        return of(streamId, seq, TOOL_END, p);
    }

    public static AgentStreamEvent planReady(String streamId, int seq, Map<String, Object> plan) {
        return of(streamId, seq, PLAN_READY, plan);
    }

    /** 表单补全请求（D2.5）。payload 即 TaskGate.createForm 的返回（含 taskId / fields / values） */
    public static AgentStreamEvent inputNeeded(String streamId, int seq, Map<String, Object> form) {
        return of(streamId, seq, INPUT_NEEDED, form == null ? Map.of() : form);
    }

    public static AgentStreamEvent messageDone(String streamId, int seq, String reply,
                                               Map<String, Object> extra) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("reply", reply == null ? "" : reply);
        if (extra != null) p.putAll(extra);
        return of(streamId, seq, MESSAGE_DONE, p);
    }

    public static AgentStreamEvent error(String streamId, int seq, String message) {
        return of(streamId, seq, ERROR, Map.of("message", message == null ? "" : message));
    }

    public static AgentStreamEvent aborted(String streamId, int seq) {
        return of(streamId, seq, ABORTED, Map.of("reason", "用户中止"));
    }
}
