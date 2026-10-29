package com.pams.module.notification;

import static org.assertj.core.api.Assertions.*;
import com.pams.common.OrgContext;
import com.pams.module.archive.dto.AnnouncementRequest;
import com.pams.module.archive.service.*;
import com.pams.module.notification.controller.NotificationOperationsController;
import com.pams.module.notification.event.GovernanceNoticeEvent;
import com.pams.module.notification.listener.GovernanceNoticeListener;
import com.pams.module.notification.repository.NotificationRepository;
import com.pams.module.notification.service.*;
import com.pams.module.permission.service.TenantAuthorizationService;
import com.pams.module.agent.core.ToolContext;
import com.pams.security.LoginUser;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NotificationStageThreeTest {
  @Autowired JdbcTemplate db;
  @Autowired AnnouncementService announcements;
  @Autowired AnnouncementDeliveryService delivery;
  @Autowired PendingWorkService pending;
  @Autowired GovernanceNoticeListener governance;
  @Autowired NotificationVisibility visibility;
  @Autowired NotificationRepository messages;
  @Autowired NotificationOperationsController operations;
  @Autowired TenantAuthorizationService auth;
  @Autowired NotificationService notifications;
  long owner,staff;String title;
  @BeforeEach void setup() {
    OrgContext.set(1L);title="第三阶段-"+UUID.randomUUID();
    owner=db.queryForObject("SELECT id FROM sys_user WHERE username='teacher'",Long.class);
    staff=db.queryForObject("SELECT id FROM sys_user WHERE username='staff'",Long.class);
  }
  @AfterEach void clear(){OrgContext.clear();}
  AnnouncementRequest request(boolean confirmation) {
    var r=new AnnouncementRequest();r.setTitle(title);r.setContent("公告正文");r.setAudienceAll(false);r.setAudienceUserIds(List.of(staff));r.setRequireConfirmation(confirmation);return r;
  }
  LoginUser principal(long uid){return new LoginUser(uid,"u"+uid,"测试","STAFF",1,null,null,1L);}
  ToolContext context(long uid){return new ToolContext(principal(uid),auth.business(uid).stream().map(SimpleGrantedAuthority::new).toList(),null,null);}
  long pendingCount(long id){return pending.summary(context(staff)).items().stream().filter(i->i.entityType().equals("ANNOUNCEMENT") && i.entityId()==id).count();}
  @Test void selectionIsFrozenAndReadDoesNotConfirm() {
    var a=announcements.create(owner,request(true));
    assertThat(db.queryForList("SELECT r.user_id FROM notification_outbox_recipient r JOIN notification_outbox o ON o.id=r.outbox_id JOIN notification n ON n.id=o.message_id WHERE n.entity_type='ANNOUNCEMENT' AND n.entity_id=?",Long.class,a.getId())).containsExactly(staff);
    assertThat(pendingCount(a.getId())).isEqualTo(1);
    announcements.markRead(a.getId(),staff);
    assertThat(delivery.receipts(a.getId(),staff).getFirst().get("confirmed_at")).isNull();
    assertThat(pendingCount(a.getId())).isEqualTo(1);
    delivery.confirm(a.getId(),1,staff);delivery.confirm(a.getId(),1,staff);
    assertThat(pendingCount(a.getId())).isZero();
    assertThat(delivery.receipts(a.getId(),staff).getFirst().get("confirmed_at")).isNotNull();
  }
  @Test void importantCorrectionRequiresNewVersionAndRejectsStaleConfirmation() {
    var req=request(true);var a=announcements.create(owner,req);
    delivery.confirm(a.getId(),1,staff);
    req.setContent("更正正文");
    assertThatThrownBy(()->announcements.update(a.getId(),req)).hasMessageContaining("重要更正");
    req.setImportantCorrection(true);announcements.update(a.getId(),req);
    assertThatThrownBy(()->delivery.confirm(a.getId(),1,staff)).hasMessageContaining("已变更");
    assertThat(pendingCount(a.getId())).isEqualTo(1);
    delivery.confirm(a.getId(),2,staff);assertThat(pendingCount(a.getId())).isZero();
  }
  @Test void nonRecipientsCannotConfirmAndDeletionClosesWork() {
    var a=announcements.create(owner,request(true));
    assertThatThrownBy(()->delivery.confirm(a.getId(),1,owner)).hasMessageContaining("受众");
    announcements.delete(a.getId());db.update("UPDATE announcement SET deleted=1 WHERE id=?",a.getId());
    assertThat(pendingCount(a.getId())).isZero();
    assertThatThrownBy(()->delivery.confirm(a.getId(),1,staff)).hasMessageContaining("已变更");
  }
  @Test void missingAudienceAndForeignRecipientsAreRejected() {
    var req=request(false);req.setAudienceUserIds(List.of());
    assertThatThrownBy(()->announcements.create(owner,req)).hasMessageContaining("受众");
    req.setAudienceUserIds(List.of(Long.MAX_VALUE));
    assertThatThrownBy(()->announcements.create(owner,req)).hasMessageContaining("无效账号");
  }
  @Test void personalGovernanceNoticeDoesNotNeedBusinessPermissionAndCannotBeDisabled() {
    db.update("DELETE FROM role_grant WHERE org_id=1 AND role_id=(SELECT role_id FROM sys_user WHERE id=?)",staff);
    assertThat(auth.business(staff)).doesNotContain("activity:view");
    governance.changed(new GovernanceNoticeEvent(1,123456,owner,"ASSIGN",staff));
    long message=db.queryForObject("SELECT MAX(id) FROM notification WHERE entity_type='GOVERNANCE' AND title='你的授权配置已更新'",Long.class);
    assertThat(visibility.canRead(staff,messages.findById(message).orElseThrow())).isTrue();
    assertThat(visibility.canRead(owner,messages.findById(message).orElseThrow())).isFalse();
    assertThat(notifications.getPreferences(staff)).anyMatch(p->p.getType().equals("GOVERNANCE_CHANGED") && p.isEnabled() && p.isSystem());
  }
  @Test void operationsAreTenantScopedAndDoNotExposeContent() {
    assertThatThrownBy(()->operations.summary(principal(staff))).hasMessageContaining("审计");
    var result=operations.summary(principal(owner));
    assertThat(result).isNotNull();
    assertThat(result.getData().keySet()).containsExactlyInAnyOrder("notification_outbox","notification_delivery","notification_reminder");
    assertThat(result.getData().toString()).doesNotContain("content=","title=","user_id=");
    assertThatThrownBy(()->operations.retry("unexpected",1,principal(owner))).hasMessageContaining("类型");
    OrgContext.set(2L);
    assertThatThrownBy(()->operations.summary(principal(owner))).hasMessageContaining("审计");
    assertThatThrownBy(()->delivery.confirm(1,1,staff)).hasMessageContaining("访问权限");
  }
  @Test void onlyFailedRecordsCanBeRetried() {
    var a=announcements.create(owner,request(false));
    long id=db.queryForObject("SELECT o.id FROM notification_outbox o JOIN notification n ON n.id=o.message_id WHERE n.entity_id=? AND n.entity_type='ANNOUNCEMENT'",Long.class,a.getId());
    assertThatThrownBy(()->operations.retry("outbox",id,principal(owner))).hasMessageContaining("无需重试");
    db.update("UPDATE notification_outbox SET status='FAILED',attempts=6 WHERE id=?",id);
    operations.retry("outbox",id,principal(owner));
    assertThat(db.queryForObject("SELECT status FROM notification_outbox WHERE id=?",String.class,id)).isEqualTo("PENDING");
    assertThat(db.queryForObject("SELECT attempts FROM notification_outbox WHERE id=?",Integer.class,id)).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_operation_audit WHERE org_id=1 AND actor_id=? AND pipeline_kind='outbox' AND record_id=? AND previous_attempts=6",Long.class,owner,id)).isEqualTo(1);
  }
  @Test void ordinaryAnnouncementIsSilentAndConfirmationRemainsAnExplicitAction() {
    var a=announcements.create(owner,request(false));
    long id=db.queryForObject("SELECT id FROM notification WHERE entity_type='ANNOUNCEMENT' AND entity_id=?",Long.class,a.getId());
    db.update("INSERT INTO notification_inbox(org_id,message_id,user_id,created_at) VALUES(1,?,?,CURRENT_TIMESTAMP)",id,staff);
    assertThat(notifications.findForUser(staff,null,null)).anyMatch(n->n.getId()==id && !n.isNotify());
    var important=announcements.create(owner,request(true));
    long next=db.queryForObject("SELECT id FROM notification WHERE entity_type='ANNOUNCEMENT' AND entity_id=?",Long.class,important.getId());
    db.update("INSERT INTO notification_inbox(org_id,message_id,user_id,created_at) VALUES(1,?,?,CURRENT_TIMESTAMP)",next,staff);
    assertThat(notifications.findForUser(staff,null,null)).anyMatch(n->n.getId()==next && n.isNotify());
    assertThat(pendingCount(important.getId())).isEqualTo(1);
    notifications.markAsRead(next,staff);
    assertThat(pendingCount(important.getId())).isEqualTo(1);
  }
}
