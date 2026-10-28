package com.pams.module.permission.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import db.migration.V47__business_permission_catalog_v2;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/**
 * Offline maintenance tool. Never starts Spring or runs Flyway. Credentials come from environment.
 */
public final class PermissionMigrationCli {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final LinkedHashMap<String, String> QUERIES = new LinkedHashMap<>();

  static {
    QUERIES.put(
        "organization",
        "SELECT id,owner_user_id,status,deleted,authz_version FROM organization ORDER BY id");
    QUERIES.put(
        "sys_user", "SELECT id,org_id,role_id,dept_id,status,deleted FROM sys_user ORDER BY id");
    QUERIES.put(
        "sys_role",
        "SELECT"
            + " id,org_id,code,name,description,status,config_version,current_revision_id,draft_revision_id"
            + " FROM sys_role ORDER BY id");
    QUERIES.put(
        "role_revision",
        "SELECT id,org_id,role_id,state,snapshot_json,draft_version FROM role_revision ORDER BY"
            + " id");
    QUERIES.put(
        "role_grant",
        "SELECT org_id,role_id,permission_id,scope_type,pack_version FROM role_grant ORDER BY"
            + " org_id,role_id,permission_id");
    QUERIES.put(
        "org_role_delegation",
        "SELECT org_id,user_id,capabilities_json,grantable_json,expires_at,status,version FROM"
            + " org_role_delegation ORDER BY org_id,user_id");
    QUERIES.put(
        "org_domain_pack",
        "SELECT org_id,pack_code,enabled,activation_version FROM org_domain_pack ORDER BY"
            + " org_id,pack_code");
    QUERIES.put("org_position", "SELECT id,org_id,name,role_code FROM org_position ORDER BY id");
    QUERIES.put(
        "org_invitation",
        "SELECT id,org_id,role_code,org_unit_id,used_by,expire_at FROM org_invitation ORDER BY id");
    QUERIES.put("permission", "SELECT id,code,name,module,category FROM permission ORDER BY id");
    QUERIES.put(
        "role_template_grant",
        "SELECT template_code,permission_id FROM role_template_grant ORDER BY"
            + " template_code,permission_id");
    QUERIES.put(
        "role_template", "SELECT code,name,template_version FROM role_template ORDER BY code");
    QUERIES.put("article", "SELECT id,org_id,author_id,status FROM article ORDER BY id");
    QUERIES.put("activity_plan", "SELECT id,org_id,owner_id,status FROM activity_plan ORDER BY id");
  }

