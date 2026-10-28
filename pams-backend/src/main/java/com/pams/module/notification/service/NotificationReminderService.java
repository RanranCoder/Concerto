package com.pams.module.notification.service;

import com.pams.module.notification.entity.NotificationType;
import com.pams.module.permission.service.TenantAuthorizationService;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Source generations prevent completion/reassignment/cancellation from reviving stale reminders. */
@Service
public class NotificationReminderService {
  private record Source(String type,long id,Long activity,String title,String fingerprint,boolean active,
                        LocalDateTime deadline,List<Long> users) {}
  private final JdbcTemplate db;
  private final EntityManager entities;
  private final NotificationService notifications;
  private final TenantAuthorizationService auth;
  public NotificationReminderService(JdbcTemplate db,EntityManager entities,NotificationService notifications,TenantAuthorizationService auth) {
    this.db=db;this.entities=entities;this.notifications=notifications;this.auth=auth;
  }
  @Transactional
  public void reconcile(String type,long id,LocalDateTime now) {
    entities.flush();
    long org=NotificationPipeline.org();Source source=source(type,id,now);
    db.update("INSERT INTO notification_reminder_source(org_id,resource_type,resource_id,fingerprint,generation,active,updated_at) "
      +"VALUES(?,?,?,'',0,FALSE,?) ON DUPLICATE KEY UPDATE resource_id=?",org,type,id,now,id);
    var state=db.queryForMap("SELECT * FROM notification_reminder_source WHERE org_id=? AND resource_type=? AND resource_id=? FOR UPDATE",org,type,id);
    String fingerprint=source==null?"missing":source.fingerprint();boolean active=source!=null && source.active();
    long generation=((Number)state.get("generation")).longValue();
    Object oldActive=state.get("active");boolean wasActive=oldActive instanceof Boolean b?b:((Number)oldActive).intValue()!=0;
    if(!Objects.equals(fingerprint,state.get("fingerprint")) || active!=wasActive)generation++;
    db.update("UPDATE notification_reminder_source SET fingerprint=?,generation=?,active=?,updated_at=? WHERE org_id=? AND resource_type=? AND resource_id=?",
      fingerprint,generation,active,now,org,type,id);
    db.update("UPDATE notification_reminder SET status='CANCELLED',updated_at=? WHERE org_id=? AND resource_type=? AND resource_id=? AND status='PENDING' AND (generation<>? OR ?=FALSE)",now,org,type,id,generation,active);
    if(!active)return;
    for(long user:source.users()) {
      switch(type) {
        case "TASK" -> {
          LocalDate due=source.deadline().toLocalDate();
          ensure(source,generation,user,"TASK_DUE",due.minusDays(1).atTime(9,0),due.plusDays(1).atStartOfDay(),null,now);
          ensure(source,generation,user,"TASK_OVERDUE",due.plusDays(1).atTime(9,0),null,null,now);
        }
        case "ARTICLE" -> {
          ensure(source,generation,user,"ARTICLE_DUE",source.deadline().minusDays(3).withHour(8).withMinute(30).withSecond(0).withNano(0),source.deadline(),null,now);
          ensure(source,generation,user,"ARTICLE_OVERDUE",source.deadline(),null,null,now);
        }
        case "SCHEDULE" -> ensure(source,generation,user,"SCHEDULE_START",source.deadline().minusMinutes(30),source.deadline(),null,now);
        case "TASK_DIGEST" -> ensure(source,generation,user,"TASK_DIGEST",now.toLocalDate().atTime(9,0),now.toLocalDate().plusDays(1).atStartOfDay(),now.toLocalDate().toString(),now);
        default -> throw new IllegalArgumentException("unsupported reminder source");
      }
    }
  }
  private void ensure(Source source,long generation,long user,String rule,LocalDateTime due,LocalDateTime expires,String window,LocalDateTime now) {
    db.update("INSERT INTO notification_reminder(org_id,resource_type,resource_id,generation,user_id,rule_key,window_key,due_at,expires_at,status,next_attempt_at,created_at,updated_at) "
      +"VALUES(?,?,?,?,?,?,?,?,?,'PENDING',?,?,?) ON DUPLICATE KEY UPDATE resource_id=?",
      NotificationPipeline.org(),source.type(),source.id(),generation,user,rule,window==null?Long.toString(generation):window,due,expires,now,now,now,source.id());
  }
  @Transactional
  public void dispatch(long id,LocalDateTime now) {
    long org=NotificationPipeline.org();
    // Lock order is source then reminder, the same order used by reconciliation.
    var meta=db.queryForList("SELECT resource_type,resource_id FROM notification_reminder WHERE org_id=? AND id=?",org,id);
    if(meta.isEmpty())return;
    String type=(String)meta.getFirst().get("resource_type");long resource=((Number)meta.getFirst().get("resource_id")).longValue();
    var state=db.queryForMap("SELECT * FROM notification_reminder_source WHERE org_id=? AND resource_type=? AND resource_id=? FOR UPDATE",org,type,resource);
    var rows=db.queryForList("SELECT * FROM notification_reminder WHERE org_id=? AND id=? AND status='PENDING' AND due_at<=? FOR UPDATE",org,id,now);
    if(rows.isEmpty())return;
    var row=rows.getFirst();long uid=((Number)row.get("user_id")).longValue();
    Source source=source(type,resource,now);
    LocalDateTime expires=time(row.get("expires_at"));
    boolean current=source!=null && source.active() && Objects.equals(source.fingerprint(),state.get("fingerprint"))
      && ((Number)state.get("generation")).longValue()==((Number)row.get("generation")).longValue() && source.users().contains(uid);
    if(!current || (expires!=null && !now.isBefore(expires))) {finish(id,"CANCELLED",null,now);return;}
    if(!auth.valid(uid) || ((Number)auth.identity(uid).get("org_id")).longValue()!=org || !permitted(uid,type)) {finish(id,"SKIPPED",null,now);return;}
    String rule=(String)row.get("rule_key");
    NotificationType noticeType=switch(rule) {
      case "TASK_DUE"->NotificationType.TASK_DUE_REMINDER;
      case "TASK_OVERDUE"->NotificationType.TASK_OVERDUE_REMINDER;
      case "TASK_DIGEST"->NotificationType.TASK_OVERDUE_DIGEST;
      case "SCHEDULE_START"->NotificationType.SCHEDULE_START_REMINDER;
      default->NotificationType.ARTICLE_DEADLINE_REMINDER;
    };
    String title=switch(rule){case "TASK_DUE"->"任务即将到期";case "TASK_OVERDUE"->"任务已逾期";case "TASK_DIGEST"->"我的逾期任务摘要";case "SCHEDULE_START"->"活动排班即将开始";default->"推文截止提醒";};
    String content=rule.equals("TASK_DIGEST")?"仍有 "+source.title()+" 项个人任务逾期，请查看待处理列表":
      "「"+source.title()+"」"+(rule.equals("SCHEDULE_START")?"将于 ":"截止 ")+source.deadline()+"，请查看当前安排";
    var message=notifications.createAndSave(noticeType,title,content,type.equals("TASK_DIGEST")?"PENDING_TASKS":type,resource,null,uid,null,null);
    finish(id,"SENT",message.getId(),now);
  }
  private boolean permitted(long uid,String type) {
    var permissions=auth.business(uid);
    return switch(type){case "TASK","TASK_DIGEST"->permissions.contains("task:view");case "ARTICLE"->permissions.contains("content:view") && (permissions.contains("article:edit") || permissions.contains("article:submit"));case "SCHEDULE"->permissions.contains("schedule:view") || permissions.contains("schedule:self:view");default->false;};
  }
  private void finish(long id,String status,Long message,LocalDateTime now) {
    db.update("UPDATE notification_reminder SET status=?,message_id=?,updated_at=?,last_error=NULL WHERE org_id=? AND id=?",status,message,now,NotificationPipeline.org(),id);
  }
  private Source source(String type,long id,LocalDateTime now) {
    long org=NotificationPipeline.org();
    if(type.equals("TASK_DIGEST")) {
      long count=db.queryForObject("SELECT COUNT(*) FROM task t JOIN activity a ON a.id=t.activity_id AND a.org_id=t.org_id "
        +"WHERE t.org_id=? AND t.assignee_user_id=? AND t.deleted=0 AND a.deleted=0 AND a.status<>'ARCHIVED' AND t.status<>'DONE' "
        +"AND COALESCE(t.progress,0)<100 AND t.end_date<=? AND EXISTS(SELECT 1 FROM notification_reminder rr "
        +"JOIN notification_reminder_source src ON src.org_id=rr.org_id AND src.resource_type=rr.resource_type AND src.resource_id=rr.resource_id "
        +"WHERE rr.org_id=t.org_id AND rr.resource_type='TASK' AND rr.resource_id=t.id AND rr.user_id=t.assignee_user_id "
        +"AND rr.rule_key='TASK_OVERDUE' AND rr.status='SENT' AND rr.generation=src.generation AND rr.updated_at<?)",Long.class,org,id,now.toLocalDate().minusDays(2),now.toLocalDate().atStartOfDay());
      boolean weekday=now.getDayOfWeek()!=DayOfWeek.SATURDAY && now.getDayOfWeek()!=DayOfWeek.SUNDAY;
      return new Source(type,id,null,Long.toString(count),hash(now.toLocalDate().toString()),count>0 && weekday,now.toLocalDate().atTime(9,0),List.of(id));
    }
    String table=switch(type){case "TASK"->"task";case "ARTICLE"->"article";case "SCHEDULE"->"schedule";default->throw new IllegalArgumentException("unsupported source");};
    var rows=db.queryForList("SELECT * FROM "+table+" WHERE id=? AND org_id=?",id,org);
    if(rows.isEmpty())return null;
    var r=rows.getFirst();Long activity=r.get("activity_id")==null?null:((Number)r.get("activity_id")).longValue();
    boolean activityActive=activity==null || db.queryForObject("SELECT COUNT(*) FROM activity WHERE id=? AND org_id=? AND deleted=0 AND status<>'ARCHIVED'",Long.class,activity,org)>0;
    if(type.equals("TASK")) {
      Long uid=r.get("assignee_user_id")==null?null:((Number)r.get("assignee_user_id")).longValue();
      LocalDate date=date(r.get("end_date"));boolean active=activityActive && ((Number)r.get("deleted")).intValue()==0 && !"DONE".equals(r.get("status")) && ((Number)Objects.requireNonNullElse(r.get("progress"),0)).intValue()<100 && uid!=null && date!=null;
      return new Source(type,id,activity,(String)r.get("name"),hash(uid+"|"+date),active,date==null?null:date.atTime(23,59,59),uid==null?List.of():List.of(uid));
    }
    if(type.equals("ARTICLE")) {
      Long uid=r.get("author_id")==null?null:((Number)r.get("author_id")).longValue();LocalDateTime deadline=time(r.get("deadline"));
      boolean active=activityActive && ((Number)r.get("deleted")).intValue()==0 && Set.of("DRAFT","REJECTED").contains(r.get("status")) && deadline!=null && uid!=null;
      return new Source(type,id,activity,(String)r.get("title"),hash(uid+"|"+deadline),active,deadline,uid==null?List.of():List.of(uid));
    }
    List<Long> users=db.queryForList("SELECT DISTINCT user_id FROM schedule_person WHERE schedule_id=? AND org_id=? AND user_id IS NOT NULL ORDER BY user_id",Long.class,id,org);
    LocalDate day=date(r.get("schedule_date"));LocalTime start=clock(r.get("start_time"));
    LocalDateTime begins=day==null || start==null?null:day.atTime(start);
    boolean active=activityActive && "ACTIVITY".equals(r.get("schedule_type")) && "PUBLISHED".equals(r.get("status")) && begins!=null && !users.isEmpty();
    return new Source(type,id,activity,"排班 · "+Objects.toString(r.get("location"),"地点待定"),hash(begins+"|"+r.get("end_time")+"|"+r.get("location")+"|"+users),active,begins,users);
  }
  private static String hash(String text) {
    try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
  }
  static LocalDate date(Object v){return v==null?null:v instanceof LocalDate d?d:((java.sql.Date)v).toLocalDate();}
  static LocalTime clock(Object v){return v==null?null:v instanceof LocalTime t?t:((java.sql.Time)v).toLocalTime();}
  static LocalDateTime time(Object v){return v==null?null:v instanceof LocalDateTime t?t:((java.sql.Timestamp)v).toLocalDateTime();}
}
