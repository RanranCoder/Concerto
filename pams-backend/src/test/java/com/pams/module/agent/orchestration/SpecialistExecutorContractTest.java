package com.pams.module.agent.orchestration;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.*;
import com.pams.module.agent.registry.AgentCatalog;
import com.pams.module.agent.stream.AgentEventPublisher;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class SpecialistExecutorContractTest {
    SpecialistAgent worker;
    AgentRunService runs;
    SpecialistExecutor executor;
    ToolContext ctx;
    AgentRun run;
    Blackboard bb;
    @BeforeEach void setup() {
        worker=mock(SpecialistAgent.class); runs=mock(AgentRunService.class);
        executor=new SpecialistExecutor(worker,runs,mock(AgentEventPublisher.class),Runnable::run);
        ctx=ToolContext.current(null,null); run=new AgentRun(); run.setId(1L); bb=new Blackboard();
        when(worker.scopedTools(anyString(),any())).thenReturn(List.of());
        when(runs.toJson(any())).thenReturn("{}");
        when(runs.recordStep(any(),anyString(),anyInt(),any(),any(),any(),anyString(),anyLong())).thenAnswer(i->{
            var step=new AgentStep(); step.setId(1L); step.setStatus(i.getArgument(6)); return step;
        });
    }
    void plan(String... names) { for(String name:names) { var item=new Blackboard.PlanItem(); item.setAgent(name); item.setTask("原始要求：地点A"); bb.getPlan().add(item); } }
    @Test void parallelReviewUsesActualTextArtifactsInsteadOfNullBlackboard() {
        plan(AgentCatalog.WRITING,AgentCatalog.PLANNING,AgentCatalog.REVIEWER);
        when(worker.execute(anyString(),anyString(),any(),isNull(),isNull(),isNull()))
            .thenAnswer(i->new SpecialistAgent.Result(i.getArgument(0),"可供核对的草稿",List.of()));
        executor.executePlan(run,bb,ctx,null,"P2");
        assertThat(bb.getArtifacts()).hasSize(2);
        assertThat(bb.getDecisions()).singleElement().satisfies(d->assertThat(d.getVerdict()).isEqualTo("UNVERIFIED"));
    }
    @Test void waitingActionStopsDependentWorkAndIsNotReportedAsSuccess() {
        plan(AgentCatalog.PLANNING,AgentCatalog.WRITING,AgentCatalog.REVIEWER);
        var plan=new AgentActionPlan(1L,"待核对",1,List.of(),List.of(),List.of(),false,"token","preview");
        when(worker.execute(anyString(),anyString(),any(),isNull(),isNull(),isNull())).thenReturn(
            new SpecialistAgent.Result("planning","待确认",List.of(),"WAITING_CONFIRM","test",false,Sensitivity.NORMAL,plan,null,List.of(),List.of()));
        executor.executePlan(run,bb,ctx,null,"P1");
        verify(worker,times(1)).execute(anyString(),anyString(),any(),isNull(),isNull(),isNull());
        assertThat(bb.executionState()).isEqualTo("WAITING_CONFIRM");
        assertThat(bb.getDecisions()).isEmpty();
        assertThat(bb.getPlan().get(1).getStatus()).isEqualTo("QUEUED");
    }
    @Test void boundedReflectionDoesNotTreatSuccessfulQueryAsContentArtifact() {
        plan(AgentCatalog.WRITING,AgentCatalog.REVIEWER);
        when(worker.execute(anyString(),anyString(),any(),isNull(),isNull(),isNull())).thenReturn(
            new SpecialistAgent.Result("writing",null,List.of(new SpecialistAgent.ToolInvocation("query","查询","OK","查到资料",1))));
        executor.executePlan(run,bb,ctx,null,"P3");
        assertThat(bb.getArtifacts()).isEmpty();
        assertThat(bb.getDecisions()).hasSize(SpecialistExecutor.MAX_REFLECTION_ROUNDS);
        assertThat(bb.executionState()).isEqualTo("PARTIAL");
    }
}
