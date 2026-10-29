package com.pams.module.notification.service;

import com.pams.common.OrgContext;
import java.time.LocalDateTime;
import org.slf4j.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class NotificationDigestScheduler {
  private static final Logger log=LoggerFactory.getLogger(NotificationDigestScheduler.class);
  private final JdbcTemplate db;private final NotificationDigestService digests;private final Environment environment;
  public NotificationDigestScheduler(JdbcTemplate db,NotificationDigestService digests,Environment environment){this.db=db;this.digests=digests;this.environment=environment;}
  @Scheduled(fixedDelayString="${pams.notification.digest-poll-ms:60000}",initialDelay=20000)
  public void scheduled(){if(!environment.matchesProfiles("test"))tick(LocalDateTime.now(PendingWorkService.ZONE));}
  public synchronized void tick(LocalDateTime now) {
    for(long org:db.queryForList("SELECT p.org_id FROM notification_org_policy p JOIN organization o ON o.id=p.org_id WHERE p.digest_enabled=TRUE AND o.status=1 AND o.deleted=0",Long.class)) {
      Long old=OrgContext.get();OrgContext.set(org);
      try {long cursor=0;while(true){var users=db.queryForList("SELECT id FROM sys_user WHERE org_id=? AND status=1 AND deleted=0 AND id>? ORDER BY id LIMIT 100",Long.class,org,cursor);if(users.isEmpty())break;
        for(long uid:users){cursor=uid;try{digests.dispatch(uid,now);}catch(RuntimeException e){log.warn("每日资讯摘要失败 org={} user={} error={}",org,uid,e.getClass().getSimpleName());}}
      }}finally{if(old==null)OrgContext.clear();else OrgContext.set(old);}
    }
  }
}
