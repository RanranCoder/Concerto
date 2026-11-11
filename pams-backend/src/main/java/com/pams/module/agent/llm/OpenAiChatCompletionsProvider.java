package com.pams.module.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.Consumer;
import static com.pams.module.agent.llm.OpenAiTransport.JSON;

/** Native Chat Completions. No vendor fields or Responses translation. */
public final class OpenAiChatCompletionsProvider implements LlmProvider {
    private final ModelEndpoint endpoint;
    private final OpenAiTransport transport;
    public OpenAiChatCompletionsProvider(ModelEndpoint endpoint, int timeout) {
        this.endpoint = endpoint; transport = new OpenAiTransport(endpoint, timeout);
    }
    public OpenAiChatCompletionsProvider(ModelEndpoint endpoint, int timeout, org.apache.hc.client5.http.impl.classic.CloseableHttpClient client) {
        this.endpoint = endpoint; transport = new OpenAiTransport(endpoint, timeout, client);
    }
    public String name() { return "openai_chat_completions"; }
    @Override public Integer contextWindowTokens() { return endpoint.contextWindowTokens(); }
    public boolean supportsToolCalling() { return true; }
    private ObjectNode body(ChatRequest request, boolean stream) {
        var body = JSON.createObjectNode().put("model", endpoint.model()).put("stream", stream)
                .put("max_completion_tokens", request.maxTokens());
        var messages = body.putArray("messages");
        for (var m : request.messages()) {
            var node = messages.addObject().put("role", m.role()).put("content", Objects.toString(m.content(), ""));
            if (m.toolCallId() != null) node.put("tool_call_id", m.toolCallId());
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                var calls = node.putArray("tool_calls");
                for (var call : m.toolCalls()) calls.addObject().put("id", call.id()).put("type", "function")
                        .putObject("function").put("name", call.name()).put("arguments", JSON.valueToTree(call.arguments()).toString());
            }
        }
        if (!request.tools().isEmpty()) {
            var tools = body.putArray("tools");
            for (var tool : request.tools()) {
                var fn = tools.addObject().put("type", "function").putObject("function");
                fn.put("name", tool.name()).put("description", tool.description()).put("strict", false)
                        .set("parameters", JSON.valueToTree(tool.parameters()));
            }
        }
        if (stream) body.putObject("stream_options").put("include_usage", true);
        return body;
    }
    public ChatResponse chat(ChatRequest request) { return parse(transport.send("/chat/completions", body(request, false), null)); }
    private ChatResponse parse(JsonNode root) {
        var choice = root.path("choices").path(0); var msg = choice.path("message");
        if (!msg.isObject()) throw new IllegalStateException("Chat Completions 响应缺少 message");
        var calls = new ArrayList<ToolCallRequest>();
        for (var call : msg.path("tool_calls")) calls.add(new ToolCallRequest(call.path("id").asText(),
                call.path("function").path("name").asText(), OpenAiTransport.arguments(call.path("function").path("arguments"))));
        return new ChatResponse(msg.path("content").asText(""), calls, name(), false, null,
                usage(root.path("usage")), root.path("model").asText(endpoint.model()), choice.path("finish_reason").asText(null));
    }
    private Usage usage(JsonNode node) {
        return node.has("prompt_tokens") ? new Usage(node.path("prompt_tokens").asLong(), node.path("completion_tokens").asLong(),
                node.path("prompt_tokens_details").has("cached_tokens") ? node.path("prompt_tokens_details").path("cached_tokens").asLong() : null, null) : null;
    }
    public ChatResponse chatStream(ChatRequest request, Consumer<String> output) { return chatStream(request, output, null); }
    public ChatResponse chatStream(ChatRequest request, Consumer<String> output, Consumer<String> summary) {
        var text = new StringBuilder(); var calls = new TreeMap<Integer,ObjectNode>();
        var result = JSON.createObjectNode(); boolean[] finished = {false};
        transport.send("/chat/completions", body(request, true), event -> {
            if (event.hasNonNull("usage")) result.set("usage", event.get("usage"));
            if (event.has("model")) result.set("model", event.get("model"));
            var choice = event.path("choices").path(0);
            if (choice.hasNonNull("finish_reason")) { result.put("finish", choice.path("finish_reason").asText()); finished[0] = true; }
            var delta = choice.path("delta"); String piece = delta.path("content").asText("");
            if (!piece.isEmpty()) { text.append(piece); output.accept(piece); }
            for (var call : delta.path("tool_calls")) {
                var saved = calls.computeIfAbsent(call.path("index").asInt(), k -> JSON.createObjectNode().put("id", "").put("name", "").put("arguments", ""));
                if (call.hasNonNull("id")) saved.put("id", call.path("id").asText());
                var fn = call.path("function");
                for (String key : List.of("name", "arguments")) if (fn.hasNonNull(key)) saved.put(key, saved.path(key).asText() + fn.path(key).asText());
            }
        });
        if (!finished[0]) throw new IllegalStateException("Chat Completions 流提前结束");
        var parsed = calls.values().stream().map(c -> new ToolCallRequest(c.path("id").asText(), c.path("name").asText(), OpenAiTransport.arguments(c.path("arguments")))).toList();
        return new ChatResponse(text.toString(), parsed, name(), false, null, usage(result.path("usage")),
                result.path("model").asText(endpoint.model()), result.path("finish").asText());
    }
}
