package com.pams.module.agent.llm;

import com.pams.module.agent.config.AgentProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式通道（UI v2 字符级流式）的确定性回归。
 *
 * <p><b>为什么值得起一个本地 HTTP 服务来测</b>：这条链路上最脆的不是"能不能调通"，
 * 而是 SSE 行解析与 tool_calls 分片拼装 —— 后者拼错<b>不会抛异常</b>，
 * 只会表现为"参数校验拒绝"，在生产上极难定位。
 * 用本地假服务喂一份<b>精心构造的分片</b>（arguments 被故意切成两半），
 * 就能在测试期把拼装逻辑钉死，完全不需要真实的 API Key 与外网。
 *
 * <p>顺带覆盖两条契约：{@code [DONE]} 结束符、非 {@code data:} 行（心跳/注释）被忽略。
 */
class DeepSeekStreamTest {

    private HttpServer server;
    private DeepSeekProvider provider;
    /** 捕获 onDelta 回调，断言"增量确实分了段"而不是一次性给整段 */
    private final List<String> deltas = new ArrayList<>();

    /** JDK HttpServer 的 context 不支持反复 remove，这里用可替换的响应体实现"同一服务多份剧本" */
    private volatile String responseBody = "";
    private volatile int responseStatus = 200;
    /**
     * 捕获最后一次请求体。
     *
     * <p>思考模式的三条契约（{@code thinking} 参数、{@code reasoning_effort} 档位、
     * assistant 回灌 {@code reasoning_content}）**只能从请求体断言** —— 它们不影响任何返回值，
     * 漏掉的表现是真实 API 返回 400，而假服务永远不报错。
     */
    private volatile String lastRequestBody = "";
    /** 响应头类型；空 = 按状态码推断（200 → text/event-stream，其余 → application/json） */
    private volatile String responseContentType = "";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            String contentType = responseContentType.isEmpty()
                    ? (responseStatus >= 300 ? "application/json" : "text/event-stream")
                    : responseContentType;
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        AgentProperties props = new AgentProperties();
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setApiKey("test-key");
        // 与 AgentProperties 的默认值保持一致：model id 随官方调整（2026-10-12 起 deepseek-chat 已不在取值列表）
        props.setModel("deepseek-flash");
        props.setTimeoutMs(5000);
        provider = new DeepSeekProvider(props);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private void serve(String sse) {
        this.responseStatus = 200;
        this.responseContentType = "";
        this.responseBody = sse;
    }

    /**
     * 非流式剧本：响应头刻意用<b>不带 charset 的 {@code application/json}</b> ——
     * 真实 API 就是这样，而 Spring 的 StringHttpMessageConverter 在缺 charset 时按
     * ISO-8859-1 解码（中文答复全乱码且不报错）。这条剧本因此同时钉住"非流式能拿到推理"
     * 与"中文不乱码"两件事。
     */
    private void serveJson(String json) {
        this.responseStatus = 200;
        this.responseContentType = "application/json";
        this.responseBody = json;
    }

    private void serveError(int status, String body) {
        this.responseStatus = status;
        this.responseContentType = "";
        this.responseBody = body;
    }

    private LlmProvider.ChatRequest request() {
        return new LlmProvider.ChatRequest(List.of(), List.of(), 0.3, 128);
    }

    @Test
    void streamsContentDeltasAndAssemblesFullText() {
        serve("""
                data: {"choices":[{"delta":{"content":"你好"}}]}

                data: {"choices":[{"delta":{"content":"，世界"}}]}

                data: {"choices":[{"delta":{}}]}

                data: [DONE]

                """);

        LlmProvider.ChatResponse resp = provider.chatStream(request(), deltas::add);

        assertThat(deltas).as("增量必须分段回调，而不是一次性给整段").containsExactly("你好", "，世界");
        assertThat(resp.content()).isEqualTo("你好，世界");
        assertThat(resp.toolCalls()).isEmpty();
        assertThat(resp.degraded()).isFalse();
    }

