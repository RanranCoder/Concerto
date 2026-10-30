package com.pams.module.agent.service;
import com.pams.module.agent.config.AgentProperties;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.stream.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ContextSummaryCallTest {
    @Test void failedCompactionHasOneAttemptAndNeverFallsBackIntoAnAnswer() {
        var primary=mock(LlmProvider.class);var stub=mock(LlmProvider.class);
        var trace=mock(ThinkingTrace.class);var publisher=mock(AgentEventPublisher.class);
        when(primary.name()).thenReturn("test");when(primary.chat(any())).thenThrow(new IllegalStateException("offline"));
        var caller=new AgentLlmCaller(primary,stub,new AgentProperties(),publisher,trace);
        assertThatThrownBy(()->caller.callSummary(List.of(LlmProvider.ChatMessage.text("user","目标")),"s"))
                .isInstanceOf(IllegalStateException.class).hasMessage("offline");
        verify(primary).chat(any());verifyNoInteractions(stub,trace);
    }
    @Test void summaryRequestHasNoToolsAndUsesSeparateOutputCap() {
        var primary=mock(LlmProvider.class);when(primary.name()).thenReturn("test");
        when(primary.chat(any())).thenReturn(LlmProvider.ChatResponse.text("{\"items\":[]}","test"));
        var props=new AgentProperties();props.setContextSummaryMaxTokens(2048);
        var caller=new AgentLlmCaller(primary,mock(LlmProvider.class),props,mock(AgentEventPublisher.class),mock(ThinkingTrace.class));
        caller.callSummary(List.of(LlmProvider.ChatMessage.text("user","目标")),"s");
        var request=ArgumentCaptor.forClass(LlmProvider.ChatRequest.class);verify(primary).chat(request.capture());
        assertThat(request.getValue().tools()).isEmpty();assertThat(request.getValue().maxTokens()).isEqualTo(2048);
    }
}
