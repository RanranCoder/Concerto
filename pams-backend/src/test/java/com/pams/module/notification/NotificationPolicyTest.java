package com.pams.module.notification;

import static org.assertj.core.api.Assertions.*;
import com.pams.common.OrgContext;
import com.pams.module.notification.entity.*;
import com.pams.module.notification.service.*;
import com.pams.module.notification.service.NotificationPolicyService.Policy;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class NotificationPolicyTest {
  @Autowired JdbcTemplate db;
  @Autowired NotificationPolicyService policies;
  @Autowired NotificationDigestService digests;
  @Autowired NotificationPipeline pipeline;
  @Autowired NotificationVisibility visibility;
  @Autowired com.pams.module.notification.repository.NotificationRepository messages;
  @Autowired NotificationMetricsService metrics;
  @Autowired NotificationOutboxWorker worker;
  @Autowired NotificationService service;
  @Autowired PlatformTransactionManager manager;
  long uid,owner;TransactionTemplate tx;LocalDateTime now;
  @BeforeEach void setup(){
    OrgContext.set(1L);tx=new TransactionTemplate(manager);now=LocalDate.now(PendingWorkService.ZONE).atTime(12,0);
    owner=db.queryForObject("SELECT id FROM sys_user WHERE username='teacher'",Long.class);
    String name="policy-"+UUID.randomUUID();
    db.update("INSERT INTO sys_user(org_id,username,password,real_name,role_id,status,deleted) SELECT 1,?,'test-only','策略验证',role_id,1,0 FROM sys_user WHERE id=?",name,owner);
    uid=db.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name);
    db.update("DELETE FROM notification_org_policy WHERE org_id=1");
  }
  @AfterEach void cleanup(){
    db.update("DELETE FROM notification_org_policy WHERE org_id=1");db.update("DELETE FROM notification_digest_item WHERE org_id=1 AND user_id=?",uid);db.update("DELETE FROM notification_daily_digest WHERE org_id=1 AND user_id=?",uid);
    var ids=db.queryForList("SELECT o.message_id FROM notification_outbox o JOIN notification_outbox_recipient r ON r.outbox_id=o.id WHERE o.org_id=1 AND r.user_id=?",Long.class,uid);
    db.update("DELETE FROM notification_inbox WHERE user_id=?",uid);db.update("DELETE FROM notification_delivery WHERE user_id=?",uid);db.update("DELETE FROM notification_preference WHERE user_id=?",uid);
    for(long id:ids){db.update("DELETE FROM notification_outbox_recipient WHERE outbox_id IN (SELECT id FROM notification_outbox WHERE message_id=?)",id);db.update("DELETE FROM notification_outbox WHERE message_id=?",id);db.update("DELETE FROM notification WHERE id=?",id);}
    db.update("DELETE FROM sys_user WHERE id=?",uid);OrgContext.clear();
  }
  Policy policy(boolean quiet,boolean digest,long version){return new Policy(quiet,LocalTime.of(22,0),LocalTime.of(8,0),digest,LocalTime.of(9,0),version,null);}
  void enable(){policies.save(policy(false,true,0),owner);db.update("UPDATE notification_org_policy SET digest_since=? WHERE org_id=1",now.minusHours(1));}
  long source(NotificationType type){
    long id=tx.execute(s->pipeline.record(type,"策略测试-"+UUID.randomUUID(),"原始资讯","MATERIAL",1L,null,uid,null,null).getId());
    db.update("INSERT INTO notification_inbox(org_id,message_id,user_id,created_at) VALUES(1,?,?,?)",id,uid,now.minusMinutes(5));return id;
  }
  @Test void defaultsValidationVersionAndAuditAreExplicit(){
    assertThat(policies.get().quietEnabled()).isFalse();assertThat(policies.get().digestEnabled()).isFalse();
    var saved=policies.save(policy(true,false,0),owner);assertThat(saved.version()).isEqualTo(1);
    assertThatThrownBy(()->policies.save(policy(false,true,0),owner)).hasMessageContaining("已变化");
    assertThatThrownBy(()->policies.save(new Policy(true,LocalTime.NOON,LocalTime.NOON,false,LocalTime.NOON,1,null),owner)).hasMessageContaining("有效");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_policy_audit WHERE org_id=1 AND actor_id=? AND version=1",Long.class,owner)).isPositive();
  }
  @Test void crossMidnightQuietHoursOnlyAffectInformationAndDoNotReplayLater(){
    Policy policy=policy(true,false,0);
    assertThat(NotificationPolicyService.quiet(policy,now.withHour(22))).isTrue();
    assertThat(NotificationPolicyService.quiet(policy,now.withHour(7))).isTrue();
    assertThat(NotificationPolicyService.quiet(policy,now.withHour(8))).isFalse();
    assertThat(NotificationPolicyService.allowImmediate(policy,NotificationType.NEWS_UPLOADED,now.withHour(23),now)).isFalse();
    for(var type:List.of(NotificationType.GOVERNANCE_CHANGED,NotificationType.SYSTEM,NotificationType.TASK_ASSIGNED,NotificationType.PLAN_SUBMITTED,NotificationType.SCHEDULE_CANCELLED))assertThat(NotificationPolicyService.allowImmediate(policy,type,now.withHour(23),now.withHour(23))).isTrue();
  }
  @Test void digestIsPersonalOnceDailyAndNeverConsumesOriginalReadState(){
    enable();long first=source(NotificationType.NEWS_UPLOADED),second=source(NotificationType.ARTICLE_PUBLISHED);
    assertThat(digests.dispatch(uid,now)).isEqualTo(1);assertThat(digests.dispatch(uid,now.plusMinutes(1))).isZero();
    long digest=db.queryForObject("SELECT message_id FROM notification_daily_digest WHERE org_id=1 AND user_id=?",Long.class,uid);
    assertThat(db.queryForObject("SELECT item_count FROM notification_daily_digest WHERE user_id=?",Integer.class,uid)).isEqualTo(2);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE message_id IN (?,?) AND read_at IS NULL",Long.class,first,second)).isEqualTo(2);
    assertThat(visibility.canRead(uid,messages.findById(digest).orElseThrow())).isTrue();assertThat(visibility.canRead(owner,messages.findById(digest).orElseThrow())).isFalse();
    tx.executeWithoutResult(s->{db.update("DELETE FROM role_grant WHERE org_id=1 AND role_id=(SELECT role_id FROM sys_user WHERE id=?)",uid);assertThat(visibility.canRead(uid,messages.findById(digest).orElseThrow())).isFalse();s.setRollbackOnly();});
  }
  @Test void readMutedAndActionableMessagesAreNotAddedToDigest(){
    enable();long read=source(NotificationType.NEWS_UPLOADED);source(NotificationType.ARTICLE_PUBLISHED);source(NotificationType.TASK_ASSIGNED);
    db.update("UPDATE notification_inbox SET read_at=CURRENT_TIMESTAMP WHERE message_id=?",read);
    db.update("INSERT INTO notification_preference(org_id,user_id,event_type,enabled) VALUES(1,?,'ARTICLE_PUBLISHED',FALSE)",uid);
    assertThat(digests.dispatch(uid,now)).isZero();
    assertThat(db.queryForObject("SELECT item_count FROM notification_daily_digest WHERE user_id=?",Integer.class,uid)).isZero();
  }
  @Test void digestWaitsForQuietEndAndTransactionsCanRollback(){
    enable();source(NotificationType.NEWS_UPLOADED);
    var p=policies.get();policies.save(new Policy(true,LocalTime.of(11,0),LocalTime.of(13,0),true,p.digestTime(),p.version(),null),owner);
    assertThat(digests.dispatch(uid,now)).isZero();
    tx.executeWithoutResult(s->{assertThat(digests.dispatch(uid,now.plusHours(2))).isEqualTo(1);s.setRollbackOnly();});
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_daily_digest WHERE user_id=?",Long.class,uid)).isZero();
    assertThat(digests.dispatch(uid,now.plusHours(2))).isEqualTo(1);
  }
  @Test void concurrentDigestRunsAndMetricsRemainTenantScoped() throws Exception {
    enable();source(NotificationType.NEWS_UPLOADED);
    try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
      java.util.concurrent.Callable<Integer> call=()->{OrgContext.set(1L);try{return digests.dispatch(uid,now);}finally{OrgContext.clear();}};
      var result=pool.invokeAll(List.of(call,call));assertThat(result.get(0).get()+result.get(1).get()).isEqualTo(1);
    }
    OrgContext.set(2L);assertThat(digests.dispatch(uid,now)).isZero();
    assertThat(metrics.snapshot().get("digestMessages")).isEqualTo(0L);OrgContext.set(1L);
  }
  @Test void metricsRecordCompletionAndRealMergeHits(){
    long before=db.queryForObject("SELECT COALESCE(SUM(total_value),0) FROM notification_metric WHERE org_id=1 AND metric_key='fact_merge_hits'",Long.class);
    tx.executeWithoutResult(s->{pipeline.record(NotificationType.SYSTEM,"指标合并","合并",null,null,null,uid,null,null);pipeline.record(NotificationType.SYSTEM,"指标合并","合并",null,null,null,uid,null,null);});
    for(int i=0;i<3;i++)worker.tick();
    assertThat(db.queryForObject("SELECT total_value FROM notification_metric WHERE org_id=1 AND metric_key='fact_merge_hits'",Long.class)).isEqualTo(before+1);
    long retries=db.queryForObject("SELECT COALESCE(SUM(total_value),0) FROM notification_metric WHERE org_id=1 AND metric_key='outbox_retry_claims'",Long.class);
    db.update("UPDATE notification_outbox SET status='PENDING',attempts=1,next_attempt_at=CURRENT_TIMESTAMP,completed_at=NULL WHERE message_id IN (SELECT message_id FROM notification_inbox WHERE user_id=?)",uid);
    worker.tick();
    assertThat(db.queryForObject("SELECT total_value FROM notification_metric WHERE org_id=1 AND metric_key='outbox_retry_claims'",Long.class)).isEqualTo(retries+1);
    assertThat(db.queryForObject("SELECT COALESCE(SUM(total_value),0) FROM notification_metric WHERE org_id=1 AND metric_key='inbox_duplicate_hits'",Long.class)).isPositive();
    var result=metrics.snapshot();assertThat(((Map<?,?>)result.get("outbox")).get("latencySamples")).isNotEqualTo(0);
    assertThat(result.toString()).doesNotContain("content=","title=","user_id=");
  }
  @Test void informationalRecordsStaySilentAfterDigestIsDisabled(){
    enable();long id=tx.execute(s->pipeline.record(NotificationType.NEWS_UPLOADED,"静默资讯","测试","MATERIAL",1L,null,uid,null,null).getId());
    for(int i=0;i<3;i++)worker.tick();
    assertThat(db.queryForObject("SELECT silent_at_materialization FROM notification_inbox WHERE message_id=? AND user_id=?",Boolean.class,id,uid)).isTrue();
    policies.save(policy(false,false,1),owner);
    assertThat(service.findForUser(uid,null,null)).anyMatch(n->n.getId()==id && !n.isNotify());
    assertThat(service.countUnreadForUser(uid,null,null)).isPositive();
  }
  @Test void lateMaterializationAroundYesterdayCutoffIsNotLostToSchedulerJitter(){
    enable();db.update("UPDATE notification_org_policy SET digest_since=? WHERE org_id=1",now.minusDays(2));
    db.update("INSERT INTO notification_daily_digest(org_id,user_id,digest_day,last_inbox_id,item_count,created_at) VALUES(1,?,?,0,0,?)",uid,now.toLocalDate().minusDays(1),now.minusDays(1));
    long id=source(NotificationType.NEWS_UPLOADED);
    db.update("UPDATE notification_inbox SET created_at=? WHERE message_id=?",now.minusDays(1).minusSeconds(1),id);
    assertThat(digests.dispatch(uid,now)).isEqualTo(1);
  }
}
