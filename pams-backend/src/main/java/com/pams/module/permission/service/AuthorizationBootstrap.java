package com.pams.module.permission.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** One-time conversion of explicitly granted legacy administration. */
@Component
@Order(1)
@lombok.extern.slf4j.Slf4j
public class AuthorizationBootstrap implements ApplicationRunner {
  private final JdbcTemplate db;
  private final TenantAuthorizationService auth;

  public AuthorizationBootstrap(JdbcTemplate db, TenantAuthorizationService auth) {
    this.db = db;
    this.auth = auth;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) throws Exception {
    for (Long org : db.queryForList("SELECT id FROM organization WHERE deleted=0", Long.class)) {
      auth.initializeOrg(org);
      if (db.queryForObject(
              "SELECT COUNT(*) FROM authorization_bootstrap WHERE org_id=?", Integer.class, org)
          > 0) continue;
      var admins =
          db.queryForList(
              "SELECT DISTINCT u.id,r.code FROM sys_user u JOIN sys_role r ON r.id=u.role_id JOIN"
                  + " role_template_grant t ON t.template_code=r.code JOIN permission p ON"
                  + " p.id=t.permission_id WHERE u.org_id=? AND u.deleted=0 AND u.status=1 AND"
                  + " p.code IN ('user:permission','user:manage')",
              org);
      var candidates =
          db.queryForList(
              "SELECT DISTINCT u.id FROM sys_user u JOIN sys_role r ON r.id=u.role_id JOIN"
                  + " role_template_grant t ON t.template_code=r.code JOIN permission p ON"
                  + " p.id=t.permission_id WHERE u.org_id=? AND u.deleted=0 AND u.status=1 AND"
                  + " p.code='user:permission'",
              Long.class,
              org);
      if (candidates.size() == 1)
        db.update(
            "UPDATE organization SET owner_user_id=? WHERE id=? AND owner_user_id IS NULL",
            candidates.getFirst(),
            org);
      if (db.queryForObject(
              "SELECT COUNT(*) FROM organization o JOIN sys_user u ON u.id=o.owner_user_id AND"
                  + " u.org_id=o.id WHERE o.id=? AND u.deleted=0 AND u.status=1",
              Integer.class,
              org)
          != 1) log.error("组织 {} 缺少有效所有者，需按迁移清单人工核验归属；系统不会选择多个候选之一", org);
      var mapper = new ObjectMapper();
      for (var user : admins) {
        long uid = ((Number) user.get("id")).longValue();
        if (auth.owner(uid)) continue;
        boolean canGrant =
            db.queryForObject(
                    "SELECT COUNT(*) FROM role_template_grant t JOIN permission p ON"
                        + " p.id=t.permission_id WHERE t.template_code=? AND"
                        + " p.code='user:permission'",
                    Integer.class,
                    user.get("code"))
                > 0;
        Set<String> caps =
            canGrant
                ? TenantAuthorizationService.GOVERNANCE
                : Set.of(
                    "user:view",
                    "user:manage",
                    "role:view",
                    "role:assign",
                    "org:manage",
                    "org:structure:manage",
                    "org:invite",
                    "member:import_account");
        var grantable = new ArrayList<Map<String, Object>>();
        for (String code : auth.business(uid)) {
          var g = new LinkedHashMap<String, Object>();
          g.put("permission", code);
          if (code.startsWith("party:")) g.put("packVersion", auth.currentPackVersion(org));
          grantable.add(g);
        }
        db.update(
            "INSERT INTO"
                + " org_role_delegation(org_id,user_id,capabilities_json,grantable_json,status)"
                + " VALUES(?,?,?,?,'ACTIVE')",
            org,
            uid,
            mapper.writeValueAsString(caps),
            mapper.writeValueAsString(grantable));
      }
      db.update("INSERT INTO authorization_bootstrap(org_id) VALUES(?)", org);
    }
  }
}
