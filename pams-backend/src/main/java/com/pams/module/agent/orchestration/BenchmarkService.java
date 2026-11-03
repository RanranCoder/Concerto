package com.pams.module.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.BizException;
import com.pams.common.OrgContext;
import com.pams.module.agent.config.AgentProperties;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.entity.*;
import com.pams.module.agent.registry.AgentCatalog;
import com.pams.module.agent.repository.AgentBenchmarkRepository;
import com.pams.module.agent.service.AgentEvaluationMeter;
import com.pams.module.agent.stream.AgentEventPublisher;
import org.springframework.stereotype.Service;
import java.util.*;

/** Preview-only comparison on the same durable engine. Quality is assessed separately against business evidence. */
@Service
public class BenchmarkService {
    public record TaskDef(String code,String text) {}
    public static final List<TaskDef> TASKS=List.of(
        new TaskDef("T1_QUERY","查一下最近有哪些活动，各自进度怎么样"),
        new TaskDef("T2_PLAN","为迎新晚会做一份活动策划方案"),
        new TaskDef("T3_WRITE","给迎新晚会写一篇报道推文"),
        new TaskDef("T4_SCHEDULE","为控烟值班生成排班"),
        new TaskDef("T5_DATA","给参加迎新晚会的新生批量加素拓分"));
    private final CoordinatorAgent coordinator;
    private final DagScheduler scheduler;
    private final AgentRunService runs;
    private final AgentBenchmarkRepository repository;
    private final AgentEventPublisher publisher;
    private final AgentEvaluationMeter meter;
    private final AgentProperties props;
    private final WorkItemState state;
    private final com.pams.module.agent.registry.AgentRegistry registry;
    private final com.pams.module.agent.core.ToolRegistry tools;
    private final com.pams.module.agent.repository.AgentArtifactRepository artifacts;
    private final ObjectMapper json=new ObjectMapper();
    public BenchmarkService(CoordinatorAgent coordinator,DagScheduler scheduler,AgentRunService runs,
            AgentBenchmarkRepository repository,AgentEventPublisher publisher,AgentEvaluationMeter meter,AgentProperties props,WorkItemState state,com.pams.module.agent.registry.AgentRegistry registry,com.pams.module.agent.core.ToolRegistry tools,com.pams.module.agent.repository.AgentArtifactRepository artifacts) {
        this.coordinator=coordinator;this.scheduler=scheduler;this.runs=runs;this.repository=repository;this.publisher=publisher;this.meter=meter;this.props=props;this.state=state;this.registry=registry;this.tools=tools;this.artifacts=artifacts;
    }
    public Map<String,Object> runBenchmark(List<String> modes) { return runCases(TASKS,modes,ToolContext.current(null,null)); }
    public Map<String,Object> runCases(List<TaskDef> cases,List<String> modes,ToolContext identity) {
        if(!props.isBenchmarkEnabled() || !props.getBenchmarkOrgIds().contains(OrgContext.get())) throw new BizException(403,"评估仅在显式启用的隔离组织开放");
        if(identity.getUserId()==null || !Objects.equals(identity.getLoginUser().getOrgId(),OrgContext.get())) throw new BizException(403,"评估身份无效");
        List<String> selected=(modes==null || modes.isEmpty())?List.of("P1"):modes.stream().distinct().toList();
        if(selected.stream().anyMatch(m->!List.of("P1","P2").contains(m)))throw new BizException(400,"持久化对照仅支持 P1 顺序与 P2 依赖并行");
        if(cases==null || cases.isEmpty() || cases.size()>10 || cases.stream().anyMatch(t->t.code()==null || t.text()==null || t.text().length()>4000))throw new BizException(400,"无效评估任务集");
        String batch=UUID.randomUUID().toString().substring(0,16);
        List<AgentBenchmark> rows=new ArrayList<>();
        // Alternate arm order by task, avoiding a systematic cache warmup advantage.
        for(int i=0;i<cases.size();i++) {
            TaskDef task=cases.get(i);
            if(i%2==0)rows.add(sample(batch,task,"SINGLE","SINGLE",identity));
            for(String mode:selected)rows.add(sample(batch,task,"MULTI",mode,identity));
            if(i%2!=0)rows.add(sample(batch,task,"SINGLE","SINGLE",identity));
        }
        repository.saveAll(rows);
        var result=new LinkedHashMap<String,Object>();result.put("batchId",batch);result.put("tasks",cases.size());result.put("rows",rows);result.put("summary",summarize(rows));
        result.put("evaluationStatus","BUSINESS_QUALITY_NOT_ASSESSED");result.put("automaticBusinessConfirmation",false);
        return result;
    }
    private AgentBenchmark sample(String batch,TaskDef task,String arm,String mode,ToolContext identity) {
        String stream=publisher.newStream("bench",identity.getUserId());
        ToolContext ctx=identity.derive(null,stream);
        meter.open(stream,props.getBenchmarkMaxCalls(),props.getBenchmarkMaxTokens());
        AgentRun run=null;long started=System.nanoTime();
        try {
            Blackboard bb;
            if("SINGLE".equals(arm)) {
                run=runs.startRun(null,ctx.getUserId(),task.text(),"SINGLE");
                bb=new Blackboard();bb.setGoal(task.text());bb.setMode("SINGLE");
                var node=new Blackboard.PlanItem();node.setId(1);node.setAgent(AgentCatalog.GENERALIST);node.setTask(task.text());bb.getPlan().add(node);
                bb=scheduler.start(run,bb,ctx.withRunId(run.getId()));
            } else {
                run=runs.startRun(null,ctx.getUserId(),task.text(),mode);
                var outcome=coordinator.executeRun(run,task.text(),ctx,stream,mode);
                run=outcome.run();bb=outcome.blackboard();
            }
            run=runs.getOwned(run.getId(),ctx.getUserId());
            var steps=runs.steps(run.getId());var row=new AgentBenchmark();row.setBatchId(batch);row.setTaskCode(task.code());row.setTaskText(task.text());
            row.setArm(arm);row.setMode(mode);row.setRunId(run.getId());row.setStatus(run.getStatus());row.setSteps(steps.size());
            row.setAgents((int)steps.stream().map(AgentStep::getAgentName).filter(n->!AgentCatalog.COORDINATOR.equals(n)).distinct().count());
            row.setToolCalls(steps.stream().mapToInt(this::invocations).sum());row.setArtifacts(artifacts.findByRunIdOrderByIdAsc(run.getId()).size());row.setReviewPass(null);
            row.setDurationMs((int)Math.min(Integer.MAX_VALUE,(System.nanoTime()-started)/1000000));row.setCreatedAt(java.time.LocalDateTime.now());
            var metadata=new LinkedHashMap<String,Object>(meter.snapshot(stream));metadata.put("engine","durable-dag-v2");metadata.put("qualityVerdict","NOT_ASSESSED");
            metadata.put("configuredModel",props.getModel());metadata.put("protocol",props.getProtocol());metadata.put("summaryEnabled",props.isSummaryEnabled());
            metadata.put("maxToolIterations",props.getMaxToolIterations());metadata.put("maxParallelNodes",props.getMaxParallelNodes());
            metadata.put("promptConfigSha256",hash(json.writeValueAsString(registry.enabled().stream().sorted(Comparator.comparing(AgentDefinition::getName))
                .map(d->Map.of("name",d.getName(),"prompt",Objects.toString(d.getRolePrompt(),""),"scope",Objects.toString(d.getToolScope(),""))).toList())));
            metadata.put("visibleTools",tools.visibleTools(ctx).stream().map(com.pams.module.agent.core.AgentTool::name).sorted().toList());
            metadata.put("businessConfirmed",false);metadata.put("cleanup","uncommitted_actions_cancelled_after_sampling");
            metadata.put("taskSha256",hash(task.text()));
            row.setMetricsJson(json.writeValueAsString(metadata));return row;
        } catch(RuntimeException e) { throw e; } catch(Exception e) { throw new IllegalStateException(e); }
        finally {
            meter.close(stream);
            if(run!=null && !List.of("SUCCESS","CANCELLED").contains(runs.getOwned(run.getId(),ctx.getUserId()).getStatus()))state.cancel(run.getId(),ctx.getUserId());
        }
    }
    private int invocations(AgentStep step) {
        try {var node=json.readTree(step.getOutputJson());return node.path("invocations").isArray()?node.path("invocations").size():0;}catch(Exception e){return 0;}
    }
    public Map<String,Object> summarize(List<AgentBenchmark> rows) {
        Map<String,Object> summary=new LinkedHashMap<>();
        rows.stream().map(r->"SINGLE".equals(r.getArm())?"SINGLE":"MULTI_"+r.getMode()).distinct().forEach(key->{
            var group=rows.stream().filter(r->key.equals("SINGLE".equals(r.getArm())?"SINGLE":"MULTI_"+r.getMode())).toList();
            var durations=group.stream().map(AgentBenchmark::getDurationMs).filter(Objects::nonNull).sorted().toList();
            var m=new LinkedHashMap<String,Object>();m.put("samples",group.size());m.put("completionRate",null);
            m.put("runSuccessRate",group.stream().filter(r->"SUCCESS".equals(r.getStatus())).count()/(double)group.size());
            m.put("waiting",group.stream().filter(r->r.getStatus().startsWith("WAITING") || "REVIEW_REQUIRED".equals(r.getStatus())).count());
            m.put("avgSteps",group.stream().mapToInt(AgentBenchmark::getSteps).average().orElse(0));m.put("avgAgents",group.stream().mapToInt(AgentBenchmark::getAgents).average().orElse(0));
            m.put("avgToolCalls",group.stream().mapToInt(AgentBenchmark::getToolCalls).average().orElse(0));m.put("avgArtifacts",group.stream().mapToInt(AgentBenchmark::getArtifacts).average().orElse(0));
            m.put("avgDurationMs",durations.isEmpty()?null:durations.stream().mapToInt(Integer::intValue).average().orElseThrow());
            m.put("p50DurationMs",percentile(durations,.5));m.put("p95DurationMs",percentile(durations,.95));m.put("reviewPassRate",null);m.put("qualityVerdict","NOT_ASSESSED");summary.put(key,m);
        });return summary;
    }
    private String hash(String text) throws Exception { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
    private Integer percentile(List<Integer> values,double p) { return values.isEmpty()?null:values.get((int)Math.ceil(values.size()*p)-1); }
}
