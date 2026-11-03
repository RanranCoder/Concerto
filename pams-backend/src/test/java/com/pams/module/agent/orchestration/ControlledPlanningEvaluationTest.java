package com.pams.module.agent.orchestration;
import com.pams.common.OrgContext;
import com.pams.module.agent.config.AgentProperties;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.service.AgentEvaluationMeter;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ControlledPlanningEvaluationTest {
    final AgentProperties props=new AgentProperties();final ControlledPlanningService planner=mock(ControlledPlanningService.class);final CoordinatorAgent rules=mock(CoordinatorAgent.class);final AgentEvaluationMeter meter=new AgentEvaluationMeter();
    final ControlledPlanningEvaluation evaluation=new ControlledPlanningEvaluation(props,planner,rules,meter);
    final ToolContext ctx=new ToolContext(new LoginUser(1L,"u","u","TEACHER",5,null,null,1L),List.of(new SimpleGrantedAuthority("org:audit:view")),null,null);
    @BeforeEach void setup(){OrgContext.set(1L);props.setBenchmarkEnabled(true);props.getBenchmarkOrgIds().add(1L);when(rules.plan(anyString())).thenReturn(new Blackboard());}
    @AfterEach void clear(){OrgContext.clear();}
    @Test void commonCeilingAndUnknownQualityCannotBecomeReleaseApproval(){
        when(planner.previewForEvaluation(anyString(),eq(ctx),anyString())).thenAnswer(call->{String stream=call.getArgument(2);meter.before(stream,2048);return new ControlledPlanningService.Preview("RULE_BASELINE",true,"MODEL_UNVERIFIED",1,"planner/v1","hash","caps","stub",new Blackboard(),List.of(),List.of(),List.of());});
        var report=evaluation.compare(List.of("读取活动42"),ctx);assertThat(report).containsEntry("previewOnly",true).containsEntry("releaseEligible",false).containsEntry("qualityVerdict","NOT_ASSESSED");
        var sample=(Map<?,?>)((List<?>)report.get("samples")).getFirst();var metrics=(Map<?,?>)sample.get("modelMetrics");assertThat(metrics.get("attempts")).isEqualTo(1);assertThat(metrics.get("maxCalls")).isEqualTo(2);assertThat(metrics.get("usageComplete")).isEqualTo(false);
        verify(rules).plan("读取活动42");
    }
    @Test void failedEvaluationCleansMeterAndClosedRolloutNeverCallsPlanner(){
        var stream=new String[1];when(planner.previewForEvaluation(anyString(),eq(ctx),anyString())).thenAnswer(call->{stream[0]=call.getArgument(2);throw new java.util.concurrent.CancellationException();});
        assertThatThrownBy(()->evaluation.compare(List.of("读取活动42"),ctx)).isInstanceOf(java.util.concurrent.CancellationException.class);assertThat(meter.snapshot(stream[0])).isEmpty();
        props.setBenchmarkEnabled(false);assertThatThrownBy(()->evaluation.compare(List.of("目标"),ctx)).hasMessageContaining("隔离组织");verify(planner,times(1)).previewForEvaluation(anyString(),any(),anyString());
    }
}
