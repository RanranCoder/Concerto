package com.pams.module.agent.orchestration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.module.agent.core.AgentTool;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControlledPlanValidatorTest {
    final ObjectMapper json=new ObjectMapper();final String goal="查询活动42，再准备任务";
    AgentTool tool(boolean write){var t=mock(AgentTool.class);when(t.writeOperation()).thenReturn(write);when(t.parametersSchema()).thenReturn(Map.of("properties",Map.of("activityId",Map.of("type","integer"))));return t;}
    Map<String,ControlledPlanValidator.Capability> capabilities(){return Map.of("generalist:read",new ControlledPlanValidator.Capability("generalist",tool(false)),"generalist:prepare",new ControlledPlanValidator.Capability("generalist",tool(true)));}
    Map<String,Object> step(int id,String tool,List<Integer> deps,List<?> bindings){return new LinkedHashMap<>(Map.of("id",id,"agent","generalist","capability",tool,"task","核对本步目标","dependsOn",deps,"inputBindings",bindings,"completionCriteria","依据实际工具回执核对目标"));}
    Map<String,Object> root(List<?> steps){return new LinkedHashMap<>(Map.of("goal",goal,"constraints",List.of("不发布"),"clarifications",List.of(),"steps",steps));}
    @Test void validGraphPreservesBindingsAndServerOwnsConfirmationSemantics()throws Exception{
        var a=step(1,"read",List.of(),List.of(Map.of("parameter","activityId","source","USER","quote","42")));
        var b=step(2,"prepare",List.of(1),List.of(Map.of("parameter","activityId","source","STEP","stepId",1,"path","payload.activityId")));
        var result=ControlledPlanValidator.validate(json.writeValueAsString(root(List.of(a,b))),goal,capabilities());
        assertThat(result.steps().getFirst().requiresConfirmation()).isFalse();assertThat(result.steps().get(1).requiresConfirmation()).isTrue();
        assertThat(result.steps().get(1).inputBindings().getFirst()).containsEntry("resultPathVerified",false);
        assertThat(result.plan().getPlan().get(1).getTask()).contains(goal,"完成条件");
    }
    @Test void clarificationCannotCoexistWithExecutableSteps()throws Exception{
        var root=root(List.of());root.put("clarifications",List.of("请选择活动版本"));assertThat(ControlledPlanValidator.validate(json.writeValueAsString(root),goal,capabilities()).steps()).isEmpty();
        root.put("steps",List.of(step(1,"read",List.of(),List.of())));assertThatThrownBy(()->ControlledPlanValidator.validate(json.writeValueAsString(root),goal,capabilities())).hasMessageContaining("PLAN_SHAPE");
    }
    @Test void cyclesMissingDependenciesDuplicateIdsAndInventedCapabilitiesAreRejected()throws Exception{
        for(var steps:List.of(List.of(step(1,"read",List.of(2),List.of()),step(2,"read",List.of(1),List.of())),List.of(step(1,"read",List.of(9),List.of())),
                List.of(step(1,"read",List.of(),List.of()),step(1,"read",List.of(),List.of())),List.of(step(1,"publish",List.of(),List.of())))){
            assertThatThrownBy(()->ControlledPlanValidator.validate(json.writeValueAsString(root(steps)),goal,capabilities())).isInstanceOf(Exception.class);
        }
    }
    @Test void nonexistentUserQuotesUndeclaredDataDependenciesAndParameterNamesAreRejected(){
        for(var binding:List.of(Map.of("parameter","activityId","source","USER","quote","凭空实体99"),Map.of("parameter","fake","source","USER","quote","42"),
                Map.of("parameter","activityId","source","STEP","stepId",2,"path","payload.id"))){
            assertThatThrownBy(()->ControlledPlanValidator.validate(json.writeValueAsString(root(List.of(step(1,"read",List.of(),List.of(binding))))),goal,capabilities())).isInstanceOf(Exception.class);
        }
    }
    @Test void modelCannotChangeGoalOverrideGatesOrAppendTrailingJson(){
        var changed=root(List.of(step(1,"read",List.of(),List.of())));changed.put("goal","追加发布通知");
        assertThatThrownBy(()->ControlledPlanValidator.validate(json.writeValueAsString(changed),goal,capabilities())).hasMessageContaining("GOAL_CHANGED");
        var override=step(1,"prepare",List.of(),List.of());override.put("requiresConfirmation",false);
        assertThatThrownBy(()->ControlledPlanValidator.validate(json.writeValueAsString(root(List.of(override))),goal,capabilities())).hasMessageContaining("UNKNOWN_FIELD");
        assertThatThrownBy(()->ControlledPlanValidator.validate(json.writeValueAsString(root(List.of(step(1,"read",List.of(),List.of()))))+" {}",goal,capabilities())).isInstanceOf(Exception.class);
    }
}
