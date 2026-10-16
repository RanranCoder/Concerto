package com.pams.module.agent.llm;

import java.util.List;
import java.util.Map;

/**
 * LLM 网关（业务设计方案 D5）——可插拔 provider，最小抽象，只暴露必要能力。
 *
 * <p>已核实 {@code pom.xml} 无任何 AI/LLM SDK 依赖，因此三个实现都走同一个接口，
 * "选哪家"在代码层面是<b>零迁移成本</b>的。
 */
public interface LlmProvider {

    /** deepseek / ollama / stub */
    String name();

    /** 是否原生支持 function calling */
    boolean supportsToolCalling();

    ChatResponse chat(ChatRequest request);

    /**
     * 流式对话（UI v2 字符级流式）。
     *
     * <p>契约：边产出边通过 {@code onDelta} 回调<b>增量文本片段</b>，最终返回与
     * {@link #chat} 完全等价的完整响应（含 toolCalls）。
     *
     * <p><b>默认实现刻意回退到非流式</b>：整段一次性回调。这样
     * {@code StubProvider}、Ollama 等不支持 SSE 的实现<b>零改动即可接入</b>，
     * 编排器不需要关心"这个 provider 会不会流式"——它只管消费回调。
     * 代价是这些 provider 下前端一次收到整段（体验退化为"一下蹦出来"），
     * 但功能完全正确，这正是默认实现存在的意义。
     *
     * <p><b>失败语义</b>：与 {@link #chat} 相同——抛异常由编排器走"重试 1 次 + 降级"。
     * 但要特别注意：流式调用<b>可能已经回调出一段文本</b>才失败，
     * 此时前端已经显示了一半。编排器必须换一个 round 重新发（见
     * {@code AgentStreamEvent#messageDelta} 的 round 约定），否则会拼出半截错文。
     */
    default ChatResponse chatStream(ChatRequest request, java.util.function.Consumer<String> onDelta) {
        ChatResponse resp = chat(request);
        if (resp.content() != null && !resp.content().isEmpty()) onDelta.accept(resp.content());
        return resp;
    }

    /**
     * 流式对话 + <b>思维链回调</b>（reasoning）。
     *
     * <p>契约：{@code onReasoning} 收到的是模型的**推理原文片段**（思考模式下的
     * {@code delta.reasoning_content}），与 {@code onDelta} 的正文是两条独立的流 ——
     * 同一时刻只会有其中一条在产出。
     *
     * <p><b>默认实现刻意忽略推理</b>：不产思考链的 provider（Stub / Ollama /
     * 关闭了思考模式的模型）零改动即可接入，行为与只传 {@code onDelta} 完全一致。
     * 调用方必须容忍"整轮都没有 reasoning 片段"。
     *
     * <p><b>失败语义与 {@link #chatStream(ChatRequest, java.util.function.Consumer)} 相同</b>；
     * 已经回调出的推理片段同样作废（调用方换 round 重发，见 AgentLlmCaller 的重试约定）。
     */
    default ChatResponse chatStream(ChatRequest request,
                                    java.util.function.Consumer<String> onDelta,
                                    java.util.function.Consumer<String> onReasoning) {
        return chatStream(request, onDelta);
    }

    /**
     * 对话请求。
     *
     * @param messages system + 最近 K 轮 + 工具结果摘要（已过 PolicyGuard）
     * @param tools    当前用户<b>可见</b>的工具（D3 第 2 层：不可见 ⇒ 不可诱导）
     */
    record ChatRequest(List<ChatMessage> messages, List<ToolSpec> tools,
                       double temperature, int maxTokens) {}

