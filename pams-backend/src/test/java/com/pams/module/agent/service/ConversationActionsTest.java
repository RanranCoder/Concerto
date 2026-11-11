package com.pams.module.agent.service;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.AgentTask;
import com.pams.module.agent.repository.AgentTaskRepository;
import com.pams.module.agent.tool.SupplyTaskInputTool;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.stream.AgentEventPublisher;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.PageImpl;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationActionsTest {
    AgentTaskRepository tasks=mock(AgentTaskRepository.class);
    TaskGate gate=mock(TaskGate.class);
    ToolRegistry registry=mock(ToolRegistry.class);
    AgentTool business=mock(AgentTool.class);
    ConversationActions service=new ConversationActions(tasks,gate,registry);
    ToolContext ctx=new ToolContext(new LoginUser(1L,"u","用户","STAFF",3,1L,"部门",9L),List.of(),2L,"new-turn");
    AgentTask task=new AgentTask();
    @BeforeEach void setup(){
        task.setId(3L);task.setUserId(1L);task.setOrgId(9L);task.setConversationId(2L);task.setToolName("createActivity");task.setStatus("WAITING_INPUT");
        when(tasks.findByIdAndUserId(3L,1L)).thenReturn(Optional.of(task));
        when(registry.find("createActivity")).thenReturn(Optional.of(business));when(registry.visibleTo(business,ctx)).thenReturn(true);
        when(gate.detail(3L,1L)).thenReturn(Map.of("form",Map.of("fields",List.of(Map.of("name","location","label","地点","required",true)),"values",Map.of(),"originalArgs",Map.of("secret","hidden"))));
    }
    @Test void suppliesSameTaskAndReturnsPersistedPlanWithoutCreatingOrCommittingAnotherTask(){
        var plan=new AgentActionPlan(3L,"活动预览",1,List.of(),List.of(),List.of(),true,"ct_test");
        when(gate.submitInput(3L,Map.of("location","会议室"),ctx)).thenReturn(Map.of("status","PLAN","plan",plan.toJson()));
        var result=service.supply(ctx,3L,Map.of("location","会议室"));
        var pending=new ToolOutcomeHandler(gate,mock(AgentEventPublisher.class)).prepare(ctx,new SupplyTaskInputTool(service),result,new LlmProvider.ToolCallRequest("call","supplyTaskInput",Map.of()));
        assertThat(pending.plan().toJson()).isEqualTo(plan.toJson());
        verify(gate).submitInput(3L,Map.of("location","会议室"),ctx);
        verify(gate,never()).confirm(any(),any(),any());
        verify(gate,never()).createPlan(any(),any(),any(),anyInt(),any(),any(),any(),anyBoolean(),any());
    }
    @Test void rejectsCrossConversationBeforeReadingInputOrSubmitting(){
        task.setConversationId(99L);
        assertThatThrownBy(()->service.supply(ctx,3L,Map.of("location","会议室"))).hasMessageContaining("当前会话");
        verify(gate,never()).submitInput(any(),any(),any());
    }
    @Test void rejectsCrossOrganizationRevokedPermissionUnknownFieldsAndSettledTask(){
        task.setOrgId(8L);assertThatThrownBy(()->service.supply(ctx,3L,Map.of("location","会议室"))).hasMessageContaining("权限");task.setOrgId(9L);
        when(registry.visibleTo(business,ctx)).thenReturn(false);assertThatThrownBy(()->service.supply(ctx,3L,Map.of("location","会议室"))).hasMessageContaining("权限");when(registry.visibleTo(business,ctx)).thenReturn(true);
        assertThatThrownBy(()->service.supply(ctx,3L,Map.of("confirmToken","x"))).hasMessageContaining("声明");
        task.setStatus("SUCCESS");assertThatThrownBy(()->service.supply(ctx,3L,Map.of("location","会议室"))).hasMessageContaining("已变化");
        verify(gate,never()).submitInput(any(),any(),any());
    }
    @Test void queryOmitsTokensAndRawArgs(){
        when(tasks.findByConversationIdAndUserIdAndStatusInOrderByIdDesc(eq(2L),eq(1L),any(),any())).thenReturn(new PageImpl<>(List.of(task)));
        var output=service.list(ctx).payload().toString();assertThat(output).contains("location").doesNotContain("secret","originalArgs","confirmToken");
    }
    @Test void readOnlyAndSensitiveTasksCannotUseGenericContinuation(){
        var readonly=new ToolContext(ctx.getLoginUser(),List.of(),2L,"new-turn","readonly");
        assertThatThrownBy(()->service.supply(readonly,3L,Map.of("location","会议室"))).hasMessageContaining("主助手");
        task.setDataSensitivity("SENSITIVE");
        assertThatThrownBy(()->service.supply(ctx,3L,Map.of("location","会议室"))).hasMessageContaining("权限");
        verify(gate,never()).submitInput(any(),any(),any());
    }
    @Test void incompleteReplyPreservesOriginalFormId(){
        var form=Map.<String,Object>of("taskId",3L,"fields",List.of(Map.of("name","location","label","地点","required",true)),"values",Map.of());
        when(gate.submitInput(3L,Map.of("location",""),ctx)).thenReturn(Map.of("status","FORM","form",form));
        var result=service.supply(ctx,3L,Map.of("location",""));
        var pending=new ToolOutcomeHandler(gate,mock(AgentEventPublisher.class)).prepare(ctx,new SupplyTaskInputTool(service),result,new LlmProvider.ToolCallRequest("call","supplyTaskInput",Map.of()));
        assertThat(pending.form()).containsEntry("taskId",3L);
        assertThat(pending.inputQuestion()).contains("地点");
        verify(gate,never()).createForm(any(),any(),any(),any());
    }
    @Test void missingInputQuestionDoesNotAskAgainForKnownRequiredValues(){
        var pending=new ToolOutcomeHandler.PendingAction(null,Map.of("fields",List.of(Map.of("name","name","label","活动名称","required",true),Map.of("name","location","label","地点","required",true)),"values",Map.of("name","培训班")));
        assertThat(pending.inputQuestion()).contains("地点","聊天").doesNotContain("活动名称");
    }
}
