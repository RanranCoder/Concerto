package com.pams.module.notification.service;

import com.pams.common.OrgContext;
import java.time.*;
import org.slf4j.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class NotificationReminderScheduler {
  private static final Logger log=LoggerFactory.getLogger(NotificationReminderScheduler.class);
  private final JdbcTemplate db;private final NotificationReminderService reminders;private final Environment environment;
  public NotificationReminderScheduler(JdbcTemplate db,NotificationReminderService reminders,Environment environment){this.db=db;this.reminders=reminders;this.environment=environment;}
  @Scheduled(fixedDelayString="${pams.notification.reminder-poll-ms:60000}",initialDelay=5000)
  public void scheduled(){if(!environment.matchesProfiles("test"))tick(LocalDateTime.now(PendingWorkService.ZONE));}
  public synchronized void tick(LocalDateTime now) {
    for(Long org:db.queryForList("SELECT id FROM organization WHERE status=1 AND deleted=0 ORDER BY id",Long.class)) {
      Long previous=OrgContext.get();OrgContext.set(org);
      try {
        for(String type:java.util.List.of("TASK","ARTICLE","SCHEDULE")) {
          String table=type.equals("TASK")?"task":type.equals("ARTICLE")?"article":"schedule";
          long cursor=0;
          while(true) {
            var ids=db.queryForList("SELECT id FROM "+table+" WHERE org_id=? AND id>? ORDER BY id LIMIT 200",Long.class,org,cursor);
            if(ids.isEmpty())break;
            for(Long id:ids){cursor=id;try{reminders.reconcile(type,id,now);}catch(Exception e){log.warn("提醒计划核对失败 org={} type={} id={}",org,type,id,e);}}
          }
        }
        for(Long uid:db.queryForList("SELECT DISTINCT assignee_user_id FROM task WHERE org_id=? AND deleted=0 AND assignee_user_id IS NOT NULL AND status<>'DONE' AND end_date<=?",Long.class,org,now.toLocalDate().minusDays(2)))
          reminders.reconcile("TASK_DIGEST",uid,now);
        // Recover deleted resources as well; source history is intentionally retained.
        for(var source:db.queryForList("SELECT resource_type,resource_id FROM notification_reminder_source WHERE org_id=? AND active=TRUE",org))
          reminders.reconcile((String)source.get("resource_type"),((Number)source.get("resource_id")).longValue(),now);
        long cursor=0;
        while(true) {
          var ids=db.queryForList("SELECT id FROM notification_reminder WHERE org_id=? AND status='PENDING' AND due_at<=? AND next_attempt_at<=? AND id>? ORDER BY id LIMIT 200",Long.class,org,now,now,cursor);
          if(ids.isEmpty())break;
          for(long id:ids){cursor=id;try{reminders.dispatch(id,now);}catch(Exception e){
            log.warn("提醒投递失败，保留补跑 org={} id={}",org,id,e);
            db.update("UPDATE notification_reminder SET status=CASE WHEN attempts>=5 THEN 'FAILED' ELSE 'PENDING' END, attempts=attempts+1,next_attempt_at=?,last_error=? WHERE org_id=? AND id=? AND status='PENDING'",now.plusMinutes(5),e.getClass().getSimpleName(),org,id);
          }}
        }
      } catch(Exception e){log.warn("组织提醒扫描失败 org={}",org,e);}
      finally{if(previous==null)OrgContext.clear();else OrgContext.set(previous);}
    }
  }
}
