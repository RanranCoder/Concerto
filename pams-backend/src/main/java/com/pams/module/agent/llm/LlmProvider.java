package com.pams.module.agent.llm;

import java.util.*;
import java.util.function.Consumer;

/** Business invocation interface. Each provider owns its native wire protocol. */
public interface LlmProvider {
    String name();
    boolean supportsToolCalling();
    ChatResponse chat(ChatRequest request);
    default ChatResponse chatStream(ChatRequest request, Consumer<String> output) {
        var response = chat(request);
        if (response.content() != null && !response.content().isEmpty()) output.accept(response.content());
        return response;
    }
    /** The third channel contains public reasoning summaries, never opaque reasoning state. */
    default ChatResponse chatStream(ChatRequest request, Consumer<String> output, Consumer<String> summary) {
        return chatStream(request, output);
    }
    record ChatRequest(List<ChatMessage> messages, List<ToolSpec> tools, double temperature, int maxTokens) {}
    /** nativeItems are server-internal Responses output items, never exposed as display text. */
    record ChatMessage(String role, String content, String toolCallId, List<ToolCallRequest> toolCalls,
                       String reasoningContent, List<Map<String,Object>> nativeItems) {
        public ChatMessage(String role, String content, String toolCallId, List<ToolCallRequest> toolCalls) {
            this(role, content, toolCallId, toolCalls, null, List.of());
        }
        public ChatMessage(String role, String content, String toolCallId, List<ToolCallRequest> toolCalls, String summary) {
            this(role, content, toolCallId, toolCalls, summary, List.of());
        }
        public static ChatMessage text(String role, String content) { return new ChatMessage(role, content, null, null); }
        public static ChatMessage toolResult(String id, String content) { return new ChatMessage("tool", content, id, null); }
        public static ChatMessage assistantCalling(String content, List<ToolCallRequest> calls) { return new ChatMessage("assistant", content, null, calls); }
        public static ChatMessage assistantCalling(String content, List<ToolCallRequest> calls, String summary) { return new ChatMessage("assistant", content, null, calls, summary); }
        public static ChatMessage assistantCalling(ChatResponse response) {
            return new ChatMessage("assistant", response.content(), null, response.toolCalls(), null, response.nativeItems());
        }
    }
    record ToolSpec(String name, String description, Map<String,Object> parameters) {}
    /** reasoningContent is the public summary; nativeItems keep original protocol state separately. */
    record ChatResponse(String content, List<ToolCallRequest> toolCalls, String provider, boolean degraded,
                        String reasoningContent, Usage usage, String model, String finishReason, List<Map<String,Object>> nativeItems) {
        public ChatResponse(String content, List<ToolCallRequest> calls, String provider, boolean degraded, String summary,
                            Usage usage, String model, String finishReason) {
            this(content, calls, provider, degraded, summary, usage, model, finishReason, List.of());
        }
        public ChatResponse(String content, List<ToolCallRequest> calls, String provider, boolean degraded, String summary) {
            this(content, calls, provider, degraded, summary, null, null, null);
        }
        public ChatResponse(String content, List<ToolCallRequest> calls, String provider, boolean degraded) { this(content, calls, provider, degraded, null); }
        public static ChatResponse text(String content, String provider) { return new ChatResponse(content, List.of(), provider, false); }
        public static ChatResponse stub(String content) { return new ChatResponse(content, List.of(), "stub", true); }
        public static ChatResponse calling(List<ToolCallRequest> calls) { return new ChatResponse("", calls, "openai_chat_completions", false); }
    }
    record Usage(long inputTokens, long outputTokens, Long cacheHitTokens, Long cacheMissTokens) {}
    record ToolCallRequest(String id, String name, Map<String,Object> arguments) {}
}
