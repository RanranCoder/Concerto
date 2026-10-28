package db.migration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.api.migration.*;

/** Frozen v2 catalogue, preserving old revisions and recording every converted role. */
public class V47__business_permission_catalog_v2 extends BaseJavaMigration {
  private final ObjectMapper json = new ObjectMapper();
  private final com.pams.module.permission.service.PermissionCatalog definitions =
      new com.pams.module.permission.service.PermissionCatalog();
  private Connection connection;
  private JsonNode catalog;
  private final Map<String, Long> ids = new LinkedHashMap<>();

  private void update(String sql, Object... args) throws SQLException {
    try (var s = connection.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
      s.executeUpdate();
    }
  }

  private List<Map<String, Object>> rows(String sql, Object... args) throws SQLException {
    try (var s = connection.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
      try (var r = s.executeQuery()) {
        var out = new ArrayList<Map<String, Object>>();
        while (r.next()) {
          var row = new LinkedHashMap<String, Object>();
          for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
            row.put(r.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT), r.getObject(i));
          out.add(row);
        }
        return out;
      }
    }
  }

  private long revision(long org, long role, String state, List<Map<String, Object>> grants)
      throws Exception {
    try (var s =
        connection.prepareStatement(
            "INSERT INTO role_revision(org_id,role_id,state,snapshot_json,created_at,published_at)"
                + " VALUES(?,?,?,?,CURRENT_TIMESTAMP,?)",
            Statement.RETURN_GENERATED_KEYS)) {
      s.setLong(1, org);
      s.setLong(2, role);
      s.setString(3, state);
      s.setString(4, json.writeValueAsString(grants));
      s.setTimestamp(
          5, state.equals("PUBLISHED") ? new Timestamp(System.currentTimeMillis()) : null);
      s.executeUpdate();
      try (var r = s.getGeneratedKeys()) {
        r.next();
        return r.getLong(1);
      }
    }
  }

  private List<Map<String, Object>> parse(Object value) throws Exception {
    return value == null ? List.of() : json.readValue(value.toString(), new TypeReference<>() {});
  }

  private List<Map<String, Object>> convert(List<Map<String, Object>> before, boolean scope) {
    var out = new LinkedHashMap<String, Map<String, Object>>();
    Set<String> retired = new HashSet<>();
    catalog.path("retired").forEach(p -> retired.add(p.asText()));
    for (var g : before) {
      String code = g.get("permission").toString();
      var mapping = catalog.path("mappings").get(code);
      List<String> codes = new ArrayList<>();
      if (mapping != null) mapping.forEach(p -> codes.add(p.asText()));
      else if (!retired.contains(code)) codes.add(code);
      for (String target : codes)
        if (ids.containsKey(target)) {
          var grant = new LinkedHashMap<String, Object>(g);
          grant.put("permission", target);
          if (scope) grant.put("scope", "ORG");
          out.put(target, grant);
        }
    }
    // Dependencies are explicit where the v2 vocabulary overrides the old module rule.
    boolean changed;
    do {
      changed = false;
      for (String code : List.copyOf(out.keySet())) {
        List<String> deps =
            definitions.contains(code) ? definitions.dependencies(code, ids.keySet()) : List.of();
        for (String dep : deps)
          if (!out.containsKey(dep)) {
            var grant = new LinkedHashMap<String, Object>(out.get(code));
            grant.put("permission", dep);
            if (!dep.startsWith("party:")) grant.remove("packVersion");
            out.put(dep, grant);
            changed = true;
          }
      }
    } while (changed);
    return new ArrayList<>(out.values());
  }

  public static List<Map<String, Object>> shadow(
      Connection connection, List<Map<String, Object>> before) throws Exception {
    return shadow(connection, before, true);
  }

  public static List<Map<String, Object>> shadowGrantable(
      Connection connection, List<Map<String, Object>> before) throws Exception {
    return shadow(connection, before, false);
  }

  private static List<Map<String, Object>> shadow(
      Connection connection, List<Map<String, Object>> before, boolean scope) throws Exception {
    var migration = new V47__business_permission_catalog_v2();
    migration.connection = connection;
    try (var stream =
        migration.getClass().getResourceAsStream("/authorization/permission-catalog-v2.json")) {
      migration.catalog = migration.json.readTree(Objects.requireNonNull(stream));
    }
    for (var r : migration.rows("SELECT id,code FROM permission"))
      migration.ids.put(r.get("code").toString(), ((Number) r.get("id")).longValue());
    for (var p : migration.catalog.path("permissions"))
      migration.ids.putIfAbsent(p.path("code").asText(), 0L);
    for (var p : migration.catalog.path("retired")) migration.ids.remove(p.asText());
    return migration.convert(before, scope);
  }

  @Override
  public void migrate(Context context) throws Exception {
    connection = context.getConnection();
    var approval =
        com.pams.module.permission.service.PermissionMigrationCli.requireApproval(connection);
    try (var stream = getClass().getResourceAsStream("/authorization/permission-catalog-v2.json")) {
      catalog = json.readTree(Objects.requireNonNull(stream));
    }
    update(
        "CREATE TABLE authorization_migration_review (org_id BIGINT NOT NULL,role_id BIGINT NOT"
            + " NULL,before_json TEXT NOT NULL,after_json TEXT NOT NULL,review_required INTEGER NOT"
            + " NULL,status VARCHAR(20) NOT NULL,created_at TIMESTAMP NOT NULL,resolved_at"
            + " TIMESTAMP,PRIMARY KEY(org_id,role_id))");
    update("ALTER TABLE sys_role ADD COLUMN updated_at TIMESTAMP");
    update("ALTER TABLE sys_role ADD COLUMN source_kind VARCHAR(20)");
    update("ALTER TABLE org_audit_log ADD COLUMN actor_name VARCHAR(100)");
    update("ALTER TABLE org_audit_log ADD COLUMN target_name VARCHAR(100)");
    for (var p : catalog.path("permissions")) {
      update(
          "INSERT INTO permission(code,name,module,category,sort_order)"
              + " VALUES(?,?,?,'BUSINESS',100)",
          p.path("code").asText(),
          p.path("name").asText(),
          p.path("module").asText());
    }
    for (var row : rows("SELECT id,code FROM permission"))
      ids.put(row.get("code").toString(), ((Number) row.get("id")).longValue());
    for (var p : catalog.path("retired")) {
      String code = p.asText();
      ids.remove(code);
      update(
          "UPDATE permission SET category=? WHERE code=?",
          code.startsWith("notification:") ? "BASIC" : "LEGACY",
          code);
    }
    for (var t : rows("SELECT code FROM role_template")) {
      String code = t.get("code").toString();
      var old = new ArrayList<Map<String, Object>>();
      for (var p :
          rows(
              "SELECT p.code FROM role_template_grant g JOIN permission p ON p.id=g.permission_id"
                  + " WHERE g.template_code=?",
              code)) old.add(Map.of("permission", p.get("code")));
      var next = convert(old, false);
      if (code.equals("STAFF"))
        next.removeIf(
            g -> Set.of("attendance:view", "attendance:manage").contains(g.get("permission")));
      update("DELETE FROM role_template_grant WHERE template_code=?", code);
      for (var g : next)
        update(
            "INSERT INTO role_template_grant(template_code,permission_id) VALUES(?,?)",
            code,
            ids.get(g.get("permission")));
    }
    for (var t : catalog.path("templates")) {
      String code = t.path("code").asText();
      update("INSERT INTO role_template(code,name) VALUES(?,?)", code, t.path("name").asText());
      for (var p : t.path("permissions"))
        update(
            "INSERT INTO role_template_grant(template_code,permission_id) VALUES(?,?)",
            code,
            ids.get(p.asText()));
    }
    for (var role :
        rows(
            "SELECT * FROM sys_role WHERE current_revision_id IS NOT NULL OR draft_revision_id IS"
                + " NOT NULL")) {
      long org = ((Number) role.get("org_id")).longValue(),
          rid = ((Number) role.get("id")).longValue();
      List<Map<String, Object>> old =
          role.get("current_revision_id") == null
              ? List.of()
              : parse(
                  rows(
                          "SELECT snapshot_json FROM role_revision WHERE id=?",
                          role.get("current_revision_id"))
                      .getFirst()
                      .get("snapshot_json"));
      var desired = convert(old, true);
      boolean review =
          desired.stream()
              .anyMatch(
                  g ->
                      Set.of("attendance:view", "attendance:manage").contains(g.get("permission")));
      var effective = new ArrayList<>(desired);
      boolean retainAttendance =
          review
              && !approval.isEmpty()
              && "KEEP"
                  .equals(
                      ((JsonNode) approval.get("decisions"))
                          .path("attendance")
                          .path(Long.toString(rid))
                          .asText());
      if (review && !retainAttendance)
        effective.removeIf(
            g -> Set.of("attendance:view", "attendance:manage").contains(g.get("permission")));
      Long rev =
          role.get("current_revision_id") == null
              ? null
              : revision(org, rid, "PUBLISHED", effective);
      if (rev != null) update("DELETE FROM role_grant WHERE org_id=? AND role_id=?", org, rid);
      for (var g : effective)
        update(
            "INSERT INTO role_grant(org_id,role_id,permission_id,scope_type,pack_version)"
                + " VALUES(?,?,?,'ORG',?)",
            org,
            rid,
            ids.get(g.get("permission")),
            g.get("packVersion"));
      Object draft = role.get("draft_revision_id");
      if (draft != null) {
        var original =
            parse(
                rows("SELECT snapshot_json FROM role_revision WHERE id=?", draft)
                    .getFirst()
                    .get("snapshot_json"));
        draft = revision(org, rid, "DRAFT", convert(original, true));
      }
      update(
          "UPDATE sys_role SET"
              + " current_revision_id=?,draft_revision_id=?,config_version=config_version+1 WHERE"
              + " org_id=? AND id=?",
          rev,
          draft,
          org,
          rid);
      update(
          "INSERT INTO"
              + " authorization_migration_review(org_id,role_id,before_json,after_json,review_required,status,created_at)"
              + " VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
          org,
          rid,
          json.writeValueAsString(old),
          json.writeValueAsString(effective),
          review ? 1 : 0,
          review ? "APPROVED" : "MAPPED");
    }
    for (var d : rows("SELECT * FROM org_role_delegation")) {
      var next = convert(parse(d.get("grantable_json")), false);
      update(
          "UPDATE org_role_delegation SET grantable_json=?,version=version+1 WHERE org_id=? AND"
              + " user_id=?",
          json.writeValueAsString(next),
          d.get("org_id"),
          d.get("user_id"));
    }
    update("UPDATE organization SET authz_version=authz_version+1");
    update("UPDATE org_authorization_change SET status='EXPIRED' WHERE status='PREVIEWED'");
    com.pams.module.permission.service.PermissionMigrationCli.cutoverComplete(connection, approval);
  }
}
