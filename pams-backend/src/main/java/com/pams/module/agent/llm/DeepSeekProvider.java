package com.pams.module.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pams.module.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek provider（业务设计方案 D5 主选）。
 *
 * <p>走 <b>OpenAI 兼容协议</b> {@code /chat/completions}，因此无需官方 SDK，一个 HTTP 客户端打通。
 * 中文能力强、function calling 成熟、国内直连。
 */
@Slf4j
public class DeepSeekProvider implements LlmProvider {

    private final AgentProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient client;
    /** 流式通道独立用 JDK HttpClient：RestClient 会把响应整体缓冲，拿不到逐行 SSE */
    private final java.net.http.HttpClient sseClient;

    public DeepSeekProvider(AgentProperties props) {
        this.props = props;
        this.client = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                    setConnectTimeout(Duration.ofMillis(props.getTimeoutMs()));
                    setReadTimeout(Duration.ofMillis(props.getTimeoutMs()));
                }})
                .build();
        this.sseClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .build();
    }

    @Override
    public String name() { return "deepseek"; }

    @Override
    public boolean supportsToolCalling() { return true; }

    @Override
    public ChatResponse chat(ChatRequest request) {
        String apiKey = props.resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 PAMS_AGENT_API_KEY");
        }

        ObjectNode body = buildBody(request);

        String raw = client.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .body(String.class);

        return parse(raw);
    }

    /**
     * 流式对话（UI v2 字符级流式）。
     *
     * <p>走同一条 {@code /chat/completions}，加 {@code stream:true} 后返回是 SSE：
     * {@code data: {choices:[{delta:{content|tool_calls}}]}} 行流，以 {@code data: [DONE]} 收尾。
     *
     * <p><b>为什么工具调用也要边流边攒</b>：模型在一个回合里要么给文本要么给 tool_calls，
     * 但这只有流到才知道。文本片段边到边回调；tool_calls 的 {@code id/name/arguments}
     * 是<b>分片到达</b>的（arguments 尤其是逐字符的 JSON 文本），必须按 {@code index}
     * 拼回完整片段再 parse——拼错了工具参数就是坏的，而这类错误不会抛异常，
     * 只会表现为"参数校验拒绝"，非常难查。
     */
    @Override
    public ChatResponse chatStream(ChatRequest request, java.util.function.Consumer<String> onDelta) {
        String apiKey = props.resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 PAMS_AGENT_API_KEY");
        }

        ObjectNode body = buildBody(request);
        body.put("stream", true);

        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(props.getBaseUrl() + "/chat/completions"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT, "text/event-stream")
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        StringBuilder content = new StringBuilder();
        Map<Integer, StringBuilder> callIds = new LinkedHashMap<>();
        Map<Integer, StringBuilder> callNames = new LinkedHashMap<>();
        Map<Integer, StringBuilder> callArgs = new LinkedHashMap<>();

        try {
            java.net.http.HttpResponse<java.util.stream.Stream<String>> resp =
                    sseClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofLines());
            if (resp.statusCode() >= 300) {
                throw new IllegalStateException("LLM 流式响应异常：HTTP " + resp.statusCode());
            }
            // BodyHandlers.ofLines() 惰性求值：forEach 不结束连接就不关闭
            resp.body().forEach(line -> {
                if (line == null || line.isBlank() || !line.startsWith("data:")) return;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) return;
                try {
                    JsonNode node = mapper.readTree(data);
                    JsonNode delta = node.path("choices").path(0).path("delta");

                    String piece = delta.path("content").isNull() ? "" : delta.path("content").asText("");
                    if (!piece.isEmpty()) {
                        content.append(piece);
                        onDelta.accept(piece);
                    }

                    for (JsonNode tc : delta.path("tool_calls")) {
                        int idx = tc.path("index").asInt(0);
                        callIds.computeIfAbsent(idx, k -> new StringBuilder()).append(tc.path("id").asText(""));
                        JsonNode fn = tc.path("function");
                        callNames.computeIfAbsent(idx, k -> new StringBuilder()).append(fn.path("name").asText(""));
                        callArgs.computeIfAbsent(idx, k -> new StringBuilder()).append(fn.path("arguments").asText(""));
                    }
                } catch (Exception e) {
                    // 单行解析失败只丢这一行（通常是注释/心跳），不该让整轮失败
                    log.debug("忽略无法解析的 SSE 行：{}", line);
                }
            });
        } catch (java.io.IOException e) {
            throw new IllegalStateException("LLM 流式调用失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM 流式调用被中断", e);
        }

        List<ToolCallRequest> calls = new ArrayList<>();
        for (Map.Entry<Integer, StringBuilder> e : callIds.entrySet()) {
            Integer idx = e.getKey();
            String argsText = callArgs.get(idx).toString();
            Map<String, Object> args = new LinkedHashMap<>();
            if (!argsText.isBlank()) {
                try {
                    JsonNode an = mapper.readTree(argsText);
                    an.fields().forEachRemaining(en ->
                            args.put(en.getKey(), mapper.convertValue(en.getValue(), Object.class)));
                } catch (Exception ignored) {
                    // 参数不是合法 JSON：留空，由服务端参数校验拒绝
                }
            }
            calls.add(new ToolCallRequest(e.getValue().toString(), callNames.get(idx).toString(), args));
        }
        return new ChatResponse(content.toString(), calls, name(), false);
    }

    /** 组装请求体（流式与非流式共用，避免两条路径的字段漂移） */
    private ObjectNode buildBody(ChatRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.getModel());
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());

        ArrayNode msgs = body.putArray("messages");
        for (ChatMessage m : request.messages()) {
            ObjectNode n = msgs.addObject();
            n.put("role", m.role());
            // assistant 发起工具调用时 content 允许为空，但字段必须在（部分网关对 null 敏感）
            n.put("content", m.content() == null ? "" : m.content());

            // ---- OpenAI 兼容协议：tool 结果必须带 tool_call_id ----
            // 缺这个字段 DeepSeek 直接 400：missing field `tool_call_id`
            if ("tool".equals(m.role())) {
                if (m.toolCallId() == null || m.toolCallId().isBlank()) {
                    throw new IllegalStateException(
                            "tool 消息缺少 tool_call_id，无法构造合法的 OpenAI 兼容请求");
                }
                n.put("tool_call_id", m.toolCallId());
            }

            // ---- assistant 回合里的 tool_calls（与其后的 tool 结果成对出现） ----
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                ArrayNode tcs = n.putArray("tool_calls");
                for (ToolCallRequest tc : m.toolCalls()) {
                    ObjectNode t = tcs.addObject();
                    t.put("id", tc.id());
                    t.put("type", "function");
                    ObjectNode fn = t.putObject("function");
                    fn.put("name", tc.name());
                    // arguments 必须是**字符串**（JSON 文本），不是嵌套对象——协议如此
                    fn.put("arguments", writeArgs(tc.arguments()));
                }
            }
        }

        // 只有存在可见工具时才带 tools（不可见 ⇒ 不可诱导，D3）
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec t : request.tools()) {
                ObjectNode fn = tools.addObject().put("type", "function").putObject("function");
                fn.put("name", t.name());
                fn.put("description", t.description());
                fn.set("parameters", mapper.valueToTree(t.parameters()));
            }
            body.put("tool_choice", "auto");
        }

        return body;
    }

    /** 工具参数序列化为 JSON 文本（协议要求 arguments 是字符串而非对象） */
    private String writeArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "{}";
        try {
            return mapper.writeValueAsString(args);
        } catch (Exception e) {
            log.warn("工具参数序列化失败，退化为空对象：{}", e.getMessage());
            return "{}";
        }
    }

    private ChatResponse parse(String raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode choice = root.path("choices").path(0);
            JsonNode msg = choice.path("message");

            String content = msg.path("content").isNull() ? "" : msg.path("content").asText("");
            List<ToolCallRequest> calls = new ArrayList<>();
            for (JsonNode tc : msg.path("tool_calls")) {
                String id = tc.path("id").asText("");
                JsonNode fn = tc.path("function");
                String fname = fn.path("name").asText("");
                Map<String, Object> args = new LinkedHashMap<>();
                String argsText = fn.path("arguments").asText("");
                if (!argsText.isBlank()) {
                    try {
                        JsonNode an = mapper.readTree(argsText);
                        an.fields().forEachRemaining(e ->
                                args.put(e.getKey(), mapper.convertValue(e.getValue(), Object.class)));
                    } catch (Exception ignored) {
                        // 参数不是合法 JSON：留空，由服务端参数校验拒绝（D5 的"失败重试 1 次 + 降级"路径）
                    }
                }
                calls.add(new ToolCallRequest(id, fname, args));
            }
            return new ChatResponse(content, calls, name(), false);
        } catch (Exception e) {
            log.warn("DeepSeek 响应解析失败", e);
            throw new IllegalStateException("LLM 响应解析失败：" + e.getMessage(), e);
        }
    }
}
