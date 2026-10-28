package com.pams.module.notification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pams.common.OrgContext;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.tool.QueryMyPendingWorkTool;
import com.pams.module.notification.service.PendingWorkService;
import com.pams.module.permission.service.TenantAuthorizationService;
import com.pams.security.LoginUser;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PendingWorkTest {
  @Autowired JdbcTemplate db;
  @Autowired PendingWorkService pending;
  @Autowired TenantAuthorizationService auth;
  @Autowired QueryMyPendingWorkTool tool;
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @Autowired com.pams.security.JwtUtil jwt;
  long user,other,activity;String prefix;ToolContext ctx;
  @BeforeEach void setup(){
    OrgContext.set(1L);user=db.queryForObject("SELECT id FROM sys_user WHERE username='teacher'",Long.class);
    other=db.queryForObject("SELECT id FROM sys_user WHERE username='staff'",Long.class);prefix="待办-"+UUID.randomUUID();
    db.update("INSERT INTO activity(name,status,org_id,deleted) VALUES(?,'EXECUTING',1,0)",prefix);
    activity=db.queryForObject("SELECT id FROM activity WHERE name=?",Long.class,prefix);
    var principal=new LoginUser(user,"teacher","同名负责人","TEACHER",5,null,null,1L);
    ctx=new ToolContext(principal,auth.business(user).stream().map(SimpleGrantedAuthority::new).toList(),null,null);
  }
  @AfterEach void clear(){OrgContext.clear();}
  long task(Long uid,String suffix){
    db.update("INSERT INTO task(activity_id,name,assignee,assignee_user_id,end_date,progress,status,org_id,deleted,revision) VALUES(?,?,?, ?,?,0,'TODO',1,0,0)",activity,prefix+suffix,"同名负责人",uid,LocalDate.now(PendingWorkService.ZONE).minusDays(1));
    return db.queryForObject("SELECT id FROM task WHERE name=?",Long.class,prefix+suffix);
  }
  @Test void ownershipUsesAccountIdentityNeverDuplicateNamesOrLegacyText(){
    long mine=task(user,"本人");task(other,"同名他人");task(null,"历史姓名");
    var own=pending.summary(ctx).items().stream().filter(i->i.title().startsWith(prefix)).toList();
    assertThat(own).extracting(PendingWorkService.Item::entityId).containsExactly(mine);
    assertThat(own.getFirst().overdue()).isTrue();
  }
  @Test void workIsIndependentOfMessageReadStateAndRecheckedOnCompletion(){
    long id=task(user,"完成");
    assertThat(pending.summary(ctx).items()).anyMatch(i->i.entityType().equals("TASK") && i.entityId()==id);
    db.update("UPDATE task SET progress=100,status='DONE' WHERE id=?",id);
    assertThat(pending.summary(ctx).items()).noneMatch(i->i.entityType().equals("TASK") && i.entityId()==id);
  }
  @Test void selectedReviewerAndLivePermissionDeterminePlanWork(){
    db.update("INSERT INTO activity_plan(activity_id,version,status,owner_id,submitter_id,org_id,revision) VALUES(?,1,'PENDING',?,?,1,0)",activity,other,other);
    long id=db.queryForObject("SELECT id FROM activity_plan WHERE activity_id=?",Long.class,activity);
    db.update("INSERT INTO activity_plan_review(plan_id,reviewer_id,name) VALUES(?,?,?)",id,user,"审核人");
    assertThat(pending.summary(ctx).items()).anyMatch(i->i.entityType().equals("PLAN") && i.entityId()==id);
    assertThat(pending.summary(ctx.restrictTo(Set.of("task:view"))).items()).noneMatch(i->i.entityType().equals("PLAN"));
    db.update("UPDATE activity_plan SET status='WITHDRAWN' WHERE id=?",id);
    assertThat(pending.summary(ctx).items()).noneMatch(i->i.entityType().equals("PLAN") && i.entityId()==id);
  }
  @Test void agentUsesTheSameSummaryAndNoGlobalOverdueList(){
    task(user,"本人的事");task(other,"别人的事");
    var summary=pending.summary(ctx);var result=tool.execute(ctx,Map.of());
    assertThat(result.payload().get("total")).isEqualTo(summary.total());
    assertThat(result.payload().get("items")).isEqualTo(summary.items());
    assertThat(result.summary()).doesNotContain("别人的事");
  }
  @Test void staleContextCannotRestoreRevokedPermissions(){
    task(user,"撤权");
    var live=mock(TenantAuthorizationService.class);when(live.valid(user)).thenReturn(true);
    when(live.identity(user)).thenReturn(Map.of("org_id",1L));when(live.business(user)).thenReturn(Set.of());
    assertThat(new PendingWorkService(db,live).summary(ctx).total()).isZero();
    OrgContext.set(2L);
    assertThatThrownBy(()->pending.summary(ctx)).isInstanceOf(com.pams.common.BizException.class);
  }
  @Test void dashboardAndNotificationApiShareTheSameCurrentPendingSummary() throws Exception {
    task(user,"接口一致");String token=jwt.generate(user,"teacher","TEACHER");
    var json=new com.fasterxml.jackson.databind.ObjectMapper();
    var inbox=json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/notifications/pending").header("Authorization","Bearer "+token)).andReturn().getResponse().getContentAsString()).path("data");
    var dashboard=json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/dashboard").header("Authorization","Bearer "+token)).andReturn().getResponse().getContentAsString()).path("data").path("pendingWork");
    assertThat(inbox).isEqualTo(dashboard);
    assertThat(inbox.path("total").asInt()).isGreaterThan(0);
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/notifications/pending"))
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
  }
}
