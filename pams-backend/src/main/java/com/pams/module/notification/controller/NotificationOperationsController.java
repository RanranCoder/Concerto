package com.pams.module.notification.controller;

import com.pams.common.*;
import com.pams.module.notification.service.NotificationPipeline;
import com.pams.module.permission.service.TenantAuthorizationService;
import com.pams.security.LoginUser;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Tenant-scoped metadata only: never exposes message bodies, recipient names or exception stacks. */
@RestController
@RequestMapping("/api/notifications/operations")
public class NotificationOperationsController {
  @org.springframework.beans.factory.annotation.Autowired
  private com.pams.module.notification.service.MaintenanceNoticeService maintenance;
  public record SubscriptionRequest(boolean enabled) {}
  @GetMapping("/subscription")
  public Result<com.pams.module.notification.service.MaintenanceNoticeService.Subscription> subscription(@AuthenticationPrincipal LoginUser user) {
    authorize(user);return Result.ok(maintenance.subscription(user.getId()));
  }
  @PutMapping("/subscription")
  public Result<com.pams.module.notification.service.MaintenanceNoticeService.Subscription> subscribe(@AuthenticationPrincipal LoginUser user,@RequestBody SubscriptionRequest request) {
    authorize(user);return Result.ok(maintenance.subscribe(user.getId(),request.enabled()));
  }
  private final JdbcTemplate db;private final TenantAuthorizationService auth;
  public NotificationOperationsController(JdbcTemplate db,TenantAuthorizationService auth){this.db=db;this.auth=auth;}
  private long authorize(LoginUser user) {
    long org=NotificationPipeline.org();
    if(user==null || !Objects.equals(user.getOrgId(),org) || !auth.valid(user.getId())
        || ((Number)auth.identity(user.getId()).get("org_id")).longValue()!=org
        || !auth.capabilities(user.getId()).contains("org:audit:view"))throw new BizException(403,"需要组织审计权限");
    return org;
  }
  @GetMapping
  public Result<Map<String,Object>> summary(@AuthenticationPrincipal LoginUser user) {
    long org=authorize(user);Map<String,Object> result=new LinkedHashMap<>();
    for(String table:List.of("notification_outbox","notification_delivery","notification_reminder")) {
      var metrics=new LinkedHashMap<String,Object>();
      metrics.put("states",db.queryForList("SELECT status,COUNT(*) AS count FROM "+table+" WHERE org_id=? GROUP BY status",org));
      metrics.put("oldestPendingAt",db.queryForObject("SELECT MIN(created_at) FROM "+table+" WHERE org_id=? AND status IN ('PENDING','PROCESSING')",java.sql.Timestamp.class,org));
      metrics.put("retried",db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE org_id=? AND attempts>1",Long.class,org));
      metrics.put("failures",db.queryForList("SELECT id,status,attempts,last_error,created_at FROM "+table+" WHERE org_id=? AND status='FAILED' ORDER BY id DESC LIMIT 50",org));
      result.put(table,metrics);
    }
    return Result.ok(result);
  }
  @PostMapping("/{kind}/{id}/retry")
  @Transactional
  public Result<Void> retry(@PathVariable String kind,@PathVariable long id,@AuthenticationPrincipal LoginUser user) {
    long org=authorize(user);
    String table=switch(kind){case "outbox"->"notification_outbox";case "delivery"->"notification_delivery";case "reminder"->"notification_reminder";default->throw new BizException(400,"无效管线类型");};
    String lease=kind.equals("reminder")?"":",lease_token=NULL,lease_until=NULL";
    var previous=db.queryForList("SELECT attempts FROM "+table+" WHERE org_id=? AND id=? AND status='FAILED' FOR UPDATE",org,id);
    if(previous.isEmpty())throw new BizException(409,"记录不存在或无需重试");
    if(db.update("UPDATE "+table+" SET status='PENDING',attempts=0,next_attempt_at=CURRENT_TIMESTAMP,last_error=NULL"+lease+" WHERE org_id=? AND id=? AND status='FAILED'",org,id)!=1)throw new BizException(409,"记录不存在或无需重试");
    db.update("INSERT INTO notification_operation_audit(org_id,actor_id,pipeline_kind,record_id,previous_attempts) VALUES(?,?,?,?,?)",org,user.getId(),kind,id,previous.getFirst().get("attempts"));
    return Result.ok();
  }
  @GetMapping("/audit")
  public Result<List<Map<String,Object>>> audit(@AuthenticationPrincipal LoginUser user) {
    return Result.ok(db.queryForList("SELECT id,actor_id,pipeline_kind,record_id,previous_attempts,created_at FROM notification_operation_audit WHERE org_id=? ORDER BY id DESC LIMIT 100",authorize(user)));
  }
  @GetMapping("/incidents")
  public Result<List<Map<String,Object>>> incidents(@AuthenticationPrincipal LoginUser user) {
    return Result.ok(db.queryForList("SELECT id,category,window_date,occurrences,first_seen_at,last_seen_at FROM notification_incident WHERE org_id=? ORDER BY id DESC LIMIT 100",authorize(user)));
  }
}
