package com.pams.module.agent.orchestration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.OrgContext;
import com.pams.module.agent.config.AgentProperties;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.AgentDefinition;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.registry.AgentRegistry;
import com.pams.module.agent.service.AgentLlmCaller;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ControlledPlanningServiceTest {
    AgentProperties props=new AgentProperties();AgentRegistry agents=mock(AgentRegistry.class);ToolRegistry tools=mock(ToolRegistry.class);AgentLlmCaller caller=mock(AgentLlmCaller.class);CoordinatorAgent coordinator=mock(CoordinatorAgent.class);
    ControlledPlanningService service=new ControlledPlanningService(props,agents,tools,caller,coordinator);
    ToolContext ctx;final String goal="查询活动";final ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup(){OrgContext.set(1L);ctx=new ToolContext(new LoginUser(1L,"u","测试","TEACHER",5,null,null,1L),List.of(),null,null);
        when(coordinator.plan(anyString())).thenReturn(new Blackboard());var agent=new AgentDefinition();agent.setName("generalist");when(agents.enabled()).thenReturn(List.of(agent));when(agents.toolScopeOf(agent)).thenReturn(null);
        var read=tool("queryActivityOverview",false);var prepare=tool("prepareTaskPlanChange",true);var send=tool("sendNotification",true);
        when(tools.visibleTools(ctx)).thenReturn(List.of(read,prepare,send));
        props.setControlledPlanningOrgIds(Set.of(1L));}
    @AfterEach void clear(){OrgContext.clear();}
    AgentTool tool(String name,boolean write){var t=mock(AgentTool.class);when(t.name()).thenReturn(name);when(t.description()).thenReturn("能力说明");when(t.writeOperation()).thenReturn(write);when(t.parametersSchema()).thenReturn(Map.of("type","object","properties",Map.of()));return t;}
    String valid()throws Exception{return json.writeValueAsString(Map.of("goal",goal,"constraints",List.of(),"clarifications",List.of(),"steps",List.of(Map.of("id",1,"agent","generalist","capability","queryActivityOverview","task","查询目标","dependsOn",List.of(),"inputBindings",List.of(),"completionCriteria","工具返回授权事实"))));}
    LlmProvider.ChatResponse response(String text){return new LlmProvider.ChatResponse(text,List.of(),"test",false,null,null,"model-id","stop");}
    @Test void defaultAndOrgAllowlistDisableModelPlanning(){assertThat(service.preview(goal,ctx).reasonCode()).isEqualTo("ROLLOUT_DISABLED");props.setControlledPlanningEnabled(true);props.setControlledPlanningOrgIds(Set.of());assertThat(service.preview(goal,ctx).reasonCode()).isEqualTo("ROLLOUT_DISABLED");verifyNoInteractions(caller);}
    @Test void validPlanIsPreviewOnlyAndNoBusinessOrExecutionEntryIsCalled()throws Exception{
        props.setControlledPlanningEnabled(true);when(caller.callPlanning(anyList(),anyString())).thenReturn(response(valid()));var result=service.preview(goal,ctx);
        assertThat(result.status()).isEqualTo("MODEL_PLAN_VALIDATED");assertThat(result.previewOnly()).isTrue();assertThat(result.steps()).hasSize(1);assertThat(result.capabilitySha256()).matches("[a-f0-9]{64}");
        verify(coordinator).plan(goal);verifyNoMoreInteractions(coordinator);verify(caller).endTask(anyString());
        verify(caller).callPlanning(argThat(messages->!messages.get(1).content().contains("\"capability\":\"sendNotification\"")),anyString());
        verify(tools,never()).dispatch(anyString(),any(),anyMap());
    }
    @Test void onlyOneRepairIsAllowedAndSecondFailureReturnsLabeledRuleBaseline(){props.setControlledPlanningEnabled(true);when(caller.callPlanning(anyList(),anyString())).thenReturn(response("invalid"));var result=service.preview(goal,ctx);assertThat(result.status()).isEqualTo("RULE_BASELINE");assertThat(result.reasonCode()).isEqualTo("INVALID_PLAN");assertThat(result.attempts()).isEqualTo(2);verify(caller,times(2)).callPlanning(anyList(),anyString());verify(caller).endTask(anyString());}
    @Test void repairCanProduceAValidPlan()throws Exception{props.setControlledPlanningEnabled(true);when(caller.callPlanning(anyList(),anyString())).thenReturn(response("{}"),response(valid()));assertThat(service.preview(goal,ctx).status()).isEqualTo("MODEL_PLAN_VALIDATED");verify(caller,times(2)).callPlanning(anyList(),anyString());}
    @Test void revocationDuringPlanningStopsAcceptance()throws Exception{props.setControlledPlanningEnabled(true);when(caller.callPlanning(anyList(),anyString())).thenReturn(response(valid()));var read=tool("queryActivityOverview",false);when(tools.visibleTools(ctx)).thenReturn(List.of(read),List.of());assertThat(service.preview(goal,ctx).reasonCode()).isEqualTo("CAPABILITIES_REVOKED");verify(caller,times(1)).callPlanning(anyList(),anyString());}
    @Test void cancellationPropagatesAndProviderBindingIsReleased(){props.setControlledPlanningEnabled(true);when(caller.callPlanning(anyList(),anyString())).thenThrow(new java.util.concurrent.CancellationException());assertThatThrownBy(()->service.preview(goal,ctx)).isInstanceOf(java.util.concurrent.CancellationException.class);verify(caller).endTask(anyString());}
    @Test void crossOrgCannotEnterPreview(){OrgContext.set(2L);assertThatThrownBy(()->service.preview(goal,ctx)).hasMessageContaining("身份或组织");verifyNoInteractions(caller);}
    @Test void knowledgeModeCannotEnterBusinessPlanning(){assertThatThrownBy(()->service.preview(goal,ctx.withMode("kb"))).hasMessageContaining("知识问答");verifyNoInteractions(caller);}
    @Test void degradedModelCannotBePresentedAsAnAcceptedPlan(){props.setControlledPlanningEnabled(true);when(caller.callPlanning(anyList(),anyString())).thenReturn(new LlmProvider.ChatResponse("{}",List.of(),"stub",true));assertThat(service.preview(goal,ctx).reasonCode()).isEqualTo("MODEL_UNVERIFIED");verify(caller,times(1)).callPlanning(anyList(),anyString());}
    @Test void incompleteTimeoutAndTransportFailuresAreDistinctAndNeverRepairedAsJson(){
        props.setControlledPlanningEnabled(true);
        for(var failure:Map.of("Responses 响应未完成","MODEL_OUTPUT_INCOMPLETE","模型调用超时","MODEL_TIMEOUT","模型连接或协议响应失败","MODEL_TRANSPORT_ERROR","private-provider-error","MODEL_UNAVAILABLE").entrySet()){
            reset(caller);when(caller.callPlanning(anyList(),anyString())).thenThrow(new IllegalStateException(failure.getKey()));var result=service.preview(goal,ctx);
            assertThat(result.status()).isEqualTo("RULE_BASELINE");assertThat(result.reasonCode()).isEqualTo(failure.getValue());assertThat(result.attempts()).isEqualTo(1);verify(caller,times(1)).callPlanning(anyList(),anyString());verify(caller).endTask(anyString());
        }
    }
    @Test void readonlyMenuExcludesWritePreparationsEvenIfRegistryReturnsThem()throws Exception{
        props.setControlledPlanningEnabled(true);var readonly=ctx.withMode("readonly");var read=tool("queryActivityOverview",false);var write=tool("prepareTaskPlanChange",true);when(tools.visibleTools(readonly)).thenReturn(List.of(read,write));
        when(caller.callPlanning(anyList(),anyString())).thenReturn(response(valid()));assertThat(service.preview(goal,readonly).status()).isEqualTo("MODEL_PLAN_VALIDATED");
        verify(caller).callPlanning(argThat(messages->!messages.get(1).content().contains("\"capability\":\"prepareTaskPlanChange\"")),anyString());
    }
    @Test void repairPreservesNativeProviderStateWithoutCopyingReasoningIntoText()throws Exception{
        props.setControlledPlanningEnabled(true);var nativeState=Map.<String,Object>of("type","reasoning","encrypted_content","native-state");
        var invalid=new LlmProvider.ChatResponse("{}",List.of(),"test",false,"public summary",null,"model-id","stop",List.of(nativeState));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(caller.callPlanning(anyList(),anyString())).thenAnswer(invocation->{
            if(calls.getAndIncrement()==0)return invalid;
            List<LlmProvider.ChatMessage> messages=invocation.getArgument(0);assertThat(messages).anySatisfy(m->{assertThat(m.role()).isEqualTo("assistant");assertThat(m.nativeItems()).containsExactly(nativeState);assertThat(m.reasoningContent()).isNull();});return response(valid());
        });assertThat(service.preview(goal,ctx).status()).isEqualTo("MODEL_PLAN_VALIDATED");
    }
}
