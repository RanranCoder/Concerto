package com.pams.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import db.migration.V47__business_permission_catalog_v2;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

class PermissionCatalogMigrationTest {
  @Test
  void upgradesExistingRolesWithoutRewritingTheirPublishedHistory() throws Exception {
    String external = System.getenv("PAMS_PERMISSION_TEST_URL");
    String url =
        external == null
            ? "jdbc:h2:mem:permission_catalog_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            : external;
    if (external != null
        && !external.matches(
            "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/pams_permission_validation_[a-zA-Z0-9_]+\\?.*"))
      throw new IllegalArgumentException("迁移测试只允许独立 pams_permission_validation_ 数据库");
    String user =
        external == null
            ? "sa"
            : Objects.requireNonNull(System.getenv("PAMS_PERMISSION_TEST_USER"));
    String password =
        external == null
            ? ""
            : Objects.requireNonNull(System.getenv("PAMS_PERMISSION_TEST_PASSWORD"));
    String vendor = external == null ? "classpath:db/migration-h2" : "classpath:db/migration-mysql";
    Flyway.configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration", vendor)
        .target("46")
        .load()
        .migrate();
    try (var c = DriverManager.getConnection(url, user, password)) {
      var db =
          new org.springframework.jdbc.core.JdbcTemplate(
              new org.springframework.jdbc.datasource.SingleConnectionDataSource(c, true));
      db.update("INSERT INTO organization(name,type,status,deleted) VALUES('权限迁移演练','OTHER',1,0)");
      long org = db.queryForObject("SELECT MAX(id) FROM organization", Long.class);
      db.update(
          "INSERT INTO sys_role(org_id,code,name,level,data_scope,status)"
              + " VALUES(?,'MIGRATION_CUSTOM','迁移测试职责',1,'ALL','ACTIVE')",
          org);
      long role =
          db.queryForObject(
              "SELECT id FROM sys_role WHERE org_id=? AND code='MIGRATION_CUSTOM'",
              Long.class,
              org);
      var mapper = new ObjectMapper();
      var before =
          List.of(
                  "activity:view",
                  "activity:edit",
                  "attendance:view",
                  "attendance:manage",
                  "content:manage",
                  "kb:manage")
              .stream()
              .map(code -> Map.of("permission", code, "scope", "ORG"))
              .toList();
      String snapshot = mapper.writeValueAsString(before);
      db.update(
          "INSERT INTO role_revision(org_id,role_id,state,snapshot_json,created_at,published_at)"
              + " VALUES(?,?,'PUBLISHED',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
          org,
          role,
          snapshot);
      long oldRevision = db.queryForObject("SELECT MAX(id) FROM role_revision", Long.class);
      db.update("UPDATE sys_role SET current_revision_id=? WHERE id=?", oldRevision, role);
      for (var grant : before)
        db.update(
            "INSERT INTO role_grant(org_id,role_id,permission_id,scope_type) SELECT ?,?,id,'ORG'"
                + " FROM permission WHERE code=?",
            org,
            role,
            grant.get("permission"));
      db.update(
          "INSERT INTO sys_user(username,password,real_name,org_id,role_id,status,deleted)"
              + " VALUES('migration-owner','unused','迁移演练所有者',?,?,1,0)",
          org,
          role);
      long owner =
          db.queryForObject("SELECT id FROM sys_user WHERE username='migration-owner'", Long.class);
      db.update("UPDATE organization SET owner_user_id=? WHERE id=?", owner, org);
      assertThatThrownBy(
              () -> com.pams.module.permission.service.PermissionMigrationCli.requireApproval(c))
          .hasMessageContaining("尚未获批");
      db.update(
          "INSERT INTO sys_role(org_id,code,name,level,data_scope,status,current_revision_id)"
              + " VALUES(?,'MIGRATION_KEEP','保留考勤职责',1,'ALL','ACTIVE',?)",
          org,
          oldRevision);
      long keep =
          db.queryForObject(
              "SELECT id FROM sys_role WHERE org_id=? AND code='MIGRATION_KEEP'", Long.class, org);
      db.update(
          "INSERT INTO role_revision(org_id,role_id,state,snapshot_json,created_at,published_at)"
              + " VALUES(?,?,'PUBLISHED',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
          org,
          keep,
          snapshot);
      long keepRevision = db.queryForObject("SELECT MAX(id) FROM role_revision", Long.class);
      db.update("UPDATE sys_role SET current_revision_id=? WHERE id=?", keepRevision, keep);
      db.update(
          "INSERT INTO role_grant(org_id,role_id,permission_id,scope_type) SELECT ?,?,id,'ORG' FROM"
              + " permission WHERE code IN"
              + " ('activity:view','activity:edit','attendance:view','attendance:manage','content:manage','kb:manage')",
          org,
          keep);
      db.update(
          "INSERT INTO sys_role(org_id,code,name,level,data_scope,status)"
              + " VALUES(?,'MIGRATION_DRAFT','旧未发布职责',1,'ALL','DRAFT')",
          org);
      long draftRole =
          db.queryForObject(
              "SELECT id FROM sys_role WHERE org_id=? AND code='MIGRATION_DRAFT'", Long.class, org);
      db.update(
          "INSERT INTO role_revision(org_id,role_id,state,snapshot_json,created_at)"
              + " VALUES(?,?,'DRAFT',?,CURRENT_TIMESTAMP)",
          org,
          draftRole,
          snapshot);
      db.update(
          "UPDATE sys_role SET draft_revision_id=(SELECT MAX(id) FROM role_revision) WHERE id=?",
          draftRole);
      var context = mock(Context.class);
      when(context.getConnection()).thenReturn(c);
      assertThatThrownBy(() -> new V47__business_permission_catalog_v2().migrate(context))
          .hasMessageContaining("尚未获批");
      assertThat(
              db.queryForObject(
                  "SELECT COUNT(*) FROM permission WHERE code='plan:edit'", Long.class))
          .isZero();
      db.update(
          "INSERT INTO sys_user(username,password,real_name,org_id,role_id,status,deleted)"
              + " VALUES('migration-administrator','unused','存量管理员',?,?,1,0)",
          org,
          role);
      long administrator =
          db.queryForObject(
              "SELECT id FROM sys_user WHERE username='migration-administrator'", Long.class);
      db.update(
          "INSERT INTO"
              + " org_role_delegation(org_id,user_id,capabilities_json,grantable_json,status,version)"
              + " VALUES(?,?,?,?,'ACTIVE',1)",
          org,
          administrator,
          "[\"role:view\"]",
          "[{\"permission\":\"content:manage\"}]");
      var source = com.pams.module.permission.service.PermissionMigrationCli.snapshot(c);
      var report = com.pams.module.permission.service.PermissionMigrationCli.report(c);
      assertThat(mapper.valueToTree(report).path("delegations").toString())
          .contains("article:create", "content:manage");
      assertThat(com.pams.module.permission.service.PermissionMigrationCli.snapshot(c))
          .isEqualTo(source);
      var decisions =
          mapper.valueToTree(
              Map.of(
                  "acknowledgeBusinessChanges",
                  true,
                  "owners",
                  Map.of(Long.toString(org), Map.of("userId", owner, "evidence", "隔离测试中的所有者确认记录")),
                  "attendance",
                  Map.of(Long.toString(role), "REVOKE", Long.toString(keep), "KEEP")));
      String release =
          com.pams.module.permission.service.PermissionMigrationCli.approve(
              c, mapper.valueToTree(report), decisions);
      db.update("UPDATE sys_role SET config_version=config_version+1 WHERE id=?", role);
      assertThatThrownBy(
              () -> com.pams.module.permission.service.PermissionMigrationCli.requireApproval(c))
          .hasMessageContaining("变化");
      db.update("UPDATE sys_role SET config_version=config_version-1 WHERE id=?", role);
      Flyway.configure()
          .dataSource(url, user, password)
          .locations("classpath:db/migration", vendor)
          .load()
          .migrate();
      assertThat(
              db.queryForObject("SELECT status FROM sys_role WHERE id=?", String.class, draftRole))
          .isEqualTo("DRAFT");
      assertThat(
              db.queryForObject(
                  "SELECT current_revision_id FROM sys_role WHERE id=?", Long.class, draftRole))
          .isNull();
      assertThat(
              db.queryForObject(
                  "SELECT rr.snapshot_json FROM role_revision rr JOIN sys_role r ON"
                      + " r.draft_revision_id=rr.id WHERE r.id=?",
                  String.class,
                  draftRole))
          .contains("plan:edit")
          .doesNotContain("content:manage");
      assertThat(
              db.queryForObject(
                  "SELECT snapshot_json FROM role_revision WHERE id=?", String.class, oldRevision))
          .isEqualTo(snapshot);
      var live =
          db.queryForList(
              "SELECT p.code FROM role_grant g JOIN permission p ON p.id=g.permission_id WHERE"
                  + " g.org_id=? AND g.role_id=?",
              String.class,
              org,
              role);
      assertThat(live)
          .contains(
              "activity:edit",
              "activity:transition",
              "task:manage",
              "task:progress",
              "plan:edit",
              "plan:submit",
              "article:create",
              "article:review",
              "kb:document:manage",
              "kb:index:manage")
          .doesNotContain("content:manage", "kb:manage", "attendance:view", "attendance:manage");
      assertThat(
              db.queryForObject(
                  "SELECT status FROM authorization_migration_review WHERE org_id=? AND role_id=?",
                  String.class,
                  org,
                  role))
          .isEqualTo("APPROVED");
      assertThat(
              db.queryForObject(
                  "SELECT COUNT(*) FROM role_template_grant g JOIN permission p ON"
                      + " p.id=g.permission_id WHERE g.template_code='TEACHER' AND"
                      + " p.code='user:permission'",
                  Long.class))
          .isEqualTo(1);
      assertThat(
              db.queryForObject(
                  "SELECT COUNT(*) FROM role_template_grant g JOIN permission p ON"
                      + " p.id=g.permission_id WHERE g.template_code='STAFF' AND p.code IN"
                      + " ('attendance:view','attendance:manage')",
                  Long.class))
          .isZero();
      assertThat(
              db.queryForList(
                  "SELECT p.code FROM role_grant g JOIN permission p ON p.id=g.permission_id WHERE"
                      + " g.org_id=? AND g.role_id=?",
                  String.class,
                  org,
                  keep))
          .contains("attendance:view", "attendance:manage");
      db.update("UPDATE sys_role SET config_version=config_version+1 WHERE id=?", role);
      assertThatThrownBy(
              () -> com.pams.module.permission.service.PermissionMigrationCli.rollback(c, release))
          .hasMessageContaining("禁止覆盖回退");
      db.update("UPDATE sys_role SET config_version=config_version-1 WHERE id=?", role);
      com.pams.module.permission.service.PermissionMigrationCli.rollback(c, release);
      assertThat(
              db.queryForObject(
                  "SELECT current_revision_id FROM sys_role WHERE id=?", Long.class, role))
          .isEqualTo(oldRevision);
      assertThat(
              db.queryForList(
                  "SELECT p.code FROM role_grant g JOIN permission p ON p.id=g.permission_id WHERE"
                      + " g.org_id=? AND g.role_id=?",
                  String.class,
                  org,
                  role))
          .contains("attendance:manage", "content:manage", "kb:manage");
    }
  }
}
