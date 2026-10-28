package com.pams.module.notification.listener;
import com.pams.module.notification.event.*;
import com.pams.module.notification.service.*;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component
public class ReminderBusinessListener {
  private final NotificationReminderService reminders;
  private final org.springframework.jdbc.core.JdbcTemplate db;
  private final com.pams.module.permission.service.TenantAuthorizationService auth;
  private final NotificationPipeline pipeline;
  public ReminderBusinessListener(NotificationReminderService reminders,org.springframework.jdbc.core.JdbcTemplate db,
      com.pams.module.permission.service.TenantAuthorizationService auth,NotificationPipeline pipeline){this.reminders=reminders;this.db=db;this.auth=auth;this.pipeline=pipeline;}
  private void changed(long id){
    reminders.reconcile("ARTICLE",id,LocalDateTime.now(PendingWorkService.ZONE));
    long org=NotificationPipeline.org();
    java.util.Set<Long> affected=new java.util.HashSet<>();
    for(Long uid:db.queryForList("SELECT author_id FROM article WHERE org_id=? AND id=?",Long.class,org,id))if(uid!=null)affected.add(uid);
    for(Long uid:db.queryForList("SELECT id FROM sys_user WHERE org_id=? AND status=1 AND deleted=0",Long.class,org)) {
      var permissions=auth.business(uid);
      if(permissions.contains("content:view") && (permissions.contains("article:review") || (permissions.contains("article:publish") && permissions.contains("article:manage"))))affected.add(uid);
    }
    affected.forEach(pipeline::invalidate);
  }
  @TransactionalEventListener(phase=TransactionPhase.BEFORE_COMMIT)
  public void assigned(ArticleAssignedEvent e){changed(e.getArticleId());}
  @TransactionalEventListener(phase=TransactionPhase.BEFORE_COMMIT)
  public void submitted(ArticleSubmittedEvent e){changed(e.getArticleId());}
  @TransactionalEventListener(phase=TransactionPhase.BEFORE_COMMIT)
  public void reviewed(ArticleReviewedEvent e){changed(e.getArticleId());}
  @TransactionalEventListener(phase=TransactionPhase.BEFORE_COMMIT)
  public void published(ArticlePublishedEvent e){changed(e.getArticleId());}
}