    @Test
    void reassemblesFragmentedToolCallArguments() {
        // arguments 被故意切成两半：SSE 协议下 tool_calls 的 arguments 是逐字符到达的
        serve("""
                data: {"choices":[{"delta":{"content":"我先建一个活动。"}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"createActivity","arguments":"{\\"name\\""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":":\\"测试活动\\"}"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_2","function":{"name":"sendNotification","arguments":"{}"}}]}}]}

                data: [DONE]

                """);

        LlmProvider.ChatResponse resp = provider.chatStream(request(), deltas::add);

        assertThat(resp.content()).isEqualTo("我先建一个活动。");
        assertThat(resp.toolCalls()).hasSize(2);

        LlmProvider.ToolCallRequest first = resp.toolCalls().get(0);
        assertThat(first.id()).isEqualTo("call_1");
        assertThat(first.name()).isEqualTo("createActivity");
        assertThat(first.arguments()).as("分片参数必须拼回完整 JSON").containsEntry("name", "测试活动");

        assertThat(resp.toolCalls().get(1).id()).isEqualTo("call_2");
        assertThat(resp.toolCalls().get(1).arguments()).isEmpty();
    }

    @Test
    void ignoresNonDataLinesAndBlankChunks() {
        // 心跳注释、空 data 行、无 choices 的 usage 块都不该进 content
        serve("""
                : keep-alive

                data: {"choices":[{"delta":{"content":"A"}}]}

                data: {"usage":{"total_tokens":10}}

                data: [DONE]

                """);

        LlmProvider.ChatResponse resp = provider.chatStream(request(), deltas::add);

        assertThat(deltas).containsExactly("A");
        assertThat(resp.content()).isEqualTo("A");
    }

