package com.pams.module.member.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.BizException;
import com.pams.entity.User;
import com.pams.module.member.dto.*;
import com.pams.module.member.dto.AccountBatchVO.*;
import com.pams.module.member.repository.MemberRepository;
import com.pams.module.permission.service.TenantAuthorizationService;
import com.pams.module.permission.service.TenantAuthorizationService.AccountImportAuthority;
import com.pams.repository.DepartmentRepository;
import com.pams.repository.RoleRepository;
import com.pams.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** Preview-bound tenant account creation. Raw setup tokens are never persisted. */
@Service
public class MemberAccountImportService {
    private final MemberRepository members;
    private final UserRepository users;
    private final RoleRepository roles;
    private final DepartmentRepository departments;
    private final TenantAuthorizationService authorization;
    private final PasswordEncoder encoder;
    private final JdbcTemplate db;
    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private static final Map<String, String> DEPARTMENT_LEADER_ROLES = Map.of(
        "组织部", "ORG_LEADER", "文秘部", "SECRETARY_LEADER",
        "新媒体中心", "MEDIA_LEADER", "青年科技部", "TECH_LEADER");

    public MemberAccountImportService(MemberRepository members, UserRepository users,
            RoleRepository roles, DepartmentRepository departments,
            TenantAuthorizationService authorization, PasswordEncoder encoder, JdbcTemplate db) {
        this.members = members; this.users = users; this.roles = roles;
        this.departments = departments; this.authorization = authorization;
        this.encoder = encoder; this.db = db;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<UnregisteredMemberVO> unregistered(Long sessionId) {
        var authority = authorization.verifyAccountImport();
        verifySession(sessionId, authority.orgId());
        Map<String, Long> assignable = new HashMap<>();
        authorization.assignable().forEach(r -> assignable.put(r.get("code").toString(), ((Number) r.get("id")).longValue()));
        return members.findBySessionId(sessionId).stream()
            .filter(m -> Objects.equals(m.getOrgId(), authority.orgId()))
            .filter(m -> "ACTIVE".equals(m.getStatus()) && validUsername(m.getStudentNo()))
            .filter(m -> available(m.getStudentNo().trim(), authority.orgId()))
            .map(m -> {
                String department = deptName(m.getDeptId(), authority.orgId());
                String code = switch (m.getPosition()) {
                    case "DIRECTOR", "SUB_DIRECTOR" -> "DIRECTOR";
                    case "DEPT_HEAD", "SUB_DEPT_HEAD" -> DEPARTMENT_LEADER_ROLES.get(department == null ? "" : department);
                    case "STAFF" -> "STAFF";
                    default -> null;
                };
                Long roleId = code == null ? null : assignable.get(code);
                return new UnregisteredMemberVO(m.getId(), m.getName(), m.getStudentNo().trim(), department,
                    MemberEnums.POSITION_LABELS.getOrDefault(m.getPosition(), m.getPosition()), m.getPosition(), roleId,
                    roleId != null ? null : code == null ? "该职位或部门暂无角色映射，请手动选择"
                        : "职位对应角色尚未发布或不在可分配范围内，请手动选择");
            })
            .toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Preview preview(AccountImportRequest request) {
        var authority = authorization.verifyAccountImport();
        var rows = rows(request, authority.orgId());
        String id = UUID.randomUUID().toString();
        var expires = LocalDateTime.now().plusMinutes(10);
        db.update("INSERT INTO member_account_batch(id,org_id,actor_id,authz_version,request_json,"
                + "preview_json,status,expires_at) VALUES(?,?,?,?,?,?,'PREVIEW',?)",
            id, authority.orgId(), authority.actorId(), authority.version(), encode(request),
            encode(rows), Timestamp.valueOf(expires));
        return new Preview(id, expires.toString(), rows);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AccountBatchVO.Result apply(String id) {
        var authority = authorization.verifyAccountImport();
        var batch = batch(id, authority);
        if ("APPLIED".equals(batch.get("status"))) return result(id, authority.orgId());
        if (!((Timestamp) batch.get("expires_at")).toLocalDateTime().isAfter(LocalDateTime.now()))
            throw new BizException(409, "开户预览已过期，请重新预览");
        if (((Number) batch.get("authz_version")).longValue() != authority.version())
            throw new BizException(409, "组织授权已变化，请重新预览");
        var request = decode(batch.get("request_json"), new TypeReference<AccountImportRequest>() {});
        var expected = decode(batch.get("preview_json"), new TypeReference<List<Row>>() {});
        // Keep the selected roster identities stable until account creation commits.
        for (long memberId : request.memberIds().stream().sorted().toList())
            db.queryForList("SELECT id FROM member WHERE id=? AND org_id=? FOR UPDATE", memberId, authority.orgId());
        var actual = rows(request, authority.orgId());
        if (!expected.equals(actual)) throw new BizException(409, "成员资料或角色已变化，请重新预览");
        var ids = new ArrayList<Long>();
        for (var row : actual) {
            User u = new User();
            u.setOrgId(authority.orgId()); u.setUsername(row.username()); u.setStudentNo(row.username());
            u.setRealName(row.name()); u.setPhone(row.phone());
            u.setDept(row.deptId() == null ? null : departments.findById(row.deptId()).orElseThrow());
            u.setRole(roles.findById(row.roleId()).orElseThrow());
            // Unknown password and inactive until the member redeems their own setup link.
            u.setPassword(encoder.encode(newToken())); u.setStatus(0); u.setDeleted(0L);
            u.setCreatedAt(LocalDateTime.now()); u.setUpdatedAt(LocalDateTime.now());
            users.saveAndFlush(u);
            ids.add(u.getId());
            db.update("INSERT INTO member_account_credential(user_id,batch_id,org_id,pending_password_hash) VALUES(?,?,?,?)",
                u.getId(), id, authority.orgId(), u.getPassword());
        }
        db.update("UPDATE member_account_batch SET status='APPLIED',applied_at=CURRENT_TIMESTAMP WHERE id=?", id);
        authorization.accountBatchAudit(authority, id, "MEMBER_ACCOUNT_CREATE", ids);
        return result(id, authority.orgId());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<AccountBatchVO.Result> recent() {
        var authority = authorization.verifyAccountImport();
        return db.queryForList("SELECT id FROM member_account_batch WHERE org_id=? AND actor_id=?"
                + " AND status='APPLIED' ORDER BY applied_at DESC LIMIT 20", String.class,
                authority.orgId(), authority.actorId()).stream()
            .map(id -> result(id, authority.orgId())).toList();
    }

    /** Reissuing replaces only unused links in this actor's batch. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Credential> credentials(String id) {
        var authority = authorization.verifyAccountImport();
        if (!"APPLIED".equals(batch(id, authority).get("status"))) throw new BizException(409, "请先确认开户");
        var pending = db.queryForList("SELECT c.user_id,u.role_id,u.real_name,u.username,r.name AS role_name,"
                + "d.name AS dept_name FROM member_account_credential c JOIN sys_user u ON u.id=c.user_id"
                + " JOIN sys_role r ON r.id=u.role_id LEFT JOIN sys_department d ON d.id=u.dept_id"
                + " WHERE c.batch_id=? AND c.org_id=? AND u.org_id=c.org_id AND c.consumed_at IS NULL"
                + " AND u.deleted=0 AND u.status=0 AND u.password=c.pending_password_hash ORDER BY c.user_id", id, authority.orgId());
        // A delegation or role may have changed since account creation.
        for (long roleId : pending.stream().map(r -> ((Number) r.get("role_id")).longValue()).distinct().toList())
            authorization.verifyAssignableRole(roleId);
        var expires = LocalDateTime.now().plusHours(48);
        var output = new ArrayList<Credential>();
        var ids = new ArrayList<Long>();
        for (var row : pending) {
            long uid = ((Number) row.get("user_id")).longValue();
            String token = newToken();
            db.update("UPDATE member_account_credential SET token_hash=?,expires_at=? WHERE user_id=?",
                hash(token), Timestamp.valueOf(expires), uid);
            ids.add(uid);
            output.add(new Credential((String) row.get("real_name"), (String) row.get("username"),
                (String) row.get("role_name"), (String) row.get("dept_name"), token, expires.toString()));
        }
        authorization.accountBatchAudit(authority, id, "MEMBER_ACCOUNT_CREDENTIAL_ISSUE", ids);
        return output;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void setupPassword(String token, String password) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalidCredential();
        if (password == null || password.isBlank() || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new BizException(400, "密码至少 8 位，且 UTF-8 编码不超过 72 字节");
        var found = db.queryForList("SELECT org_id FROM member_account_credential WHERE token_hash=?", hash(token));
        if (found.isEmpty()) throw invalidCredential();
        long org = ((Number) found.getFirst().get("org_id")).longValue();
        // Same lock order as governance and credential issuance; recheck hash after locking.
        db.queryForList("SELECT id FROM organization WHERE id=? FOR UPDATE", org);
        var credentials = db.queryForList("SELECT * FROM member_account_credential WHERE token_hash=? FOR UPDATE", hash(token));
        if (credentials.isEmpty()) throw invalidCredential();
        var credential = credentials.getFirst();
        if (credential.get("consumed_at") != null || credential.get("expires_at") == null
                || !((Timestamp) credential.get("expires_at")).toLocalDateTime().isAfter(LocalDateTime.now()))
            throw invalidCredential();
        long uid = ((Number) credential.get("user_id")).longValue();
        var eligible = db.queryForList("SELECT u.id FROM sys_user u JOIN organization o ON o.id=u.org_id"
                + " JOIN sys_role r ON r.id=u.role_id AND r.org_id=u.org_id WHERE u.id=? AND u.org_id=?"
                + " AND u.deleted=0 AND u.status=0 AND o.deleted=0 AND o.status=1 AND r.status='ACTIVE'"
                + " AND u.password=? FOR UPDATE", uid, org, credential.get("pending_password_hash"));
        if (eligible.isEmpty()) throw invalidCredential();
        db.update("UPDATE sys_user SET password=?,status=1,updated_at=CURRENT_TIMESTAMP WHERE id=?", encoder.encode(password), uid);
        db.update("UPDATE member_account_credential SET consumed_at=CURRENT_TIMESTAMP,token_hash=NULL WHERE user_id=?", uid);
        authorization.userChanged(org);
        db.update("INSERT INTO org_audit_log(org_id,actor_id,action,target_id,detail_json,created_at)"
                + " VALUES(?,?,'MEMBER_ACCOUNT_ACTIVATE',?,'{}',CURRENT_TIMESTAMP)", org, uid, uid);
    }

    private List<Row> rows(AccountImportRequest request, long org) {
        if (request == null || request.memberIds() == null || request.memberIds().isEmpty()
                || request.memberIds().size() > 200 || request.memberIds().stream().anyMatch(Objects::isNull)
                || new HashSet<>(request.memberIds()).size() != request.memberIds().size())
            throw new BizException(400, "请选择 1 至 200 位不同的成员");
        if (request.roleIds() == null || !request.roleIds().keySet().equals(new HashSet<>(request.memberIds()))
                || request.roleIds().values().stream().anyMatch(Objects::isNull)) throw new BizException(400, "请为每位选中成员指定角色");
        verifySession(request.sessionId(), org);
        var source = new HashMap<Long, com.pams.module.member.entity.Member>();
        members.findBySessionId(request.sessionId()).stream()
            .filter(m -> Objects.equals(m.getOrgId(), org)).forEach(m -> source.put(m.getId(), m));
        var roleCache = new HashMap<Long, TenantAuthorizationService.AccountRolePreview>();
        var usernames = new HashSet<String>();
        var output = new ArrayList<Row>();
        for (Long memberId : request.memberIds()) {
            var m = source.get(memberId);
            if (m == null) throw new BizException(404, "所选成员不属于当前组织或届别");
            if (!"ACTIVE".equals(m.getStatus()) || !validUsername(m.getStudentNo()))
                throw new BizException(409, m.getName() + "：仅支持有有效学号的在职成员");
            String username = m.getStudentNo().trim();
            if (!usernames.add(username.toLowerCase(Locale.ROOT)) || !available(username, org))
                throw new BizException(409, m.getName() + "：学号或账号已被占用，请刷新名单");
            var role = roleCache.computeIfAbsent(request.roleIds().get(memberId), authorization::accountRolePreview);
            output.add(new Row(memberId, m.getName(), username, m.getPhone(), m.getDeptId(),
                deptName(m.getDeptId(), org), role.id(), role.name(), role.version(), role.permissions(), role.permissionNames()));
        }
        return output;
    }

    private void verifySession(Long id, long org) {
        if (id == null || db.queryForObject("SELECT COUNT(*) FROM member_session WHERE id=? AND org_id=? AND deleted=0",
                Integer.class, id, org) != 1) throw new BizException(404, "届别不存在");
    }

    private boolean validUsername(String value) {
        return value != null && value.trim().matches("[A-Za-z0-9_]{1,30}");
    }

    private boolean available(String username, long org) {
        // Never resurrect a former owner's/admin's soft-deleted identity in bulk.
        return !users.existsByUsername(username) && users.findDeletedByUsername(username).isEmpty()
            && users.findByStudentNo(username).stream().noneMatch(u -> Objects.equals(u.getOrgId(), org));
    }

    private String deptName(Long id, long org) {
        if (id == null) return null;
        return departments.findById(id).filter(d -> Objects.equals(d.getOrgId(), org))
            .orElseThrow(() -> new BizException(404, "成员所属部门不存在")).getName();
    }

    private Map<String, Object> batch(String id, AccountImportAuthority authority) {
        var found = db.queryForList("SELECT * FROM member_account_batch WHERE id=? AND org_id=? AND actor_id=? FOR UPDATE",
            id, authority.orgId(), authority.actorId());
        if (found.isEmpty()) throw new BizException(404, "开户批次不存在");
        return found.getFirst();
    }

    private AccountBatchVO.Result result(String id, long org) {
        var accounts = db.query("SELECT u.id,u.real_name,u.username,r.name AS role_name,d.name AS dept_name,"
                + "c.consumed_at,u.status,u.deleted,u.password,c.pending_password_hash FROM member_account_credential c JOIN sys_user u ON u.id=c.user_id"
                + " JOIN sys_role r ON r.id=u.role_id LEFT JOIN sys_department d ON d.id=u.dept_id"
                + " WHERE c.batch_id=? AND c.org_id=? AND u.org_id=c.org_id ORDER BY u.id",
            (rs, index) -> new Account(rs.getLong("id"), rs.getString("real_name"), rs.getString("username"),
                rs.getString("role_name"), rs.getString("dept_name"), rs.getTimestamp("consumed_at") != null,
                rs.getTimestamp("consumed_at") == null && rs.getInt("status") == 0 && rs.getLong("deleted") == 0
                    && Objects.equals(rs.getString("password"), rs.getString("pending_password_hash"))), id, org);
        return new AccountBatchVO.Result(id, accounts.size(), accounts);
    }

    private String newToken() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private BizException invalidCredential() {
        return new BizException(400, "设密链接无效、已使用或已过期，请联系管理员重新发放");
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private <T> T decode(Object value, TypeReference<T> type) {
        try { return json.readValue(value.toString(), type); }
        catch (Exception e) { throw new IllegalStateException("开户预览格式错误", e); }
    }
}
