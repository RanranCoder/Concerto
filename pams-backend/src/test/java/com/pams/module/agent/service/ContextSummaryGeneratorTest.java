package com.pams.module.agent.service;
import com.pams.module.agent.llm.LlmProvider;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextSummaryGeneratorTest {
    @Test void isolatedCallHasNoToolsAndRejectsDegradedOrTruncatedOutput() {
        var caller=mock(AgentLlmCaller.class);var generator=new ContextSummaryGenerator(caller);
        when(caller.callSummary(anyList(),eq("s"))).thenReturn(LlmProvider.ChatResponse.stub("规则回答"));
        assertThatThrownBy(()->generator.generate(List.of(),List.of(new ConversationMemory.Source(1L,"USER","目标")),"s",3000))
                .isInstanceOf(IllegalStateException.class);
        when(caller.callSummary(anyList(),eq("s"))).thenReturn(new LlmProvider.ChatResponse("{",List.of(),"test",false,null,null,null,"length"));
        assertThatThrownBy(()->generator.generate(List.of(),List.of(),"s",3000)).isInstanceOf(IllegalStateException.class);
        when(caller.callSummary(anyList(),eq("s"))).thenReturn(LlmProvider.ChatResponse.text("{\"items\":[]}","test"));
        assertThat(generator.generate(List.of(),List.of(),"s",3000)).isEqualTo("{\"items\":[]}");
    }
}
