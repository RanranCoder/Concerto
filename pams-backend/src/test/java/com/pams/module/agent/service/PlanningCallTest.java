package com.pams.module.agent.service;
import com.pams.module.agent.config.AgentProperties;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.stream.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanningCallTest {
    @Test void configuredServiceWindowAppliesToPlanningAndSpecialistCalls() {
        var primary = mock(LlmProvider.class);
        when(primary.name()).thenReturn("test");
        when(primary.chat(any())).thenReturn(LlmProvider.ChatResponse.text("{}", "test"));
        when(primary.contextWindowTokens()).thenReturn(128_000);
        var caller = new AgentLlmCaller(primary, mock(LlmProvider.class), new AgentProperties(),
                mock(AgentEventPublisher.class), mock(ThinkingTrace.class));
        var messages = List.of(LlmProvider.ChatMessage.text("user", "x".repeat(80_000)));
        caller.callPlanning(messages, "preview");
        caller.callIsolated(messages, List.of(), "specialist");
        verify(primary, times(2)).chat(any());
    }
    @Test void planningUsesNoToolsBoundedOutputAndOneTransportAttempt(){
        var primary=mock(LlmProvider.class);var stub=mock(LlmProvider.class);var trace=mock(ThinkingTrace.class);
        when(primary.name()).thenReturn("test");when(primary.chat(any())).thenReturn(LlmProvider.ChatResponse.text("{}","test"));
        var caller=new AgentLlmCaller(primary,stub,new AgentProperties(),mock(AgentEventPublisher.class),trace);
        caller.callPlanning(List.of(LlmProvider.ChatMessage.text("user","规划预览")),"preview");
        var request=ArgumentCaptor.forClass(LlmProvider.ChatRequest.class);verify(primary).chat(request.capture());
        assertThat(request.getValue().tools()).isEmpty();assertThat(request.getValue().maxTokens()).isEqualTo(2048);verifyNoInteractions(stub,trace);
    }
    @Test void providerFailureDoesNotTriggerTransportRetryOrStubFallback(){
        var primary=mock(LlmProvider.class);var stub=mock(LlmProvider.class);when(primary.name()).thenReturn("test");when(primary.chat(any())).thenThrow(new IllegalStateException("offline"));
        var caller=new AgentLlmCaller(primary,stub,new AgentProperties(),mock(AgentEventPublisher.class),mock(ThinkingTrace.class));
        assertThatThrownBy(()->caller.callPlanning(List.of(LlmProvider.ChatMessage.text("user","目标")),"preview")).hasMessage("offline");verify(primary).chat(any());verifyNoInteractions(stub);
    }
    @Test void insufficientContextBudgetFailsBeforeTransportInsteadOfTruncatingJson(){
        var primary=mock(LlmProvider.class);var props=new AgentProperties();props.setContextWindowTokens(128);
        var caller=new AgentLlmCaller(primary,mock(LlmProvider.class),props,mock(AgentEventPublisher.class),mock(ThinkingTrace.class));
        assertThatThrownBy(()->caller.callPlanning(List.of(LlmProvider.ChatMessage.text("user","输入JSON")),"preview")).hasMessageContaining("budget");verify(primary,never()).chat(any());
    }
    @Test void nativeProtocolStateAlsoConsumesInputBudget(){
        var primary=mock(LlmProvider.class);var props=new AgentProperties();props.setContextWindowTokens(4096);
        var caller=new AgentLlmCaller(primary,mock(LlmProvider.class),props,mock(AgentEventPublisher.class),mock(ThinkingTrace.class));
        var response=new LlmProvider.ChatResponse("{}",List.of(),"test",false,null,null,"model","stop",List.of(java.util.Map.of("type","reasoning","encrypted_content","x".repeat(20000))));
        assertThatThrownBy(()->caller.callPlanning(List.of(LlmProvider.ChatMessage.assistantCalling(response)),"preview")).hasMessageContaining("budget");verify(primary,never()).chat(any());
    }
}
