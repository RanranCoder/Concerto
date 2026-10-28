package com.pams.module.permission.service;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AuthorizationChangeListener {
  private final JdbcTemplate db;
  private final SimpMessagingTemplate messages;

  public AuthorizationChangeListener(JdbcTemplate db, SimpMessagingTemplate messages) {
    this.db = db;
    this.messages = messages;
  }

  @TransactionalEventListener
  public void changed(AuthorizationChanged event) {
    for (String username :
        db.queryForList(
            "SELECT username FROM sys_user WHERE org_id=? AND deleted=0 AND status=1",
            String.class,
            event.orgId())) {
      try {
        messages.convertAndSendToUser(
            username,
            "/queue/authorization",
            Map.of("orgId", event.orgId(), "version", event.version()));
      } catch (RuntimeException ignored) {
        /* Request-time authorization and client refresh remain authoritative. */
      }
    }
  }
}
