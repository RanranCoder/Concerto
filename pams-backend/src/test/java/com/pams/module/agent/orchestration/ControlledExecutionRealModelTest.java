package com.pams.module.agent.orchestration;
import com.pams.common.OrgContext;
import com.pams.module.agent.core.*;
import com.pams.module.agent.service.*;
import com.pams.module.activity.entity.*;
import com.pams.module.activity.repository.*;
import com.pams.module.agent.repository.*;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual tool execution, paired goals, identical call/output ceilings. No confirmed business writes. */
@EnabledIfEnvironmentVariable(named="PAMS_RUN_AGENT_EXECUTION_COMPARE_REAL",matches="true")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:pams_execution_real;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","pams.agent.max-tokens=2048","pams.agent.max-tool-iterations=4","pams.agent.timeout-ms=20000","pams.agent.thinking=false","pams.agent.controlled-planning-enabled=true","pams.agent.controlled-execution-enabled=true","pams.agent.controlled-planning-org-ids[0]=1"})
@ActiveProfiles("test")
class ControlledExecutionRealModelTest {
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @Autowired org.springframework.core.env.Environment environment;
    @Autowired com.pams.module.agent.provider.ProviderSecrets secrets;
    @Autowired com.pams.module.permission.service.TenantAuthorizationService authorization;
    @Autowired ActivityRepository activities;@Autowired ControlledPlanStore plans;@Autowired CoordinatorAgent rules;
    @Autowired DagScheduler scheduler;@Autowired AgentStepRepository steps;@Autowired AgentTaskRepository actions;
    @Autowired AgentLlmCaller caller;@Autowired com.pams.module.agent.stream.AgentEventPublisher publisher;
    @MockitoSpyBean AgentEvaluationMeter meter;
    record Sample(String code,String goal,Set<String> expected){}
    @Test void compareRealReceiptCoverageAndOverheadAgainstRules()throws Exception{
        OrgContext.set(1L);var streams=new ArrayList<String>();var report=new ArrayList<Map<String,Object>>();
        var output=Path.of(environment.getProperty("PAMS_EXECUTION_OUTPUT","../docs/audits/agent-closure-20261103/execution-comparison-"+UUID.randomUUID()));Files.createDirectories(output);
        if(Files.exists(output.resolve("report.json")))throw new IllegalStateException("Evidence report already exists; choose a fresh PAMS_EXECUTION_OUTPUT");
        try{
            var installer=new ControlledPlanningRealModelTest();installer.db=db;installer.environment=environment;installer.isolatedSecrets=secrets;var provider=installer.installDeploymentProvider();
            var ctx=new ToolContext(new LoginUser(1L,"teacher","执行对照","TEACHER",5,null,null,1L),authorization.authorities(1L).stream().map(SimpleGrantedAuthority::new).toList(),null,null).withTimezone("Asia/Shanghai").withMode("readonly");
            var a=new Activity();a.setName("执行收益对照");a.setType("OTHER");a.setStatus(ActivityStatus.EXECUTING);a.setDeleted(0);a.setCreatedBy(1L);a=activities.saveAndFlush(a);long id=a.getId();
            db.update("INSERT INTO activity_agenda(activity_id,step_no,title,org_id) VALUES(?,1,'唯一登记议程',1)",id);
            db.update("INSERT INTO score_record(activity_id,team_name,total,org_id) VALUES(?,'合成队伍',80,1)",id);
            var cases=List.of(
                new Sample("E01","只查询活动"+id+"详情。",Set.of("getActivityDetail")),
                new Sample("E02","先读取活动"+id+"详情，再查该活动的已登记议程，只读，不写策划。",Set.of("getActivityDetail","queryActivityAgenda")),
                new Sample("E03","先读取活动"+id+"详情，再查该活动已录入的评分汇总，只读。",Set.of("getActivityDetail","queryActivityScores")),
                new Sample("E04","查询活动"+id+"详情、已登记议程和已录入评分汇总，不写建议、不做修改。",Set.of("getActivityDetail","queryActivityAgenda","queryActivityScores")),
                new Sample("E05","查询活动"+id+"的筹备任务及已登记排班，只读。",Set.of("queryActivityTasks","queryActivitySchedules")),
                new Sample("E06","先读取活动"+id+"详情，然后查询其已登记议程、筹备任务和已登记排班；仅核实事实。",Set.of("getActivityDetail","queryActivityAgenda","queryActivityTasks","queryActivitySchedules")));
            String selected=environment.getProperty("PAMS_EXECUTION_CASES","");
            if(!selected.isBlank())cases=cases.stream().filter(c->Set.of(selected.split(",")).contains(c.code())).toList();
            assertThat(cases).isNotEmpty();
            doAnswer(invocation->{String stream=invocation.getArgument(0);if(stream!=null&&meter.snapshot(stream).isEmpty()){meter.open(stream,6,2048);streams.add(stream);}return invocation.callRealMethod();}).when(meter).before(any(),anyInt());
            for(var sample:cases)for(String path:List.of("rules","controlled")){
                var row=new LinkedHashMap<String,Object>();row.put("case",sample.code());row.put("goal",sample.goal());row.put("path",path);long start=System.nanoTime();int begin=streams.size();Long runId=null;
                String stream=publisher.newStream("execution-eval",1L);var scoped=ctx.derive(null,stream);caller.beginTask(stream,scoped);meter.open(stream,6,2048);streams.add(stream);
                try{
                    Blackboard bb;
                    if(path.equals("rules")){var result=rules.runWithBlackboard(sample.goal(),scoped,null,"P1",stream);runId=result.run().getId();bb=result.blackboard();}
                    else{var proposal=plans.prepare(sample.goal(),scoped);row.put("planningStatus",proposal.preview().status());row.put("planningReason",proposal.preview().reasonCode());if(proposal.planRef()==null)throw new IllegalStateException("No validated candidate");runId=plans.start(proposal.planRef(),scoped);bb=scheduler.drive(runId,1L);}
                    row.put("status",bb.executionState());row.put("runId",runId);
                    row.put("findings",bb.getFindings());
                    var successful=new HashSet<String>();int toolCount=0;
                    for(var step:steps.findByRunIdOrderBySeqAsc(runId)){
                        if(step.getOutputJson()==null)continue;var root=AgentPreparedOperations.mapper().readTree(step.getOutputJson());if(root.isTextual())root=AgentPreparedOperations.mapper().readTree(root.asText());
                        for(var invocation:root.path("invocations")){toolCount++;if("OK".equals(invocation.path("status").asText()))successful.add(invocation.path("name").asText());}
                    }
                    row.put("successfulCapabilities",successful);row.put("requiredCapabilities",sample.expected());row.put("toolCalls",toolCount);row.put("requiredReceiptsObserved",successful.containsAll(sample.expected()));
                    row.put("unexpectedActions",actions.findByRunIdOrderByIdAsc(runId).size());
                    row.put("completed",successful.containsAll(sample.expected())&&"SUCCESS".equals(bb.executionState())&&actions.findByRunIdOrderByIdAsc(runId).isEmpty());
                }catch(Exception e){row.put("completed",false);row.put("failureType",e.getClass().getSimpleName());}
                finally{caller.endTask(stream);publisher.clear(stream);}
                row.put("elapsedMs",(System.nanoTime()-start)/1_000_000);var usage=new ArrayList<Map<String,Object>>();for(int i=begin;i<streams.size();i++)usage.add(meter.close(streams.get(i)));row.put("usage",usage);report.add(row);
                boolean real=usage.stream().anyMatch(u->((Number)u.getOrDefault("reportedCalls",0)).intValue()>0&&((List<?>)u.getOrDefault("models",List.of())).contains(environment.getProperty("PAMS_REAL_MODEL","deepseek-flash")));
                boolean clean=usage.stream().allMatch(u->((Number)u.getOrDefault("failures",0)).intValue()==0&&((Number)u.getOrDefault("degradedResponses",0)).intValue()==0);
                row.put("validRealSample",Boolean.TRUE.equals(row.get("completed"))&&real&&clean);
                new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("report.json").toFile(),Map.of("provider",provider,"samples",report,"commonBudget",Map.of("maxCalls",6,"perCallOutputTokens",2048),"businessWriteComparison","NOT_ASSESSED","factSemanticsReview","PENDING","releaseEligible",false));
                if(!clean)throw new IllegalStateException("Real-model transport or degradation failure; stopped remaining paid calls; report retained");
            }
            assertThat(report).hasSize(cases.size()*2);assertThat(report).allSatisfy(row->{assertThat(((Number)row.getOrDefault("unexpectedActions",0)).intValue()).isZero();assertThat(row.get("validRealSample")).isEqualTo(true);});
        }finally{for(var stream:streams)meter.close(stream);OrgContext.clear();}
    }
}
