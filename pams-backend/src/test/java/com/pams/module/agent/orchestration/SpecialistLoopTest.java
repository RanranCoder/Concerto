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
    @Test void requiredActionPreviewCannotBeReplacedByAnUnconfirmedTextClaim() {
        var registry=mock(AgentRegistry.class);var tools=mock(ToolRegistry.class);var caller=mock(AgentLlmCaller.class);
        var definition=new AgentDefinition();definition.setName("writing");
        var ctx=ToolContext.current(null,null);var tool=mock(AgentTool.class);
        when(tool.name()).thenReturn("draftArticleFromActivity");when(tool.writeOperation()).thenReturn(true);when(tool.parametersSchema()).thenReturn(Map.of());
        when(registry.byName("writing")).thenReturn(Optional.of(definition));when(registry.toolScopeOf(definition)).thenReturn(null);when(tools.visibleTools(ctx)).thenReturn(List.of(tool));
        when(caller.callIsolated(anyList(),anyList(),isNull())).thenReturn(LlmProvider.ChatResponse.text("我已经保存了草稿","test"));
        var props=new AgentProperties();props.setMaxToolIterations(1);
        var result=new SpecialistAgent(registry,tools,caller,mock(AgentEventPublisher.class),mock(ToolOutcomeHandler.class),new PolicyGuard(),mock(AuditService.class),props)
            .execute("writing","生成推文草稿，等待确认",ctx,null,null,null);
        assertThat(result.state()).isEqualTo("PARTIAL");assertThat(result.text()).startsWith("尚未生成可确认清单");assertThat(result.plan()).isNull();
        verify(caller,times(2)).callIsolated(anyList(),anyList(),isNull());verify(tools,never()).dispatch(anyString(),any(),anyMap());
    }
    @Test void aTruncatedTextAnswerRemainsPartial() {
        var registry=mock(AgentRegistry.class);var tools=mock(ToolRegistry.class);var caller=mock(AgentLlmCaller.class);
        var definition=new AgentDefinition();definition.setName("retrieval");var ctx=ToolContext.current(null,null);
        when(registry.byName("retrieval")).thenReturn(Optional.of(definition));when(registry.toolScopeOf(definition)).thenReturn(List.of());when(tools.visibleTools(ctx)).thenReturn(List.of());
        when(caller.callIsolated(anyList(),anyList(),isNull())).thenReturn(new LlmProvider.ChatResponse("只返回一半",List.of(),"test",false,null,null,"actual-model","length"));
        var result=new SpecialistAgent(registry,tools,caller,mock(AgentEventPublisher.class),mock(ToolOutcomeHandler.class),new PolicyGuard(),mock(AuditService.class),new AgentProperties())
            .execute("retrieval","查询活动",ctx,null,null,null);
        assertThat(result.state()).isEqualTo("PARTIAL");
    }

}
