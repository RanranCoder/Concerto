package com.pams.module.permission.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.BizException;
import com.pams.security.LoginUser;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-bound authorization. No write operation resolves a role by a global role code. */
@Service
public class TenantAuthorizationService {
  public static final Set<String> GOVERNANCE =
      Set.of(
          "role:view",
          "role:manage",
          "role:grant",
          "role:assign",
          "user:view",
          "user:manage",
          "org:manage",
          "org:structure:manage",
          "org:invite",
          "org:audit:view",
          "member:import_account");
  private final JdbcTemplate db;
  private final PermissionCatalog vocabulary;
  private final org.springframework.context.ApplicationEventPublisher events;
  private final ObjectMapper json = new ObjectMapper();

  public TenantAuthorizationService(
      JdbcTemplate db,
      PermissionCatalog vocabulary,
      org.springframework.context.ApplicationEventPublisher events) {
    this.db = db;
    this.vocabulary = vocabulary;
    this.events = events;
  }

  private long number(Object n) {
    return n == null ? 0 : ((Number) n).longValue();
  }

  private List<Map<String, Object>> clientRows(List<Map<String, Object>> rows) {
    Map<String, String> labels =
        Map.of(
            "usercount",
            "userCount",
            "currentrevisionid",
            "currentRevisionId",
            "draftrevisionid",
            "draftRevisionId",
            "realname",
            "realName",
            "deptid",
            "deptId",
            "createdby",
            "createdBy",
            "publishedat",
            "publishedAt",
            "actorid",
            "actorId",
            "targetid",
            "targetId",
            "createdat",
            "createdAt");
    return rows.stream()
        .map(
            row -> {
              Map<String, Object> result = new LinkedHashMap<>();
              row.forEach(
                  (key, value) ->
                      result.put(
                          labels.getOrDefault(key.toLowerCase(Locale.ROOT), key),
                          value instanceof Timestamp t ? t.toLocalDateTime().toString() : value));
              for (var alias :
                  Map.of(
                          "permissioncount",
                          "permissionCount",
                          "updatedat",
                          "updatedAt",
                          "sourcekind",
                          "sourceKind",
                          "actorname",
                          "actorName",
                          "targetname",
                          "targetName",
                          "rolename",
                          "roleName")
                      .entrySet())
                if (result.containsKey(alias.getKey()))
                  result.put(alias.getValue(), result.remove(alias.getKey()));
              for (var a :
                  Map.of(
                          "deptname",
                          "deptName",
                          "managementsource",
                          "managementSource",
                          "updatedbyname",
                          "updatedByName",
                          "expiresat",
                          "expiresAt")
                      .entrySet())
                if (result.containsKey(a.getKey()))
                  result.put(a.getValue(), result.remove(a.getKey()));
              for (var alias :
                  Map.of(
                          "catalogversion",
                          "catalogVersion",
                          "changeid",
                          "changeId",
                          "targettype",
                          "targetType")
                      .entrySet())
                if (result.containsKey(alias.getKey()))
                  result.put(alias.getValue(), result.remove(alias.getKey()));
              return result;
            })
        .toList();
  }

