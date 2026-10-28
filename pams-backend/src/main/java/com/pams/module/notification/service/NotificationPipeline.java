package com.pams.module.notification.service;

import com.pams.common.*;
import com.pams.module.notification.entity.*;
import com.pams.module.notification.repository.NotificationRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

/** Records immutable notification facts and a frozen audience in the business transaction. */
@Service
public class NotificationPipeline {
  private final JdbcTemplate db;
  private final NotificationRepository messages;
  private static final Object TX_KEY = new Object();
  public NotificationPipeline(JdbcTemplate db, NotificationRepository messages) {
    this.db=db; this.messages=messages;
  }
  public static long org() {
    Long org=OrgContext.get();
    if(org==null) throw new IllegalStateException("通知操作缺少组织上下文");
    return org;
  }
  private String key(NotificationType type,String title,String content,String entity,Long id,Long sender) {
    if(!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("通知必须在事务内记录");
    String tx=(String)TransactionSynchronizationManager.getResource(TX_KEY);
    if(tx==null) {
      tx=UUID.randomUUID().toString(); TransactionSynchronizationManager.bindResource(TX_KEY,tx);
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCompletion(int status) { TransactionSynchronizationManager.unbindResourceIfPossible(TX_KEY); }
      });
    }
    try {
      var bytes=MessageDigest.getInstance("SHA-256").digest(
        (type+"|"+title+"|"+content+"|"+entity+"|"+id+"|"+sender).getBytes(StandardCharsets.UTF_8));
      return tx+":"+HexFormat.of().formatHex(bytes).substring(0,32);
    } catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
  }
  @Transactional
  public Notification record(NotificationType type,String title,String content,String entity,Long entityId,
                             Long sender,Long uid,String role,Long dept) {
    long org=org();
    if(title==null || title.isBlank() || content==null)
      throw new BizException(2051,"通知标题或正文无效");
    // Notifications contain summaries. A valid long review comment must never roll back its business transaction.
    title=summary(title,100);content=summary(content,500);
    if(uid==null && (role==null || role.isBlank()) && dept==null)
      throw new BizException(2051,"必须指定通知接收范围");
    var audience=db.queryForList("SELECT u.id FROM sys_user u LEFT JOIN sys_role r ON r.id=u.role_id "
      +"WHERE u.org_id=? AND u.deleted=0 AND u.status=1 AND (u.id=? OR r.code=? OR u.dept_id=?)",
      Long.class,org,uid,role,dept);
    String eventKey=key(type,title,content,entity,entityId,sender);
    var existing=db.queryForList("SELECT id FROM notification WHERE org_id=? AND event_key=?",Long.class,org,eventKey);
    Notification n;
    if(existing.isEmpty()) {
      n=new Notification(); n.setOrgId(org);n.setType(type);n.setTitle(title);n.setContent(content);
      n.setEntityType(entity==null?null:entity.toUpperCase(Locale.ROOT));n.setEntityId(entityId);
      n.setSenderId(sender);n.setEventKey(eventKey);n.setCreatedAt(LocalDateTime.now());n.setIsRead(0);n.setDeleted(0);
      n.setActivityId(activityId(n.getEntityType(),entityId));
      n=messages.saveAndFlush(n);
      db.update("INSERT INTO notification_outbox(org_id,message_id,status,next_attempt_at,created_at) VALUES(?,?,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",org,n.getId());
    } else n=messages.findById(existing.getFirst()).orElseThrow();
    Long outbox=db.queryForObject("SELECT id FROM notification_outbox WHERE org_id=? AND message_id=?",Long.class,org,n.getId());
    for(Long user:audience) db.update("INSERT INTO notification_outbox_recipient(outbox_id,user_id) VALUES(?,?) ON DUPLICATE KEY UPDATE user_id=?",outbox,user,user);
    return n;
  }
  private Long activityId(String type,Long id) {
    if(type==null || id==null) return null;
    if(type.equals("ACTIVITY") || type.equals("SIGNIN")) return id;
    String table=switch(type) {case "TASK"->"task";case "PLAN"->"activity_plan";case "ARTICLE"->"article";case "SCHEDULE"->"schedule";default->null;};
    if(table==null) return null;
    // Table names are constants; resource lookup remains explicitly tenant scoped.
    var rows=db.queryForList("SELECT activity_id FROM "+table+" WHERE id=? AND org_id=?",Long.class,id,org());
    return rows.isEmpty()?null:rows.getFirst();
  }
  private static String summary(String text,int limit) {
    if(text.length()<=limit)return text;
    int end=limit-1;if(Character.isHighSurrogate(text.charAt(end-1)))end--;
    return text.substring(0,end)+"…";
  }
  public void invalidate(long user) {
    db.update("INSERT INTO notification_delivery(org_id,message_id,user_id,status,next_attempt_at,created_at) "
      +"VALUES(?,0,?,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE status='PENDING',next_attempt_at=CURRENT_TIMESTAMP,lease_token=NULL,lease_until=NULL",org(),user);
  }
}
