package com.pams.module.notification.listener;

import com.pams.module.notification.event.GovernanceNoticeEvent;
import com.pams.module.notification.entity.NotificationType;
import com.pams.module.notification.service.NotificationPipeline;
import com.pams.module.permission.service.TenantAuthorizationService;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component
public class GovernanceNoticeListener {
  private final JdbcTemplate db;
  private final TenantAuthorizationService auth;
  private final NotificationPipeline pipeline;
  public GovernanceNoticeListener(JdbcTemplate db,TenantAuthorizationService auth,NotificationPipeline pipeline) {
    this.db=db;this.auth=auth;this.pipeline=pipeline;
  }
  @TransactionalEventListener(phase=TransactionPhase.BEFORE_COMMIT)
  public void changed(GovernanceNoticeEvent e) {
    if(NotificationPipeline.org()!=e.orgId())throw new IllegalStateException("治理通知组织不一致");
    Set<Long> affected=new LinkedHashSet<>();
    if(Set.of("ASSIGN","DELEGATE").contains(e.action()))affected.add(e.targetId());
    else affected.addAll(db.queryForList("SELECT id FROM sys_user WHERE org_id=? AND role_id=? AND status=1 AND deleted=0",Long.class,e.orgId(),e.targetId()));
    for(long uid:affected) {
      pipeline.record(NotificationType.GOVERNANCE_CHANGED,"你的授权配置已更新","角色或管理身份发生变化，请刷新当前身份并核对可用操作。",
          "GOVERNANCE",e.auditId(),e.actorId(),uid,null,null);
      pipeline.invalidate(uid);
    }
    // Only current authorized governors receive the summary. Draft saves never emit this event.
    for(long uid:db.queryForList("SELECT id FROM sys_user WHERE org_id=? AND status=1 AND deleted=0",Long.class,e.orgId())) {
      if(!affected.contains(uid) && auth.capabilities(uid).contains("org:audit:view"))pipeline.record(NotificationType.GOVERNANCE_CHANGED,
          "组织授权变更已生效","一项角色或管理员配置变更已生效，详情请查看组织治理审计。",
          "GOVERNANCE",e.auditId(),e.actorId(),uid,null,null);
    }
  }
}