  private Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw new BizException(404, "对象不存在或不可见");
    return rows.getFirst();
  }

  private long insert(String sql, Object... args) {
    var keys = new GeneratedKeyHolder();
    db.update(
        c -> {
          var s = c.prepareStatement(sql, new String[] {"id"});
          for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
          return s;
        },
        keys);
    return Objects.requireNonNull(keys.getKey()).longValue();
  }

  private Map<String, Object> roleImpact(long org, long id) {
    var role = role(org, id);
    return Map.of(
        "userCount",
            db.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE org_id=? AND role_id=? AND deleted=0",
                Long.class,
                org,
                id),
        "activeUserCount",
            db.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE org_id=? AND role_id=? AND deleted=0 AND"
                    + " status=1",
                Long.class,
                org,
                id),
        "positions",
            db.queryForList(
                "SELECT id,name FROM org_position WHERE org_id=? AND role_code=?",
                org,
                role.get("code")),
        "invitations",
            db.queryForObject(
                "SELECT COUNT(*) FROM org_invitation WHERE org_id=? AND role_code=? AND used_by IS"
                    + " NULL AND (expire_at IS NULL OR expire_at>CURRENT_TIMESTAMP)",
                Long.class,
                org,
                role.get("code")));
  }

  private Map<String, Object> roleActions(long uid, Map<String, Object> role) {
    String reason = "";
    try {
      protectRole(uid, number(role.get("id")));
    } catch (BizException e) {
      reason = e.getMessage();
    }
    boolean archived = "ARCHIVED".equals(role.get("status"));
    var caps = capabilities(uid);
    return Map.of(
        "canEdit",
        !archived && reason.isEmpty() && caps.contains("role:manage"),
        "canGrant",
        !archived && reason.isEmpty() && caps.contains("role:grant"),
        "reason",
        archived ? "归档角色只可查看或复制" : reason);
  }

  public List<Map<String, Object>> migrationReviews() {
    long uid = actor("role:view");
    return clientRows(
        db.queryForList(
            "SELECT m.*,r.name AS roleName FROM authorization_migration_review m JOIN sys_role r ON"
                + " r.id=m.role_id AND r.org_id=m.org_id WHERE m.org_id=? ORDER BY"
                + " m.review_required DESC,m.role_id",
            org(uid)));
  }

  public Map<String, Object> auditPage(
      int page, int size, String keyword, String action, String from, String to) {
    long uid = actor("org:audit:view");
    page = Math.max(1, page);
    size = Math.max(1, Math.min(100, size));
    String filter =
        " WHERE org_id=? AND (?='' OR action=?) AND (?='' OR actor_name LIKE ? OR target_name LIKE"
            + " ?)";
    String q = keyword == null ? "" : keyword.trim(), a = action == null ? "" : action;
    var args = new ArrayList<Object>(List.of(org(uid), a, a, q, "%" + q + "%", "%" + q + "%"));
    try {
      if (from != null && !from.isBlank()) {
        filter += " AND created_at>=?";
        args.add(Timestamp.valueOf(LocalDate.parse(from).atStartOfDay()));
      }
      if (to != null && !to.isBlank()) {
        filter += " AND created_at<?";
        args.add(Timestamp.valueOf(LocalDate.parse(to).plusDays(1).atStartOfDay()));
      }
    } catch (java.time.format.DateTimeParseException invalid) {
      throw new BizException(400, "日期须为 yyyy-MM-dd");
    }
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM org_audit_log" + filter, Long.class, args.toArray());
    args.add(size);
    args.add((long) (page - 1) * size);
    var rows =
        db.queryForList(
            "SELECT id,actor_id AS actorId,actor_name AS actorName,action,target_id AS"
                + " targetId,target_name AS targetName,detail_json AS"
                + " details,outcome,reason,target_type AS targetType,change_id AS"
                + " changeId,created_at AS createdAt FROM org_audit_log"
                + filter
                + " ORDER BY id DESC LIMIT ? OFFSET ?",
            args.toArray());
    return Map.of("items", clientRows(rows), "total", total);
  }

  public Map<String, Object> managementSummary() {
    long uid = actor("role:view");
    var result = new LinkedHashMap<String, Object>();
    result.put("isOwner", owner(uid));
    result.put("capabilities", capabilities(uid));
    result.put("grantable", grantable(uid));
    result.put(
        "administrators",
        owner(uid)
            ? clientRows(
                db.queryForList(
                    "SELECT d.user_id AS id,u.real_name AS realName,d.status,d.expires_at AS"
                        + " expiresAt,d.version,d.updated_at AS updatedAt,a.real_name AS"
                        + " updatedByName FROM org_role_delegation d LEFT JOIN sys_user a ON"
                        + " a.id=d.updated_by AND a.org_id=d.org_id JOIN sys_user u ON"
                        + " u.id=d.user_id AND u.org_id=d.org_id WHERE d.org_id=? AND u.deleted=0",
                    org(uid)))
            : List.of());
    result.put("ownDelegation", delegationInfo(org(uid), uid));
    return result;
  }

  @Transactional
  public Map<String, Object> previewDelegation(
      long target, boolean active, Set<String> allowed, LocalDateTime expires, long version) {
    return previewDelegation(target, active, allowed, expires, version, null);
  }

  @Transactional
  public Map<String, Object> previewDelegation(
      long target,
      boolean active,
      Set<String> allowed,
      LocalDateTime expires,
      long version,
      String reason) {
    reason = normalizeReason(reason);
    long uid = actor("role:grant");
    long org = org(uid);
    lockOrg(org);
    if (!owner(uid)) throw new BizException(403, "仅所有者可维护管理员");
    if (target == uid || org != number(identity(target).get("org_id")) || !valid(target))
      throw new BizException(400, "请选择本组织其他有效账号");
    var before = delegation(target);
    if (number(before.get("version")) != version) throw new BizException(409, "管理员配置已变化，请重新读取");
    if (allowed == null || !grantable(uid).containsAll(allowed))
      throw new BizException(403, "包含不可授予操作");
    if (expires != null && !expires.isAfter(LocalDateTime.now()))
      throw new BizException(400, "有效期必须晚于当前时间");
    var payload = new LinkedHashMap<String, Object>();
    payload.put("active", active);
    payload.put("grantable", allowed);
    payload.put("expiresAt", expires == null ? null : expires.toString());
    payload.put("version", version);
    payload.put("delegationBefore", before);
    payload.put("reason", reason);
    return storePreview(uid, "DELEGATE", target, payload);
  }

  private String encode(Object v) {
    try {
      return json.writeValueAsString(v);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private <T> T decode(Object v, TypeReference<T> type) {
    try {
      return json.readValue(v.toString(), type);
    } catch (Exception e) {
      throw new IllegalStateException("授权配置格式错误", e);
    }
  }

  private List<Map<String, Object>> grants(Object v) {
    return decode(v, new TypeReference<>() {});
  }

  private Set<String> strings(Object v) {
    return v == null ? Set.of() : decode(v, new TypeReference<LinkedHashSet<String>>() {});
  }

  private String normalizeReason(String reason) {
    String value = reason == null ? "" : reason.trim();
    if (value.length() > 500) throw new BizException(400, "变更原因最多 500 字");
    return value;
  }

  private void audit(long org, long actor, String action, long target, Object detail) {
    long id =
        insert(
            "INSERT INTO org_audit_log(org_id,actor_id,action,target_id,detail_json,created_at)"
                + " VALUES(?,?,?,?,?,CURRENT_TIMESTAMP)",
            org,
            actor,
            action,
            target,
            encode(detail));
    if (Set.of("ASSIGN", "DELEGATE", "PUBLISH", "ENABLE", "DISABLE", "ARCHIVE").contains(action))
      events.publishEvent(new com.pams.module.notification.event.GovernanceNoticeEvent(org,id,actor,action,target));
    String actorName =
        db
            .queryForList(
                "SELECT COALESCE(real_name,username) FROM sys_user WHERE id=? AND org_id=?",
                String.class,
                actor,
                org)
            .stream()
            .findFirst()
            .orElse("系统迁移");
    boolean userTarget = Set.of("ASSIGN", "DELEGATE").contains(action);
    var names =
        db.queryForList(
            userTarget
                ? "SELECT real_name FROM sys_user WHERE id=? AND org_id=?"
                : "SELECT name FROM sys_role WHERE id=? AND org_id=?",
            String.class,
            target,
            org);
    db.update(
        "UPDATE org_audit_log SET actor_name=?,target_name=?,target_type=?,reason=?,change_id=?"
            + " WHERE id=? AND org_id=?",
        actorName,
        names.isEmpty() ? "历史对象 #" + target : names.getFirst(),
        userTarget ? "USER" : "ROLE",
        detail instanceof Map<?, ?> m ? m.get("reason") : null,
        detail instanceof Map<?, ?> m ? m.get("changeId") : null,
        id,
        org);
  }

  public Map<String, Object> identity(long uid) {
    return one(
        "SELECT u.id,u.org_id,u.role_id,u.dept_id,u.status,u.deleted,o.status AS"
            + " org_status,o.deleted AS org_deleted,o.owner_user_id,o.authz_version FROM sys_user u"
            + " JOIN organization o ON o.id=u.org_id WHERE u.id=?",
        uid);
  }

  public boolean valid(long uid) {
    try {
      var u = identity(uid);
      boolean result =
          number(u.get("status")) == 1
              && number(u.get("deleted")) == 0
              && number(u.get("org_status")) == 1
              && number(u.get("org_deleted")) == 0;
      return result;
    } catch (BizException e) {
      return false;
    }
  }

  public boolean owner(long uid) {
    var u = identity(uid);
    return valid(uid) && number(u.get("owner_user_id")) == uid;
  }

  public Set<String> capabilities(long uid) {
    if (!valid(uid)) return Set.of();
    var u = identity(uid);
    if (number(u.get("owner_user_id")) == uid) return GOVERNANCE;
    var rows =
        db.queryForList(
            "SELECT capabilities_json FROM org_role_delegation WHERE org_id=? AND user_id=? AND"
                + " status='ACTIVE' AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)",
            u.get("org_id"),
            uid);
    return rows.isEmpty()
        ? Set.of()
        : strings(rows.getFirst().get("capabilities_json")).stream()
            .filter(GOVERNANCE::contains)
            .collect(Collectors.toSet());
  }

  private boolean packActive(long org, String code, Object version) {
    if (!code.startsWith("party:")) return true;
    var rows =
        db.queryForList(
            "SELECT enabled,activation_version FROM org_domain_pack WHERE org_id=? AND"
                + " pack_code='party'",
            org);
    return !rows.isEmpty()
        && number(rows.getFirst().get("enabled")) == 1
        && number(rows.getFirst().get("activation_version")) == number(version);
  }

  public Set<String> business(long uid) {
    if (!valid(uid)) return Set.of();
    var u = identity(uid);
    return rolePermissions(number(u.get("org_id")), number(u.get("role_id")));
  }

  public Set<String> rolePermissions(long org, long role) {
    var rows =
        db.queryForList(
            "SELECT p.code,g.pack_version FROM role_grant g JOIN permission p ON"
                + " p.id=g.permission_id JOIN sys_role r ON r.id=g.role_id AND r.org_id=g.org_id"
                + " WHERE g.org_id=? AND g.role_id=? AND r.status='ACTIVE' AND"
                + " p.category='BUSINESS'",
            org,
            role);
    return rows.stream()
        .filter(r -> packActive(org, r.get("code").toString(), r.get("pack_version")))
        .map(r -> r.get("code").toString())
        .collect(Collectors.toSet());
  }

  public Set<String> authorities(long uid) {
    var all = new HashSet<>(business(uid));
    all.addAll(capabilities(uid));
    return all;
  }

  public com.pams.module.agent.core.ToolContext refresh(
      com.pams.module.agent.core.ToolContext ctx) {
    if (ctx == null || ctx.getUserId() == null || !valid(ctx.getUserId()))
      throw new BizException(403, "工具发起人身份已失效");
    var u =
        one(
            "SELECT u.*,r.code AS role_code,r.level AS role_level,d.name AS dept_name FROM sys_user"
                + " u JOIN sys_role r ON r.id=u.role_id AND r.org_id=u.org_id LEFT JOIN"
                + " sys_department d ON d.id=u.dept_id AND d.org_id=u.org_id WHERE u.id=?",
            ctx.getUserId());
    var principal =
        new LoginUser(
            ctx.getUserId(),
            u.get("username").toString(),
            Objects.toString(u.get("real_name"), ""),
            u.get("role_code").toString(),
            (int) number(u.get("role_level")),
            u.get("dept_id") == null ? null : number(u.get("dept_id")),
            (String) u.get("dept_name"),
            number(u.get("org_id")));
    return new com.pams.module.agent.core.ToolContext(
            principal,
            authorities(ctx.getUserId()).stream()
                .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                .toList(),
            ctx.getConversationId(),
            ctx.getStreamId(),
            ctx.getMode())
        .withRunId(ctx.getRunId())
        .withWorkItem(ctx.getWorkItemId(), ctx.getWorkItemRevision())
        .withRequestId(ctx.getRequestId())
        .withAttempt(ctx.getAttempt())
        .withTimezone(ctx.getTimezone())
        .withPromptBundle(ctx.getPromptBundleJson());
  }

  public Set<String> grantable(long uid) {
    if (owner(uid))
      return db
          .queryForList("SELECT code FROM permission WHERE category='BUSINESS'", String.class)
          .stream()
          .filter(
              c ->
                  !c.startsWith("party:")
                      || currentPackVersion(number(identity(uid).get("org_id"))) > 0)
          .collect(Collectors.toSet());
    var u = identity(uid);
    var rows =
        db.queryForList(
            "SELECT grantable_json FROM org_role_delegation WHERE org_id=? AND user_id=? AND"
                + " status='ACTIVE' AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)",
            u.get("org_id"),
            uid);
    if (rows.isEmpty()) return Set.of();
    var entries = grants(rows.getFirst().get("grantable_json"));
    long org = number(u.get("org_id"));
    return entries.stream()
        .filter(g -> packActive(org, g.get("permission").toString(), g.get("packVersion")))
        .map(g -> g.get("permission").toString())
        .collect(Collectors.toSet());
  }

  public long currentPackVersion(long org) {
    var rows =
        db.queryForList(
            "SELECT activation_version FROM org_domain_pack WHERE org_id=? AND pack_code='party'"
                + " AND enabled=1",
            Long.class,
            org);
    return rows.isEmpty() ? 0 : rows.getFirst();
  }

  private long actor(String capability) {
    var a = SecurityContextHolder.getContext().getAuthentication();
    if (a == null || !(a.getPrincipal() instanceof LoginUser u) || !valid(u.getId()))
      throw new BizException(401, "登录身份已失效");
    if (!Objects.equals(number(identity(u.getId()).get("org_id")), u.getOrgId()))
      throw new BizException(401, "组织身份已变化");
    if (!capabilities(u.getId()).contains(capability))
      throw new BizException(403, "没有管理权限：" + capability);
    return u.getId();
  }

  private long org(long uid) {
    return number(identity(uid).get("org_id"));
  }

  private Map<String, Object> role(long org, long id) {
    return one("SELECT * FROM sys_role WHERE id=? AND org_id=?", id, org);
  }

  private void lockOrg(long org) {
    one("SELECT id FROM organization WHERE id=? FOR UPDATE", org);
  }

  private void bump(long org) {
    db.update("UPDATE organization SET authz_version=authz_version+1 WHERE id=?", org);
    events.publishEvent(
        new AuthorizationChanged(
            org,
            db.queryForObject(
                "SELECT authz_version FROM organization WHERE id=?", Long.class, org)));
  }

  private void protectRole(long uid, long role) {
    if (owner(uid)) return;
    var protectedUsers =
        db.queryForList(
            "SELECT u.id FROM sys_user u LEFT JOIN org_role_delegation d ON d.org_id=u.org_id AND"
                + " d.user_id=u.id AND d.status='ACTIVE' AND (d.expires_at IS NULL OR"
                + " d.expires_at>CURRENT_TIMESTAMP) JOIN organization o ON o.id=u.org_id WHERE"
                + " u.org_id=? AND u.role_id=? AND u.deleted=0 AND (u.id=? OR u.id=o.owner_user_id"
                + " OR d.user_id IS NOT NULL)",
            org(uid),
            role,
            uid);
    if (!protectedUsers.isEmpty()) throw new BizException(403, "此角色被本人、所有者或其他管理员使用，需所有者处理");
  }

  private void protectUser(long uid, long target) {
    var u = identity(target);
    if (org(uid) != number(u.get("org_id")) || number(u.get("deleted")) != 0)
      throw new BizException(404, "用户不存在");
    if (!owner(uid)
        && (target == uid
            || number(u.get("owner_user_id")) == target
            || hasActiveDelegation(number(u.get("org_id")), target)))
      throw new BizException(403, "不能修改本人、所有者或其他管理员");
  }

  // A disabled administrator retains a delegation. Ordinary account managers must not reactivate
  // it.
  private boolean hasActiveDelegation(long organization, long target) {
    return db
        .queryForList(
            "SELECT capabilities_json FROM org_role_delegation WHERE org_id=? AND user_id=?"
                + " AND status='ACTIVE' AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)",
            organization,
            target)
        .stream()
        .anyMatch(
            row -> strings(row.get("capabilities_json")).stream().anyMatch(GOVERNANCE::contains));
  }

  public Map<String, Object> me(long uid) {
    var u = identity(uid);
    return Map.of(
        "businessPermissions",
        business(uid),
        "managementCapabilities",
        capabilities(uid),
        "isOwner",
        owner(uid),
        "authzVersion",
        u.get("authz_version"),
        "roleId",
        u.get("role_id"));
  }

  public Map<String, Object> effective(long target) {
    long uid = actor("role:view");
    var identity = identity(target);
    if (org(uid) != number(identity.get("org_id")) || number(identity.get("deleted")) != 0)
      throw new BizException(404, "用户不存在");
    var result = new LinkedHashMap<>(me(target));
    var role = role(org(uid), number(identity.get("role_id")));
    var configured = published(role);
    var effective = business(target);
    result.put(
        "configured",
        configured.stream()
            .map(
                g -> {
                  String code = g.get("permission").toString();
                  return Map.of(
                      "permission",
                      code,
                      "effective",
                      effective.contains(code),
                      "reason",
                      effective.contains(code)
                          ? ""
                          : !valid(target)
                              ? "账号或组织已停用"
                              : !"ACTIVE".equals(role.get("status"))
                                  ? "业务角色未启用"
                                  : !packActive(org(uid), code, g.get("packVersion"))
                                      ? "领域包未启用或授权批次已失效"
                                      : "授权未生效");
                })
            .toList());
    result.put("roleName", role.get("name"));
    result.put(
        "managementSource",
        owner(target) ? "OWNER" : capabilities(target).isEmpty() ? "NONE" : "DELEGATED");
    return result;
  }

  public Map<String, Object> change(String id) {
    long uid = actor("role:view");
    var row =
        one(
            "SELECT * FROM org_authorization_change WHERE id=? AND org_id=? AND actor_id=?",
            id,
            org(uid),
            uid);
    var result = new LinkedHashMap<String, Object>();
    result.put("id", id);
    result.put("status", row.get("status"));
    result.put("expiresAt", com.pams.common.JdbcDateTimes.local(row.get("expires_at")).toString());
    if (row.get("result_json") != null)
      result.put(
          "result", decode(row.get("result_json"), new TypeReference<Map<String, Object>>() {}));
    return result;
  }

  public void verifyUserMutation(long target) {
    long uid = actor("user:manage");
    lockOrg(org(uid));
    actor("user:manage");
    protectUser(uid, target);
    if (number(identity(target).get("owner_user_id")) == target)
      throw new BizException(403, "所有者须通过专用流程处理");
  }

  public void userChanged(long organization) {
    lockOrg(organization);
    bump(organization);
  }

  public void invalidatePendingAccountCredentials(long userId) {
    db.update(
        "UPDATE member_account_credential SET token_hash=NULL,expires_at=NULL WHERE user_id=? AND"
            + " consumed_at IS NULL",
        userId);
  }

  public boolean pendingAccountSetup(long userId) {
    return db.queryForObject(
            "SELECT COUNT(*) FROM member_account_credential c JOIN sys_user u ON u.id=c.user_id AND"
                + " u.org_id=c.org_id WHERE c.user_id=? AND c.consumed_at IS NULL AND u.status=0"
                + " AND u.deleted=0 AND u.password=c.pending_password_hash",
            Integer.class,
            userId)
        > 0;
  }

  /**
   * Account directory metadata, scoped to the current organization; role names imply no governance.
   */
  public Map<Long, String> accountManagementSources() {
    Long organization = com.pams.common.OrgContext.get();
    if (organization == null) throw new BizException(403, "缺少组织上下文");
    Map<Long, String> result = new LinkedHashMap<>();
    for (var row :
        db.queryForList(
            "SELECT u.id,o.owner_user_id,u.status,d.capabilities_json FROM sys_user u JOIN"
                + " organization o ON o.id=u.org_id LEFT JOIN org_role_delegation d ON"
                + " d.org_id=u.org_id AND d.user_id=u.id AND d.status='ACTIVE' AND (d.expires_at IS"
                + " NULL OR d.expires_at>CURRENT_TIMESTAMP) WHERE u.org_id=? AND u.deleted=0",
            organization)) {
      long id = number(row.get("id"));
      boolean delegated =
          strings(row.get("capabilities_json")).stream().anyMatch(GOVERNANCE::contains);
      result.put(
          id,
          row.get("owner_user_id") != null && number(row.get("owner_user_id")) == id
              ? "OWNER"
              : delegated ? "DELEGATED" : "NONE");
    }
    return result;
  }

  public Set<Long> pendingAccountIds() {
    Long organization = com.pams.common.OrgContext.get();
    if (organization == null) throw new BizException(403, "缺少组织上下文");
    return new LinkedHashSet<>(
        db.queryForList(
            "SELECT DISTINCT u.id FROM member_account_credential c JOIN sys_user u ON"
                + " u.id=c.user_id AND u.org_id=c.org_id WHERE u.org_id=? AND u.status=0 AND"
                + " u.deleted=0 AND c.consumed_at IS NULL AND u.password=c.pending_password_hash",
            Long.class,
            organization));
  }

  public void verifyAssignableRole(long roleId) {
    long uid = actor("role:assign");
    lockOrg(org(uid));
    actor("role:assign");
    var r = role(org(uid), roleId);
    if (!"ACTIVE".equals(r.get("status"))
        || !grantable(uid).containsAll(rolePermissions(org(uid), roleId)))
      throw new BizException(403, "该角色不可分配");
  }

  public record AccountImportAuthority(long actorId, long orgId, long version) {}

  /** Recheck live governance after taking the same organization lock as role changes. */
  public AccountImportAuthority verifyAccountImport() {
    long uid = actor("member:import_account");
    long organization = org(uid);
    lockOrg(organization);
    actor("member:import_account");
    actor("user:manage");
    actor("role:assign");
    if (!Objects.equals(com.pams.common.OrgContext.get(), organization))
      throw new BizException(403, "组织上下文不匹配");
    return new AccountImportAuthority(
        uid, organization, number(identity(uid).get("authz_version")));
  }

  public record AccountRolePreview(
      long id, String name, long version, List<String> permissions, List<String> permissionNames) {}

  public AccountRolePreview accountRolePreview(long roleId) {
    verifyAssignableRole(roleId);
    long uid = actor("role:assign");
    var r = role(org(uid), roleId);
    var permissions = rolePermissions(org(uid), roleId).stream().sorted().toList();
    var names =
        db.queryForList("SELECT code,name FROM permission").stream()
            .collect(
                Collectors.toMap(p -> p.get("code").toString(), p -> p.get("name").toString()));
    return new AccountRolePreview(
        roleId,
        r.get("name").toString(),
        number(r.get("config_version")),
        permissions,
        permissions.stream().map(code -> names.getOrDefault(code, code)).toList());
  }

  public void accountBatchAudit(
      AccountImportAuthority authority, String batchId, String action, List<Long> users) {
    audit(
        authority.orgId(),
        authority.actorId(),
        action,
        0,
        Map.of("batchId", batchId, "userIds", users));
    if ("MEMBER_ACCOUNT_CREATE".equals(action)) bump(authority.orgId());
  }

  @Transactional
  public void markProvisioned(long org) {
    db.update("INSERT INTO authorization_bootstrap(org_id) VALUES(?)", org);
  }

  public List<Map<String, Object>> catalog() {
    long uid = actor("role:view");
    Set<String> allowed = grantable(uid);
    Set<String> known =
        new HashSet<>(
            db.queryForList("SELECT code FROM permission WHERE category='BUSINESS'", String.class));
    return db
        .queryForList(
            "SELECT id,code,name,module FROM permission WHERE category='BUSINESS' ORDER BY"
                + " module,sort_order,id")
        .stream()
        .map(
            p -> {
              p.put("supportedScopes", List.of("ORG"));
              p.putAll(
                  vocabulary.describe(p.get("code").toString(), p.get("module").toString(), known));
              if (p.get("code").toString().startsWith("party:"))
                p.put("packVersion", currentPackVersion(org(uid)));
              p.put("grantable", allowed.contains(p.get("code")));
              boolean packDisabled =
                  p.get("code").toString().startsWith("party:")
                      && currentPackVersion(org(uid)) == 0;
              p.put(
                  "reasonCode",
                  allowed.contains(p.get("code"))
                      ? ""
                      : "" + (packDisabled ? "PACK_DISABLED" : "GRANT_CEILING"));
              p.put(
                  "reason",
                  allowed.contains(p.get("code"))
                      ? ""
                      : packDisabled ? "党建领域包未启用" : "超出当前管理员可授权清单");
              return p;
            })
        .toList();
  }

  public List<Map<String, Object>> templates() {
    actor("role:view");
    return db.queryForList(
        "SELECT code,name,template_version AS version FROM role_template ORDER BY code");
  }

  @Transactional
  public void metadata(long id, long version, String name, String description) {
    long uid = actor("role:manage"), org = org(uid);
    lockOrg(org);
    actor("role:manage");
    var r = role(org, id);
    protectRole(uid, id);
    if ("ARCHIVED".equals(r.get("status"))) throw new BizException(409, "归档角色不可编辑");
    if (name == null
        || name.isBlank()
        || name.trim().length() > 100
        || description != null && description.length() > 500)
      throw new BizException(400, "名称或说明长度不合法");
    if (db.queryForObject(
            "SELECT COUNT(*) FROM sys_role WHERE org_id=? AND name=? AND id<>?",
            Integer.class,
            org,
            name.trim(),
            id)
        > 0) throw new BizException(409, "本组织已存在同名角色");
    if (db.update(
            "UPDATE sys_role SET"
                + " name=?,description=?,updated_at=CURRENT_TIMESTAMP,config_version=config_version+1"
                + " WHERE org_id=? AND id=? AND config_version=?",
            name.trim(),
            description,
            org,
            id,
            version)
        != 1) throw new BizException(409, "角色已被其他管理员修改");
    db.update("UPDATE sys_role SET updated_by=? WHERE org_id=? AND id=?", uid, org, id);
    bump(org);
    audit(org, uid, "UPDATE_ROLE", id, Map.of("name", name.trim()));
  }

  @Transactional
  public void deleteDraft(long id) {
    long uid = actor("role:manage"), org = org(uid);
    lockOrg(org);
    actor("role:manage");
    var r = role(org, id);
    if (!"DRAFT".equals(r.get("status")) || r.get("current_revision_id") != null)
      throw new BizException(409, "仅未发布草稿可删除");
    protectRole(uid, id);
    if (db.queryForObject(
            "SELECT COUNT(*) FROM sys_user WHERE org_id=? AND role_id=? AND deleted=0",
            Long.class,
            org,
            id)
        > 0) throw new BizException(409, "角色仍有关联账号，请先替换其角色");
    noReferences(org, id);
    db.update("DELETE FROM role_revision WHERE org_id=? AND role_id=? AND state='DRAFT'", org, id);
    db.update("DELETE FROM sys_role WHERE org_id=? AND id=?", org, id);
    bump(org);
    audit(org, uid, "DELETE_DRAFT", id, Map.of("name", r.get("name")));
  }

  private void draftVersion(Map<String, Object> r, long version, long draftVersion) {
    if (number(r.get("config_version")) != version) throw new BizException(409, "角色版本已变化");
    long actual =
        r.get("draft_revision_id") == null
            ? 0
            : db.queryForObject(
                "SELECT draft_version FROM role_revision WHERE id=? AND org_id=?",
                Long.class,
                r.get("draft_revision_id"),
                r.get("org_id"));
    if (actual != draftVersion) throw new BizException(409, "工作稿版本已变化");
  }

  @Transactional
  public void deleteDraft(long id, long version, long draftVersion) {
    long uid = actor("role:manage");
    lockOrg(org(uid));
    draftVersion(role(org(uid), id), version, draftVersion);
    deleteDraft(id);
  }

  @Transactional
  public void discardDraft(long id, long version, long draftVersion) {
    long uid = actor("role:manage"), org = org(uid);
    lockOrg(org);
    actor("role:manage");
    var r = role(org, id);
    protectRole(uid, id);
    draftVersion(r, version, draftVersion);
    if (r.get("current_revision_id") == null
        || r.get("draft_revision_id") == null
        || "ARCHIVED".equals(r.get("status"))) throw new BizException(409, "仅已发布角色可放弃工作稿");
    db.update(
        "DELETE FROM role_revision WHERE id=? AND org_id=? AND state='DRAFT'",
        r.get("draft_revision_id"),
        org);
    db.update(
        "UPDATE sys_role SET"
            + " draft_revision_id=NULL,config_version=config_version+1,updated_at=CURRENT_TIMESTAMP,updated_by=?"
            + " WHERE id=? AND org_id=?",
        uid,
        id,
        org);
    bump(org);
    audit(org, uid, "DISCARD_DRAFT", id, Map.of("draftVersion", draftVersion));
  }

  public List<Map<String, Object>> list() {
    long uid = actor("role:view");
    var result =
        clientRows(
            db.queryForList(
                "SELECT r.id,r.code,r.name,r.description,r.status,r.config_version AS"
                    + " version,r.updated_at AS updatedAt,r.source_kind AS sourceKind,(SELECT"
                    + " COUNT(*) FROM role_grant g JOIN permission p ON p.id=g.permission_id WHERE"
                    + " g.role_id=r.id AND g.org_id=r.org_id AND p.category='BUSINESS') AS"
                    + " permissionCount,r.current_revision_id AS"
                    + " currentRevisionId,r.draft_revision_id AS draftRevisionId,(SELECT COUNT(*)"
                    + " FROM sys_user u WHERE u.org_id=r.org_id AND u.role_id=r.id AND u.deleted=0)"
                    + " AS userCount FROM sys_role r WHERE r.org_id=? ORDER BY r.id",
                org(uid)));
    for (var r : result)
      r.put("permissionCount", rolePermissions(org(uid), number(r.get("id"))).size());
    return result;
  }

  public Map<String, Object> rolePage(
      int page, int size, String keyword, String status, boolean drafts) {
    long uid = actor("role:view");
    page = Math.max(1, page);
    size = Math.max(1, Math.min(size, 100));
    String filter =
        " WHERE r.org_id=? AND (?='' OR r.name LIKE ? OR r.code LIKE ? OR r.description LIKE ?) AND"
            + " (?='' OR r.status=?) AND (?=0 OR r.draft_revision_id IS NOT NULL)";
    String q = keyword == null ? "" : keyword.trim(), st = status == null ? "" : status;
    Object[] args = {
      org(uid), q, "%" + q + "%", "%" + q + "%", "%" + q + "%", st, st, drafts ? 1 : 0
    };
    long total = db.queryForObject("SELECT COUNT(*) FROM sys_role r" + filter, Long.class, args);
    var pagedArgs = new ArrayList<Object>(Arrays.asList(args));
    pagedArgs.add(size);
    pagedArgs.add((long) (page - 1) * size);
    var items =
        clientRows(
            db.queryForList(
                "SELECT r.id,r.code,r.name,r.description,r.status,r.config_version AS"
                    + " version,r.draft_revision_id AS draftRevisionId,r.updated_at AS"
                    + " updatedAt,r.source_kind AS sourceKind,u.real_name AS updatedByName,(SELECT"
                    + " COUNT(*) FROM sys_user s WHERE s.org_id=r.org_id AND s.role_id=r.id AND"
                    + " s.deleted=0) AS userCount FROM sys_role r LEFT JOIN sys_user u ON"
                    + " u.id=r.updated_by AND u.org_id=r.org_id"
                    + filter
                    + " ORDER BY r.id LIMIT ? OFFSET ?",
                pagedArgs.toArray()));
    for (var item : items)
      item.put("permissionCount", rolePermissions(org(uid), number(item.get("id"))).size());
    return Map.of("items", items, "total", total);
  }

  public List<Map<String, Object>> assignable() {
    long uid = actor("role:assign");
    return listInternal(org(uid)).stream()
        .filter(
            r ->
                "ACTIVE".equals(r.get("status"))
                    && grantable(uid).containsAll(rolePermissions(org(uid), number(r.get("id")))))
        .toList();
  }

  private List<Map<String, Object>> listInternal(long org) {
    return db.queryForList(
        "SELECT id,code,name,status FROM sys_role WHERE org_id=? ORDER BY id", org);
  }

  public Map<String, Object> detail(long id) {
    long uid = actor("role:view");
    var r = role(org(uid), id);
    var out = new LinkedHashMap<String, Object>();
    out.put("role", r);
    out.put("impact", roleImpact(org(uid), id));
    out.put("actions", roleActions(uid, r));
    out.put(
        "migrationReview",
        clientRows(
            db.queryForList(
                "SELECT status,review_required AS reviewRequired FROM"
                    + " authorization_migration_review WHERE org_id=? AND role_id=?",
                org(uid),
                id)));
    out.put(
        "published",
        r.get("current_revision_id") == null
            ? List.of()
            : grants(
                one(
                        "SELECT snapshot_json FROM role_revision WHERE id=? AND org_id=?",
                        r.get("current_revision_id"),
                        org(uid))
                    .get("snapshot_json")));
    out.put(
        "draft",
        r.get("draft_revision_id") == null
            ? null
            : one(
                "SELECT id,draft_version AS version,snapshot_json AS grants FROM role_revision"
                    + " WHERE id=? AND org_id=?",
                r.get("draft_revision_id"),
                org(uid)));
    out.put(
        "users",
        clientRows(
            db.queryForList(
                "SELECT u.id,u.real_name AS realName,u.username,u.status,u.dept_id AS deptId,d.name"
                    + " AS deptName,CASE WHEN o.owner_user_id=u.id THEN 'OWNER' WHEN EXISTS(SELECT"
                    + " 1 FROM org_role_delegation g WHERE g.user_id=u.id AND g.org_id=u.org_id AND"
                    + " g.status='ACTIVE' AND (g.expires_at IS NULL OR"
                    + " g.expires_at>CURRENT_TIMESTAMP)) THEN 'DELEGATED' ELSE 'NONE' END AS"
                    + " managementSource FROM sys_user u JOIN organization o ON o.id=u.org_id LEFT"
                    + " JOIN sys_department d ON d.id=u.dept_id AND d.org_id=u.org_id WHERE"
                    + " u.org_id=? AND u.role_id=? AND u.deleted=0 ORDER BY u.id LIMIT 20",
                org(uid),
                id)));
    out.put(
        "history",
        clientRows(
            db.queryForList(
                "SELECT id,created_by AS createdBy,published_by_name AS actorName,publish_reason AS"
                    + " reason,catalog_version AS catalogVersion,published_at AS"
                    + " publishedAt,snapshot_json AS grants FROM role_revision WHERE org_id=? AND"
                    + " role_id=? AND state='PUBLISHED' ORDER BY id DESC LIMIT 20",
                org(uid),
                id)));
    return out;
  }

  @Transactional
  public long create(String name, String description, Long sourceRole) {
    return create(name, description, sourceRole, null);
  }

  @Transactional
  public long createCombined(
      String name, String description, Long source, String template, List<String> templates) {
    if (templates == null || templates.isEmpty())
      return create(name, description, source, template);
    if (source != null || template != null || templates.size() > 10)
      throw new BizException(400, "复制来源和职责模板不能混用，最多组合十个模板");
    long uid = actor("role:manage"), organization = org(uid);
    var config = new LinkedHashMap<String, Map<String, Object>>();
    for (String code : new LinkedHashSet<>(templates)) {
      one("SELECT code FROM role_template WHERE code=?", code);
      for (var p :
          db.queryForList(
              "SELECT p.code FROM role_template_grant t JOIN permission p ON p.id=t.permission_id"
                  + " WHERE t.template_code=? AND p.category='BUSINESS'",
              code)) {
        String permission = p.get("code").toString();
        if (!grantable(uid).contains(permission)) throw new BizException(403, "模板包含超出可授权清单的操作");
        var grant = new LinkedHashMap<String, Object>();
        grant.put("permission", permission);
        grant.put("scope", "ORG");
        if (permission.startsWith("party:"))
          grant.put("packVersion", currentPackVersion(organization));
        config.put(permission, grant);
      }
    }
    long id = create(name, description, null, null);
    db.update(
        "UPDATE role_revision SET snapshot_json=? WHERE id=(SELECT draft_revision_id FROM sys_role"
            + " WHERE id=? AND org_id=?)",
        encode(new ArrayList<>(config.values())),
        id,
        organization);
    db.update(
        "UPDATE sys_role SET source_kind='TEMPLATE' WHERE id=? AND org_id=?", id, organization);
    return id;
  }

  @Transactional
  public long create(String name, String description, Long sourceRole, String templateCode) {
    long uid = actor("role:manage"), org = org(uid);
    lockOrg(org);
    actor("role:manage");
    if (name == null
        || name.isBlank()
        || name.trim().length() > 100
        || description != null && description.length() > 500)
      throw new BizException(400, "角色名称须为 1—100 字，说明最多 500 字");
    if (db.queryForObject(
            "SELECT COUNT(*) FROM sys_role WHERE org_id=? AND name=?", Long.class, org, name.trim())
        > 0) throw new BizException(409, "本组织已存在同名角色");
    List<Map<String, Object>> config = new ArrayList<>();
    if (sourceRole != null) {
      var source = role(org, sourceRole);
      if (source.get("current_revision_id") != null)
        config =
            grants(
                one(
                        "SELECT snapshot_json FROM role_revision WHERE id=?",
                        source.get("current_revision_id"))
                    .get("snapshot_json"));
      if (!grantable(uid)
          .containsAll(config.stream().map(g -> g.get("permission").toString()).toList()))
        throw new BizException(403, "复制来源含不可授予权限，请选择空白创建");
    }
    if (templateCode != null) {
      if (sourceRole != null) throw new BizException(400, "模板和复制来源只能选择一个");
      one("SELECT code FROM role_template WHERE code=?", templateCode);
      for (var p :
          db.queryForList(
              "SELECT p.code FROM role_template_grant t JOIN permission p ON p.id=t.permission_id"
                  + " WHERE t.template_code=? AND p.category='BUSINESS'",
              templateCode)) {
        String code = p.get("code").toString();
        if (!grantable(uid).contains(code)) throw new BizException(403, "模板包含未启用或不可授予的权限，请空白创建");
        var g = new LinkedHashMap<String, Object>();
        g.put("permission", code);
        g.put("scope", "ORG");
        if (code.startsWith("party:")) g.put("packVersion", currentPackVersion(org));
        config.add(g);
      }
    }
    long id =
        insert(
            "INSERT INTO sys_role(org_id,code,name,description,status,level,data_scope,created_at)"
                + " VALUES(?,?,?,?, 'DRAFT',1,'ALL',CURRENT_TIMESTAMP)",
            org,
            "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
            name.trim(),
            description);
    long draft =
        insert(
            "INSERT INTO role_revision(org_id,role_id,state,snapshot_json,created_by,created_at)"
                + " VALUES(?,?,'DRAFT',?,?,CURRENT_TIMESTAMP)",
            org,
            id,
            encode(config),
            uid);
    db.update(
        "UPDATE sys_role SET draft_revision_id=?,source_kind=?,updated_at=CURRENT_TIMESTAMP WHERE"
            + " id=? AND org_id=?",
        draft,
        sourceRole != null ? "COPY" : templateCode != null ? "TEMPLATE" : "BLANK",
        id,
        org);
    db.update("UPDATE sys_role SET updated_by=? WHERE org_id=? AND id=?", uid, org, id);
    bump(org);
    audit(org, uid, "CREATE_ROLE", id, Map.of("name", name.trim()));
    return id;
  }

  private List<Map<String, Object>> validate(
      long uid, List<Map<String, Object>> desired, List<Map<String, Object>> before) {
    if (desired == null || desired.size() > 300) throw new BizException(400, "授权配置不合法");
    Set<String> allowed = grantable(uid);
    var old =
        before.stream().collect(Collectors.toMap(g -> g.get("permission").toString(), g -> g));
    Set<String> seen = new HashSet<>();
    var result = new ArrayList<Map<String, Object>>();
    for (var grant : desired) {
      String code = Objects.toString(grant.get("permission"), "");
      if (!seen.add(code)) throw new BizException(400, "权限重复：" + code);
      var p = one("SELECT category FROM permission WHERE code=?", code);
      if (!"BUSINESS".equals(p.get("category"))) throw new BizException(403, "普通角色不能包含治理权限");
      if (!"ORG".equals(grant.get("scope"))) throw new BizException(400, "该版本尚未开放此数据范围");
      boolean unchanged = Objects.equals(old.get(code), grant);
      if (!unchanged && !allowed.contains(code)) throw new BizException(403, "超出可授权清单：" + code);
      var normalized = new LinkedHashMap<String, Object>();
      normalized.put("permission", code);
      normalized.put("scope", "ORG");
      if (code.startsWith("party:")) {
        long v = unchanged ? number(grant.get("packVersion")) : currentPackVersion(org(uid));
        if (v == 0) throw new BizException(403, "党建领域包未启用");
        normalized.put("packVersion", v);
      }
      result.add(normalized);
    }
    for (String code : old.keySet())
      if (!seen.contains(code) && !allowed.contains(code))
        throw new BizException(403, "不能撤销只读权限：" + code);
    Set<String> known =
        new HashSet<>(
            db.queryForList("SELECT code FROM permission WHERE category='BUSINESS'", String.class));
    for (String code : seen)
      for (String dependency : vocabulary.dependencies(code, known))
        if (!seen.contains(dependency)) throw new BizException(400, "该操作依赖权限：" + dependency);
    return result;
  }

  @Transactional
  public long saveDraftChanges(long id, long version, List<Map<String, Object>> changes) {
    long uid = actor("role:manage"), org = org(uid);
    lockOrg(org);
    actor("role:manage");
    var role = role(org, id);
    protectRole(uid, id);
    if (changes == null || changes.size() > 300) throw new BizException(400, "必须提交明确的权限变更操作");
    long draft = number(role.get("draft_revision_id"));
    if ("ARCHIVED".equals(role.get("status"))) throw new BizException(409, "归档角色不可编辑");
    long actualVersion =
        draft == 0
            ? 0
            : number(
                one("SELECT draft_version FROM role_revision WHERE id=? AND org_id=?", draft, org)
                    .get("draft_version"));
    if (version != actualVersion) throw new BizException(409, "工作稿已被其他管理员更新");
    if (changes.isEmpty()) return draft;
    var base =
        draft == 0
            ? published(role)
            : grants(
                one("SELECT snapshot_json FROM role_revision WHERE id=? AND org_id=?", draft, org)
                    .get("snapshot_json"));
    var desired = new LinkedHashMap<String, Map<String, Object>>();
    for (var grant : base) desired.put(grant.get("permission").toString(), grant);
    var seen = new HashSet<String>();
    for (var change : changes) {
      String code = Objects.toString(change.get("permission"), "");
      if (code.isBlank() || !seen.add(code)) throw new BizException(400, "权限为空或重复");
      var permissions = db.queryForList("SELECT category FROM permission WHERE code=?", code);
      if (permissions.isEmpty()) throw new BizException(400, "未知权限");
      if (!"BUSINESS".equals(permissions.getFirst().get("category"))
          || !grantable(uid).contains(code)) throw new BizException(403, "超出可授权清单：" + code);
      String operation = Objects.toString(change.get("operation"), "");
      if (operation.equals("REVOKE")) desired.remove(code);
      else if (operation.equals("SET")) {
        if (!"ORG".equals(change.get("scope"))) throw new BizException(400, "该版本尚未开放此数据范围");
        var grant = new LinkedHashMap<String, Object>();
        grant.put("permission", code);
        grant.put("scope", change.get("scope"));
        if (code.startsWith("party:")) grant.put("packVersion", currentPackVersion(org));
        desired.put(code, grant);
      } else throw new BizException(400, "不支持的权限变更操作");
    }
    long result = saveDraft(id, version, new ArrayList<>(desired.values()));
    audit(org, uid, "SAVE_DRAFT", id, changes);
    return result;
  }

  private long saveDraft(long id, long version, List<Map<String, Object>> config) {
    long uid = actor("role:manage"), org = org(uid);
    lockOrg(org);
    actor("role:manage");
    var r = role(org, id);
    if ("ARCHIVED".equals(r.get("status"))) throw new BizException(409, "归档角色不可编辑");
    protectRole(uid, id);
    long draft = number(r.get("draft_revision_id"));
    if (draft == 0) {
      if (version != 0) throw new BizException(409, "工作稿版本已变化");
      draft =
          insert(
              "INSERT INTO"
                  + " role_revision(org_id,role_id,state,base_revision_id,snapshot_json,created_by,created_at)"
                  + " VALUES(?,?,'DRAFT',?,?,?,CURRENT_TIMESTAMP)",
              org,
              id,
              r.get("current_revision_id"),
              encode(config),
              uid);
      db.update("UPDATE sys_role SET draft_revision_id=? WHERE id=?", draft, id);
    } else if (db.update(
            "UPDATE role_revision SET snapshot_json=?,draft_version=draft_version+1 WHERE id=? AND"
                + " org_id=? AND state='DRAFT' AND draft_version=?",
            encode(config),
            draft,
            org,
            version)
        != 1) throw new BizException(409, "工作稿已被其他管理员更新");
    db.update(
        "UPDATE sys_role SET updated_at=CURRENT_TIMESTAMP,updated_by=? WHERE org_id=? AND id=?",
        uid,
        org,
        id);
    bump(org);
    return draft;
  }

  private List<Map<String, Object>> published(Map<String, Object> r) {
    return r.get("current_revision_id") == null
        ? List.of()
        : grants(
            one("SELECT snapshot_json FROM role_revision WHERE id=?", r.get("current_revision_id"))
                .get("snapshot_json"));
  }

  private Map<String, Object> paged(
      String query, String count, List<Object> args, int page, int size) {
    size = Math.max(1, Math.min(100, size));
    page = Math.max(1, page);
    long total = db.queryForObject(count, Long.class, args.toArray());
    var params = new ArrayList<>(args);
    params.add(size);
    params.add((long) (page - 1) * size);
    return Map.of(
        "items",
        clientRows(db.queryForList(query + " LIMIT ? OFFSET ?", params.toArray())),
        "total",
        total);
  }

  public Map<String, Object> members(long id, int page, int size) {
    long uid = actor("role:view"), org = org(uid);
    role(org, id);
    String from =
        " FROM sys_user u JOIN organization o ON o.id=u.org_id LEFT JOIN sys_department d ON"
            + " d.id=u.dept_id AND d.org_id=u.org_id WHERE u.org_id=? AND u.role_id=? AND"
            + " u.deleted=0";
    return paged(
        "SELECT u.id,u.real_name AS realName,u.username,u.status,d.name AS deptName,CASE WHEN"
            + " o.owner_user_id=u.id THEN 'OWNER' WHEN EXISTS(SELECT 1 FROM org_role_delegation g"
            + " WHERE g.org_id=u.org_id AND g.user_id=u.id AND g.status='ACTIVE' AND (g.expires_at"
            + " IS NULL OR g.expires_at>CURRENT_TIMESTAMP)) THEN 'DELEGATED' ELSE 'NONE' END AS"
            + " managementSource"
            + from
            + " ORDER BY u.id",
        "SELECT COUNT(*)" + from,
        List.of(org, id),
        page,
        size);
  }

  public Map<String, Object> history(long id, int page, int size) {
    long uid = actor("role:view"), org = org(uid);
    role(org, id);
    String from = " FROM role_revision WHERE org_id=? AND role_id=? AND state='PUBLISHED'";
    return paged(
        "SELECT id,published_by_name AS actorName,publish_reason AS reason,catalog_version AS"
            + " catalogVersion,published_at AS publishedAt,snapshot_json AS grants"
            + from
            + " ORDER BY id DESC",
        "SELECT COUNT(*)" + from,
        List.of(org, id),
        page,
        size);
  }

  public Map<String, Object> references(long id, int page, int size) {
    long uid = actor("role:view"), org = org(uid);
    var r = role(org, id);
    // Union only IDs and constants: legacy tables can have different MySQL text collations.
    String union =
        "SELECT 'USER' AS kind,u.id,CASE WHEN u.status=1 THEN 1 ELSE 0 END AS blocking FROM"
            + " sys_user u WHERE u.org_id=? AND u.role_id=? AND u.deleted=0 UNION ALL SELECT"
            + " 'POSITION',p.id,1 FROM org_position p WHERE p.org_id=? AND p.role_code=? UNION ALL"
            + " SELECT 'INVITATION',i.id,1 FROM org_invitation i WHERE i.org_id=? AND i.role_code=?"
            + " AND i.used_by IS NULL AND (i.expire_at IS NULL OR i.expire_at>CURRENT_TIMESTAMP)";
    var result =
        paged(
            "SELECT * FROM (" + union + ") refs ORDER BY kind,id",
            "SELECT COUNT(*) FROM (" + union + ") refs",
            List.of(org, id, org, r.get("code"), org, r.get("code")),
            page,
            size);
    for (var ref : (List<Map<String, Object>>) result.get("items")) {
      long refId = number(ref.get("id"));
      String kind = ref.get("kind").toString();
      var descriptions =
          switch (kind) {
            case "USER" ->
                db.queryForList(
                    "SELECT real_name AS name,username AS detail FROM sys_user WHERE org_id=? AND"
                        + " id=?",
                    org,
                    refId);
            case "POSITION" ->
                db.queryForList(
                    "SELECT name,'岗位默认角色' AS detail FROM org_position WHERE org_id=? AND id=?",
                    org,
                    refId);
            default ->
                db.queryForList(
                    "SELECT d.name AS detail FROM org_invitation i LEFT JOIN sys_department d ON"
                        + " d.id=i.org_unit_id AND d.org_id=i.org_id WHERE i.org_id=? AND i.id=?",
                    org,
                    refId);
          };
      if (!descriptions.isEmpty()) ref.putAll(clientRows(descriptions).getFirst());
      if (ref.get("name") == null)
        ref.put(
            "name",
            kind.equals("INVITATION")
                ? "邀请 #" + refId
                : Objects.toString(ref.get("detail"), "引用 #" + refId));
      if (ref.get("detail") == null) ref.put("detail", "未指定部门");
    }
    var out = new LinkedHashMap<String, Object>(result);
    out.put(
        "blockingTotal",
        db.queryForObject(
            "SELECT COUNT(*) FROM (" + union + ") refs WHERE blocking=1",
            Long.class,
            org,
            id,
            org,
            r.get("code"),
            org,
            r.get("code")));
    return out;
  }

  private void noReferences(long org, long role) {
    long count =
        db.queryForObject(
            "SELECT COUNT(*) FROM sys_user WHERE org_id=? AND role_id=? AND deleted=0 AND status=1",
            Long.class,
            org,
            role);
    String code = role(org, role).get("code").toString();
    count +=
        db.queryForObject(
            "SELECT COUNT(*) FROM org_position WHERE org_id=? AND role_code=?",
            Long.class,
            org,
            code);
    count +=
        db.queryForObject(
            "SELECT COUNT(*) FROM org_invitation WHERE org_id=? AND role_code=? AND used_by IS NULL"
                + " AND (expire_at IS NULL OR expire_at>CURRENT_TIMESTAMP)",
            Long.class,
            org,
            code);
    if (count > 0) throw new BizException(409, "角色仍被有效用户、岗位或邀请使用，请先处理引用");
  }

  @Transactional
  public Map<String, Object> preview(long id, String action, long expectedVersion) {
    return preview(id, action, expectedVersion, null);
  }

  @Transactional
  public Map<String, Object> preview(long id, String action, long expectedVersion, String reason) {
    reason = normalizeReason(reason);
    long uid = actor("role:grant"), org = org(uid);
    lockOrg(org);
    actor("role:grant");
    var r = role(org, id);
    if (!owner(uid)
        && db.queryForObject(
                "SELECT COUNT(*) FROM authorization_migration_review WHERE org_id=? AND role_id=?"
                    + " AND status='PENDING'",
                Integer.class,
                org,
                id)
            > 0) throw new BizException(403, "存量考勤授权须由所有者核对发布");
    if (number(r.get("config_version")) != expectedVersion) throw new BizException(409, "角色版本已变化");
    protectRole(uid, id);
    var payload = new LinkedHashMap<String, Object>();
    payload.put("roleVersion", expectedVersion);
    payload.put("reason", reason);
    if (Set.of("PUBLISH", "ENABLE").contains(action)) {
      if ("ARCHIVED".equals(r.get("status"))) throw new BizException(409, "归档角色不可启用");
      List<Map<String, Object>> candidate;
      long draft = number(r.get("draft_revision_id"));
      if (draft > 0) {
        var d = one("SELECT * FROM role_revision WHERE id=?", draft);
        candidate = grants(d.get("snapshot_json"));
        payload.put("draftId", draft);
        payload.put("draftVersion", d.get("draft_version"));
      } else candidate = published(r);
      payload.put("grants", validate(uid, candidate, published(r)));
    } else if (action.equals("DISABLE")) {
      if (!"ACTIVE".equals(r.get("status"))) throw new BizException(409, "仅启用角色可停用");
      noReferences(org, id);
    } else if (action.equals("ARCHIVE")) {
      if (!"DISABLED".equals(r.get("status"))) throw new BizException(409, "请先停用角色");
      noReferences(org, id);
    } else throw new BizException(400, "不支持的角色动作");
    payload.put("before", published(r));
    payload.put("impact", roleImpact(org, id));
    if (Set.of("PUBLISH", "ENABLE").contains(action)
        && "ACTIVE".equals(r.get("status"))
        && new HashSet<>(grants(encode(payload.get("grants"))))
            .equals(new HashSet<>(grants(encode(payload.get("before"))))))
      throw new BizException(409, "工作稿与生效配置一致，无需重复发布");
    return storePreview(uid, action, id, payload);
  }

  private Map<String, Object> storePreview(
      long uid, String action, long target, Map<String, Object> payload) {
    var u = identity(uid);
    String id = UUID.randomUUID().toString();
    payload = new LinkedHashMap<>(payload);
    payload.put("changeId", id);
    db.update(
        "INSERT INTO"
            + " org_authorization_change(id,org_id,actor_id,action,target_id,payload_json,baseline_version,actor_version,expires_at,status,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,'PREVIEWED',CURRENT_TIMESTAMP)",
        id,
        org(uid),
        uid,
        action,
        target,
        encode(payload),
        number(u.get("authz_version")),
        number(u.get("authz_version")),
        Timestamp.valueOf(LocalDateTime.now().plusMinutes(5)));
    var out = new LinkedHashMap<String, Object>();
    out.put("id", id);
    out.put("action", action);
    out.put("targetId", target);
    out.put("details", payload);
    out.put("expiresInSeconds", 300);
    out.put("expiresAt", LocalDateTime.now().plusMinutes(5).toString());
    return out;
  }

  @Transactional
  public Map<String, Object> previewAssignment(long target, long roleId) {
    return previewAssignment(target, roleId, null);
  }

  @Transactional
  public Map<String, Object> previewAssignment(long target, long roleId, String reason) {
    reason = normalizeReason(reason);
    long uid = actor("role:assign"), org = org(uid);
    lockOrg(org);
    actor("role:assign");
    protectUser(uid, target);
    var r = role(org, roleId);
    if (!"ACTIVE".equals(r.get("status"))) throw new BizException(400, "目标角色未启用");
    if (!grantable(uid).containsAll(rolePermissions(org, roleId)))
      throw new BizException(403, "无权分配整个目标角色");
    var u = identity(target);
    return storePreview(
        uid,
        "ASSIGN",
        target,
        Map.of(
            "reason",
            reason,
            "roleId",
            roleId,
            "oldRoleId",
            u.get("role_id"),
            "before",
            u.get("role_id") == null
                ? List.of()
                : effectiveRoleSnapshot(role(org, number(u.get("role_id")))),
            "grants",
            effectiveRoleSnapshot(r),
            "roleVersion",
            r.get("config_version")));
  }

  private List<Map<String, Object>> effectiveRoleSnapshot(Map<String, Object> r) {
    var enabled = rolePermissions(number(r.get("org_id")), number(r.get("id")));
    return published(r).stream().filter(g -> enabled.contains(g.get("permission"))).toList();
  }

  @Transactional
  public Map<String, Object> apply(String changeId) {
    long uid = actor("role:view"), org = org(uid);
    lockOrg(org);
    actor("role:view");
    var c =
        one(
            "SELECT * FROM org_authorization_change WHERE id=? AND org_id=? AND actor_id=? FOR"
                + " UPDATE",
            changeId,
            org,
            uid);
    if ("APPLIED".equals(c.get("status")))
      return decode(c.get("result_json"), new TypeReference<>() {});
    if (com.pams.common.JdbcDateTimes.local(c.get("expires_at")).isBefore(LocalDateTime.now()))
      throw new BizException(409, "预览已过期，请重新预览");
    if (number(c.get("baseline_version")) != number(identity(uid).get("authz_version")))
      throw new BizException(409, "人员或授权已变化，请重新预览");
    String action = c.get("action").toString();
    long target = number(c.get("target_id"));
    Map<String, Object> p = decode(c.get("payload_json"), new TypeReference<>() {});
    if (action.equals("DELEGATE")) {
      delegate(
          target,
          Boolean.TRUE.equals(p.get("active")),
          new HashSet<>((List<String>) p.get("grantable")),
          p.get("expiresAt") == null ? null : LocalDateTime.parse(p.get("expiresAt").toString()),
          number(p.get("version")));
      audit(org, uid, "DELEGATE", target, p);
      var result = Map.<String, Object>of("id", changeId, "status", "APPLIED");
      db.update(
          "UPDATE org_authorization_change SET status='APPLIED',result_json=? WHERE id=?",
          encode(result),
          changeId);
      return result;
    } else if (action.equals("ASSIGN")) {
      actor("role:assign");
      protectUser(uid, target);
      var r = role(org, number(p.get("roleId")));
      if (!"ACTIVE".equals(r.get("status"))
          || !grantable(uid).containsAll(rolePermissions(org, number(p.get("roleId")))))
        throw new BizException(403, "角色已失效或超出分配上限");
      if (number(r.get("config_version")) != number(p.get("roleVersion"))
          || number(identity(target).get("role_id")) != number(p.get("oldRoleId")))
        throw new BizException(409, "目标角色或用户已变化，请重新预览");
      db.update(
          "UPDATE sys_user SET role_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND org_id=?",
          p.get("roleId"),
          target,
          org);
    } else {
      actor("role:grant");
      protectRole(uid, target);
      var r = role(org, target);
      if (action.equals("PUBLISH") || action.equals("ENABLE")) {
        List<Map<String, Object>> config =
            validate(uid, grants(encode(p.get("grants"))), published(r));
        long revision =
            insert(
                "INSERT INTO"
                    + " role_revision(org_id,role_id,state,base_revision_id,snapshot_json,created_by,created_at,published_at)"
                    + " VALUES(?,?,'PUBLISHED',?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                org,
                target,
                r.get("current_revision_id"),
                encode(config),
                uid);
        db.update(
            "UPDATE role_revision SET catalog_version=3,publish_reason=?,published_by_name=(SELECT"
                + " real_name FROM sys_user WHERE id=? AND org_id=?) WHERE id=?",
            p.get("reason"),
            uid,
            org,
            revision);
        replaceGrants(org, target, config);
        db.update(
            "UPDATE sys_role SET"
                + " current_revision_id=?,draft_revision_id=NULL,status='ACTIVE',config_version=config_version+1"
                + " WHERE id=? AND org_id=?",
            revision,
            target,
            org);
      } else {
        noReferences(org, target);
        db.update(
            "UPDATE sys_role SET status=?,config_version=config_version+1 WHERE id=? AND org_id=?",
            action.equals("DISABLE") ? "DISABLED" : "ARCHIVED",
            target,
            org);
      }
    }
    db.update(
        "UPDATE sys_role SET updated_at=CURRENT_TIMESTAMP,updated_by=? WHERE org_id=? AND id=? AND"
            + " ?<>'ASSIGN'",
        uid,
        org,
        target,
        action);
    if (action.equals("PUBLISH") || action.equals("ENABLE"))
      db.update(
          "UPDATE authorization_migration_review SET"
              + " status='RESOLVED',resolved_at=CURRENT_TIMESTAMP WHERE org_id=? AND role_id=?",
          org,
          target);
    bump(org);
    audit(org, uid, action, target, p);
    var result = Map.<String, Object>of("id", changeId, "status", "APPLIED");
    db.update(
        "UPDATE org_authorization_change SET status='APPLIED',result_json=? WHERE id=?",
        encode(result),
        changeId);
    return result;
  }

  private void replaceGrants(long org, long role, List<Map<String, Object>> config) {
    db.update("DELETE FROM role_grant WHERE org_id=? AND role_id=?", org, role);
    for (var g : config) {
      long perm =
          db.queryForObject(
              "SELECT id FROM permission WHERE code=? AND category='BUSINESS'",
              Long.class,
              g.get("permission"));
      db.update(
          "INSERT INTO role_grant(org_id,role_id,permission_id,scope_type,pack_version)"
              + " VALUES(?,?,?,'ORG',?)",
          org,
          role,
          perm,
          g.get("packVersion"));
    }
  }

  public List<Map<String, Object>> auditLogs() {
    long uid = actor("org:audit:view");
    return clientRows(
        db.queryForList(
            "SELECT id,actor_id AS actorId,action,target_id AS targetId,detail_json AS"
                + " details,created_at AS createdAt FROM org_audit_log WHERE org_id=? ORDER BY id"
                + " DESC LIMIT 100",
            org(uid)));
  }

  public Map<String, Object> delegation(long target) {
    long uid = actor("role:view");
    if (!owner(uid)) throw new BizException(403, "仅所有者可查看管理员配置");
    if (org(uid) != number(identity(target).get("org_id"))) throw new BizException(404, "用户不存在");
    return delegationInfo(org(uid), target);
  }

  private Map<String, Object> delegationInfo(long org, long target) {
    var rows =
        db.queryForList(
            "SELECT d.*,u.real_name AS updatedbyname FROM org_role_delegation d LEFT JOIN sys_user"
                + " u ON u.id=d.updated_by AND u.org_id=d.org_id WHERE d.org_id=? AND d.user_id=?",
            org,
            target);
    if (rows.isEmpty()) return Map.of("active", false, "grantable", List.of(), "version", 0);
    var r = rows.getFirst();
    var out = new LinkedHashMap<String, Object>();
    out.put(
        "active",
        "ACTIVE".equals(r.get("status"))
            && (r.get("expires_at") == null
                || com.pams.common.JdbcDateTimes.local(r.get("expires_at"))
                    .isAfter(LocalDateTime.now())));
    out.put("version", r.get("version"));
    out.put("updatedAt", r.get("updated_at") == null ? null : r.get("updated_at").toString());
    out.put("updatedByName", r.get("updatedbyname"));
    out.put("status", r.get("status"));
    out.put(
        "grantable",
        grants(r.get("grantable_json")).stream().map(g -> g.get("permission")).toList());
    out.put(
        "expiresAt",
        r.get("expires_at") == null
            ? null
            : com.pams.common.JdbcDateTimes.local(r.get("expires_at")).toString());
    return out;
  }

  @Transactional
  public void delegate(
      long target, boolean active, Set<String> allowed, LocalDateTime expires, long version) {
    long uid = actor("role:grant"), org = org(uid);
    lockOrg(org);
    actor("role:grant");
    if (!owner(uid)) throw new BizException(403, "仅所有者可维护管理员");
    if (org != number(identity(target).get("org_id")) || target == uid || !valid(target))
      throw new BizException(400, "目标须为本组织其他有效账号");
    var existing =
        db.queryForList(
            "SELECT version FROM org_role_delegation WHERE org_id=? AND user_id=?", org, target);
    if (version != (existing.isEmpty() ? 0 : number(existing.getFirst().get("version"))))
      throw new BizException(409, "管理员配置已变化，请重新读取");
    if (allowed == null) throw new BizException(400, "必须提供可授权清单");
    Set<String> available = grantable(uid);
    if (!available.containsAll(allowed)) throw new BizException(403, "包含不可授予操作");
    if (expires != null && !expires.isAfter(LocalDateTime.now()))
      throw new BizException(400, "有效期必须晚于当前时间");
    var list =
        allowed.stream()
            .sorted()
            .map(
                code -> {
                  var g = new LinkedHashMap<String, Object>();
                  g.put("permission", code);
                  if (code.startsWith("party:")) g.put("packVersion", currentPackVersion(org));
                  return g;
                })
            .toList();
    db.update("DELETE FROM org_role_delegation WHERE org_id=? AND user_id=?", org, target);
    db.update(
        "INSERT INTO"
            + " org_role_delegation(org_id,user_id,capabilities_json,grantable_json,expires_at,status,version)"
            + " VALUES(?,?,?,?,?,?,?)",
        org,
        target,
        encode(GOVERNANCE),
        encode(list),
        expires == null ? null : Timestamp.valueOf(expires),
        active ? "ACTIVE" : "DISABLED",
        version + 1);
    bump(org);
    db.update(
        "UPDATE org_role_delegation SET updated_at=CURRENT_TIMESTAMP,updated_by=? WHERE org_id=?"
            + " AND user_id=?",
        uid,
        org,
        target);
  }

  /** Called only from startup/provisioning, never exposed to clients. */
  @Transactional
  public void initializeOrg(long org) {
    var o = one("SELECT enabled_packs FROM organization WHERE id=?", org);
    String packs = Objects.toString(o.get("enabled_packs"), "[\"party\"]");
    int enabled = packs.contains("party") ? 1 : 0;
    if (db.queryForObject(
            "SELECT COUNT(*) FROM org_domain_pack WHERE org_id=? AND pack_code='party'",
            Integer.class,
            org)
        == 0) db.update("INSERT INTO org_domain_pack VALUES(?,'party',?,1)", org, enabled);
    for (var template :
        db.queryForList("SELECT code,name FROM role_template WHERE code NOT LIKE 'DUTY_%'")) {
      String code = template.get("code").toString();
      if (db.queryForObject(
              "SELECT COUNT(*) FROM sys_role WHERE org_id=? AND code=?", Integer.class, org, code)
          == 0)
        db.update(
            "INSERT INTO sys_role(org_id,code,name,level,data_scope,status,created_at)"
                + " VALUES(?,?,?,?,?,'ACTIVE',CURRENT_TIMESTAMP)",
            org,
            code,
            template.get("name"),
            code.equals("TEACHER") ? 5 : code.equals("DIRECTOR") ? 4 : code.equals("STAFF") ? 1 : 3,
            code.equals("STAFF") ? "DEPT" : "ALL");
    }
    for (var r :
        db.queryForList(
            "SELECT * FROM sys_role WHERE org_id=? AND status='ACTIVE' AND current_revision_id IS"
                + " NULL",
            org)) {
      long rid = number(r.get("id"));
      var config = new ArrayList<Map<String, Object>>();
      for (var p :
          db.queryForList(
              "SELECT p.code FROM role_template_grant t JOIN permission p ON p.id=t.permission_id"
                  + " WHERE t.template_code=? AND p.category='BUSINESS'",
              r.get("code"))) {
        String code = p.get("code").toString();
        var g = new LinkedHashMap<String, Object>();
        g.put("permission", code);
        g.put("scope", "ORG");
        if (code.startsWith("party:")) g.put("packVersion", 1);
        config.add(g);
      }
      long rev =
          insert(
              "INSERT INTO"
                  + " role_revision(org_id,role_id,state,snapshot_json,created_at,published_at)"
                  + " VALUES(?,?,'PUBLISHED',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
              org,
              rid,
              encode(config));
      replaceGrants(org, rid, config);
      db.update("UPDATE sys_role SET current_revision_id=? WHERE id=?", rev, rid);
    }
  }

  @Transactional
  public void updatePacks(long org, boolean enabled) {
    lockOrg(org);
    var p = one("SELECT * FROM org_domain_pack WHERE org_id=? AND pack_code='party'", org);
    boolean was = number(p.get("enabled")) == 1;
    db.update(
        "UPDATE org_domain_pack SET enabled=?,activation_version=activation_version+? WHERE"
            + " org_id=? AND pack_code='party'",
        enabled ? 1 : 0,
        enabled && !was ? 1 : 0,
        org);
    bump(org);
  }
}
