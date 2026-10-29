package com.pams.module.notification.service;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class NotificationMetricsService {
  private final JdbcTemplate db;private final NotificationStreamRegistry streams;
  public NotificationMetricsService(JdbcTemplate db,NotificationStreamRegistry streams){this.db=db;this.streams=streams;}
  public Map<String,Object> snapshot() {
    long org=NotificationPipeline.org();var now=LocalDateTime.now(PendingWorkService.ZONE);Map<String,Object> result=new LinkedHashMap<>();
    for(String kind:List.of("outbox","delivery","reminder")) {
      String table="notification_"+kind;var metric=new LinkedHashMap<String,Object>();
      String due=kind.equals("reminder")?" AND due_at<=CURRENT_TIMESTAMP":"";
      metric.put("pending",db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE org_id=? AND status IN ('PENDING','PROCESSING')"+due,Long.class,org));
      var oldest=db.queryForObject("SELECT MIN("+(kind.equals("reminder")?"due_at":"created_at")+") FROM "+table+" WHERE org_id=? AND status IN ('PENDING','PROCESSING')"+due,java.sql.Timestamp.class,org);
      metric.put("oldestPendingSeconds",oldest==null?null:Math.max(0,Duration.between(oldest.toLocalDateTime(),now).getSeconds()));
      metric.put("failed",db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE org_id=? AND status='FAILED'",Long.class,org));
      metric.put("expiredLeases",kind.equals("reminder")?0:db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE org_id=? AND status='PROCESSING' AND lease_until<?",Long.class,org,now));
      String attemptsExpression=kind.equals("reminder")?"attempts":"CASE WHEN attempts>1 THEN attempts-1 ELSE 0 END";
      String previousExpression=kind.equals("reminder")?"previous_attempts":"CASE WHEN previous_attempts>1 THEN previous_attempts-1 ELSE 0 END";
      long attempts=db.queryForObject("SELECT COALESCE(SUM("+attemptsExpression+"),0) FROM "+table+" WHERE org_id=?",Long.class,org);
      long reset=db.queryForObject("SELECT COALESCE(SUM("+previousExpression+"),0) FROM notification_operation_audit WHERE org_id=? AND pipeline_kind=?",Long.class,org,kind);
      metric.put(kind.equals("reminder")?"recordedFailureAttempts":"recordedRetryAttempts",attempts+reset);
      if(!kind.equals("reminder"))metric.put("observedRetryClaims",db.queryForObject("SELECT COALESCE(SUM(total_value),0) FROM notification_metric WHERE org_id=? AND metric_key=?",Long.class,org,kind+"_retry_claims"));
      String completion=kind.equals("reminder")?"updated_at":"completed_at";
      String origin=kind.equals("reminder")?"due_at":"created_at";
      var latency=db.query("SELECT "+origin+","+completion+" FROM "+table+" WHERE org_id=? AND "+completion+" IS NOT NULL"+(kind.equals("reminder")?" AND status='SENT'":"")+" ORDER BY "+completion+" DESC LIMIT 1000",(r,n)->Math.max(0,Duration.between(r.getTimestamp(1).toLocalDateTime(),r.getTimestamp(2).toLocalDateTime()).toMillis()),org);
      latency.sort(Long::compareTo);metric.put("latencySamples",latency.size());
      metric.put("averageLatencyMs",latency.isEmpty()?null:latency.stream().mapToLong(Long::longValue).average().orElse(0));
      metric.put("p95LatencyMs",latency.isEmpty()?null:latency.get((int)Math.ceil(latency.size()*.95)-1));
      result.put(kind,metric);
    }
    result.put("noRecipientFacts",db.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE org_id=? AND status='COMPLETED' AND recipient_count=0",Long.class,org));
    result.put("allRecipientsSkippedFacts",db.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE org_id=? AND status='COMPLETED' AND recipient_count>0 AND recipient_count=skipped_count",Long.class,org));
    result.put("mergeCounters",db.queryForList("SELECT metric_key,total_value FROM notification_metric WHERE org_id=? ORDER BY metric_key",org));
    result.put("localSseConnections",streams.size(org));
    result.put("silentInboxRecords",db.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE org_id=? AND silent_at_materialization=TRUE",Long.class,org));
    result.put("digestRuns",db.queryForObject("SELECT COUNT(*) FROM notification_daily_digest WHERE org_id=?",Long.class,org));
    result.put("digestMessages",db.queryForObject("SELECT COUNT(*) FROM notification_daily_digest WHERE org_id=? AND message_id IS NOT NULL",Long.class,org));
    return result;
  }
}