    /**
     * 对话消息。
     *
     * <p><b>⚠️ OpenAI 兼容协议的硬约束</b>（踩过坑）：{@code role="tool"} 的消息
     * <b>必须</b>携带 {@code tool_call_id}，且其<b>紧邻前一条</b>必须是一条带
     * {@code tool_calls} 的 {@code assistant} 消息。DeepSeek 会严格校验，
     * 缺失直接 400（{@code missing field `tool_call_id`}）。
     *
     * <p>因此本 record 保留了 tool 协议所需的两个字段，而不是只留 role/content：
     * <ul>
     *   <li>{@code toolCallId} —— {@code role="tool"} 时必填</li>
     *   <li>{@code toolCalls} —— {@code role="assistant"} 且该回合发起了工具调用时必填</li>
     * </ul>
     *
     * <p>历史上下文（{@link #buildContext}）<b>不</b>回灌历史 tool 消息——
     * 工具结果只对产出它的那一轮有意义，跨轮回灌既无价值又必然破坏上面这条配对约束。
     *
     * @param reasoningContent 该 assistant 回合的模型推理原文（{@code reasoning_content}）。
     *                         <b>不是给人看的装饰，是请求能否成立的一部分</b>：思考模式下
     *                         只要请求带了 {@code tools}，后续请求就必须完整回传它，否则官方 400。
     *                         非 assistant 消息、以及模型没给推理的回合为 {@code null}。
     */
    record ChatMessage(String role, String content, String toolCallId,
                       List<ToolCallRequest> toolCalls, String reasoningContent) {

        /**
         * 4 参重载：既有的构造点（{@code StubProvider}、单测、工具结果消息）零改动。
         *
         * <p>为什么用"非规范构造器"而不是给每个调用点补第 5 个参数：record 加字段会
         * 一次性打断所有 {@code new ChatMessage(...)}，而它们绝大多数与思考模式无关；
         * 改法本身比这个字段的风险更大。
         */
        public ChatMessage(String role, String content, String toolCallId, List<ToolCallRequest> toolCalls) {
            this(role, content, toolCallId, toolCalls, null);
        }

        /** 普通文本消息（user / assistant 无工具调用 / system） */
        public static ChatMessage text(String role, String content) {
            return new ChatMessage(role, content, null, null);
        }

        /** 工具结果消息（role="tool"，必须带 toolCallId） */
        public static ChatMessage toolResult(String toolCallId, String content) {
            return new ChatMessage("tool", content, toolCallId, null);
        }

        /**
         * 发起工具调用的 assistant 回合（必须带 toolCalls，且其后紧跟对应的 tool 结果）。
         * content 允许为空字符串（协议允许 assistant 只发 tool_calls）。
         *
         * <p>不带推理的重载：思考模式关闭、或该回合模型没给 {@code reasoning_content} 时用。
         */
        public static ChatMessage assistantCalling(String content, List<ToolCallRequest> toolCalls) {
            return assistantCalling(content, toolCalls, null);
        }

        /**
         * 发起工具调用的 assistant 回合 + <b>该回合的推理原文</b>。
         *
         * <p><b>为什么带着推理回灌</b>：官方思考模式规定——只要请求带了 {@code tools}，
         * 后续请求必须完整回传历史 assistant 的 {@code reasoning_content}，否则 400。
         * 工具循环的第二轮恰好就是"带 tools + 回灌上一轮 assistant"。
         *
         * <p><b>已复现（2026-10-12，JVM 直连真 API）</b>：带 {@code tools} + 历史里有"产生过推理的
         * assistant {@code tool_calls} 回合"时，不回灌即 <b>HTTP 400</b>：
         * {@code The `reasoning_content` in the thinking mode must be passed back to the API.}
         * （{@code thinking} 关闭时不触发）。工具循环的第二轮正好是这个组合 —— 漏掉推理
         * 等于"第一次工具调用之后整轮失败"，而且会被上层静默降级成规则模式。
         *
         * <p>空串/空白一律归一化成 {@code null}：字段要么不带，要么是有意义的原文，
         * 不带空值（部分网关对空字符串与缺字段的处理并不一致）。
         */
        public static ChatMessage assistantCalling(String content, List<ToolCallRequest> toolCalls,
                                                   String reasoningContent) {
            return new ChatMessage("assistant", content == null ? "" : content, null, toolCalls,
                    reasoningContent == null || reasoningContent.isBlank() ? null : reasoningContent);
        }
    }

    record ToolSpec(String name, String description, Map<String, Object> parameters) {}

    /**
     * 对话响应。
     *
     * @param content   文本答复（可能为空，此时应看 toolCalls）
     * @param toolCalls LLM 请求调用的工具
     * @param provider  实际应答的 provider（降级后与请求的 provider 不同）
     * @param degraded  是否发生了降级（→ 前端必须标注"规则模式"，灵思 §3.5.1）
     * @param reasoningContent 本轮模型的推理原文（{@code reasoning_content}）。
     *                 流式路径把整个回合攒下的推理一次性带出来，非流式取
     *                 {@code choices[0].message.reasoning_content}；模型没给推理时为 {@code null}。
     *                 <p>为什么不只靠 {@code onReasoning} 回调：回调是"边到边展示"，
     *                 而工具循环的下一轮需要**完整原文**回灌给 API（官方契约如此，见
     *                 {@code assistantCalling} 里同日复现的 400 证据），
     *                 所以返回值里必须有一份完整的，而不是让调用方自己再攒一遍。
     */
    record ChatResponse(String content, List<ToolCallRequest> toolCalls, String provider,
                        boolean degraded, String reasoningContent) {

        /**
         * 4 参重载：既有的构造点（{@code StubProvider}、{@code AgentLlmCaller.safeStub}、
         * 本接口的三个静态工厂、单测）零改动即兼容——它们都不产推理。
         */
        public ChatResponse(String content, List<ToolCallRequest> toolCalls, String provider, boolean degraded) {
            this(content, toolCalls, provider, degraded, null);
        }

        public static ChatResponse text(String content, String provider) {
            return new ChatResponse(content, List.of(), provider, false);
        }

        public static ChatResponse stub(String content) {
            return new ChatResponse(content, List.of(), "stub", true);
        }

        public static ChatResponse calling(List<ToolCallRequest> calls) {
            return new ChatResponse("", calls, "deepseek", false);
        }
    }

    /** LLM 请求调用某个工具 */
    record ToolCallRequest(String id, String name, Map<String, Object> arguments) {}
}
