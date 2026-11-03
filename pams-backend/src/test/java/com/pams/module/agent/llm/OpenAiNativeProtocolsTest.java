package com.pams.module.agent.llm;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class OpenAiNativeProtocolsTest {
    private final org.apache.hc.client5.http.impl.classic.CloseableHttpClient local = com.pams.module.agent.provider.ProviderHttpClient.build(new com.pams.module.agent.provider.ProviderUrlPolicy("127.0.0.1"));
    private HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final Queue<String> replies = new ConcurrentLinkedQueue<>();
    private String contentType = "application/json";
    private int status = 200;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            byte[] bytes = replies.remove().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType); exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        }); server.start();
    }
    @AfterEach void stop() throws Exception { server.stop(0); local.close(); }
    private ModelEndpoint endpoint(String protocol) { return new ModelEndpoint(protocol, "http://127.0.0.1:"+server.getAddress().getPort()+"/v1/", "test-only", "model-id", true); }
    private LlmProvider.ChatRequest request(List<LlmProvider.ChatMessage> messages) {
        return new LlmProvider.ChatRequest(messages, List.of(new LlmProvider.ToolSpec("probe", "probe", Map.of("type", "object", "properties", Map.of()))), 0.3, 128);
    }
    @Test void chatUsesOnlyNativeFieldsAndIgnoresVendorReasoning() {
        replies.add("""
            {"model":"actual","choices":[{"message":{"content":"中文回答","reasoning_content":"vendor-private"},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":2}}
            """);
        var provider = new OpenAiChatCompletionsProvider(endpoint("openai_chat_completions"), 3000, local);
        var response = provider.chat(request(List.of(LlmProvider.ChatMessage.text("user", "hello"))));
        assertThat(response.content()).isEqualTo("中文回答"); assertThat(response.reasoningContent()).isNull();
        assertThat(response.usage().inputTokens()).isEqualTo(4);
        var sent = requests.getFirst(); assertThat(sent.has("messages")).isTrue(); assertThat(sent.has("input")).isFalse();
        assertThat(sent.toString()).doesNotContain("thinking", "reasoning_content", "reasoning_effort");
        assertThat(sent.path("tools").path(0).path("function").path("name").asText()).isEqualTo("probe");
    }
    @Test void chatReassemblesFragmentedToolArguments() {
        contentType = "text/event-stream";
        replies.add("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"probe\",\"arguments\":\"{\"}}]}}]}\n\n"+
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n");
        var response = new OpenAiChatCompletionsProvider(endpoint("openai_chat_completions"),3000,local)
                .chatStream(request(List.of(LlmProvider.ChatMessage.text("user","tool"))), s -> {});
        assertThat(response.toolCalls()).hasSize(1); assertThat(response.toolCalls().getFirst().id()).isEqualTo("call-1");
        assertThat(response.toolCalls().getFirst().arguments()).isEmpty();
    }
    private String nativeOutput() { return """
        {"status":"completed","model":"actual","output":[
         {"id":"rs_1","type":"reasoning","summary":[{"type":"summary_text","text":"公开摘要"}],"encrypted_content":"opaque-state"},
         {"id":"fc_1","type":"function_call","call_id":"call-1","name":"probe","arguments":"{}","status":"completed"}
        ],"usage":{"input_tokens":5,"output_tokens":2}}
        """; }
    @Test void responsesRetainsNativeItemsThroughToolRoundTrip() throws Exception {
        replies.add(nativeOutput());
        replies.add("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"完成\"}]}]}");
        var provider = new OpenAiResponsesProvider(endpoint("openai_responses"),3000,local);
        var messages = new ArrayList<LlmProvider.ChatMessage>(); messages.add(LlmProvider.ChatMessage.text("user","hello"));
        var first = provider.chat(request(messages));
        assertThat(first.reasoningContent()).isEqualTo("公开摘要");
        messages.add(LlmProvider.ChatMessage.assistantCalling(first)); messages.add(LlmProvider.ChatMessage.toolResult("call-1","OK"));
        assertThat(provider.chat(request(messages)).content()).isEqualTo("完成");
        JsonNode sent = requests.get(1), input = sent.path("input");
        assertThat(sent.has("messages")).isFalse(); assertThat(sent.path("store").asBoolean()).isFalse();
        assertThat(input.get(1)).isEqualTo(json.readTree(nativeOutput()).path("output").get(0));
        assertThat(input.get(2).path("type").asText()).isEqualTo("function_call");
        assertThat(input.get(3).path("type").asText()).isEqualTo("function_call_output");
        assertThat(input.get(3).path("call_id").asText()).isEqualTo("call-1");
        assertThat(sent.path("tools").path(0).path("name").asText()).isEqualTo("probe");
        assertThat(sent.path("tools").path(0).has("function")).isFalse();
    }
    @Test void responsesStreamsSummaryAndTextSeparately() {
        contentType="text/event-stream";
        replies.add("data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"摘要\"}\n\n"+
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"回答\"}\n\n"+
            "data: {\"type\":\"response.completed\",\"response\":"+nativeOutput().replace("\n", "")+"}\n\n");
        var text=new StringBuilder();var summary=new StringBuilder();
        var response=new OpenAiResponsesProvider(endpoint("openai_responses"),3000,local)
            .chatStream(request(List.of(LlmProvider.ChatMessage.text("user","hello"))),text::append,summary::append);
        assertThat(text.toString()).isEqualTo("回答"); assertThat(summary.toString()).isEqualTo("摘要");
        assertThat(response.nativeItems()).hasSize(2); assertThat(text.toString()+summary).doesNotContain("opaque-state");
    }
    @Test void truncatedStreamsFailInsteadOfReportingSuccess() {
        contentType="text/event-stream";replies.add("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n");
        assertThatThrownBy(()->new OpenAiResponsesProvider(endpoint("openai_responses"),3000,local)
            .chatStream(request(List.of()),s->{})).isInstanceOf(IllegalStateException.class).hasMessageContaining("提前结束");
    }
    @Test void errorBodiesCannotLeakProviderCredentials() {
        status=401;replies.add("upstream echoed test-only and private credential details");
        assertThatThrownBy(()->new OpenAiResponsesProvider(endpoint("openai_responses"),3000,local).chat(request(List.of())))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP 401").hasMessageContaining("鉴权失败")
            .hasMessageNotContaining("test-only").hasMessageNotContaining("private credential");
    }
}
