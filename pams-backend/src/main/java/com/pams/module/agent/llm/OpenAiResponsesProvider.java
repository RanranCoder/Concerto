package com.pams.module.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.Consumer;
import static com.pams.module.agent.llm.OpenAiTransport.JSON;

/** Native Responses input/output items, including opaque reasoning state. */
public final class OpenAiResponsesProvider implements LlmProvider {
    private final ModelEndpoint endpoint;
    private final OpenAiTransport transport;
    public OpenAiResponsesProvider(ModelEndpoint endpoint, int timeout) { this.endpoint = endpoint; transport = new OpenAiTransport(endpoint, timeout); }
    public OpenAiResponsesProvider(ModelEndpoint endpoint, int timeout, org.apache.hc.client5.http.impl.classic.CloseableHttpClient client) {
        this.endpoint = endpoint; transport = new OpenAiTransport(endpoint, timeout, client);
    }
    public String name() { return "openai_responses"; }
    public boolean supportsToolCalling() { return true; }
    private ObjectNode body(ChatRequest request, boolean stream) {
        var body = JSON.createObjectNode().put("model", endpoint.model()).put("stream", stream)
                .put("store", false).put("max_output_tokens", request.maxTokens());
        body.putArray("include").add("reasoning.encrypted_content");
        if (endpoint.summary()) body.putObject("reasoning").put("summary", "auto");
        var input = body.putArray("input");
        for (var msg : request.messages()) {
            if (msg.nativeItems() != null && !msg.nativeItems().isEmpty()) {
                for (var item : msg.nativeItems()) input.add(JSON.valueToTree(item));
            } else if ("tool".equals(msg.role())) {
                input.addObject().put("type", "function_call_output").put("call_id", msg.toolCallId()).put("output", Objects.toString(msg.content(), ""));
            } else {
                if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) throw new IllegalStateException("Responses 工具轮次缺少原生输出状态");
                input.addObject().put("role", msg.role()).put("content", Objects.toString(msg.content(), ""));
            }
        }
        if (!request.tools().isEmpty()) {
            var tools = body.putArray("tools");
            for (var tool : request.tools()) tools.addObject().put("type", "function").put("name", tool.name())
                    .put("description", tool.description()).put("strict", false).set("parameters", JSON.valueToTree(tool.parameters()));
        }
        return body;
    }
    public ChatResponse chat(ChatRequest request) { return parse(transport.send("/responses", body(request, false), null)); }
    private ChatResponse parse(JsonNode root) {
        if (!"completed".equals(root.path("status").asText())) throw new IllegalStateException("Responses 响应未完成");
        var text = new StringBuilder(); var summary = new StringBuilder();
        var calls = new ArrayList<ToolCallRequest>(); var items = new ArrayList<Map<String,Object>>();
        if (!root.path("output").isArray()) throw new IllegalStateException("Responses 响应缺少 output");
        for (var item : root.path("output")) {
            items.add(JSON.convertValue(item, new com.fasterxml.jackson.core.type.TypeReference<>() {}));
            switch (item.path("type").asText()) {
                case "message" -> { for (var part : item.path("content")) if ("output_text".equals(part.path("type").asText())) text.append(part.path("text").asText()); }
                case "reasoning" -> { for (var part : item.path("summary")) if ("summary_text".equals(part.path("type").asText())) summary.append(part.path("text").asText()); }
                case "function_call" -> calls.add(new ToolCallRequest(item.path("call_id").asText(), item.path("name").asText(), OpenAiTransport.arguments(item.path("arguments"))));
                default -> throw new IllegalStateException("Responses 返回未启用的输出类型");
            }
        }
        var u = root.path("usage"); Usage usage = u.has("input_tokens") ? new Usage(u.path("input_tokens").asLong(), u.path("output_tokens").asLong(),
                u.path("input_tokens_details").has("cached_tokens") ? u.path("input_tokens_details").path("cached_tokens").asLong() : null, null) : null;
        return new ChatResponse(text.toString(), calls, name(), false, summary.isEmpty() ? null : summary.toString(), usage,
                root.path("model").asText(endpoint.model()), "completed", List.copyOf(items));
    }
    public ChatResponse chatStream(ChatRequest request, Consumer<String> output) { return chatStream(request, output, null); }
    public ChatResponse chatStream(ChatRequest request, Consumer<String> output, Consumer<String> summary) {
        JsonNode[] completed = {null};
        transport.send("/responses", body(request, true), event -> {
            switch (event.path("type").asText()) {
                case "response.output_text.delta" -> output.accept(event.path("delta").asText());
                case "response.reasoning_summary_text.delta" -> { if (summary != null) summary.accept(event.path("delta").asText()); }
                case "response.completed" -> completed[0] = event.path("response");
                case "response.failed", "response.incomplete", "error" -> throw new IllegalStateException("Responses 流返回失败或不完整状态");
                default -> { /* Other lifecycle events do not contain display text. */ }
            }
        });
        if (completed[0] == null) throw new IllegalStateException("Responses 流提前结束");
        return parse(completed[0]);
    }
}
