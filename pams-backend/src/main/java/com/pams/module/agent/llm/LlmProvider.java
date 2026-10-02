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
     */
    record ChatMessage(String role, String content, String toolCallId, List<ToolCallRequest> toolCalls) {

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
         */
        public static ChatMessage assistantCalling(String content, List<ToolCallRequest> toolCalls) {
            return new ChatMessage("assistant", content == null ? "" : content, null, toolCalls);
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
     */
    record ChatResponse(String content, List<ToolCallRequest> toolCalls, String provider, boolean degraded) {

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
