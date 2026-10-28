package com.pams.module.notification;

import static org.assertj.core.api.Assertions.*;
import com.pams.common.*;
import com.pams.module.notification.entity.*;
import com.pams.module.notification.dto.NotificationPreferenceVO;
import com.pams.module.notification.service.*;
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
class NotificationServiceTest {
  @Autowired NotificationService service;
  @Autowired NotificationOutboxWorker worker;
  @Autowired JdbcTemplate db;
  @Autowired PlatformTransactionManager manager;
  @Autowired org.springframework.context.ApplicationEventPublisher events;
  TransactionTemplate tx;
  long teacher,staff;
  @BeforeEach void setup(){
    OrgContext.set(1L);tx=new TransactionTemplate(manager);
    teacher=db.queryForObject("SELECT id FROM sys_user WHERE username='teacher'",Long.class);
    staff=db.queryForObject("SELECT id FROM sys_user WHERE username='staff'",Long.class);
  }
  @AfterEach void cleanup(){OrgContext.clear();}
  Notification send(Long user,String role){return service.createAndSave(NotificationType.SYSTEM,"测试-"+UUID.randomUUID(),"test",null,null,null,user,role,null);}
  @Test void rollbackDoesNotLeakOutboxOrInbox(){
    long before=db.queryForObject("SELECT COUNT(*) FROM notification_outbox",Long.class);
    tx.executeWithoutResult(s->{send(teacher,null);s.setRollbackOnly();});
    worker.tick();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_outbox",Long.class)).isEqualTo(before);
  }
  @Test void longBusinessCommentsAreSummarizedWithoutRejectingTheTransaction(){
    var n=tx.execute(s->service.createAndSave(NotificationType.SYSTEM,"有效业务结果","审".repeat(1000),null,null,null,teacher,null,null));
    assertThat(n.getContent()).hasSize(500).endsWith("…");
    worker.tick();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE message_id=?",Long.class,n.getId())).isEqualTo(1);
  }
  @Test void committedEventRecoversAndRepeatedDeliveryIsIdempotent(){
    Notification n=tx.execute(s->send(teacher,null));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE message_id=?",Long.class,n.getId())).isZero();
    worker.tick();
    db.update("UPDATE notification_outbox SET status='PENDING',next_attempt_at=CURRENT_TIMESTAMP WHERE message_id=?",n.getId());
    worker.tick();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE message_id=?",Long.class,n.getId())).isEqualTo(1);
    assertThat(service.findForUser(teacher,null,null)).anyMatch(v->v.getId().equals(n.getId()));
  }
  @Test void independentReadsAndOwnershipAndTenantChecks(){
    Notification n=tx.execute(s->service.createAndSave(NotificationType.SYSTEM,"共同消息-"+UUID.randomUUID(),"test",null,null,null,teacher,"STAFF",null));
    worker.tick();
    assertThat(db.queryForList("SELECT user_id FROM notification_inbox WHERE message_id=?",Long.class,n.getId()))
      .as("outbox=%s targets=%s",db.queryForList("SELECT * FROM notification_outbox WHERE message_id=?",n.getId()),db.queryForList("SELECT * FROM notification_outbox_recipient"))
      .contains(teacher,staff);
    service.markAsRead(n.getId(),teacher);service.markAsRead(n.getId(),teacher);
    assertThat(db.queryForObject("SELECT read_at IS NULL FROM notification_inbox WHERE message_id=? AND user_id=?",Boolean.class,n.getId(),staff)).isTrue();
    assertThatThrownBy(()->service.markAsRead(n.getId(),-1L)).isInstanceOf(BizException.class);
    OrgContext.set(2L);
    assertThatThrownBy(()->service.markAsRead(n.getId(),teacher)).isInstanceOf(BizException.class);
    assertThat(service.countUnreadForUser(teacher,null,null)).isZero();
  }
  @Test void duplicateAudienceWithinOneTransactionAndPersistentPreference(){
    String title="去重-"+UUID.randomUUID();
    long id=tx.execute(s->{
      var first=service.createAndSave(NotificationType.SYSTEM,title,"test",null,null,null,teacher,null,null);
      var second=service.createAndSave(NotificationType.SYSTEM,title,"test",null,null,null,teacher,null,null);
      assertThat(second.getId()).isEqualTo(first.getId());return first.getId();
    });
    worker.tick();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE message_id=?",Long.class,id)).isEqualTo(1);
    service.savePreferences(teacher,List.of(NotificationPreferenceVO.of("ARTICLE_PUBLISHED",false,false)));
    assertThat(service.getPreferences(teacher)).anyMatch(p->p.getType().equals("ARTICLE_PUBLISHED") && !p.isEnabled());
    assertThat(db.queryForObject("SELECT enabled FROM notification_preference WHERE org_id=1 AND user_id=? AND event_type='ARTICLE_PUBLISHED'",Boolean.class,teacher)).isFalse();
  }
  @Test void selectedPlanReviewerReceivesCommittedSubmissionAndActionTracksBusinessState(){
    String name="审核链路-"+UUID.randomUUID();
    long plan=tx.execute(s->{
      db.update("INSERT INTO activity(name,status,org_id,deleted,created_at,updated_at) VALUES(?,'ASSIGNED',1,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",name);
      Long activity=db.queryForObject("SELECT id FROM activity WHERE name=?",Long.class,name);
      db.update("INSERT INTO activity_plan(activity_id,version,status,owner_id,submitter_id,org_id,revision,created_at,updated_at) VALUES(?,1,'PENDING',?,?,1,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",activity,staff,staff);
      Long id=db.queryForObject("SELECT id FROM activity_plan WHERE activity_id=?",Long.class,activity);
      db.update("INSERT INTO activity_plan_review(plan_id,reviewer_id,name) VALUES(?,?,?)",id,teacher,"审核人");
      events.publishEvent(new com.pams.module.notification.event.PlanSubmittedEvent(id,activity,name,staff,List.of(teacher)));
      return id;
    });
    worker.tick();
    var notice=service.findForUser(teacher,null,null).stream().filter(v->"PLAN_SUBMITTED".equals(v.getType()) && Objects.equals(v.getEntityId(),plan)).findFirst().orElseThrow();
    assertThat(notice.getActionState()).isEqualTo("open");
    service.markAsRead(notice.getId(),teacher);
    assertThat(service.findForUser(teacher,null,null).stream().filter(v->v.getId().equals(notice.getId())).findFirst().orElseThrow().getActionState()).isEqualTo("open");
    db.update("UPDATE activity_plan_review SET approved=TRUE WHERE plan_id=? AND reviewer_id=?",plan,teacher);
    assertThat(service.findForUser(teacher,null,null).stream().filter(v->v.getId().equals(notice.getId())).findFirst().orElseThrow().getActionState()).isEqualTo("completed");
    assertThat(service.findForUser(teacher,null,null).stream().filter(v->v.getId().equals(notice.getId())).findFirst().orElseThrow().isNotify()).isFalse();
  }
}
