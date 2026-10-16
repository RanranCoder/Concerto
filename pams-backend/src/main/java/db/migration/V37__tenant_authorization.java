package db.migration;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Portable schema upgrade. Legacy role_permission becomes a read-only template source. */
public class V37__tenant_authorization extends BaseJavaMigration {
  @Override
  public void migrate(Context context) throws Exception {
    Connection c = context.getConnection();
    execute(
        c,
        "ALTER TABLE sys_role ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'",
        "ALTER TABLE sys_role ADD COLUMN description VARCHAR(500)",
        "ALTER TABLE sys_role ADD COLUMN config_version BIGINT NOT NULL DEFAULT 0",
        "ALTER TABLE sys_role ADD COLUMN current_revision_id BIGINT",
        "ALTER TABLE sys_role ADD COLUMN draft_revision_id BIGINT",
        "ALTER TABLE organization ADD COLUMN authz_version BIGINT NOT NULL DEFAULT 0",
        "ALTER TABLE permission ADD COLUMN category VARCHAR(30) NOT NULL DEFAULT 'BUSINESS'",
        "ALTER TABLE activity ADD COLUMN owner_unit_id BIGINT",
        "ALTER TABLE task ADD COLUMN assignee_user_id BIGINT");
    // H2 unique constraints and MySQL unique indexes require different drop syntax.
    if (c.getMetaData().getDatabaseProductName().equalsIgnoreCase("H2")) {
      execute(c, "ALTER TABLE sys_role ALTER COLUMN name VARCHAR(100)");
      List<String> constraints = new ArrayList<>();
      try (Statement s = c.createStatement();
          ResultSet r =
              s.executeQuery(
                  "SELECT DISTINCT k.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE k"
                      + " JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS t ON"
                      + " k.CONSTRAINT_NAME=t.CONSTRAINT_NAME AND"
                      + " k.CONSTRAINT_SCHEMA=t.CONSTRAINT_SCHEMA WHERE"
                      + " UPPER(k.TABLE_NAME)='SYS_ROLE' AND UPPER(k.COLUMN_NAME)='CODE' AND"
                      + " t.CONSTRAINT_TYPE='UNIQUE'")) {
        while (r.next()) constraints.add(r.getString(1));
      }
      for (String name : constraints)
        execute(c, "ALTER TABLE sys_role DROP CONSTRAINT \"" + name.replace("\"", "\"\"") + "\"");
    } else {
      execute(c, "ALTER TABLE sys_role MODIFY COLUMN name VARCHAR(100) NOT NULL");
      List<String> indexes = new ArrayList<>();
      try (ResultSet r =
          c.getMetaData().getIndexInfo(c.getCatalog(), null, "sys_role", true, false)) {
        while (r.next())
          if ("code".equalsIgnoreCase(r.getString("COLUMN_NAME")))
            indexes.add(r.getString("INDEX_NAME"));
      }
      for (String name : new HashSet<>(indexes))
        execute(c, "ALTER TABLE sys_role DROP INDEX `" + name.replace("`", "``") + "`");
    }
    execute(
        c,
        "UPDATE sys_role SET org_id=1 WHERE org_id IS NULL",
        "CREATE UNIQUE INDEX uk_role_org_code ON sys_role(org_id,code)",
        "CREATE UNIQUE INDEX uk_role_org_id ON sys_role(org_id,id)",
        "CREATE TABLE role_grant (org_id BIGINT NOT NULL,role_id BIGINT NOT NULL,permission_id"
            + " BIGINT NOT NULL,scope_type VARCHAR(20) NOT NULL,pack_version BIGINT,PRIMARY"
            + " KEY(org_id,role_id,permission_id))",
        "CREATE TABLE role_revision (id BIGINT AUTO_INCREMENT PRIMARY KEY,org_id BIGINT NOT"
            + " NULL,role_id BIGINT NOT NULL,state VARCHAR(20) NOT NULL,base_revision_id"
            + " BIGINT,snapshot_json TEXT NOT NULL,draft_version BIGINT NOT NULL DEFAULT"
            + " 0,created_by BIGINT,created_at TIMESTAMP NOT NULL,published_at TIMESTAMP)",
        "CREATE INDEX idx_revision_role ON role_revision(org_id,role_id,id)",
        "CREATE TABLE org_role_delegation (org_id BIGINT NOT NULL,user_id BIGINT NOT"
            + " NULL,capabilities_json TEXT NOT NULL,grantable_json TEXT NOT NULL,expires_at"
            + " TIMESTAMP,status VARCHAR(20) NOT NULL,version BIGINT NOT NULL DEFAULT 0,PRIMARY"
            + " KEY(org_id,user_id))",
        "CREATE TABLE org_domain_pack (org_id BIGINT NOT NULL,pack_code VARCHAR(50) NOT"
            + " NULL,enabled INTEGER NOT NULL,activation_version BIGINT NOT NULL,PRIMARY"
            + " KEY(org_id,pack_code))",
        "CREATE TABLE org_authorization_change (id VARCHAR(64) PRIMARY KEY,org_id BIGINT NOT"
            + " NULL,actor_id BIGINT NOT NULL,action VARCHAR(40) NOT NULL,target_id BIGINT NOT"
            + " NULL,payload_json TEXT NOT NULL,baseline_version BIGINT NOT NULL,actor_version"
            + " BIGINT NOT NULL,expires_at TIMESTAMP NOT NULL,status VARCHAR(20) NOT"
            + " NULL,result_json TEXT,created_at TIMESTAMP NOT NULL)",
        "CREATE TABLE org_audit_log (id BIGINT AUTO_INCREMENT PRIMARY KEY,org_id BIGINT NOT"
            + " NULL,actor_id BIGINT NOT NULL,action VARCHAR(50) NOT NULL,target_id"
            + " BIGINT,detail_json TEXT NOT NULL,created_at TIMESTAMP NOT NULL)",
        "CREATE INDEX idx_org_audit ON org_audit_log(org_id,id)",
        "CREATE TABLE authorization_bootstrap(org_id BIGINT PRIMARY KEY)",
        "CREATE TABLE role_template (code VARCHAR(30) PRIMARY KEY,name VARCHAR(100) NOT"
            + " NULL,template_version INTEGER NOT NULL DEFAULT 1)",
        "CREATE TABLE role_template_grant (template_code VARCHAR(30) NOT NULL,permission_id BIGINT"
            + " NOT NULL,PRIMARY KEY(template_code,permission_id))",
        "INSERT INTO role_template_grant(template_code,permission_id) SELECT role,permission_id"
            + " FROM role_permission",
        "UPDATE permission SET category='ORG_GOVERNANCE' WHERE module='user' OR code IN"
            + " ('member:import_account')");
    String[][] additions = {
      {"role:view", "查看角色", "role", "ORG_GOVERNANCE"},
      {"role:manage", "维护角色", "role", "ORG_GOVERNANCE"},
      {"role:grant", "调整业务授权", "role", "ORG_GOVERNANCE"},
      {"role:assign", "分配角色", "role", "ORG_GOVERNANCE"},
      {"org:manage", "组织配置", "org", "ORG_GOVERNANCE"},
      {"org:structure:manage", "组织架构管理", "org", "ORG_GOVERNANCE"},
      {"org:invite", "邀请成员", "org", "ORG_GOVERNANCE"},
      {"org:audit:view", "治理记录", "org", "ORG_GOVERNANCE"},
      {"user:reset-password", "重置密码", "user", "ORG_GOVERNANCE"},
      {"content:manage", "宣传内容管理", "content", "BUSINESS"},
      {"file:view", "读取本人及同部门普通文件", "file", "BUSINESS"},
      {"file:org:read", "跨部门读取普通文件", "file", "BUSINESS"},
      {"file:sensitive:read", "读取签到名单等敏感文件", "file", "BUSINESS"},
      {"kb:restricted:read", "读取受限知识", "kb", "BUSINESS"},
      {"party:sensitive:view", "党务敏感字段", "party", "BUSINESS"}
    };
    for (String[] p : additions)
      try (PreparedStatement s =
          c.prepareStatement(
              "INSERT INTO permission(code,name,module,category,sort_order) VALUES(?,?,?,?,100)")) {
        for (int i = 0; i < 4; i++) s.setString(i + 1, p[i]);
        s.executeUpdate();
      }
    execute(
        c,
        "INSERT INTO permission(code,name,module,category,sort_order)"
            + " VALUES('content:view','查看宣传内容','content','BUSINESS',1)",
        "INSERT INTO role_template_grant(template_code,permission_id) SELECT t.role,p.id FROM"
            + " (SELECT DISTINCT role FROM role_permission) t CROSS JOIN permission p WHERE"
            + " p.code='file:view'",
        "INSERT INTO role_template_grant(template_code,permission_id) SELECT t.role,p.id FROM"
            + " (SELECT DISTINCT role FROM role_permission) t CROSS JOIN permission p WHERE"
            + " t.role<>'STAFF' AND p.code IN ('file:org:read','file:sensitive:read')",
        "INSERT INTO role_template_grant(template_code,permission_id) SELECT t.role,p.id FROM"
            + " (SELECT DISTINCT role FROM role_permission) t CROSS JOIN permission p WHERE"
            + " p.code='content:view'");
    execute(
        c,
        "INSERT INTO permission(code,name,module,category,sort_order)"
            + " VALUES('news:manage','新闻稿管理','content','BUSINESS',2)",
        "INSERT INTO role_template_grant(template_code,permission_id) SELECT t.role,p.id FROM"
            + " (SELECT DISTINCT role FROM role_permission) t CROSS JOIN permission p WHERE"
            + " t.role<>'STAFF' AND p.code='news:manage'");
    // Preserve existing role-only abilities as explicit template grants, never as synthetic ROLE_*.
    execute(
        c,
        "INSERT INTO role_template_grant(template_code,permission_id) SELECT t.role,p.id FROM"
            + " (SELECT DISTINCT role FROM role_permission) t CROSS JOIN permission p WHERE"
            + " t.role<>'STAFF' AND p.code IN ('kb:restricted:read','party:sensitive:view')",
        "INSERT INTO role_template_grant(template_code,permission_id) SELECT t.role,p.id FROM"
            + " (SELECT DISTINCT role FROM role_permission) t CROSS JOIN permission p WHERE t.role"
            + " IN ('TEACHER','DIRECTOR','MEDIA_LEADER') AND p.code='content:manage'");
    String[][] templates = {
      {"TEACHER", "指导老师"},
      {"DIRECTOR", "主任"},
      {"ORG_LEADER", "组织部长"},
      {"SECRETARY_LEADER", "文秘部长"},
      {"MEDIA_LEADER", "新媒体部长"},
      {"TECH_LEADER", "青年科技部长"},
      {"STAFF", "干事"}
    };
    for (String[] t : templates)
      try (PreparedStatement s =
          c.prepareStatement("INSERT INTO role_template(code,name) VALUES(?,?)")) {
        s.setString(1, t[0]);
        s.setString(2, t[1]);
        s.executeUpdate();
      }
    execute(
        c,
        "DELETE FROM role_template_grant WHERE template_code='STAFF' AND permission_id IN (SELECT"
            + " id FROM permission WHERE module='party')");
    // Materialize tenant role IDs before remapping users previously sharing global rows.
    List<long[]> links = new ArrayList<>();
    try (Statement s = c.createStatement();
        ResultSet r =
            s.executeQuery(
                "SELECT DISTINCT org_id,role_id FROM sys_user WHERE role_id IS NOT NULL")) {
      while (r.next()) links.add(new long[] {r.getLong(1), r.getLong(2)});
    }
    for (long[] link : links) {
      long org = link[0], old = link[1], target = old;
      try (PreparedStatement s =
          c.prepareStatement("SELECT code,name,level,data_scope,org_id FROM sys_role WHERE id=?")) {
        s.setLong(1, old);
        try (ResultSet r = s.executeQuery()) {
          if (!r.next() || r.getLong(5) == org) continue;
          String code = r.getString(1);
          Long found = null;
          try (PreparedStatement f =
              c.prepareStatement("SELECT id FROM sys_role WHERE org_id=? AND code=?")) {
            f.setLong(1, org);
            f.setString(2, code);
            try (ResultSet x = f.executeQuery()) {
              if (x.next()) found = x.getLong(1);
            }
          }
          if (found != null) target = found;
          else
            try (PreparedStatement ins =
                c.prepareStatement(
                    "INSERT INTO sys_role(code,name,level,data_scope,org_id,created_at)"
                        + " VALUES(?,?,?,?,?,CURRENT_TIMESTAMP)",
                    Statement.RETURN_GENERATED_KEYS)) {
              ins.setString(1, code);
              ins.setString(2, r.getString(2));
              ins.setInt(3, r.getInt(3));
              ins.setString(4, r.getString(4));
              ins.setLong(5, org);
              ins.executeUpdate();
              try (ResultSet keys = ins.getGeneratedKeys()) {
                keys.next();
                target = keys.getLong(1);
              }
            }
        }
      }
      try (PreparedStatement s =
          c.prepareStatement("UPDATE sys_user SET role_id=? WHERE org_id=? AND role_id=?")) {
        s.setLong(1, target);
        s.setLong(2, org);
        s.setLong(3, old);
        s.executeUpdate();
      }
    }
    execute(
        c,
        "ALTER TABLE role_grant ADD CONSTRAINT fk_grant_tenant_role FOREIGN KEY(org_id,role_id)"
            + " REFERENCES sys_role(org_id,id)",
        "ALTER TABLE role_grant ADD CONSTRAINT fk_grant_permission FOREIGN KEY(permission_id)"
            + " REFERENCES permission(id)",
        "ALTER TABLE role_revision ADD CONSTRAINT fk_revision_tenant_role FOREIGN"
            + " KEY(org_id,role_id) REFERENCES sys_role(org_id,id)");
  }

  private static void execute(Connection c, String... sqls) throws SQLException {
    try (Statement s = c.createStatement()) {
      for (String sql : sqls) s.execute(sql);
    }
  }
}
