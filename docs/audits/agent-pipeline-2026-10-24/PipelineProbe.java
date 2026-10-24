import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.*;
import com.pams.module.agent.orchestration.*;
import com.pams.module.agent.registry.*;
import com.pams.module.agent.service.*;
import com.pams.module.agent.stream.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

// Isolated probes: mock tools/services, no database, network, or business writes.
class PipelineProbe {
    public static void main(String[] args) {
        ToolRegistry tools = mock(ToolRegistry.class);
        AgentRegistry registry = mock(AgentRegistry.class);
        AgentLlmCaller caller = mock(AgentLlmCaller.class);
        AgentEventPublisher publisher = mock(AgentEventPublisher.class);
        ToolContext ctx = mock(ToolContext.class);
        WriteAgentTool tool = mock(WriteAgentTool.class);
        when(tool.name()).thenReturn("draftArticleFromActivity");
        when(tool.label()).thenReturn("起草推文");
        when(tools.visibleTools(ctx)).thenReturn(List.of(tool));
        when(tools.find(tool.name())).thenReturn(Optional.of(tool));
        AgentDefinition def = new AgentDefinition(); def.setName(AgentCatalog.WRITING);
        when(registry.byName(AgentCatalog.WRITING)).thenReturn(Optional.of(def));
        when(registry.toolScopeOf(def)).thenReturn(List.of("draftArticleFromActivity"));
        AgentActionPlan plan = new AgentActionPlan(null,"probe",1,List.of(),List.of(),List.of(),true,null,"body");
        when(tools.dispatch(eq(tool.name()),eq(ctx),anyMap())).thenReturn(ToolResult.plan("await confirmation",plan));
        SpecialistAgent specialist = new SpecialistAgent(registry,tools,caller,publisher);
        var result = specialist.execute(AgentCatalog.WRITING,"probe",ctx,
            List.of(new com.pams.module.agent.llm.LlmProvider.ToolCallRequest("c",tool.name(),Map.of())),null,null);
        if (!"OK".equals(result.invocations().get(0).status())) throw new AssertionError(result);
        var fields = Arrays.stream(SpecialistAgent.ToolInvocation.class.getRecordComponents()).map(c->c.getName()).toList();
        if (fields.contains("plan") || fields.contains("form")) throw new AssertionError(fields);
        System.out.println("CONFIRMED: specialist returns OK but ToolInvocation carries no plan/form: "+fields);
        when(registry.toolScopeOf(def)).thenReturn(null);
        if(!specialist.scopedTools(AgentCatalog.WRITING,ctx).isEmpty()) throw new AssertionError("scope behavior changed");
        System.out.println("CONFIRMED: null tool scope returns empty tools despite one visible tool (Optional.map drops null)");

        SpecialistAgent worker = mock(SpecialistAgent.class);
        when(worker.execute(anyString(),anyString(),eq(ctx),isNull(),isNull(),isNull())).thenReturn(
            new SpecialistAgent.Result(AgentCatalog.WRITING,null,List.of(
                new SpecialistAgent.ToolInvocation("draftArticleFromActivity","draft","OK","preview",1L))));
        AgentRunService runs = mock(AgentRunService.class);
        when(runs.toJson(any())).thenReturn("{}");
        AtomicLong id = new AtomicLong();
        when(runs.recordStep(any(),anyString(),anyInt(),any(),any(),any(),anyString(),anyLong()))
            .thenAnswer(i->{ AgentStep s=new AgentStep(); s.setId(id.incrementAndGet()); s.setStatus(i.getArgument(6)); return s; });
        Blackboard bb = new Blackboard();
        for (String name:List.of(AgentCatalog.WRITING,AgentCatalog.REVIEWER)) {
            Blackboard.PlanItem item=new Blackboard.PlanItem(); item.setAgent(name); item.setTask("probe"); bb.getPlan().add(item);
        }
        AgentRun run=new AgentRun(); run.setId(1L);
        new SpecialistExecutor(worker,runs,publisher,Runnable::run).executePlan(run,bb,ctx,null,"P2");
        if(bb.getArtifacts().isEmpty() || !"REVISE".equals(bb.getDecisions().get(0).getVerdict())) throw new AssertionError(bb);
        System.out.println("CONFIRMED: P2 has "+bb.getArtifacts().size()+" artifact but review returns "+bb.getDecisions().get(0).getVerdict());
    }
}
