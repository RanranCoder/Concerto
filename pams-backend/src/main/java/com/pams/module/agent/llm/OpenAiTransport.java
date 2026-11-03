package com.pams.module.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.URI;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.Objects;

/** Shared HTTP transport only. Protocols construct and parse their own native payloads. */
public final class OpenAiTransport {
    static final ObjectMapper JSON = new ObjectMapper();
    private static final CloseableHttpClient DEFAULT_CLIENT = com.pams.module.agent.provider.ProviderHttpClient.build(new com.pams.module.agent.provider.ProviderUrlPolicy(""));
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "model-deadlines"); t.setDaemon(true); return t;
    });
    private final ModelEndpoint endpoint;
    private final int timeout;
    private final CloseableHttpClient client;
    public OpenAiTransport(ModelEndpoint endpoint, int timeout) { this(endpoint, timeout, DEFAULT_CLIENT); }
    public OpenAiTransport(ModelEndpoint endpoint, int timeout, CloseableHttpClient client) { this.endpoint = endpoint; this.timeout = timeout; this.client = client; }
    public JsonNode send(String path, ObjectNode body, Consumer<JsonNode> event) {
        LlmCancellation.check();
        var request = new HttpPost(URI.create(endpoint.baseUrl().replaceAll("/+$", "") + path));
        request.setHeader("Authorization", "Bearer " + endpoint.apiKey());
        request.setHeader("Accept", event == null ? "application/json" : "text/event-stream");
        request.setEntity(new StringEntity(body.toString(), ContentType.APPLICATION_JSON));
        request.setConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofMilliseconds(timeout))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(timeout)).setRedirectsEnabled(false).build());
        LlmCancellation.onCancel(request::cancel);
        var deadline = DEADLINES.schedule(request::cancel, timeout, TimeUnit.MILLISECONDS);
        try {
            return client.execute(request, response -> {
            if (response.getCode() != 200) throw statusError(response.getCode());
            if (response.getEntity() == null) throw new IllegalStateException("模型服务返回空响应");
            InputStream input = response.getEntity().getContent();
            Runnable close = () -> { try { input.close(); } catch (IOException ignored) {} };
            LlmCancellation.onCancel(close);
            try (input) {
                if (event == null) return JSON.readTree(input);
                if (!Objects.toString(response.getEntity().getContentType(), "").contains("text/event-stream"))
                    throw new IllegalStateException("模型服务未返回 SSE");
                var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                StringBuilder data = new StringBuilder(); String line;
                while ((line = reader.readLine()) != null) {
                    LlmCancellation.check();
                    if (line.isEmpty()) { dispatch(data, event); }
                    else if (line.startsWith("data:")) {
                        if (!data.isEmpty()) data.append('\n');
                        data.append(line.substring(5).stripLeading());
                        if (data.length() > 4_000_000) throw new IllegalStateException("模型事件过大");
                    }
                }
                dispatch(data, event); LlmCancellation.check(); return null;
            }
            });
        } catch (IllegalStateException e) { throw e;
        } catch (Exception e) { LlmCancellation.check(); throw new IllegalStateException("模型连接或协议响应失败"); }
        finally { deadline.cancel(false); }
    }
    private static IllegalStateException statusError(int status) {
        String hint = switch (status) {
            case 401 -> "鉴权失败，请核对 API Key";
            case 403 -> "服务拒绝访问，请核对账号与模型权限";
            case 402 -> "服务要求付费，请核对账户状态";
            case 404 -> "接口或模型不存在，请核对地址、协议和模型 ID";
            case 408, 504 -> "服务响应超时";
            case 429 -> "请求被限流或配额不足，请稍后重试并核对配额";
            case 400, 422 -> "请求参数或所选协议不被模型支持";
            default -> status >= 500 ? "模型服务暂时不可用" : "请核对服务配置";
        };
        return new IllegalStateException("模型服务返回 HTTP " + status + "：" + hint);
    }
    private static void dispatch(StringBuilder data, Consumer<JsonNode> event) throws IOException {
        if (data.isEmpty()) return;
        String raw = data.toString(); data.setLength(0);
        if (!"[DONE]".equals(raw)) {
            JsonNode node = JSON.readTree(raw);
            if (node.has("error")) throw new IllegalStateException("模型服务返回错误事件");
            event.accept(node);
        }
    }
    static java.util.Map<String,Object> arguments(JsonNode value) {
        try {
            JsonNode args = JSON.readTree(value.asText("{}"));
            if (!args.isObject()) throw new IllegalArgumentException();
            return JSON.convertValue(args, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) { throw new IllegalStateException("模型工具参数不是合法 JSON 对象"); }
    }
}
