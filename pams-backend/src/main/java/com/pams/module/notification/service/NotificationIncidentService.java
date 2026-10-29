package com.pams.module.notification.service;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Independent of notification delivery: an outbox outage must not hide maintenance incidents. */
@Service
public class NotificationIncidentService {
  public enum Category { KB_INDEX_FAILURE,KB_QUEUE_REJECTED,KB_DAILY_REBUILD_FAILURE }
  private final JdbcTemplate db;
  public NotificationIncidentService(JdbcTemplate db){this.db=db;}
  @Transactional(propagation=Propagation.REQUIRES_NEW)
  public void record(long org,Category category) {
    LocalDateTime now=LocalDateTime.now(PendingWorkService.ZONE);
    db.update("INSERT INTO notification_incident(org_id,category,window_date,occurrences,first_seen_at,last_seen_at) VALUES(?,?,?,1,?,?) ON DUPLICATE KEY UPDATE occurrences=occurrences+1,last_seen_at=?",org,category.name(),now.toLocalDate(),now,now,now);
  }
}
