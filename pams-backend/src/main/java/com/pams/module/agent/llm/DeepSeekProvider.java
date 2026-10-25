package com.pams.module.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pams.module.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

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
    /** 流式通道独立用 JDK HttpClient：RestClient 会把响应整体缓冲，拿不到逐行 SSE */
    private final java.net.http.HttpClient sseClient;

    public DeepSeekProvider(AgentProperties props) {
        this.props = props;
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

        // ⚠️ 刻意取 byte[] 而不是 String：真实 API 的响应头是 application/json（**不带 charset**），
        // 而 Spring 的 StringHttpMessageConverter 在缺 charset 时按 ISO-8859-1 解码 ——
        // 中文答复会变成一堆 ? 与乱码，且不报错。JSON 按 RFC 8259 就是 UTF-8，自己解码最稳。
        var requestMessage=java.net.http.HttpRequest.newBuilder(java.net.URI.create(props.getBaseUrl()+"/chat/completions"))
                .header(HttpHeaders.AUTHORIZATION,"Bearer "+apiKey).header(HttpHeaders.CONTENT_TYPE,MediaType.APPLICATION_JSON_VALUE)
                .timeout(Duration.ofMillis(props.getTimeoutMs())).POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString())).build();
        var pending=sseClient.sendAsync(requestMessage,java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        LlmCancellation.onCancel(()->pending.cancel(true));
        try {
            var response=pending.get(); LlmCancellation.check();
            if(response.statusCode()>=300) throw new IllegalStateException("LLM 响应异常：HTTP "+response.statusCode());
            return parse(new String(response.body(),java.nio.charset.StandardCharsets.UTF_8));
        } catch(InterruptedException e) { pending.cancel(true); Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException(); }
        catch(java.util.concurrent.ExecutionException e) { throw new IllegalStateException("模型连接失败",e.getCause()); }
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
        return chatStream(request, onDelta, null);
    }

    /**
     * 流式对话 + 思维链（{@code reasoning_content}）。
     *
     * <p>思考模式下 {@code delta} 里除了 {@code content} 还会给 {@code reasoning_content}：
     * 那是**模型真实推理原文**，与正文是两条独立流（同一时刻只有一条在产出）。
     * 这里把它同步回调给调用方（{@code AgentLlmCaller} 负责合并成 THINKING_DELTA 事件
     * 与落库），本方法只做"边到边转交"，不做合并 —— 合并阈值只有一处（DeltaBatch），
     * 否则两条链路（正文/推理）的节奏会各自漂移。
     *
     * <p>⚠️ 注意：思考模式现在由请求参数 {@code thinking} 控制（见 {@link #buildBody}），
     * 不再依赖 {@code deepseek-reasoner} 这个模型名。
     *
     * @param onReasoning 可为 null（调用方不关心推理时，行为与两参重载完全一致）。
     *                    无论它是否为 null，攒下的完整推理都会放进返回的
     *                    {@link ChatResponse#reasoningContent()} —— 工具循环要靠它回灌。
     */
    @Override
    public ChatResponse chatStream(ChatRequest request, java.util.function.Consumer<String> onDelta,
                                   java.util.function.Consumer<String> onReasoning) {
        String apiKey = props.resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 PAMS_AGENT_API_KEY");
        }

        ObjectNode body = buildBody(request);
        body.put("stream", true);
        body.putObject("stream_options").put("include_usage",true);

        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(props.getBaseUrl() + "/chat/completions"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT, "text/event-stream")
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Usage[] usage = new Usage[1];
        String[] actualModel = new String[]{null};
        String[] finishReason = new String[]{null};
        Map<Integer, StringBuilder> callIds = new LinkedHashMap<>();
        Map<Integer, StringBuilder> callNames = new LinkedHashMap<>();
        Map<Integer, StringBuilder> callArgs = new LinkedHashMap<>();

        try {
            var pending=sseClient.sendAsync(req,java.net.http.HttpResponse.BodyHandlers.ofLines());
            LlmCancellation.onCancel(()->pending.cancel(true));
            java.net.http.HttpResponse<java.util.stream.Stream<String>> resp = pending.get();
            LlmCancellation.onCancel(resp.body()::close);
            try(var lines=resp.body()) {
            if (resp.statusCode() >= 300) {
                throw new IllegalStateException("LLM 流式响应异常：HTTP " + resp.statusCode());
            }
            // BodyHandlers.ofLines() 惰性求值：forEach 不结束连接就不关闭
            lines.forEach(line -> {
                LlmCancellation.check();
                if (line == null || line.isBlank() || !line.startsWith("data:")) return;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) return;
                try {
                    JsonNode node = mapper.readTree(data);
                    if(node.hasNonNull("usage"))usage[0]=parseUsage(node.path("usage"));
                    if(node.hasNonNull("model"))actualModel[0]=node.path("model").asText();
                    if(node.path("choices").path(0).hasNonNull("finish_reason"))finishReason[0]=node.path("choices").path(0).path("finish_reason").asText();
                    JsonNode delta = node.path("choices").path(0).path("delta");

                    String piece = delta.path("content").isNull() ? "" : delta.path("content").asText("");
                    if (!piece.isEmpty()) {
                        content.append(piece);
                        onDelta.accept(piece);
                    }

                    // 思维链：思考模式（请求参数 thinking，官方默认开启）才有的字段；
                    // 关掉思考模式的请求这一行永远为空，零成本。
                    String think = delta.path("reasoning_content").isNull()
                            ? "" : delta.path("reasoning_content").asText("");
                    if (!think.isEmpty()) {
                        reasoning.append(think);
                        if (onReasoning != null) onReasoning.accept(think);
                    }

                    for (JsonNode tc : delta.path("tool_calls")) {
                        int idx = tc.path("index").asInt(0);
                        callIds.computeIfAbsent(idx, k -> new StringBuilder()).append(tc.path("id").asText(""));
                        JsonNode fn = tc.path("function");
                        callNames.computeIfAbsent(idx, k -> new StringBuilder()).append(fn.path("name").asText(""));
                        callArgs.computeIfAbsent(idx, k -> new StringBuilder()).append(fn.path("arguments").asText(""));
                    }
                } catch (java.util.concurrent.CancellationException e) { throw e;
                } catch (Exception e) {
                    // 单行解析失败只丢这一行（通常是注释/心跳），不该让整轮失败
                    log.debug("忽略无法解析的 SSE 行：{}", line);
                }
            });
            }
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("LLM 流式调用失败",e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("LLM 流式调用被中断");
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
        if (reasoning.length() > 0) {
            log.debug("思维链 {} 字 / 正文 {} 字", reasoning.length(), content.length());
        }
        // 攒下的推理**必须带在返回值里**，不能只走 onReasoning 回调：
        // 工具循环的下一轮要把本轮 assistant 的推理原文回灌（思考模式 + tools 的硬约束），
        // 而回调是"边到边展示"、调用方并不保证留存完整文本。
        return new ChatResponse(content.toString(), calls, name(), false,
                reasoning.length() == 0 ? null : reasoning.toString(),usage[0],actualModel[0],finishReason[0]);
    }

    /** 组装请求体（流式与非流式共用，避免两条路径的字段漂移） */
    private ObjectNode buildBody(ChatRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.getModel());
        // 思考模式下 temperature 不生效（传了不报错也不起作用）。
        // 仍然传：关闭思考模式时它是唯一的采样控制器，按开关删字段会让两条路径的请求体形状不一致
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());

        // ---- 思考模式（官方 2026-10-12 起是**参数**，不再是 deepseek-reasoner 这个模型名）----
        // 默认 enabled。它与工具调用的耦合见下面 messages 循环的说明
        // （官方契约要求回灌 assistant 的 reasoning_content；同日实测该约束当前未强制）。
        body.putObject("thinking").put("type", props.isThinking() ? "enabled" : "disabled");
        if (props.isThinking()) {
            // 关闭思考时**不发**这个字段：它此时不可能生效，发出去只会让"请求体里有什么"
            // 与"实际生效什么"对不上（排查线上问题时这点最容易误导）
            body.put("reasoning_effort", props.getReasoningEffort());
        }

        ArrayNode msgs = body.putArray("messages");
        for (ChatMessage m : request.messages()) {
            ObjectNode n = msgs.addObject();
            n.put("role", m.role());
            // assistant 发起工具调用时 content 允许为空，但字段必须在（部分网关对 null 敏感）
            n.put("content", m.content() == null ? "" : m.content());

            // ---- 思考模式 + tools 的耦合 ----
            // 官方契约：只要请求带过 tools，后续请求就必须**完整回传**历史 assistant 的
            // reasoning_content，否则 400。这里按消息上的字段原样带上；
            // 没有推理的回合（思考模式关闭 / 模型没给）不带该字段即可。
            // ⚠️ 已复现（2026-10-12 JVM 直连真 API）：带 tools + 历史 assistant(tool_calls) 且该轮
            // 产生过推理时，不带它会 **HTTP 400**（"The reasoning_content in the thinking mode must be
            // passed back to the API."）；thinking 关闭时不触发。所以这是必需品，不是保险。
            if (m.reasoningContent() != null && !m.reasoningContent().isBlank()) {
                n.put("reasoning_content", m.reasoningContent());
            }

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

    private Usage parseUsage(JsonNode usage) {
        if(!usage.path("prompt_tokens").isIntegralNumber() || !usage.path("completion_tokens").isIntegralNumber())return null;
        long input=usage.path("prompt_tokens").asLong(), output=usage.path("completion_tokens").asLong();
        if(input<0 || output<0)return null;
        return new Usage(input,output,usage.hasNonNull("prompt_cache_hit_tokens")?usage.path("prompt_cache_hit_tokens").asLong():null,usage.hasNonNull("prompt_cache_miss_tokens")?usage.path("prompt_cache_miss_tokens").asLong():null);
    }

    private ChatResponse parse(String raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode choice = root.path("choices").path(0);
            JsonNode msg = choice.path("message");

            String content = msg.path("content").isNull() ? "" : msg.path("content").asText("");
            // 非流式也要把推理带出来：它与流式路径是同一条业务链路的两种传输方式，
            // 少一边就会出现"流式有思维链、非流式没有"这种只在特定调用点复现的怪现象
            String reasoningContent = msg.path("reasoning_content").isNull()
                    ? "" : msg.path("reasoning_content").asText("");
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
            return new ChatResponse(content, calls, name(), false,
                    reasoningContent.isBlank() ? null : reasoningContent,parseUsage(root.path("usage")),root.path("model").asText(null),choice.path("finish_reason").asText(null));
        } catch (Exception e) {
            log.warn("DeepSeek 响应解析失败", e);
            throw new IllegalStateException("LLM 响应解析失败：" + e.getMessage(), e);
        }
    }
}
