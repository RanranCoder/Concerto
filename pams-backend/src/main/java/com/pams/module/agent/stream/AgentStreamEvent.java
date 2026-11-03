package com.pams.module.agent.stream;

import com.pams.module.agent.registry.AgentCatalog;

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

    public AgentStreamEvent withContext(com.pams.module.agent.core.ToolContext ctx) {
        var data=new LinkedHashMap<>(payload);data.put("requestId",ctx.getRequestId());data.put("conversationId",ctx.getConversationId());
        data.put("runId",ctx.getRunId());data.put("workItemId",ctx.getWorkItemId());data.put("revision",ctx.getWorkItemRevision());data.put("attempt",ctx.getAttempt());
        data.put("channel",ctx.getWorkItemId()==null?"final":"task");return new AgentStreamEvent(streamId,seq,type,data,ts);
    }
    // —— 一期实发的事件类型 ——
    /**
     * 编排自述（「正在理解你的问题…」这类后端写死的短句）。
     *
     * <p><b>它不是模型的思维链</b>，只是 TTFE 兜底：进入编排的第一时间就得有东西可看。
     * 模型真正的推理文本走 {@link #THINKING_DELTA}。
     * **必须在意图分类之前**推送，否则 TTFE 不达标（灵思 §3.5.3 规则 5）。
     */
    public static final String THINKING = "THINKING";
    /**
     * 模型公开推理摘要增量。
     *
     * <p>来源：原生 Responses 的 {@code response.reasoning_summary_text.delta}。
     * 不包含加密 reasoning 状态，也不依赖厂商扩展字段。
     * 与 {@link #MESSAGE_DELTA} 同构：{@code round} 标识"本轮第几次 LLM 调用"，
     * 前端把**同 round 的片段拼成一段推理**，round 变化即新起一段。
     *
     * <p>为什么不复用 THINKING：两者的语义与时效完全不同 ——
     * THINKING 是后端的编排旁白（有意的占位），THINKING_DELTA 是模型的真实推理原文
     * （可长可短、需可展开回看、需随消息落库）。混在一起会让"这段到底是谁说的"无法分辨。
     */
    public static final String THINKING_DELTA = "THINKING_DELTA";
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
    /**
     * 协作判定：标准模式下判定「这一轮升级为协作」时先发出，带 mode 与理由。
     * 前端据此在过程区顶部显示一行「判定为协作：…」，否则用户不知道为何突然出现专家。
     */
    public static final String COLLAB_DECISION = "COLLAB_DECISION";
    /** 某专家 Agent 开始执行 */
    public static final String AGENT_START = "AGENT_START";
    /** 某专家 Agent 执行结束 */
    public static final String AGENT_END = "AGENT_END";
    /** Agent 间委派（from → to） */
    public static final String AGENT_DELEGATE = "AGENT_DELEGATE";
    /** 审查结论（PASS / REVISE） */
    public static final String REVIEW_RESULT = "REVIEW_RESULT";
    /** 一次协作 Run 结束（异步执行完成；前端据此拉取最终 Run 详情） */
    public static final String RUN_DONE = "RUN_DONE";

    public static AgentStreamEvent runDone(String streamId, int seq, Long runId, String status) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("runId", runId);
        p.put("status", status);
        return of(streamId, seq, RUN_DONE, p);
    }

    /**
     * 给协作事件补上会话归属。
     *
     * <p>过程事件按**用户**广播，前端必须能判断「这条事件属不属于我正在看的会话」，
     * 否则后台或其它会话的协作会把专家行进当前时间轴。
     * 用会话 id 而不是 streamId：streamId 要等 REST 返回，事件可能先到。
     */
    private static void withConversation(Map<String, Object> p, Long conversationId) {
        if (conversationId != null) {
            p.put("conversationId", conversationId);
        }
    }

    public static AgentStreamEvent agentStart(String streamId, int seq, String agent,
                                              Long stepId, Long parentStepId, Long conversationId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("agent", agent);
        // 展示名随事件下发：只给英文 id 的话前端只能显示 "retrieval" 而不是「检索专家」
        p.put("displayName", AgentCatalog.displayName(agent));
        p.put("stepId", stepId);
        if (parentStepId != null) p.put("parentStepId", parentStepId);
        withConversation(p, conversationId);
        return of(streamId, seq, AGENT_START, p);
    }

    public static AgentStreamEvent agentEnd(String streamId, int seq, String agent, Long stepId,
                                            String status, long durationMs, Long conversationId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("agent", agent);
        p.put("displayName", AgentCatalog.displayName(agent));
        p.put("stepId", stepId);
        p.put("status", status);
        p.put("durationMs", durationMs);
        withConversation(p, conversationId);
        return of(streamId, seq, AGENT_END, p);
    }

    public static AgentStreamEvent agentDelegate(String streamId, int seq, String from, String to,
                                                 String reason, Long conversationId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("from", from);
        p.put("fromName", AgentCatalog.displayName(from));
        p.put("to", to);
        p.put("toName", AgentCatalog.displayName(to));
        if (reason != null) p.put("reason", reason);
        withConversation(p, conversationId);
        return of(streamId, seq, AGENT_DELEGATE, p);
    }

    /**
     * 协作判定事件：告诉用户「这一轮为什么升级成了协作」。
     * 没有它的话，用户只会看到突然冒出一堆专家行而不知道为什么。
     *
     * <p>带上 {@code conversationId} 是刻意的：过程事件按**用户**广播，
     * 而前端在 REST 返回之前并不知道本轮 streamId。会话 id 是发请求时就知道的，
     * 前端据此就能判断「这条协作事件属不属于我正在看的会话」，
     * 避免后台/其它会话的协作事件串进当前时间轴。
     */
    public static AgentStreamEvent collabDecision(String streamId, int seq, Long conversationId,
                                                  String mode, String reason) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("conversationId", conversationId);
        p.put("mode", mode);
        p.put("reason", reason);
        return of(streamId, seq, COLLAB_DECISION, p);
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

    /**
     * 模型推理增量（思维链正文片段）。
     *
     * @param round 本轮问答内的第几次 LLM 调用（与 {@link #messageDelta} 同一套 round 语义）
     * @param text  增量片段（调用方已做长度/时间双阈值合并，不是单 token）
     */
    public static AgentStreamEvent thinkingDelta(String streamId, int seq, int round, String text) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("round", round);
        p.put("text", text == null ? "" : text);
        return of(streamId, seq, THINKING_DELTA, p);
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
