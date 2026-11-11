package com.pams.module.agent.service;
import com.pams.common.OrgContext;
import com.pams.module.agent.core.*;
import com.pams.module.agent.tool.EditorialTools;
import com.pams.module.activity.entity.*;
import com.pams.module.activity.repository.*;
import com.pams.module.content.entity.Article;
import com.pams.module.content.repository.ArticleRepository;
import com.pams.module.content.service.ArticleService;
import com.pams.module.content.dto.ArticleRequest;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class AgentEditorialIntegrationTest {
    @org.springframework.test.context.DynamicPropertySource static void database(org.springframework.test.context.DynamicPropertyRegistry registry){com.pams.module.agent.testing.BatchSixDatabase.configure(registry);}
    @Autowired AgentEditorialService editorial;@Autowired ArticleRepository articles;@Autowired ArticleService nativeArticles;
    @Autowired ActivityRepository activities;@Autowired ActivityPlanRepository plans;@Autowired TaskGate gate;
    @Autowired EditorialTools.ArticleRevision revisionTool;@Autowired EditorialTools.ArticleSubmission submissionTool;@Autowired EditorialTools.PlanSubmission planTool;
    @Autowired ActionReconciliationService reconciliation;@Autowired com.pams.module.agent.repository.AgentTaskRepository actions;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @Autowired AgentExtensionReadService extensions;
    @Autowired com.pams.module.agent.tool.PrepareSchedulePublicationTool publication;
    @Autowired AgentSchedulePublicationService publications;
    @Autowired com.pams.module.routine.repository.ScheduleRepository schedules;
    @Autowired com.pams.module.seat.repository.SeatLayoutRepository layouts;
    @Autowired com.pams.module.agent.tool.QuerySeatLayoutTool seatQuery;
    ToolContext ctx;Article article;Activity activity;
    @BeforeEach void setup(){OrgContext.set(1L);var permissions=List.of("content:view","article:edit","article:submit","activity:view","plan:view","plan:submit").stream().map(SimpleGrantedAuthority::new).toList();
        var user=new LoginUser(1L,"teacher","测试","TEACHER",5,null,null,1L);ctx=new ToolContext(user,permissions,null,null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,permissions));
        activity=new Activity();activity.setName("第六批测试活动");activity.setType("OTHER");activity.setStatus(ActivityStatus.EXECUTING);activity.setDeleted(0);activity.setCreatedBy(1L);activity=activities.saveAndFlush(activity);
        article=new Article();article.setTitle("活动报道");article.setContent("<p>预算2500元，日期保留。</p>");article.setSummary("摘要保留");article.setAuthorId(1L);article.setActivityId(activity.getId());article.setArticleType(Article.ArticleType.REPORT);article.setStatus(Article.ArticleStatus.DRAFT);article.setDeleted(0);article=articles.saveAndFlush(article);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();OrgContext.clear();}
    Map<String,Object> read(){return editorial.readArticle(ctx,Map.of("articleId",article.getId()),false).payload();}
    Map<String,Object> patch(){return Map.of("articleId",article.getId(),"baseline",read().get("baseline"),"changes",Map.of("content",Map.of("before","2500元","after","3000元")));}
    AgentActionPlan pending(AgentTool tool,ToolResult result){assertThat(result.plan()).as(result.summary()).isNotNull();var p=result.plan();return gate.createPlan(ctx,tool,p.summary(),p.total(),p.items(),p.skipped(),p.detailLines(),false,p.preview());}
    @Test void exactRevisionPreservesNativeFieldsAndRequiresConfirmation()throws Exception{
        var p=pending(revisionTool,revisionTool.execute(ctx,patch()));assertThat(articles.findById(article.getId()).orElseThrow().getContent()).contains("2500");
        assertThat(gate.confirm(p.planId(),p.confirmToken(),ctx)).containsEntry("status","SUCCESS");
        var saved=articles.findById(article.getId()).orElseThrow();assertThat(saved.getContent()).isEqualTo("<p>预算3000元，日期保留。</p>");assertThat(saved.getSummary()).isEqualTo("摘要保留");assertThat(saved.getActivityId()).isEqualTo(activity.getId());assertThat(saved.getAuthorId()).isEqualTo(1L);assertThat(saved.getStatus()).isEqualTo(Article.ArticleStatus.DRAFT);assertThat(saved.getRevision()).isGreaterThan(0);
        assertThat(reconciliation.reconcile(p.planId(),ctx)).containsEntry("reconciled",true);
    }
    @Test void concurrentNativeEditInvalidatesPreview(){
        var p=pending(revisionTool,revisionTool.execute(ctx,patch()));var req=new ArticleRequest();req.setTitle("并发标题");req.setContent("并发原文");nativeArticles.update(article.getId(),req,1L,false);articles.flush();
        assertThat(gate.confirm(p.planId(),p.confirmToken(),ctx)).containsEntry("status","FAILED");assertThat(articles.findById(article.getId()).orElseThrow().getContent()).isEqualTo("并发原文");
    }
    @Test void submissionWaitsForHumanAndCannotBeTreatedAsPublication()throws Exception{
        var p=pending(submissionTool,submissionTool.execute(ctx,Map.of("articleId",article.getId(),"baseline",read().get("baseline"))));
        assertThat(gate.confirm(p.planId(),p.confirmToken(),ctx)).containsEntry("status","SUCCESS");assertThat(articles.findById(article.getId()).orElseThrow().getStatus()).isEqualTo(Article.ArticleStatus.PENDING);
        assertThat(editorial.readArticle(ctx,Map.of("articleId",article.getId()),true).payload()).containsEntry("status",Article.ArticleStatus.PENDING);
        assertThat(reconciliation.reconcile(p.planId(),ctx)).containsEntry("reconciled",true);
    }
    @Test void unsupportedHtmlDuplicateScopeReadonlyAndAuthorChangesAreRejected(){
        var args=new HashMap<>(patch());args.put("changes",Map.of("content",Map.of("before","2500元","after","<script>坏</script>")));assertThat(revisionTool.execute(ctx,args).plan()).isNull();
        assertThat(revisionTool.execute(ctx.withMode("readonly"),patch()).plan()).isNull();
        var p=pending(revisionTool,revisionTool.execute(ctx,patch()));var changed=articles.findById(article.getId()).orElseThrow();changed.setAuthorId(2L);articles.saveAndFlush(changed);
        assertThat(gate.confirm(p.planId(),p.confirmToken(),ctx)).containsEntry("status","FAILED");
        var foreign=new ToolContext(new LoginUser(1L,"t","t","TEACHER",5,null,null,2L),List.of(new SimpleGrantedAuthority("content:view")),null,null);
        assertThatThrownBy(()->editorial.readArticle(foreign,Map.of("articleId",article.getId()),false)).hasMessageContaining("组织");
    }
    ActivityPlan plan(){var p=new ActivityPlan();p.setActivityId(activity.getId());p.setOwnerId(1L);p.setVersion(1);p.setStatus(ActivityPlan.PlanStatus.DRAFT);p.setContent("策划正文");return plans.saveAndFlush(p);}
    @Test void planSubmissionRevalidatesCandidatesAndCurrentRevision()throws Exception{
        var plan=plan();var info=editorial.planReview(ctx,plan.getId());var candidates=(List<?>)info.payload().get("reviewerCandidates");assertThat(candidates).isNotEmpty();
        var candidate=(Map<?,?>)candidates.getFirst();assertThat(new EditorialTools.PlanReview(editorial).toLlmText(ctx,info)).contains(candidate.get("name").toString()).doesNotContain("未展开的结构化字段");
        var p=pending(planTool,planTool.execute(ctx,Map.of("planId",plan.getId(),"expectedRevision",plan.getRevision(),"reviewerIds",List.of(candidate.get("id")))));
        assertThat(gate.confirm(p.planId(),p.confirmToken(),ctx)).containsEntry("status","SUCCESS");
        assertThat(plans.findById(plan.getId()).orElseThrow().getStatus()).isEqualTo(ActivityPlan.PlanStatus.PENDING);
        assertThat(reconciliation.reconcile(p.planId(),ctx)).containsEntry("reconciled",true);
    }
    @Test void noReceiptNeverReplaysEditorialWrite()throws Exception{
        var p=pending(revisionTool,revisionTool.execute(ctx,patch()));actions.claimOwned(p.planId(),"PENDING_CONFIRM","RESULT_UNKNOWN",java.time.LocalDateTime.now(),"test");
        assertThat(reconciliation.reconcile(p.planId(),ctx)).containsEntry("reconciled",false);assertThat(articles.findById(article.getId()).orElseThrow().getContent()).contains("2500");
    }
    @Test void interruptedOrdinaryActionBecomesExplicitUnknownWithoutReplaying()throws Exception{
        var p=pending(revisionTool,revisionTool.execute(ctx,patch()));actions.claimOwned(p.planId(),"PENDING_CONFIRM","RUNNING",com.pams.module.agent.orchestration.RuntimeIdentity.STARTED.minusSeconds(1),"previous-process");
        assertThat(reconciliation.reconcile(p.planId(),ctx)).containsEntry("reconciled",false).containsEntry("status","RESULT_UNKNOWN");
        assertThat(actions.findById(p.planId()).orElseThrow().getStatus()).isEqualTo("RESULT_UNKNOWN");assertThat(articles.findById(article.getId()).orElseThrow().getContent()).contains("2500");
    }
    @Test void extensionsReadOnlyRegisteredDataWithPaginationAndNoScoreInference(){
        for(int i=0;i<23;i++)db.update("INSERT INTO activity_agenda(activity_id,step_no,title,org_id) VALUES(?,?,?,?)",activity.getId(),i,"议程"+i,1L);
        var page=extensions.read("queryActivityAgenda",ctx,Map.of("activityId",activity.getId(),"page",1));assertThat((List<?>)page.payload().get("records")).hasSize(3);assertThat(page.payload()).containsEntry("hasMore",false);
        db.update("INSERT INTO score_record(activity_id,team_name,total,org_id) VALUES(?,?,?,1)",activity.getId(),"合成甲组",70);db.update("INSERT INTO score_record(activity_id,team_name,total,org_id) VALUES(?,?,?,1)",activity.getId(),"合成乙组",90);
        var scores=extensions.read("queryActivityScores",ctx,Map.of("activityId",activity.getId()));assertThat(scores.payload()).containsEntry("scope","RECORDED_SCORES_ONLY");
        var statistics=(Map<?,?>)scores.payload().get("statistics");assertThat(((Number)statistics.get("recordedCount")).longValue()).isEqualTo(2);assertThat(((Number)statistics.get("minTotal")).intValue()).isEqualTo(70);assertThat(((Number)statistics.get("maxTotal")).intValue()).isEqualTo(90);assertThat(((Number)statistics.get("meanTotal")).doubleValue()).isEqualTo(80);
        assertThatThrownBy(()->extensions.read("queryActivitySchedules",ctx,Map.of("activityId",activity.getId()))).hasMessageContaining("权限");
        assertThatThrownBy(()->extensions.read("queryActivityAgenda",ctx,Map.of("activityId",activity.getId()+999999))).hasMessageContaining("不可见");
        assertThatThrownBy(()->extensions.read("queryActivityAgenda",ctx,Map.of("activityId",activity.getId(),"page",1.5))).hasMessageContaining("整数");
    }
    com.pams.module.routine.entity.Schedule schedule(){var s=new com.pams.module.routine.entity.Schedule();s.setActivityId(activity.getId());s.setScheduleType("ACTIVITY");s.setStatus("DRAFT");s.setScheduleDate(java.time.LocalDate.of(2026,12,1));s.setStartTime(java.time.LocalTime.of(8,0));s.setEndTime(java.time.LocalTime.of(9,0));s.setSource("MANUAL");return schedules.saveAndFlush(s);}
    ToolContext publishing(){return new ToolContext(ctx.getLoginUser(),List.of("activity:view","schedule:view","schedule:publish").stream().map(SimpleGrantedAuthority::new).toList(),null,null);}
    AgentActionPlan publicationPlan(ToolContext who,Long id){var meta=publications.metadata(who,id);var result=publication.execute(who,Map.of("scheduleIds",List.of(id),"baselines",Map.of(id.toString(),meta.get("baseline"))));assertThat(result.plan()).as(result.summary()).isNotNull();var p=result.plan();return gate.createPlan(who,publication,p.summary(),p.total(),p.items(),p.skipped(),p.detailLines(),false,p.preview());}
    @Test void publishesOnlyExplicitScheduleAndReconcilesWithoutRepublishing()throws Exception{
        var a=schedule();var other=schedule();var who=publishing();var p=publicationPlan(who,a.getId());
        assertThat(gate.confirm(p.planId(),p.confirmToken(),who)).containsEntry("status","SUCCESS");assertThat(schedules.findById(a.getId()).orElseThrow().getStatus()).isEqualTo("PUBLISHED");assertThat(schedules.findById(other.getId()).orElseThrow().getStatus()).isEqualTo("DRAFT");
        assertThat(reconciliation.reconcile(p.planId(),who)).containsEntry("reconciled",true);assertThat(publication.execute(who,Map.of()).plan()).isNull();
    }
    @Test void scheduleNativeEditInvalidatesPublicationPreview(){var s=schedule();var who=publishing();var p=publicationPlan(who,s.getId());var changed=schedules.findById(s.getId()).orElseThrow();changed.setLocation("新地点");schedules.saveAndFlush(changed);
        assertThat(gate.confirm(p.planId(),p.confirmToken(),who)).containsEntry("status","FAILED");assertThat(schedules.findById(s.getId()).orElseThrow().getStatus()).isEqualTo("DRAFT");}
    @Test void seatReadingUsesExplicitLayoutAndBaselineWithoutInventingCapacity(){
        var layout=new com.pams.module.seat.entity.SeatLayout();layout.setName("布局");layout.setActivityId(activity.getId());layout.setRows(3);layout.setCols(4);layout.setSeatData("[{\"row\":1,\"col\":1,\"label\":\"指定席位\",\"state\":\"occupied\"}]");layout=layouts.saveAndFlush(layout);
        var who=new ToolContext(ctx.getLoginUser(),List.of(new SimpleGrantedAuthority("seat:view")),null,null);var result=seatQuery.execute(who,Map.of("layoutId",layout.getId()));assertThat(result.payload()).containsEntry("capacityVerified",false).containsEntry("totalCells",1);
        assertThat(seatQuery.execute(who,Map.of("layoutId",layout.getId(),"page",1,"baseline","stale")).isOk()).isFalse();
    }
}