  public static List<Map<String, String>> rows(Connection c, String sql, Object... args)
      throws SQLException {
    try (var s = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
      try (var r = s.executeQuery()) {
        var list = new ArrayList<Map<String, String>>();
        while (r.next()) {
          Map<String, String> row = new TreeMap<>();
          for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
            row.put(r.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT), r.getString(i));
          list.add(row);
        }
        return list;
      }
    }
  }

  private static void execute(Connection c, String sql, Object... args) throws SQLException {
    try (var s = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
      s.executeUpdate();
    }
  }

  public static Map<String, List<Map<String, String>>> snapshot(Connection c) throws SQLException {
    Map<String, List<Map<String, String>>> result = new TreeMap<>();
    for (var e : QUERIES.entrySet()) result.put(e.getKey(), rows(c, e.getValue()));
    return result;
  }

  private static String hash(Object value) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value)));
  }

  private static String catalogHash() throws Exception {
    Map<String, JsonNode> inputs = new TreeMap<>();
    for (String name : List.of("permission-catalog-v2.json", "permission-catalog-v3.json"))
      try (var in = PermissionMigrationCli.class.getResourceAsStream("/authorization/" + name)) {
        inputs.put(name, JSON.readTree(Objects.requireNonNull(in)));
      }
    return hash(inputs);
  }

  public static Map<String, Object> report(Connection c) throws Exception {
    var before = snapshot(c);
    var roles = new ArrayList<Map<String, Object>>();
    var sourceIssues = new ArrayList<String>();
    for (var r : before.get("sys_role")) {
      if (r.get("current_revision_id") == null && r.get("draft_revision_id") == null) continue;
      var rev =
          before.get("role_revision").stream()
              .filter(v -> v.get("id").equals(r.get("current_revision_id")))
              .findFirst()
              .orElse(null);
      List<Map<String, Object>> grants =
          rev == null
              ? List.of()
              : JSON.readValue(rev.get("snapshot_json"), new TypeReference<>() {});
      var candidate = V47__business_permission_catalog_v2.shadow(c, grants);
      if (rev != null) {
        Set<String> recorded =
            grants.stream()
                .map(
                    g ->
                        g.get("permission")
                            + "|"
                            + g.get("scope")
                            + "|"
                            + Objects.toString(g.get("packVersion"), ""))
                .collect(java.util.stream.Collectors.toSet());
        Set<String> projected = new HashSet<>();
        for (var g : before.get("role_grant"))
          if (r.get("id").equals(g.get("role_id")) && r.get("org_id").equals(g.get("org_id"))) {
            var code =
                before.get("permission").stream()
                    .filter(p -> p.get("id").equals(g.get("permission_id")))
                    .findFirst()
                    .orElseThrow()
                    .get("code");
            projected.add(
                code
                    + "|"
                    + g.get("scope_type")
                    + "|"
                    + Objects.toString(g.get("pack_version"), ""));
          }
        if (!recorded.equals(projected)
            || !r.get("id").equals(rev.get("role_id"))
            || !r.get("org_id").equals(rev.get("org_id")))
          sourceIssues.add("角色 #" + r.get("id") + " 发布快照与生效投影不一致，须先核对修复");
      }
      var item = new LinkedHashMap<String, Object>();
      item.put("orgId", r.get("org_id"));
      item.put("roleId", r.get("id"));
      item.put("name", r.get("name"));
      item.put("before", grants);
      item.put("candidate", candidate);
      item.put(
          "attendanceDecisionRequired",
          candidate.stream()
              .anyMatch(
                  g ->
                      Set.of("attendance:view", "attendance:manage")
                          .contains(g.get("permission"))));
      item.put(
          "users",
          before.get("sys_user").stream()
              .filter(
                  u ->
                      r.get("id").equals(u.get("role_id"))
                          && r.get("org_id").equals(u.get("org_id")))
              .toList());
      var oldCodes =
          grants.stream()
              .map(g -> g.get("permission").toString())
              .collect(java.util.stream.Collectors.toSet());
      var newCodes =
          candidate.stream()
              .map(g -> g.get("permission").toString())
              .collect(java.util.stream.Collectors.toSet());
      item.put("added", newCodes.stream().filter(p -> !oldCodes.contains(p)).sorted().toList());
      item.put("removed", oldCodes.stream().filter(p -> !newCodes.contains(p)).sorted().toList());
      item.put(
          "highImpact",
          newCodes.stream()
              .filter(
                  p ->
                      p.contains("export")
                          || p.contains("publish")
                          || p.contains("delete")
                          || p.contains("sensitive")
                          || p.equals("attendance:manage")
                          || p.equals("article:manage")
                          || p.equals("kb:index:manage"))
              .sorted()
              .toList());
      List<Map<String, String>> implicit = new ArrayList<>();
      for (var u : before.get("sys_user")) {
        if (!r.get("id").equals(u.get("role_id")) || !r.get("org_id").equals(u.get("org_id")))
          continue;
        for (String source : List.of("article", "activity_plan")) {
          String author = source.equals("article") ? "author_id" : "owner_id";
          Set<String> required =
              source.equals("article")
                  ? Set.of(
                      "content:view",
                      "article:edit",
                      "article:submit",
                      "article:publish",
                      "article:stats",
                      "article:delete")
                  : Set.of("activity:view", "plan:view", "plan:edit", "plan:submit");
          String missing =
              String.join(
                  ",", required.stream().filter(p -> !newCodes.contains(p)).sorted().toList());
          if (!missing.isBlank())
            for (var object : before.get(source))
              if (u.get("id").equals(object.get(author))
                  && r.get("org_id").equals(object.get("org_id")))
                implicit.add(
                    Map.of(
                        "userId",
                        u.get("id"),
                        "source",
                        source,
                        "resourceId",
                        object.get("id"),
                        "missingOperations",
                        missing));
        }
      }
      item.put("implicitAuthorReview", implicit);
      boolean roleActive = "ACTIVE".equals(r.get("status"));
      var pack =
          before.get("org_domain_pack").stream()
              .filter(
                  p ->
                      r.get("org_id").equals(p.get("org_id")) && "party".equals(p.get("pack_code")))
              .findFirst();
      java.util.function.Predicate<Map<String, Object>> effective =
          g ->
              roleActive
                  && (!g.get("permission").toString().startsWith("party:")
                      || pack.filter(
                              p ->
                                  "1".equals(p.get("enabled"))
                                      && Objects.equals(
                                          p.get("activation_version"),
                                          Objects.toString(g.get("packVersion"), null)))
                          .isPresent());
      item.put(
          "effectiveBefore",
          grants.stream()
              .filter(effective)
              .map(g -> g.get("permission"))
              .filter(
                  code ->
                      before.get("permission").stream()
                          .anyMatch(
                              p ->
                                  code.equals(p.get("code"))
                                      && "BUSINESS".equals(p.get("category"))))
              .toList());
      item.put(
          "effectiveCandidate",
          candidate.stream().filter(effective).map(g -> g.get("permission")).toList());
      if (r.get("draft_revision_id") != null) {
        var d =
            before.get("role_revision").stream()
                .filter(v -> v.get("id").equals(r.get("draft_revision_id")))
                .findFirst()
                .orElseThrow();
        item.put(
            "draftCandidate",
            V47__business_permission_catalog_v2.shadow(
                c,
                JSON.readValue(
                    d.get("snapshot_json"), new TypeReference<List<Map<String, Object>>>() {})));
      }
      item.put("note", "候选配置包含旧隐式能力的显式映射；作者、指定审核人及业务状态规则仍独立校验。所有者须逐项核对新增、撤销、范围和考勤决策。");
      roles.add(item);
    }
    var delegations = new ArrayList<Map<String, Object>>();
    for (var delegation : before.get("org_role_delegation")) {
      List<Map<String, Object>> grants =
          JSON.readValue(delegation.get("grantable_json"), new TypeReference<>() {});
      var item = new LinkedHashMap<String, Object>();
      item.put("orgId", delegation.get("org_id"));
      item.put("userId", delegation.get("user_id"));
      item.put("status", delegation.get("status"));
      item.put("expiresAt", delegation.get("expires_at"));
      item.put("version", delegation.get("version"));
      item.put("managementCapabilities", JSON.readTree(delegation.get("capabilities_json")));
      item.put("before", grants);
      item.put("candidate", V47__business_permission_catalog_v2.shadowGrantable(c, grants));
      delegations.add(item);
    }
    if (!hash(before).equals(hash(snapshot(c))))
      throw new IllegalStateException("预检期间数据发生变化，请在停写维护窗口重新生成");
    return Map.of(
        "format",
        1,
        "catalogHash",
        catalogHash(),
        "fingerprint",
        hash(before),
        "before",
        before,
        "roles",
        roles,
        "sourceIssues",
        sourceIssues,
        "delegations",
        delegations,
        "behaviorChanges",
        List.of(
            "所有者须确认角色和委派中的敏感、导出、发布、人工考勤职责；考勤逐角色选择 KEEP/REVOKE。",
            "作者关系不会替代显式新操作权限；implicitAuthorReview 所列对象需要确认受限或先补齐职责后重做报告。",
            "本人排班与考勤仅按账号 ID 认领；历史姓名记录必须先核对关联。",
            "ORG 不解除作者、指定审核人、来源对象和业务状态规则。"));
  }

  private static void table(Connection c) throws SQLException {
    execute(
        c,
        "CREATE TABLE IF NOT EXISTS authorization_v2_release (id VARCHAR(64) PRIMARY KEY,status"
            + " VARCHAR(20) NOT NULL,source_hash VARCHAR(64) NOT NULL,catalog_hash VARCHAR(64) NOT"
            + " NULL,before_json LONGTEXT NOT NULL,approval_json LONGTEXT NOT NULL,after_hash"
            + " VARCHAR(64),created_at TIMESTAMP NOT NULL)");
  }

  /**
   * Approval is an operator-recorded owner attestation, accompanied by an external evidence
   * reference.
   */
  public static String approve(Connection c, JsonNode report, JsonNode decisions) throws Exception {
    var now = snapshot(c);
    if (!report.path("sourceIssues").isArray() || !report.path("sourceIssues").isEmpty())
      throw new IllegalStateException("源授权存在待修复问题，禁止批准切换");
    if (!hash(now).equals(report.path("fingerprint").asText())
        || !catalogHash().equals(report.path("catalogHash").asText())
        || !JSON.valueToTree(report(c)).equals(report))
      throw new IllegalStateException("预检报告已过期或内容被修改，请重新生成并确认");
    if (!decisions.path("acknowledgeBusinessChanges").asBoolean())
      throw new IllegalStateException("须确认全部业务映射与数据范围变化");
    Set<String> orgs = new HashSet<>();
    for (var role : report.path("roles")) {
      String org = role.path("orgId").asText();
      orgs.add(org);
      if (role.path("attendanceDecisionRequired").asBoolean()
          && !Set.of("KEEP", "REVOKE")
              .contains(decisions.path("attendance").path(role.path("roleId").asText()).asText()))
        throw new IllegalStateException("每个存量考勤角色须明确 KEEP 或 REVOKE");
    }
    for (String org : orgs) {
      var approval = decisions.path("owners").path(org);
      String uid = approval.path("userId").asText();
      if (approval.path("evidence").asText().isBlank())
        throw new IllegalStateException("缺少所有者确认依据");
      var o =
          now.get("organization").stream()
              .filter(r -> org.equals(r.get("id")))
              .findFirst()
              .orElseThrow();
      if (!uid.equals(o.get("owner_user_id"))
          || !"1".equals(o.get("status"))
          || !"0".equals(o.get("deleted"))
          || now.get("sys_user").stream()
              .noneMatch(
                  u ->
                      uid.equals(u.get("id"))
                          && org.equals(u.get("org_id"))
                          && "1".equals(u.get("status"))
                          && "0".equals(u.get("deleted"))))
        throw new IllegalStateException("所有者已变化或账号无效");
    }
    table(c);
    String id = UUID.randomUUID().toString();
    execute(c, "UPDATE authorization_v2_release SET status='SUPERSEDED' WHERE status='APPROVED'");
    execute(
        c,
        "INSERT INTO"
            + " authorization_v2_release(id,status,source_hash,catalog_hash,before_json,approval_json,created_at)"
            + " VALUES(?,'APPROVED',?,?,?,?,CURRENT_TIMESTAMP)",
        id,
        hash(now),
        catalogHash(),
        JSON.writeValueAsString(now),
        decisions.toString());
    return id;
  }

  public static Map<String, Object> requireApproval(Connection c) throws Exception {
    if (rows(
            c,
            "SELECT id FROM sys_role WHERE current_revision_id IS NOT NULL OR draft_revision_id IS"
                + " NOT NULL")
        .isEmpty()) return Map.of();
    boolean exists;
    try (var tables =
        c.getMetaData()
            .getTables(
                c.getCatalog(),
                c.getSchema(),
                "authorization_v2_release",
                new String[] {"TABLE"})) {
      exists = tables.next();
    }
    if (!exists)
      throw new IllegalStateException(
          "权限升级尚未获批：先运行 PermissionMigrationCli report 和 approve，禁止直接切换存量授权");
    var list = rows(c, "SELECT * FROM authorization_v2_release WHERE status='APPROVED'");
    if (list.size() != 1) throw new IllegalStateException("缺少唯一有效的升级批准记录");
    var r = list.getFirst();
    if (!r.get("source_hash").equals(hash(snapshot(c)))
        || !r.get("catalog_hash").equals(catalogHash()))
      throw new IllegalStateException("批准后人员或授权发生变化，请重新预检确认");
    return Map.of("id", r.get("id"), "decisions", JSON.readTree(r.get("approval_json")));
  }

  public static void cutoverComplete(Connection c, Map<String, Object> approval) throws Exception {
    if (!approval.isEmpty())
      execute(
          c,
          "UPDATE authorization_v2_release SET status='APPLIED',after_hash=? WHERE id=?",
          hash(snapshot(c)),
          approval.get("id"));
  }

  /**
   * Authorization projection rollback only. Historical published snapshots are deliberately
   * retained.
   */
  public static void rollback(Connection c, String id) throws Exception {
    table(c);
    c.setAutoCommit(false);
    try {
      rows(c, "SELECT id FROM organization ORDER BY id FOR UPDATE");
      var release =
          rows(c, "SELECT * FROM authorization_v2_release WHERE id=? FOR UPDATE", id).getFirst();
      if (!"APPLIED".equals(release.get("status"))
          || !Objects.equals(release.get("after_hash"), hash(snapshot(c))))
        throw new IllegalStateException("切换后授权或人员已变化，禁止覆盖回退；请人工核对备份");
      Map<String, List<Map<String, String>>> before =
          JSON.readValue(release.get("before_json"), new TypeReference<>() {});
      for (var r : before.get("sys_role")) {
        var cols = new ArrayList<>(r.keySet());
        cols.remove("id");
        var args = new ArrayList<Object>();
        for (String col : cols) args.add(r.get(col));
        args.add(r.get("id"));
        execute(
            c,
            "UPDATE sys_role SET "
                + String.join(",", cols.stream().map(col -> col + "=?").toList())
                + " WHERE id=?",
            args.toArray());
      }
      for (String name :
          List.of("role_grant", "org_role_delegation", "role_template_grant", "role_template"))
        execute(c, "DELETE FROM " + name);
      for (String name :
          List.of("role_template", "role_template_grant", "role_grant", "org_role_delegation")) {
        for (var row : before.get(name)) {
          var cols = new ArrayList<>(row.keySet());
          var args = new ArrayList<Object>();
          for (String col : cols) args.add(row.get(col));
          execute(
              c,
              "INSERT INTO "
                  + name
                  + "("
                  + String.join(",", cols)
                  + ") VALUES("
                  + String.join(",", Collections.nCopies(cols.size(), "?"))
                  + ")",
              args.toArray());
        }
      }
      execute(c, "UPDATE permission SET category='LEGACY'");
      for (var p : before.get("permission"))
        execute(c, "UPDATE permission SET category=? WHERE id=?", p.get("category"), p.get("id"));
      execute(c, "UPDATE organization SET authz_version=authz_version+1");
      execute(c, "UPDATE org_authorization_change SET status='EXPIRED' WHERE status='PREVIEWED'");
      execute(c, "UPDATE authorization_v2_release SET status='ROLLED_BACK' WHERE id=?", id);
      c.commit();
    } catch (Exception e) {
      c.rollback();
      throw e;
    } finally {
      c.setAutoCommit(true);
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 2)
      throw new IllegalArgumentException(
          "report <输出.json> | approve <报告.json> <决策.json> | rollback <releaseId>；仅在停写维护窗口运行");
    try (var c =
        DriverManager.getConnection(
            Objects.requireNonNull(System.getenv("PAMS_MIGRATION_URL")),
            Objects.requireNonNull(System.getenv("PAMS_MIGRATION_USER")),
            Objects.requireNonNull(System.getenv("PAMS_MIGRATION_PASSWORD")))) {
      switch (args[0]) {
        case "report" ->
            Files.writeString(
                Path.of(args[1]),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report(c)),
                StandardOpenOption.CREATE_NEW);
        case "approve" -> {
          if (args.length != 3) throw new IllegalArgumentException("缺少决策文件");
          System.out.println(
              approve(
                  c,
                  JSON.readTree(Files.readString(Path.of(args[1]))),
                  JSON.readTree(Files.readString(Path.of(args[2])))));
        }
        case "rollback" -> rollback(c, args[1]);
        default -> throw new IllegalArgumentException("未知操作");
      }
    }
  }
}
