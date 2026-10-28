package com.pams.module.permission.service;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** A reverted authority projection may only run with the previous application release. */
@Component
@DependsOn("flywayInitializer")
public class AuthorizationRollbackGuard {
  private final JdbcTemplate db;

  public AuthorizationRollbackGuard(JdbcTemplate db) {
    this.db = db;
  }

  @PostConstruct
  void check() {
    Boolean exists =
        db.execute(
            (org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                c -> {
                  try (var tables =
                      c.getMetaData()
                          .getTables(
                              c.getCatalog(),
                              c.getSchema(),
                              "authorization_v2_release",
                              new String[] {"TABLE"})) {
                    return tables.next();
                  }
                });
    if (Boolean.TRUE.equals(exists)
        && db.queryForObject(
                "SELECT COUNT(*) FROM authorization_v2_release WHERE status='ROLLED_BACK'",
                Integer.class)
            > 0) throw new IllegalStateException("权限投影已回退：请使用对应的旧应用版本，禁止继续运行新版");
  }
}
