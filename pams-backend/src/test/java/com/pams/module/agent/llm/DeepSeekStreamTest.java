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

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type",
                    responseStatus >= 300 ? "application/json" : "text/event-stream");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        AgentProperties props = new AgentProperties();
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setApiKey("test-key");
        props.setModel("deepseek-chat");
        props.setTimeoutMs(5000);
        provider = new DeepSeekProvider(props);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private void serve(String sse) {
        this.responseStatus = 200;
        this.responseBody = sse;
    }

    private void serveError(int status, String body) {
        this.responseStatus = status;
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
}