    @Test
    void httpErrorSurfacesAsFailure() {
        serveError(401, "{\"error\":{\"message\":\"bad key\"}}");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.chatStream(request(), deltas::add))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401");
    }

    /**
     * 思维链（V35）：{@code reasoning_content} 必须走**独立的回调通道**。
     *
     * <p>为什么不能塞进 onDelta：那是正文（用户读到的答复）。混在一起会出现
     * "推理被当成答复显示给用户"，而这正是思维链展示要避免的事 ——
     * 推理要能单独折叠、单独落库、单独隐藏。
     */
    @Test
    void streamsReasoningDeltasSeparatelyFromContent() {
        serve("""
                data: {"choices":[{"delta":{"reasoning_content":"先看"}}]}

                data: {"choices":[{"delta":{"reasoning_content":"活动时间"}}]}

                data: {"choices":[{"delta":{"content":"结论：可以办"}}]}

                data: [DONE]

                """);

        List<String> reasoning = new ArrayList<>();
        LlmProvider.ChatResponse resp = provider.chatStream(request(), deltas::add, reasoning::add);

        assertThat(reasoning).as("推理原文必须逐段回调，且不混进正文").containsExactly("先看", "活动时间");
        assertThat(deltas).containsExactly("结论：可以办");
        assertThat(resp.content()).isEqualTo("结论：可以办");
    }

    /**
     * 不支持思维链的模型/调用方走两参重载：reasoning 字段被忽略，行为与改动前完全一致
     * （非 reasoner 模型的 SSE 里根本没有该字段，但这条断言把"兼容"钉死）。
     */
    @Test
    void reasoningIsIgnoredWhenNotRequested() {
        serve("""
                data: {"choices":[{"delta":{"reasoning_content":"只在推理里"}}]}

                data: {"choices":[{"delta":{"content":"正文"}}]}

                data: [DONE]

                """);

        LlmProvider.ChatResponse resp = provider.chatStream(request(), deltas::add);

        assertThat(resp.content()).isEqualTo("正文");
        assertThat(deltas).containsExactly("正文");
    }

    /* ================================================================= 思考模式（2026-10-12 契约） */

    /**
     * 流式：攒下的推理必须进 {@link LlmProvider.ChatResponse#reasoningContent()}，不只是走回调。
     *
     * <p>为什么这条值得钉死：工具循环的下一轮要把本轮推理**完整回灌**（思考模式 + tools 的硬约束），
     * 而 {@code onReasoning} 回调只保证"边到边展示"，调用方不保证留存完整文本。
     * 只测回调不测返回值，就会漏掉"能看但不能回灌"这个致命缺口。
     */
    @Test
    void streamingResponseCarriesReasoningContent() {
        serve("""
                data: {"choices":[{"delta":{"reasoning_content":"先看"}}]}

                data: {"choices":[{"delta":{"reasoning_content":"活动时间"}}]}

                data: {"choices":[{"delta":{"content":"结论：可以办"}}]}

                data: [DONE]

                """);

        List<String> reasoning = new ArrayList<>();
        LlmProvider.ChatResponse resp = provider.chatStream(request(), deltas::add, reasoning::add);

        assertThat(resp.reasoningContent()).as("返回值必须带完整推理，供下一轮回灌").isEqualTo("先看活动时间");
        assertThat(resp.content()).isEqualTo("结论：可以办");
        assertThat(reasoning).containsExactly("先看", "活动时间");
    }

    /** 非流式：从 {@code choices[0].message.reasoning_content} 取，两条传输路径行为必须一致 */
    @Test
    void nonStreamingResponseCarriesReasoningContent() {
        serveJson("{\"choices\":[{\"message\":{\"content\":\"答\",\"reasoning_content\":\"想过了\"}}]}");

        LlmProvider.ChatResponse resp = provider.chat(request());

        assertThat(resp.reasoningContent()).isEqualTo("想过了");
        assertThat(resp.content())
                .as("缺 charset 的 application/json 必须按 UTF-8 解码，否则中文全乱码")
                .isEqualTo("答");
    }

    /** 模型没给推理（思考模式关闭 / 普通模型）→ 字段为 null，而不是空字符串 */
    @Test
    void absentReasoningContentStaysNull() {
        serve("""
                data: {"choices":[{"delta":{"content":"正文"}}]}

                data: [DONE]

                """);

        LlmProvider.ChatResponse resp = provider.chatStream(request(), deltas::add);

        assertThat(resp.reasoningContent()).isNull();
    }

    /**
     * ② assistant 消息带 reasoningContent → 序列化后请求体**必须含** {@code reasoning_content}。
     * 缺了它就是"第一次工具调用之后每轮 400"（这条只在真实 API 上暴露，假服务不报错）。
     */
    @Test
    void assistantMessageSerializesReasoningContent() {
        serve("data: [DONE]\n\n");

        LlmProvider.ChatRequest req = new LlmProvider.ChatRequest(
                List.of(LlmProvider.ChatMessage.assistantCalling("我先查一下",
                        List.of(new LlmProvider.ToolCallRequest("call_1", "queryMyPendingWork", Map.of())),
                        "上一轮的推理原文")),
                List.of(), 0.3, 128);

        provider.chatStream(req, deltas::add);

        assertThat(lastRequestBody)
                .as("思考模式 + tools：回灌的 assistant 必须带 reasoning_content")
                .contains("\"reasoning_content\":\"上一轮的推理原文\"");
        assertThat(lastRequestBody).contains("\"tool_calls\"");
    }

    /** 没有推理的 assistant 回合不该凭空多一个字段（老行为零变化） */
    @Test
    void assistantMessageWithoutReasoningOmitsField() {
        serve("data: [DONE]\n\n");

        LlmProvider.ChatRequest req = new LlmProvider.ChatRequest(
                List.of(LlmProvider.ChatMessage.assistantCalling("我先查一下", List.of())),
                List.of(), 0.3, 128);

        provider.chatStream(req, deltas::add);

        assertThat(lastRequestBody).doesNotContain("reasoning_content");
    }

    /** ③ 请求体必须携带 thinking 与 reasoning_effort（思考模式是参数，不是模型名） */
    @Test
    void requestBodyCarriesThinkingMode() {
        serve("data: [DONE]\n\n");

        provider.chatStream(request(), deltas::add);

        assertThat(lastRequestBody).contains("\"thinking\":{\"type\":\"enabled\"}");
        assertThat(lastRequestBody).contains("\"reasoning_effort\":\"high\"");
        assertThat(lastRequestBody).as("模型 id 必须取配置值").contains("\"model\":\"deepseek-flash\"");
    }

    /** 关掉思考模式 → thinking 为 disabled，且不再发 inference 不生效的 reasoning_effort */
    @Test
    void thinkingCanBeDisabled() {
        AgentProperties off = new AgentProperties();
        off.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        off.setApiKey("test-key");
        off.setThinking(false);
        DeepSeekProvider offProvider = new DeepSeekProvider(off);
        serve("data: [DONE]\n\n");

        offProvider.chatStream(request(), deltas::add);

        assertThat(lastRequestBody).contains("\"thinking\":{\"type\":\"disabled\"}");
        assertThat(lastRequestBody).doesNotContain("reasoning_effort");
    }

    /**
     * ④ 带 tools 时 {@code tool_choice} 必须仍是 {@code auto}。
     *
     * <p>官方明确：思考模式下 {@code tool_choice} <b>不支持</b> {@code required}、
     * 也不支持指定具体工具（直接 400）。这条断言守住"以后有人为了强制调用工具改成 required"。
     */
    @Test
    void toolChoiceStaysAutoWithTools() {
        serve("data: [DONE]\n\n");

        LlmProvider.ToolSpec spec = new LlmProvider.ToolSpec("queryMyPendingWork", "查询我的待办",
                Map.of("type", "object", "properties", Map.of()));
        LlmProvider.ChatRequest req = new LlmProvider.ChatRequest(
                List.of(LlmProvider.ChatMessage.text("user", "我有什么待办")), List.of(spec), 0.3, 128);

        provider.chatStream(req, deltas::add);

        assertThat(lastRequestBody).contains("\"tool_choice\":\"auto\"");
        assertThat(lastRequestBody)
                .as("思考模式下 required / 指定工具会 400，绝不能出现在请求体里")
                .doesNotContain("\"required\"")
                .doesNotContain("tool_choice\":{\"type\"");
    }

    /** 无可见工具时不带 tools（不可见 ⇒ 不可诱导，D3）：这条老契约不能被思考模式改动破坏 */
    @Test
    void toolsAreOmittedWhenNoneVisible() {
        serve("data: [DONE]\n\n");

        provider.chatStream(request(), deltas::add);

        assertThat(lastRequestBody).doesNotContain("\"tools\"");
        assertThat(lastRequestBody).doesNotContain("tool_choice");
    }
    @Test void capturesStreamingUsageEvenWhenTheFinalChunkHasNoChoices() {
        serve("data: {\"model\":\"actual-model\",\"choices\":[{\"delta\":{\"content\":\"完成\"},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"prompt_cache_hit_tokens\":40,\"prompt_cache_miss_tokens\":60}}\n\n"
            + "data: [DONE]\n\n");
        var response=provider.chatStream(new LlmProvider.ChatRequest(List.of(),List.of(),0.2,256),deltas::add);
        assertThat(response.content()).isEqualTo("完成");assertThat(response.model()).isEqualTo("actual-model");
        assertThat(response.finishReason()).isEqualTo("stop");assertThat(response.usage().inputTokens()).isEqualTo(100);
        assertThat(response.usage().cacheHitTokens()).isEqualTo(40L);assertThat(lastRequestBody).contains("include_usage");
    }
    @Test void capturesActualNonStreamingModelAndDoesNotInventMissingUsage() {
        serveJson("{\"model\":\"actual-model\",\"choices\":[{\"message\":{\"content\":\"完成\"},\"finish_reason\":\"length\"}]}");
        var response=provider.chat(new LlmProvider.ChatRequest(List.of(),List.of(),0.2,256));
        assertThat(response.model()).isEqualTo("actual-model");assertThat(response.finishReason()).isEqualTo("length");assertThat(response.usage()).isNull();
    }

}
