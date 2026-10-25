package com.pams.module.agent.llm;

import com.pams.module.agent.config.AgentProperties;
import com.pams.module.agent.service.AgentLlmCaller;
import com.pams.module.agent.stream.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmCancellationTest {
    @Test void abortInterruptsWorkerClosesTransportAndDoesNotRetryOrFallback() throws Exception {
        var lifecycle=new LlmCancellation();var primary=mock(LlmProvider.class);var fallback=mock(LlmProvider.class);
        var publisher=mock(AgentEventPublisher.class);var started=new CountDownLatch(1);var closed=new CountDownLatch(1);
        when(primary.name()).thenReturn("test");
        when(primary.chat(any())).thenAnswer(i->{LlmCancellation.onCancel(closed::countDown);started.countDown();Thread.sleep(60000);return LlmProvider.ChatResponse.text("late","test");});
        var caller=new AgentLlmCaller(primary,fallback,new AgentProperties(),publisher,new ThinkingTrace());
        ReflectionTestUtils.setField(caller,"cancellation",lifecycle);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var result=pool.submit(()->caller.callIsolated(List.of(),List.of(),"cancel"));
            assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();lifecycle.abort("cancel");
            assertThat(closed.await(2,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->result.get(2,TimeUnit.SECONDS)).hasCauseInstanceOf(CancellationException.class);
            verify(primary,times(1)).chat(any());verifyNoInteractions(fallback);assertThat(lifecycle.activeCount()).isZero();
        }
    }
    @Test void cancellingRealSseClosesTheHttpResponseBody() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var started=new CountDownLatch(1);var disconnected=new CountDownLatch(1);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/chat/completions",exchange->{
            exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            try(var body=exchange.getResponseBody()) {
                body.write("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n".getBytes());body.flush();started.countDown();
                for(int i=0;i<400;i++) {Thread.sleep(10);body.write(": heartbeat\n\n".getBytes());body.flush();}
            } catch(Exception expected) {disconnected.countDown();} finally {exchange.close();}
        });
        server.start();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var props=new AgentProperties();props.setApiKey("local-test-only");props.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
            var provider=new DeepSeekProvider(props);var lifecycle=new LlmCancellation();
            var request=new LlmProvider.ChatRequest(List.of(LlmProvider.ChatMessage.text("user","test")),List.of(),0,10);
            var result=pool.submit(()->lifecycle.call("sse",()->false,10000,()->provider.chatStream(request,text->{})));
            assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();lifecycle.abort("sse");
            assertThatThrownBy(()->result.get(2,TimeUnit.SECONDS)).hasCauseInstanceOf(CancellationException.class);
            assertThat(disconnected.await(3,TimeUnit.SECONDS)).as("Server observes closed connection").isTrue();
        } finally {server.stop(0);}
    }
}
