package com.pams.module.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.OrgContext;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.*;
import com.pams.module.agent.repository.*;
import com.pams.module.agent.service.*;
import com.pams.module.agent.stream.AgentEventPublisher;
import com.pams.module.permission.service.TenantAuthorizationService;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in; refuses production database names before Spring opens a connection. */
@SpringBootTest(properties={"pams.agent.benchmark-enabled=true","pams.agent.benchmark-org-ids=1",
    "pams.agent.thinking=false","pams.agent.max-tokens=2048","pams.agent.timeout-ms=60000",
    "pams.agent.max-tool-iterations=4","pams.agent.benchmark-max-calls=8","pams.agent.benchmark-max-tokens=2048"})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="PAMS_S5_ACCEPTANCE",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S5AcceptanceTest {
    private static final Set<String> LEGACY_MATRIX=Set.of("queryActivityOverview","queryActivityTasks","queryAttendance","queryCommonFreeTime","queryCreditRecords","queryMyPendingWork","queryNotifications","queryPartyMemberProgress","queryRosterSigninStats","searchArticles","searchMaterials",
        "createActivity","createMember","createSeatLayout","draftArticleFromActivity","draftPlanSections","generateActivitySchedule","generateActivityTasks","previewCreditBatch","publishAnnouncement","sendNotification");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String url=System.getenv("PAMS_S5_DB_URL");
        if(url==null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/pams_s5_[a-zA-Z0-9_]+\\?.*"))throw new IllegalStateException("S5 requires an isolated pams_s5_* database on a local tunnel");
        registry.add("spring.datasource.url",()->url);registry.add("spring.datasource.username",()->System.getenv("PAMS_S5_DB_USER"));
        registry.add("spring.datasource.password",()->System.getenv("PAMS_S5_DB_PASSWORD"));registry.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/migration-mysql");
    }
    @Autowired JdbcTemplate db;
    @Autowired ToolRegistry tools;
    @Autowired SpecialistAgent specialist;
    @Autowired TaskGate gate;
    @Autowired TenantAuthorizationService auth;
    @Autowired AgentEventPublisher events;
    @Autowired AgentTaskRepository tasks;
    @Autowired AgentStepRepository steps;
    @Autowired AgentRunService runs;
    @Autowired DagScheduler scheduler;
    @Autowired WorkItemState state;
    @Autowired PlanReviewService reviews;
    @Autowired BenchmarkService benchmark;
    @Autowired AgentEvaluationMeter meter;
    @Autowired com.pams.module.notification.service.NotificationOutboxWorker notificationWorker;
    private final ObjectMapper json=new ObjectMapper().registerModule(new com.fasterxml.jackson.databind.module.SimpleModule().addSerializer(java.time.LocalDateTime.class,new com.fasterxml.jackson.databind.ser.std.ToStringSerializer()));
    private final List<Map<String,Object>> evidence=new ArrayList<>();
    private final String prefix="S5-"+UUID.randomUUID().toString().substring(0,8);
    private long activity,dept,session,template,emptyActivity;
    private ToolContext ctx;
    private Path output() { return Path.of(System.getProperty("pams.s5.output","../docs/audits/lingsi-upgrade-implementation/s5-acceptance")).toAbsolutePath().normalize(); }
    private void save(String name,Object value) throws Exception { Files.createDirectories(output());Files.writeString(output().resolve(name),json.writerWithDefaultPrettyPrinter().writeValueAsString(value)); }
    private ToolContext identity(long id) {
        Long org=db.queryForObject("select org_id from sys_user where id=?",Long.class,id);
        OrgContext.set(org);
        var granted=auth.authorities(id).stream().map(SimpleGrantedAuthority::new).toList();
        String name=db.queryForObject("select real_name from sys_user where id=?",String.class,id);
        var user=new LoginUser(id,id==1?"teacher":"s5-none",name,"TEACHER",5,null,null,org);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,granted));
        return auth.refresh(new ToolContext(user,granted,null,events.newStream("s5",id)));
    }
    @BeforeAll void fixture() throws Exception {
        ctx=identity(1L);
        assertThat(tools.all().stream().map(AgentTool::name).toList()).containsAll(LEGACY_MATRIX);
        save("tool-coverage.json",Map.of("registered",tools.all().stream().map(AgentTool::name).toList(),"legacyMatrix",LEGACY_MATRIX,
            "requiresAdditionalAcceptance",tools.all().stream().map(AgentTool::name).filter(n->!LEGACY_MATRIX.contains(n)).toList()));
        dept=db.queryForObject("select min(id) from sys_department where org_id=1",Long.class);
        session=db.queryForObject("select min(id) from member_session where org_id=1",Long.class);
        template=db.queryForObject("select min(id) from seat_layout where is_template=1 and org_id=1",Long.class);
        var result=preview("createActivity",Map.of("name",prefix+"-事实活动","startDate","2026-11-20","endDate","2026-11-20","location","S5 测试礼堂","type","OTHER"));
        gate.confirm(result.plan().planId(),result.plan().confirmToken(),ctx);
        activity=db.queryForObject("select id from activity where name=?",Long.class,prefix+"-事实活动");
        // Seed the reader's inbox explicitly; announcement senders are not recipients.
        var notification=preview("sendNotification",Map.of("title",prefix+"-查询通知样本","content","独立测试库通知样本","recipientUserId",1));
        assertThat(gate.confirm(notification.plan().planId(),notification.plan().confirmToken(),ctx).get("status")).isEqualTo("SUCCESS");
        notificationWorker.tick(); // Scheduled delivery is intentionally disabled under the test profile.
        db.update("insert ignore into sys_role(id,org_id,code,name,level,data_scope,status) values(9001,1,'S5_NONE','S5 无业务授权',1,'SELF',1)");
        db.update("insert ignore into sys_user(id,org_id,username,password,real_name,role_id,status) values(9001,1,'s5-none','not-a-login-password','S5 无权限测试用户',9001,1)");
        db.update("insert into signin_roster(activity_id,fields_json,org_id) values(?,?,1)",activity,"{\"姓名\":\"S5 合成学生\",\"学号\":\"S50001\"}");
        db.update("insert into party_member(name,org_id) values(?,1)",prefix+"-合成发展对象");
        db.update("insert into material(name,biz_type,activity_id,uploader_id,org_id) values(?,'ACTIVITY',?,1,1)",prefix+"-材料",activity);
        db.update("insert into schedule_config(period,label,start_time,end_time,org_id) select 3,'S5 节次','14:00:00','15:40:00',1 where not exists(select 1 from schedule_config where org_id=1 and period=3)");
        db.update("insert into task(activity_id,name,assignee,start_date,end_date,status,org_id) values(?,?,?,DATE_SUB(CURDATE(),INTERVAL 2 DAY),DATE_SUB(CURDATE(),INTERVAL 1 DAY),'TODO',1)",activity,prefix+"-个人待办",ctx.getLoginUser().getRealName());
        db.update("insert into schedule(schedule_type,notes,schedule_date,status,org_id) values('CLASS_DUTY',?,'2026-11-20','PUBLISHED',1)",prefix+"-考勤排班");
        Long attendanceSchedule=db.queryForObject("select id from schedule where notes=?",Long.class,prefix+"-考勤排班");
        db.update("insert into attendance(schedule_id,person_id,person_name,status,status_source,org_id) values(?,1,?,'NORMAL','MANUAL',1)",attendanceSchedule,ctx.getLoginUser().getRealName());
        var rows=new ArrayList<Map<String,Object>>();
        for(Integer period:db.queryForList("select period from schedule_config",Integer.class)) {
            var days=new LinkedHashMap<String,Object>();for(int d=1;d<=5;d++)days.put(""+d,List.of(Map.of("name","S5 合成值班员","freeWeeks","")));
            rows.add(Map.of("period",period,"days",days));
        }
        db.update("insert into no_class_schedule(dept_id,dept_name,semester,grid_json,org_id) values(?,?,'S5-TEST',?,1) on duplicate key update grid_json=values(grid_json)",dept,"S5 测试部门",json.writeValueAsString(rows));
        db.update("insert ignore into organization(id,name,type,enabled_packs,owner_user_id) values(99001,'S5 空数据隔离组织','PARTY','[\"party\"]',99001)");
        auth.initializeOrg(99001L);
        Long emptyRole=db.queryForObject("select id from sys_role where org_id=99001 and code='TEACHER'",Long.class);
        db.update("insert ignore into sys_user(id,org_id,username,password,real_name,role_id,status) values(99001,99001,'s5-empty','not-a-login-password','S5 空数据用户',?,1)",emptyRole);
        db.update("insert into activity(name,type,status,start_date,created_by,org_id) values(?,'OTHER','ASSIGNED','2026-11-20',99001,99001)",prefix+"-空数据活动");
        emptyActivity=db.queryForObject("select id from activity where org_id=99001 and name=?",Long.class,prefix+"-空数据活动");
        for(int i=0;i<2;i++)db.update("insert into activity(name,type,status,start_date,created_by,org_id) values(?,'OTHER','ASSIGNED','2026-11-20',1,1)",prefix+"-歧义活动");
        save("environment.json",Map.of("database",db.queryForObject("select database()",String.class),"mysql",db.queryForObject("select version()",String.class),"prefix",prefix,"activityId",activity,"fixture","synthetic-only-v1"));
    }
    private SpecialistAgent.Result preview(String name,Map<String,Object> args) {
        return specialist.execute("generalist","S5 确定性工具验收",ctx,List.of(new com.pams.module.agent.llm.LlmProvider.ToolCallRequest("s5-call",name,args)),ctx.getStreamId(),ctx.getLoginUser().getUsername());
    }
    private Map<String,Object> args(String name) {
        return switch(name) {
            case "queryActivityOverview" -> Map.of("month","2026-11","keyword",prefix);
            case "createActivity" -> Map.of("name",prefix+"-新增活动","startDate","2026-11-20");
            case "createMember" -> Map.of("name",prefix+"-合成成员","sessionId",session,"deptId",dept,"position","STAFF","studentNo",prefix);
            case "createSeatLayout" -> Map.of("activityId",activity,"templateId",template,"name",prefix+"-座位表");
            case "draftArticleFromActivity" -> Map.of("activityId",activity);
            case "draftPlanSections" -> Map.of("activityId",activity,"documentBody","<h1>自由策划书</h1><p>2026-11-20 在 S5 测试礼堂开展合成测试活动</p><p>正文完整尾部</p>");
            case "generateActivityTasks","queryActivityTasks","queryRosterSigninStats" -> Map.of("activityId",activity);
            case "generateActivitySchedule" -> Map.of("activityId",activity,"startDate","2026-11-20","endDate","2026-11-20","startTime","14:00","endTime","15:40","deptIds",List.of(dept),"peoplePerSlot",1,"semester","S5-TEST");
            case "previewCreditBatch" -> Map.of("activityId",activity,"credit",0.5,"onlySigned",false);
            case "publishAnnouncement" -> Map.of("title",prefix+"-公告","content","仅发送到独立测试库中的合成用户");
            case "sendNotification" -> Map.of("title",prefix+"-通知","content","S5 测试通知","recipientUserId",1);
            default -> Map.of();
        };
    }
    private String table(String name) {
        return switch(name) {
            case "createActivity" -> "activity";case "createMember" -> "member";case "createSeatLayout" -> "seat_layout";
            case "draftArticleFromActivity" -> "article";case "draftPlanSections" -> "activity_plan";case "generateActivityTasks" -> "task";
            case "generateActivitySchedule" -> "schedule";case "previewCreditBatch" -> "credit_record";
            case "publishAnnouncement" -> "announcement";case "sendNotification" -> "notification";default -> throw new IllegalArgumentException(name);
        };
    }
    private long count(String table) { return db.queryForObject("select count(*) from "+table+" where org_id=1",Long.class); }
    @TestFactory @Order(1) Stream<DynamicTest> legacyTwentyOneToolMatrix() {
        return tools.all().stream().filter(t->LEGACY_MATRIX.contains(t.name())).sorted(Comparator.comparing(AgentTool::name)).map(tool->DynamicTest.dynamicTest(tool.name(),()->{
            var row=new LinkedHashMap<String,Object>();row.put("tool",tool.name());row.put("fixturePrefix",prefix);row.put("write",tool.writeOperation());
            try {
                var low=identity(9001L);
                if(!tool.requiredPermissions().isEmpty() || tool.requiresLeader())assertThatThrownBy(()->tools.dispatch(tool.name(),low,args(tool.name()))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                ctx=identity(1L);
                @SuppressWarnings("unchecked") var properties=(Map<String,Object>)tool.parametersSchema().get("properties");
                if(properties!=null && properties.containsKey("activityName")) {
                    var ambiguous=new LinkedHashMap<String,Object>(args(tool.name()));ambiguous.remove("activityId");ambiguous.put("activityName",prefix+"-歧义活动");
                    var ambiguity=tools.dispatch(tool.name(),ctx,ambiguous);assertThat(ambiguity.isOk()).isFalse();assertThat(ambiguity.plan()).isNull();row.put("ambiguityRejected",true);
                }
                if(tool.writeOperation()) {
                    assertThatThrownBy(()->tools.dispatch(tool.name(),ctx.withMode("readonly"),args(tool.name()))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                    String table=table(tool.name());long before=count(table);
                    var result=preview(tool.name(),args(tool.name()));
                    assertThat(result.plan()).as(tool.name()+" preview: "+result.text()).isNotNull();
                    assertThat(count(table)).isEqualTo(before);
                    var receipt=gate.confirm(result.plan().planId(),result.plan().confirmToken(),ctx);
                    assertThat(receipt.get("status")).as(tool.name()+" receipt "+receipt).isEqualTo("SUCCESS");
                    long after=count(table);assertThat(after).isGreaterThan(before);
                    if("sendNotification".equals(tool.name())) {
                        notificationWorker.tick();
                        assertThat(db.queryForObject("select count(*) from notification_inbox i join notification n on n.id=i.message_id where i.org_id=1 and i.user_id=1 and n.title=?",Long.class,prefix+"-通知")).isEqualTo(1L);
                        row.put("recipientInboxDelivered",true);
                    }
                    gate.confirm(result.plan().planId(),result.plan().confirmToken(),ctx);
                    assertThat(count(table)).as("repeated confirmation").isEqualTo(after);
                    row.put("actualBusinessRowsAdded",after-before);row.put("previewNoWrite",true);row.put("repeatedConfirmationNoWrite",true);
                } else {
                    var result=tools.dispatch(tool.name(),ctx,args(tool.name()));
                    assertThat(result.isOk()).as(result.summary()).isTrue();
                    assertThat(result.summary()).isNotBlank();row.put("resultStatus",result.status());
                    if(result.payload().get("count") instanceof Number n)assertThat(n.longValue()).as(tool.name()+" populated tenant").isPositive();
                    if(result.payload().get("expected") instanceof Number n)assertThat(n.longValue()).isPositive();
                    if(result.payload().get("table") instanceof Map<?,?> table && table.get("rows") instanceof Collection<?> data)assertThat(data).as(tool.name()+" populated rows").isNotEmpty();
                    row.put("populatedTenantRead",true);
                    var empty=identity(99001L);var emptyArgs=new LinkedHashMap<String,Object>();
                    if(properties!=null && properties.containsKey("activityId"))emptyArgs.put("activityId",emptyActivity);
                    if(properties!=null && properties.containsKey("keyword"))emptyArgs.put("keyword",prefix+"-不存在");
                    var noData=tools.dispatch(tool.name(),empty,emptyArgs);assertThat(noData.isOk()).as(noData.summary()).isTrue();
                    if(noData.payload().get("count") instanceof Number n)assertThat(n.longValue()).isZero();
                    if(noData.payload().get("returned") instanceof Number n)assertThat(n.longValue()).isZero();
                    if(noData.payload().get("expected") instanceof Number n)assertThat(n.longValue()).isZero();
                    row.put("emptyTenantNoFailure",true);ctx=identity(1L);
                }
                row.put("status","PASS");
            } catch(Throwable failure) {row.put("status","FAIL");row.put("failureType",failure.getClass().getSimpleName());throw failure;}
            finally { evidence.add(row);save("tool-matrix.json",evidence); }
        }));
    }
    @Test @Order(2) void liveNaturalLanguagePreviewAndRealCommit() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("PAMS_S5_LIVE")));
        ctx=identity(1L);meter.open(ctx.getStreamId(),8,2048);
        var run=runs.startRun(null,1L,"S5 真实模型草稿","SINGLE");
        try {
            var bb=new Blackboard();bb.setGoal("S5 真实模型草稿");var node=new Blackboard.PlanItem();node.setId(1);node.setAgent("generalist");
            node.setTask("请先查活动，再为活动 ID "+activity+"（"+prefix+"-事实活动）写推文草稿。实际日期为2026-11-20，地点为S5 测试礼堂。调用工具生成待确认草稿，不要发布，不要创建其他活动。");bb.getPlan().add(node);
            long before=count("article");scheduler.start(run,bb,ctx.withRunId(run.getId()));
            var action=tasks.findByRunIdOrderByIdAsc(run.getId()).stream().filter(t->"draftArticleFromActivity".equals(t.getToolName())).findFirst().orElseThrow();
            assertThat(action.getStatus()).isEqualTo(AgentTask.PENDING_CONFIRM);assertThat(count("article")).isEqualTo(before);
            @SuppressWarnings("unchecked") var plan=(Map<String,Object>)gate.detail(action.getId(),1L).get("plan");
            var receipt=gate.confirm(action.getId(),(String)plan.get("confirmToken"),ctx);
            assertThat(receipt.get("status")).isEqualTo("SUCCESS");assertThat(count("article")).isEqualTo(before+1);
            String body=db.queryForObject("select content from article where activity_id=? order by id desc limit 1",String.class,activity);
            assertThat(body).contains("S5 测试礼堂",prefix+"-事实活动");
            gate.confirm(action.getId(),(String)plan.get("confirmToken"),ctx);assertThat(count("article")).isEqualTo(before+1);
            save("live-business.json",Map.of("status","PASS","runId",run.getId(),"receiptStatus",receipt.get("status"),"newArticleRows",1,"usage",meter.snapshot(ctx.getStreamId())));
        } finally {meter.close(ctx.getStreamId());state.cancel(run.getId(),1L);}
    }
    @Test @Order(3) void liveFreePlanPreservesExistingVersionAndCommitsFullBody() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("PAMS_S5_LIVE")));
        ctx=identity(1L);meter.open(ctx.getStreamId(),8,4096);
        long before=count("activity_plan");
        Long previous=db.queryForObject("select id from activity_plan where activity_id=? order by version desc limit 1",Long.class,activity);
        String original=db.queryForObject("select content from activity_plan where id=?",String.class,previous);
        try {
            var result=specialist.execute("generalist","为活动 ID "+activity+"（"+prefix+"-事实活动）编写一份简短策划书，日期2026-11-20，地点S5 测试礼堂。正文只用三个自定义标题：目标、安排、验收。通过 draftPlanSections 的 documentBody 参数传入完整HTML正文，末尾包含验收标记 PLAN_BODY_END。不要套用12章，不要创建其他活动，生成待确认清单即可。",ctx,null,ctx.getStreamId(),"teacher");
            assertThat(result.plan()).as(result.text()).isNotNull();
            assertThat(result.plan().preview()).contains("PLAN_BODY_END");
            assertThat(count("activity_plan")).isEqualTo(before);
            var receipt=gate.confirm(result.plan().planId(),result.plan().confirmToken(),ctx);
            assertThat(receipt.get("status")).as(receipt.toString()).isEqualTo("SUCCESS");
            assertThat(count("activity_plan")).isEqualTo(before+1);
            assertThat(db.queryForObject("select content from activity_plan where id=?",String.class,previous)).isEqualTo(original);
            String body=db.queryForObject("select content from activity_plan where activity_id=? order by version desc limit 1",String.class,activity);
            assertThat(body).contains("PLAN_BODY_END","S5 测试礼堂");
            gate.confirm(result.plan().planId(),result.plan().confirmToken(),ctx);
            assertThat(count("activity_plan")).isEqualTo(before+1);
            save("live-plan.json",Map.of("status","PASS","previousPreserved",true,"bodyTailPresent",true,"usage",meter.snapshot(ctx.getStreamId())));
        } finally {meter.close(ctx.getStreamId());}
    }
    @Test @Order(3) void liveReviewLocatesAnInjectedFactError() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("PAMS_S5_LIVE")));
        ctx=identity(1L);meter.open(ctx.getStreamId(),3,2048);var run=runs.startRun(null,1L,"S5 事实审查","P1");
        try {
            var bb=new Blackboard();var n=new Blackboard.PlanItem();n.setId(1);n.setAgent("writing");n.setTask("只写活动事实：日期2026-11-20，地点S5 测试礼堂。日期地点不得修改。");bb.getPlan().add(n);
            state.initialize(run.getId(),bb,ctx.withRunId(run.getId()));var item=state.nodes(run.getId()).getFirst();
            var result=reviews.evaluate(ctx.withRunId(run.getId()).withWorkItem(item.getId(),1),tools.find("draftArticleFromActivity").orElseThrow(),"活动于2026-11-21在S5 测试体育馆举行。");
            save("live-review.json",Map.of("review",result,"usage",meter.snapshot(ctx.getStreamId())));
            assertThat(result.status()).isEqualTo("BLOCKED");assertThat(result.issues()).isNotEmpty();
        } finally {meter.close(ctx.getStreamId());state.cancel(run.getId(),1L);}
    }
    @Test @Order(4) void liveSameBudgetSingleAndMultiComparison() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("PAMS_S5_LIVE")));ctx=identity(1L);
        var benchmarkPreview=preview("createActivity",Map.of("name",prefix+"-对照活动","startDate","2026-11-20","endDate","2026-11-20","location","S5 测试礼堂"));
        gate.confirm(benchmarkPreview.plan().planId(),benchmarkPreview.plan().confirmToken(),ctx);
        long benchmarkActivity=db.queryForObject("select id from activity where name=?",Long.class,prefix+"-对照活动");
        var result=benchmark.runCases(List.of(
            new BenchmarkService.TaskDef("S5_QUERY","查询活动 ID "+benchmarkActivity+"（"+prefix+"-对照活动）的活动信息和任务进度，只查数据，不写入"),
            new BenchmarkService.TaskDef("S5_DRAFT","先查询活动 ID "+benchmarkActivity+"（"+prefix+"-对照活动），然后生成推文草稿。日期2026-11-20，地点S5 测试礼堂。只出预览，等待确认。")),List.of("P1","P2"),ctx);
        save("live-comparison.json",result);
        @SuppressWarnings("unchecked") var rows=(List<AgentBenchmark>)result.get("rows");assertThat(rows).hasSize(6);
        var quality=new ArrayList<Map<String,Object>>();
        for(var row:rows) {
            var metrics=json.readTree(row.getMetricsJson());assertThat(metrics.path("degradedResponses").asInt()).isZero();
            assertThat(metrics.path("usageComplete").asBoolean()).isTrue();
            boolean accepted;
            if("S5_DRAFT".equals(row.getTaskCode())) {
                accepted="WAITING_CONFIRM".equals(row.getStatus()) && state.nodes(row.getRunId()).stream().anyMatch(n->"CHECKED".equals(n.getReviewStatus())) && tasks.findByRunIdOrderByIdAsc(row.getRunId()).stream().filter(t->"draftArticleFromActivity".equals(t.getToolName())).anyMatch(t->{
                    try {String body=AgentJson.readTree(json,t.getPlanJson()).path("preview").asText();return body.contains(prefix+"-对照活动") && body.contains("S5 测试礼堂");}
                    catch(Exception e){return false;}
                });
            } else {
                boolean actualFact=false;
                for(var step:steps.findByRunIdOrderBySeqAsc(row.getRunId())) {
                    if(step.getWorkItemId()==null || step.getOutputJson()==null)continue;
                    var output=AgentJson.readTree(json,step.getOutputJson());
                    for(var encoded:output.path("toolFacts")) {
                        var data=AgentJson.readTree(json,encoded.asText());
                        for(var fact:data.path("data").path("table").path("rows")) {
                            if(fact.path("id").asLong()==benchmarkActivity && fact.path("name").asText().equals(prefix+"-对照活动")
                                && "2026-11-20".equals(fact.path("startDate").asText()) && "S5 测试礼堂".equals(fact.path("location").asText()))actualFact=true;
                        }
                    }
                }
                accepted=row.getToolCalls()>0 && "SUCCESS".equals(row.getStatus()) && actualFact;
            }
            quality.add(Map.of("runId",row.getRunId(),"task",row.getTaskCode(),"arm",row.getArm(),"mode",row.getMode(),"accepted",accepted,"scope","READ_OR_PREVIEW_QUALITY_NOT_BUSINESS_COMMIT"));
        }
        save("live-quality.json",quality);
        assertThat(quality).allSatisfy(row->assertThat(row.get("accepted")).as(row.toString()).isEqualTo(true));
    }
    @Test @Order(5) void actualCourseRowsAffectTheFreeTimeCalculation() {
        ctx=identity(1L);
        db.update("insert into course_schedule(user_id,semester,day_of_week,period,course_name,org_id) values(1,?,1,3,'S5 合成课程',1)",prefix);
        var result=tools.dispatch("queryCommonFreeTime",ctx,Map.of("userIds",List.of(1),"semester",prefix));
        assertThat(result.isOk()).isTrue();
        var heatmap=(Map<?,?>)result.payload().get("heatmap");var monday=(Map<?,?>)heatmap.get("1");
        assertThat(((Number)monday.get(3)).intValue()).isZero();
        var tuesday=(Map<?,?>)heatmap.get("2");assertThat(((Number)tuesday.get(3)).intValue()).isEqualTo(1);
        var foreign=identity(99001L);
        assertThat(tools.dispatch("queryCommonFreeTime",foreign,Map.of("userIds",List.of(1),"semester",prefix)).isOk()).isFalse();
    }
    @AfterAll void clear() { OrgContext.clear();SecurityContextHolder.clearContext(); }
}
