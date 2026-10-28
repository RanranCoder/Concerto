package com.pams.module.notification.service;

import com.pams.module.activity.service.PlanAccessService;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.notification.entity.Notification;
import com.pams.module.permission.service.TenantAuthorizationService;
import com.pams.repository.UserRepository;
import com.pams.security.LoginUser;
import java.util.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

@Component
public class NotificationVisibility {
  private final TenantAuthorizationService auth;
  private final PlanAccessService plans;
  private final UserRepository users;

  public NotificationVisibility(
      TenantAuthorizationService auth, PlanAccessService plans, UserRepository users) {
    this.auth = auth;
    this.plans = plans;
    this.users = users;
  }

  public boolean canRead(long uid, Notification notification) {
    if (!auth.valid(uid)) return false;
    var user = users.findById(uid).orElse(null);
    if (user == null || !Objects.equals(user.getOrgId(), notification.getOrgId())) return false;
    String type = Objects.toString(notification.getEntityType(), "");
    if (type.isBlank()) return true; // personal messages without a business source
    var permissions = auth.business(uid);
    String permission =
        switch (type.toUpperCase(Locale.ROOT)) {
          case "TASK" -> "task:view";
          case "ACTIVITY" -> "activity:view";
          case "PLAN" -> "plan:view";
          case "ARTICLE", "NEWS" -> "content:view";
          case "SIGNIN" -> "checkin:view";
          case "MATERIAL", "CONTENT", "FILE" -> "material:view";
          case "ANNOUNCEMENT" -> "notice:view";
          case "SCHEDULE" -> "schedule:view";
          case "ATTENDANCE" -> "attendance:view";
          default -> null;
        };
    if (permission == null || !permissions.contains(permission)) return false;
    if (!type.equalsIgnoreCase("PLAN")) return true;
    if (notification.getEntityId() == null || user.getRole() == null) return false;
    var principal =
        new LoginUser(
            uid,
            user.getUsername(),
            user.getRealName(),
            user.getRole().getCode(),
            user.getRole().getLevel(),
            user.getDept() == null ? null : user.getDept().getId(),
            user.getDept() == null ? null : user.getDept().getName(),
            user.getOrgId());
    return plans.canRead(
        notification.getEntityId(),
        new ToolContext(
            principal, permissions.stream().map(SimpleGrantedAuthority::new).toList(), null, null));
  }
}
