package com.pams.module.permission.service;

import com.pams.security.LoginUser;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Runs after the failed business transaction, without persisting request bodies or credentials. */
@Service
public class GovernanceDenialAudit {
  private final JdbcTemplate db;

  public GovernanceDenialAudit(JdbcTemplate db) {
    this.db = db;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void rejected(String method, String path, int code, String message) {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof LoginUser user))
      return;
    String safePath = path.length() > 250 ? path.substring(0, 250) : path;
    String reason =
        message == null ? "治理操作被拒绝" : message.substring(0, Math.min(500, message.length()));
    long target = 0;
    String targetType = "GOVERNANCE";
    var match =
        java.util.regex.Pattern.compile("/(roles|users|role-delegations)/(\\d+)(?:/|$)")
            .matcher(path);
    if (match.find()) {
      target = Long.parseLong(match.group(2));
      targetType = match.group(1).equals("roles") ? "ROLE" : "USER";
    }
    String detail;
    try {
      detail =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .writeValueAsString(Map.of("method", method, "path", safePath, "code", code));
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
    db.update(
        "INSERT INTO"
            + " org_audit_log(org_id,actor_id,actor_name,action,target_id,target_type,detail_json,outcome,reason,created_at)"
            + " VALUES(?,?,?,'REJECTED',?,?,?,'REJECTED',?,CURRENT_TIMESTAMP)",
        user.getOrgId(),
        user.getId(),
        user.getRealName(),
        target,
        targetType,
        detail,
        reason);
  }
}
