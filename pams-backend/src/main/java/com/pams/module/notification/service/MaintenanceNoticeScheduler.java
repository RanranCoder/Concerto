package com.pams.module.notification.service;

import com.pams.common.OrgContext;
import java.time.LocalDateTime;
import org.slf4j.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MaintenanceNoticeScheduler {
  private static final Logger log=LoggerFactory.getLogger(MaintenanceNoticeScheduler.class);
  private final JdbcTemplate db;private final MaintenanceNoticeService notices;private final Environment environment;
  public MaintenanceNoticeScheduler(JdbcTemplate db,MaintenanceNoticeService notices,Environment environment){this.db=db;this.notices=notices;this.environment=environment;}
  @Scheduled(fixedDelayString="${pams.notification.maintenance-poll-ms:60000}",initialDelay=15000)
  public void scheduled(){if(!environment.matchesProfiles("test"))tick(LocalDateTime.now(PendingWorkService.ZONE));}
  public synchronized void tick(LocalDateTime now) {
    // Scan metadata only; each subscriber is processed in an explicitly scoped transaction.
    long orgCursor=0,userCursor=0;
    while(true) {
      var rows=db.queryForList("SELECT s.org_id,s.user_id FROM notification_maintenance_subscription s JOIN organization o ON o.id=s.org_id AND o.status=1 AND o.deleted=0 WHERE s.enabled=TRUE AND (s.org_id>? OR (s.org_id=? AND s.user_id>?)) ORDER BY s.org_id,s.user_id LIMIT 100",orgCursor,orgCursor,userCursor);
      if(rows.isEmpty())return;
      for(var row:rows) {
        long org=((Number)row.get("org_id")).longValue(),uid=((Number)row.get("user_id")).longValue();orgCursor=org;userCursor=uid;
        Long previous=OrgContext.get();OrgContext.set(org);
        try {notices.dispatch(uid,now);}catch(RuntimeException e){log.warn("维护摘要处理失败 org={} user={} error={}",org,uid,e.getClass().getSimpleName());}
        finally {if(previous==null)OrgContext.clear();else OrgContext.set(previous);}
      }
    }
  }
}
