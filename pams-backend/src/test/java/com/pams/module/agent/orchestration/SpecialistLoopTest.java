package com.pams.module.agent.orchestration;
import com.pams.module.agent.core.*;
import com.pams.module.agent.config.AgentProperties;
import com.pams.module.agent.entity.AgentDefinition;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.registry.AgentRegistry;
import com.pams.module.agent.service.*;
import com.pams.module.agent.stream.AgentEventPublisher;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class SpecialistLoopTest {
    @Test void toolFactsReturnToTheModelWithTheRequiredCallingAndReasoningPair() {
        var registry=mock(AgentRegistry.class); var tools=mock(ToolRegistry.class);
        var caller=mock(AgentLlmCaller.class); var publisher=mock(AgentEventPublisher.class);
        var actions=mock(ToolOutcomeHandler.class); var audit=mock(AuditService.class);
        var props=new AgentProperties(); props.setMaxToolIterations(2);
        var ctx=ToolContext.current(null,null);
        var definition=new AgentDefinition(); definition.setName("retrieval"); definition.setRolePrompt("查询事实");
        when(registry.byName("retrieval")).thenReturn(Optional.of(definition));
        when(registry.toolScopeOf(definition)).thenReturn(List.of("query"));
        var tool=mock(AgentTool.class); when(tool.name()).thenReturn("query"); when(tool.label()).thenReturn("查询");
        when(tool.sensitivity()).thenReturn(Sensitivity.NORMAL); when(tool.parametersSchema()).thenReturn(Map.of());
        when(tools.visibleTools(ctx)).thenReturn(List.of(tool)); when(tools.find("query")).thenReturn(Optional.of(tool));
        var facts=ToolResult.ok("活动地点为A楼"); when(tools.dispatch(eq("query"),eq(ctx),anyMap())).thenReturn(facts);
        when(tool.toLlmText(ctx,facts)).thenReturn(facts.summary());
        when(actions.prepare(any(),any(),any(),any())).thenReturn(new ToolOutcomeHandler.PendingAction(null,null));
        List<List<LlmProvider.ChatMessage>> requests=new ArrayList<>();
        when(caller.callIsolated(anyList(),anyList(),isNull())).thenAnswer(i->{
            requests.add(List.copyOf(i.getArgument(0)));
            return requests.size()==1 ? new LlmProvider.ChatResponse("",List.of(new LlmProvider.ToolCallRequest("call","query",Map.of())),"test",false,"reasoning")
                : new LlmProvider.ChatResponse("活动在A楼",List.of(),"test",false);
        });
        var result=new SpecialistAgent(registry,tools,caller,publisher,actions,new PolicyGuard(),audit,props)
            .execute("retrieval","核对活动地点",ctx,null,null,null);
        assertThat(result.state()).isEqualTo("SUCCESS"); assertThat(result.text()).isEqualTo("活动在A楼");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1)).anySatisfy(m->{ assertThat(m.role()).isEqualTo("tool"); assertThat(m.toolCallId()).isEqualTo("call"); assertThat(m.content()).contains("A楼"); });
        assertThat(requests.get(1)).anySatisfy(m->{ assertThat(m.role()).isEqualTo("assistant"); assertThat(m.reasoningContent()).isEqualTo("reasoning"); });
        verify(caller,never()).callLlm(any(),any(),any(),any(),anyInt());
    }
}
