package com.pams.module.notification.service;

import com.pams.common.BizException;
import com.pams.module.notification.entity.NotificationType;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationPolicyService {
  public record Policy(boolean quietEnabled,LocalTime quietStart,LocalTime quietEnd,boolean digestEnabled,
                       LocalTime digestTime,long version,LocalDateTime digestSince) {}
  public static final Set<NotificationType> INFORMATION=Set.of(NotificationType.NEWS_UPLOADED,NotificationType.ARTICLE_PUBLISHED,
      NotificationType.QUALITY_SUMMARY,NotificationType.PARTY_SUMMARY,NotificationType.IMPORT_RESULT,NotificationType.MAINTENANCE_ALERT);
  private final JdbcTemplate db;
  public NotificationPolicyService(JdbcTemplate db){this.db=db;}
  public Policy defaults(){return new Policy(false,LocalTime.of(22,0),LocalTime.of(8,0),false,LocalTime.of(9,0),0,LocalDateTime.now(PendingWorkService.ZONE));}
  public Policy get(){return get(false);}
  public Policy get(boolean lock) {
    var rows=db.queryForList("SELECT * FROM notification_org_policy WHERE org_id=?"+(lock?" FOR UPDATE":""),NotificationPipeline.org());
    if(rows.isEmpty())return defaults();var r=rows.getFirst();
    return new Policy(bool(r.get("quiet_enabled")),time(r.get("quiet_start")),time(r.get("quiet_end")),bool(r.get("digest_enabled")),time(r.get("digest_time")),((Number)r.get("version")).longValue(),com.pams.common.JdbcDateTimes.local(r.get("digest_since")));
  }
  static boolean bool(Object v){return v instanceof Boolean b?b:((Number)v).intValue()!=0;}
  static LocalTime time(Object v){return v instanceof LocalTime t?t:((java.sql.Time)v).toLocalTime();}
  public static boolean quiet(Policy policy,LocalDateTime at) {
    if(!policy.quietEnabled())return false;LocalTime t=at.toLocalTime(),start=policy.quietStart(),end=policy.quietEnd();
    return start.isBefore(end)?!t.isBefore(start) && t.isBefore(end):!t.isBefore(start) || t.isBefore(end);
  }
  public static boolean allowImmediate(Policy policy,NotificationType type,LocalDateTime created,LocalDateTime now) {
    if(!INFORMATION.contains(type) && type!=NotificationType.ORG_DIGEST)return true;
    if(quiet(policy,now) || quiet(policy,created))return false;
    // Informational records are replaced by a daily summary; actionable notifications stay immediate.
    return !policy.digestEnabled() || type==NotificationType.ORG_DIGEST;
  }
  @Transactional
  public Policy save(Policy request,long actor) {
    if(request==null || request.quietStart()==null || request.quietEnd()==null || request.digestTime()==null
        || request.quietStart().equals(request.quietEnd()))throw new BizException(400,"请设置有效的免打扰起止时间及摘要时间");
    for(LocalTime value:List.of(request.quietStart(),request.quietEnd(),request.digestTime()))if(value.getSecond()!=0 || value.getNano()!=0)throw new BizException(400,"通知策略时间精度为分钟");
    long org=NotificationPipeline.org();LocalDateTime now=LocalDateTime.now(PendingWorkService.ZONE);
    db.update("INSERT INTO notification_org_policy(org_id,quiet_start,quiet_end,digest_time,digest_since,updated_at) VALUES(?,'22:00:00','08:00:00','09:00:00',?,?) ON DUPLICATE KEY UPDATE org_id=?",org,now,now,org);
    Policy old=get(true);if(old.version()!=request.version())throw new BizException(409,"组织通知策略已变化，请刷新后重试");
    LocalDateTime since=request.digestEnabled() && !old.digestEnabled()?now:old.digestSince();
    db.update("UPDATE notification_org_policy SET quiet_enabled=?,quiet_start=?,quiet_end=?,digest_enabled=?,digest_time=?,digest_since=?,version=version+1,updated_by=?,updated_at=? WHERE org_id=?",
        request.quietEnabled(),request.quietStart(),request.quietEnd(),request.digestEnabled(),request.digestTime(),since,actor,now,org);
    db.update("INSERT INTO notification_policy_audit(org_id,actor_id,version,quiet_enabled,quiet_start,quiet_end,digest_enabled,digest_time,created_at) VALUES(?,?,?,?,?,?,?,?,?)",org,actor,old.version()+1,request.quietEnabled(),request.quietStart(),request.quietEnd(),request.digestEnabled(),request.digestTime(),now);
    return get();
  }
}
