package com.pams.module.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.OrgContext;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.*;
import com.pams.module.agent.repository.*;
import com.pams.module.agent.service.*;
import com.pams.module.activity.entity.*;
import com.pams.module.activity.repository.*;
import com.pams.module.content.entity.Article;
import com.pams.module.content.repository.ArticleRepository;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"pams.agent.controlled-planning-enabled=true","pams.agent.controlled-execution-enabled=true","pams.agent.controlled-planning-org-ids[0]=1"})
@ActiveProfiles("test") @Transactional
class ControlledExecutionIntegrationTest {
    @org.springframework.test.context.DynamicPropertySource static void database(org.springframework.test.context.DynamicPropertyRegistry registry){com.pams.module.agent.testing.BatchSixDatabase.configure(registry);}
    @MockitoBean ControlledPlanningService planner;@MockitoBean SpecialistAgent specialist;
    @Autowired ControlledPlanStore store;@Autowired DagScheduler scheduler;@Autowired WorkItemState state;
    @Autowired ToolRegistry tools;@Autowired ActivityRepository activities;@Autowired ArticleRepository articles;
    @Autowired AgentWorkItemRepository nodes;@Autowired AgentStepRepository steps;@Autowired AgentTaskRepository tasks;
    @Autowired TaskGate gate;@Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @Autowired com.pams.module.agent.config.AgentProperties props;
    ToolContext ctx;Activity activity;String goal;Map<String,ControlledPlanValidator.Capability> caps;
    @BeforeEach void setup(){OrgContext.set(1L);ctx=new ToolContext(new LoginUser(1L,"teacher","测试","TEACHER",5,null,null,1L),List.of("activity:view","content:view","article:submit").stream().map(SimpleGrantedAuthority::new).toList(),null,null);
        activity=new Activity();activity.setName("受控运行测试");activity.setType("OTHER");activity.setStatus(ActivityStatus.EXECUTING);activity.setDeleted(0);activity=activities.saveAndFlush(activity);
        goal="读取活动"+activity.getId()+"及议程";caps=new HashMap<>();
        for(var t:tools.all())for(String a:List.of("retrieval","writing"))caps.put(a+":"+t.name(),new ControlledPlanValidator.Capability(a,t));
        when(planner.capabilities(any())).thenReturn(caps);
    }
    @AfterEach void clear(){props.setControlledExecutionEnabled(true);OrgContext.clear();}
    Map<String,Object> node(int id,String agent,String tool,List<Integer> deps,List<Map<String,Object>> bindings){return Map.of("id",id,"agent",agent,"capability",tool,"task","明确步骤","dependsOn",deps,"inputBindings",bindings,"completionCriteria","以该能力真实成功回执核验");}
    Map<String,Object> user(String param,Object literal){return Map.of("parameter",param,"source","USER","quote",literal.toString());}
    Map<String,Object> upstream(String param,int id,String path){return Map.of("parameter",param,"source","STEP","stepId",id,"path",path);}
    String prepare(List<Map<String,Object>> planned)throws Exception{
        var json=new ObjectMapper();var validated=ControlledPlanValidator.validate(json.writeValueAsString(Map.of("goal",goal,"constraints",List.of(),"clarifications",List.of(),"steps",planned)),goal,caps);
        when(planner.preview(eq(goal),any())).thenReturn(new ControlledPlanningService.Preview("MODEL_PLAN_VALIDATED",true,"",1,"planner/v1","hash","caps","fixture",validated.plan(),List.of(),List.of(),validated.steps()));
        return store.prepare(goal,ctx).planRef();
    }
    List<Map<String,Object>> reads(){return List.of(node(1,"retrieval","getActivityDetail",List.of(),List.of(user("activityId",activity.getId()))),node(2,"retrieval","queryActivityAgenda",List.of(1),List.of(upstream("activityId",1,"payload.activityId"))));}
    @Test void persistedContractExecutesOnlyResolvedToolsAndStoresAttemptEvidence()throws Exception{
        String ref=prepare(reads());Long run=store.start(ref,ctx);assertThat(store.start(ref,ctx)).isEqualTo(run);
        var bb=scheduler.drive(run,ctx.getUserId());assertThat(bb.executionState()).isEqualTo("SUCCESS");
        var all=state.nodes(run);assertThat(all).allSatisfy(n->{assertThat(n.getContractJson()).isNotBlank();assertThat(n.getResultJson()).isNotBlank();});
        var first=all.getFirst();assertThat(steps.findByWorkItemIdAndRevisionAndAttempt(first.getId(),1,1).orElseThrow().getInputJson()).contains("resolvedArguments","completionCriteria","schema");
        verifyNoInteractions(specialist);
    }
    @Test void absentResultFieldBlocksDownstreamRatherThanInventingId()throws Exception{
        var plan=new ArrayList<>(reads());plan.set(1,node(2,"retrieval","queryActivityAgenda",List.of(1),List.of(upstream("activityId",1,"payload.nonexistent"))));
        Long run=store.start(prepare(plan),ctx);scheduler.drive(run,1L);assertThat(state.nodes(run)).extracting(AgentWorkItem::getStatus).containsExactly("SUCCESS","FAILED");verifyNoInteractions(specialist);
    }
    @Test void dispatchCannotSwapCapabilityOrBoundArguments()throws Exception{
        Long run=store.start(prepare(reads()),ctx);String lease="test";assertThat(state.acquire(run,lease)).isTrue();var n=state.begin(run,state.nodes(run).getFirst().getId(),lease);
        var nodeCtx=ctx.withRunId(run).withWorkItem(n.getId(),n.getRevision()).withAttempt(n.getAttempt());
        assertThatThrownBy(()->tools.dispatch("queryActivityAgenda",nodeCtx,Map.of("activityId",activity.getId()))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->tools.dispatch("getActivityDetail",nodeCtx,Map.of("activityId",activity.getId()+1))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void ownerModeExpiryAndIndependentExecutionSwitchAreEnforced()throws Exception{
        String ref=prepare(reads());var other=new ToolContext(new LoginUser(2L,"u","u","TEACHER",5,null,null,1L),List.of(new SimpleGrantedAuthority("activity:view")),null,null);
        assertThatThrownBy(()->store.start(ref,other)).hasMessageContaining("不可见");assertThatThrownBy(()->store.start(ref,ctx.withMode("readonly"))).hasMessageContaining("模式");
        props.setControlledExecutionEnabled(false);assertThatThrownBy(()->store.start(ref,ctx)).hasMessageContaining("未");props.setControlledExecutionEnabled(true);
        db.update("UPDATE agent_controlled_plan SET expires_at=? WHERE id=?",java.time.LocalDateTime.now().minusMinutes(1),ref);assertThatThrownBy(()->store.start(ref,ctx)).hasMessageContaining("过期");
    }
    @Test void missingNativeFormFieldsDoNotCreateAnUnusableFormOrBroadenBoundArguments()throws Exception{
        ctx=new ToolContext(ctx.getLoginUser(),List.of("activity:view","activity:create").stream().map(SimpleGrantedAuthority::new).toList(),null,null);
        goal="新建活动，地点和日期还没确定";var create=tools.find("createActivity").orElseThrow();caps.put("generalist:createActivity",new ControlledPlanValidator.Capability("generalist",create));
        long before=activities.count();Long run=store.start(prepare(List.of(node(1,"generalist","createActivity",List.of(),List.of()))),ctx);var result=scheduler.drive(run,1L);
        assertThat(result.executionState()).isEqualTo("FAILED");assertThat(state.nodes(run).getFirst().getOutputText()).contains("普通办事流程");assertThat(tasks.findByRunIdOrderByIdAsc(run)).isEmpty();assertThat(activities.count()).isEqualTo(before);
    }
    @Test void localReplanInvalidatesPendingConfirmationAndDependentRevision()throws Exception{
        var a=new Article();a.setTitle("待送审稿");a.setContent("原文");a.setAuthorId(1L);a.setArticleType(Article.ArticleType.REPORT);a.setStatus(Article.ArticleStatus.DRAFT);a.setDeleted(0);a=articles.saveAndFlush(a);goal="读取稿件"+a.getId()+"再提交审核";
        var plan=List.of(node(1,"writing","readArticleDocument",List.of(),List.of(user("articleId",a.getId()))),node(2,"writing","prepareArticleSubmission",List.of(1),List.of(user("articleId",a.getId()),upstream("baseline",1,"payload.baseline"))),node(3,"retrieval","getArticleReviewStatus",List.of(2),List.of(upstream("articleId",2,"detail.articleId"))));
        Long run=store.start(prepare(plan),ctx);var bb=scheduler.drive(run,1L);assertThat(bb.executionState()).isEqualTo("WAITING_CONFIRM");
        var all=state.nodes(run);var root=all.get(1);var action=tasks.findById(root.getActionTaskId()).orElseThrow();
        assertThatThrownBy(()->state.revise(run,root.getId(),1L,root.getRevision(),"换个能力")).hasMessageContaining("规划句柄");
        store.replace(run,root.getId(),root.getRevision(),prepare(plan),ctx);
        assertThat(tasks.findById(action.getId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
        assertThat(state.nodes(run)).extracting(AgentWorkItem::getRevision).containsExactly(1,2,2);assertThat(state.nodes(run).getFirst().getStatus()).isEqualTo("SUCCESS");
        assertThat(gate.confirm(action.getId(),"old-token",ctx)).containsEntry("status","CANCELLED");
        scheduler.drive(run,1L);var replacement=state.nodes(run).get(1);var nextAction=tasks.findById(replacement.getActionTaskId()).orElseThrow();
        assertThat(gate.confirm(nextAction.getId(),nextAction.getConfirmToken(),ctx)).containsEntry("status","SUCCESS");
        var completed=scheduler.drive(run,1L);assertThat(completed.executionState()).isEqualTo("SUCCESS");
        assertThat(state.nodes(run).get(2).getResultJson()).contains("PENDING");
        String newRef=prepare(plan);assertThatThrownBy(()->store.replace(run,replacement.getId(),replacement.getRevision(),newRef,ctx)).hasMessageContaining("业务提交");
    }
}
