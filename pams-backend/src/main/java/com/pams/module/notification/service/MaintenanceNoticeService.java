package com.pams.module.notification.service;

import com.pams.common.BizException;
import com.pams.module.notification.entity.NotificationType;
import com.pams.module.permission.service.TenantAuthorizationService;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaintenanceNoticeService {
  public record Subscription(boolean enabled,LocalDateTime enabledAt) {}
  private final JdbcTemplate db;private final TenantAuthorizationService auth;private final NotificationPipeline pipeline;
  public MaintenanceNoticeService(JdbcTemplate db,TenantAuthorizationService auth,NotificationPipeline pipeline){this.db=db;this.auth=auth;this.pipeline=pipeline;}
  private boolean eligible(long org,long uid) {
    return auth.valid(uid) && ((Number)auth.identity(uid).get("org_id")).longValue()==org && auth.capabilities(uid).contains("org:audit:view");
  }
  private void authorize(long uid){if(!eligible(NotificationPipeline.org(),uid))throw new BizException(403,"需要当前组织审计权限");}
  @Transactional(readOnly=true)
  public Subscription subscription(long uid) {
    authorize(uid);
    var rows=db.queryForList("SELECT enabled,enabled_at FROM notification_maintenance_subscription WHERE org_id=? AND user_id=?",NotificationPipeline.org(),uid);
    if(rows.isEmpty())return new Subscription(false,null);
    Object enabled=rows.getFirst().get("enabled");
    return new Subscription(enabled instanceof Boolean b?b:((Number)enabled).intValue()!=0,((java.sql.Timestamp)rows.getFirst().get("enabled_at")).toLocalDateTime());
  }
  @Transactional
  public Subscription subscribe(long uid,boolean enabled) {
    authorize(uid);long org=NotificationPipeline.org();LocalDateTime now=LocalDateTime.now(PendingWorkService.ZONE);
    // An already enabled subscription keeps its original boundary, including on repeated requests.
    db.update("INSERT INTO notification_maintenance_subscription(org_id,user_id,enabled,enabled_at,updated_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE enabled_at=CASE WHEN enabled=FALSE AND ?=TRUE THEN ? ELSE enabled_at END,enabled=?,updated_at=?",org,uid,enabled,now,now,enabled,now,enabled,now);
    return subscription(uid);
  }
  @Transactional
  public int dispatch(long uid,LocalDateTime now) {
    long org=NotificationPipeline.org();
    var subscription=db.queryForList("SELECT enabled_at FROM notification_maintenance_subscription WHERE org_id=? AND user_id=? AND enabled=TRUE FOR UPDATE",org,uid);
    if(subscription.isEmpty() || !eligible(org,uid))return 0;
    var rows=db.queryForList("SELECT i.* FROM notification_incident i WHERE i.org_id=? AND i.last_seen_at>=? AND i.window_date>=? AND NOT EXISTS(SELECT 1 FROM notification_incident_delivery d WHERE d.org_id=i.org_id AND d.incident_id=i.id AND d.user_id=?) ORDER BY i.id LIMIT 100",org,subscription.getFirst().get("enabled_at"),now.toLocalDate().minusDays(1),uid);
    int count=0;
    for(var row:rows) {
      long id=((Number)row.get("id")).longValue();
      String category=Objects.toString(row.get("category"),"");
      String label=switch(category){case "KB_INDEX_FAILURE"->"知识库增量索引失败";case "KB_QUEUE_REJECTED"->"知识库索引队列繁忙";case "KB_DAILY_REBUILD_FAILURE"->"知识库每日重建失败";default->null;};
      if(label==null)continue;
      var message=pipeline.record(NotificationType.MAINTENANCE_ALERT,"维护故障摘要",label+"："+row.get("window_date")+" 已记录 "+row.get("occurrences")+" 次。请前往管线运维核对；后续次数在台账中更新。","MAINTENANCE",id,null,uid,null,null);
      db.update("INSERT INTO notification_incident_delivery(org_id,incident_id,user_id,message_id,created_at) VALUES(?,?,?,?,?)",org,id,uid,message.getId(),now);
      count++;
    }
    return count;
  }
}
