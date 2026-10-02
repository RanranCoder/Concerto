package com.pams.module.user;

import com.pams.common.BizException;
import com.pams.common.OrgContext;
import com.pams.common.PageResult;
import com.pams.entity.Department;
import com.pams.entity.Role;
import com.pams.entity.User;
import com.pams.module.user.dto.UserSaveRequest;
import com.pams.repository.DepartmentRepository;
import com.pams.repository.RoleRepository;
import com.pams.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final DepartmentRepository departmentRepository;
    private final RoleRepository roleRepository;

    @Autowired
    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       DepartmentRepository departmentRepository, RoleRepository roleRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.departmentRepository = departmentRepository;
        this.roleRepository = roleRepository;
    }

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this(userRepository, passwordEncoder, null, null);
    }

    /**
     * 用户选项（PRD-v5 F5 选人组件数据源）：仅返回 id/姓名/部门/角色等最小字段，
     * 不含用户名/手机号等敏感信息，登录即可用（排班选人、群聊负责人等场景）。
     */
    public java.util.List<Map<String, Object>> options() {
        return userRepository.findAll(Sort.by(Sort.Direction.ASC, "id")).stream()
                .filter(u -> OrgContext.getOrDefault().equals(u.getOrgId())) // 显式按组织过滤（A3）
                .map(u -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", u.getId());
                    m.put("realName", u.getRealName());
                    m.put("deptId", u.getDept() == null ? null : u.getDept().getId());
                    m.put("deptName", u.getDept() == null ? null : u.getDept().getName());
                    m.put("roleName", u.getRole() == null ? null : u.getRole().getName());
                    return m;
                })
                .toList();
    }

    /**
     * 判断角色数据范围是否为本部门（DEPT）。
     * 由 Role.dataScope 决定：干事（STAFF）dataScope=DEPT → 只能看本部门；
     * 部长及以上 dataScope=ALL → 可全量查看。Task 5 预留，Task 26 接入。
     */
    public boolean isDeptScoped(Role role) {
        return role != null && "DEPT".equals(role.getDataScope());
    }

    /**
     * 防提权校验：被授予角色的 level 不得超过当前操作者 level。
     * 部长（level 3）不能创建/改造成主任（4）或指导老师（5）。currentLevel 为 null 时放行（保留旧行为）。
     */
    private void checkAssignableLevel(Role targetRole, Integer currentLevel) {
        if (currentLevel == null) return;
        Integer targetLevel = targetRole.getLevel() == null ? 0 : targetRole.getLevel();
        if (targetLevel > currentLevel) {
            throw new BizException(1007, "不能授予高于自己级别的角色");
        }
    }

    public PageResult<Map<String, Object>> page(String keyword, Long deptId, Long currentDeptId,
                                                boolean forceOwnDept, int page, int size) {
        Long filterDept = forceOwnDept ? currentDeptId : deptId;
        Page<User> p = userRepository.findAll((root, q, cb) -> {
            var preds = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            // 账号表未参与 @TenantId 自动过滤（注册需跨租户供给），此处显式按组织过滤（A3）
            preds.add(cb.equal(root.get("orgId"), OrgContext.getOrDefault()));
            if (filterDept != null) preds.add(cb.equal(root.get("dept").get("id"), filterDept));
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim() + "%";
                preds.add(cb.or(cb.like(root.get("realName"), like),
                                cb.like(root.get("username"), like)));
            }
            return cb.and(preds.toArray(new jakarta.persistence.criteria.Predicate[0]));
        }, PageRequest.of(page - 1, size, Sort.by(Sort.Direction.ASC, "id")));

        PageResult<Map<String, Object>> r = new PageResult<>();
        r.setRecords(p.getContent().stream().map(this::toVO).toList());
        r.setTotal(p.getTotalElements());
        r.setCurrent(page);
        r.setSize(size);
        return r;
    }

    private Map<String, Object> toVO(User u) {
        // 用 HashMap 而非 Map.of：Map.of 遇 null value 抛 NPE，teacher/admin 无部门（dept 为 null）
        Map<String, Object> vo = new HashMap<>();
        vo.put("id", u.getId());
        vo.put("username", u.getUsername());
        vo.put("realName", u.getRealName());
        vo.put("studentNo", u.getStudentNo() == null ? "" : u.getStudentNo());
        vo.put("phone", u.getPhone() == null ? "" : u.getPhone());
        vo.put("deptId", u.getDept() == null ? null : u.getDept().getId());
        vo.put("deptName", u.getDept() == null ? null : u.getDept().getName());
        vo.put("roleCode", u.getRole() == null ? null : u.getRole().getCode());
        vo.put("roleName", u.getRole() == null ? null : u.getRole().getName());
        vo.put("status", u.getStatus());
        return vo;
    }

    @Transactional
    public Long createUser(UserSaveRequest req, Integer currentLevel) {
        if (userRepository.existsByUsername(req.getUsername())) {
            throw new BizException(1003, "用户名已存在");
        }
        // C3：若同名账号已被软删，复活该行（setDeleted(0) + 更新字段）而非插入新行，
        //     规避 sys_user.username 硬唯一键与软删冲突（删除账号后无法重建同名）。
        User deleted = userRepository.findDeletedByUsername(req.getUsername()).orElse(null);
        if (deleted != null) {
            deleted.setPassword(passwordEncoder.encode(
                    req.getPassword() == null || req.getPassword().isBlank() ? "123456" : req.getPassword()));
            apply(deleted, req, currentLevel);
            deleted.setDeleted(0L);
            deleted.setUpdatedAt(LocalDateTime.now());
            userRepository.save(deleted);
            return deleted.getId();
        }
        User u = new User();
        u.setUsername(req.getUsername());
        u.setPassword(passwordEncoder.encode(
                req.getPassword() == null || req.getPassword().isBlank() ? "123456" : req.getPassword()));
        apply(u, req, currentLevel);
        u.setDeleted(0L);
        return userRepository.save(u).getId();
    }

    /**
     * 仅测试使用，禁止生产调用：传 currentLevel=null，绕过防提权校验（1007）。
     * 生产代码必须走 {@link #createUser(UserSaveRequest, Integer)} 传真实操作者级别。
     */
    @Deprecated
    @Transactional
    public Long createUser(UserSaveRequest req) {
        return createUser(req, null);
    }

    @Transactional
    public void updateUser(Long id, UserSaveRequest req, Integer currentLevel) {
        User u = userRepository.findById(id).orElseThrow(() -> new BizException(1004, "用户不存在"));
        apply(u, req, currentLevel);
        userRepository.save(u);
    }

    /**
     * 仅测试使用，禁止生产调用：传 currentLevel=null，绕过防提权校验（1007）。
     * 生产代码必须走 {@link #updateUser(Long, UserSaveRequest, Integer)} 传真实操作者级别。
     */
    @Deprecated
    @Transactional
    public void updateUser(Long id, UserSaveRequest req) {
        updateUser(id, req, null);
    }

    private void apply(User u, UserSaveRequest req, Integer currentLevel) {
        u.setRealName(req.getRealName());
        u.setStudentNo(req.getStudentNo());
        u.setPhone(req.getPhone());
        u.setStatus(req.getStatus() == null ? 1 : req.getStatus());
        if (req.getDeptId() != null) {
            Department d = departmentRepository.findById(req.getDeptId()).orElseThrow(() -> new BizException(1005, "部门不存在"));
            u.setDept(d);
        } else {
            u.setDept(null);
        }
        Role role = roleRepository.findById(req.getRoleId()).orElseThrow(() -> new BizException(1006, "角色不存在"));
        checkAssignableLevel(role, currentLevel);
        u.setRole(role);
        u.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * 防提权校验：被删用户的角色 level ≥ 当前操作者 level 时拒绝（部长不能删主任/指导老师，
     * 也不能删平级部长）。与 createUser/updateUser 的"不能授予高于自己级别角色"形成闭环，
     * 避免"有建无删不一致"。currentLevel 为 null 时放行（保留旧行为）。
     */
    private void checkDeleteLevel(User target, Integer currentLevel) {
        if (currentLevel == null) return;
        Integer targetLevel = target.getRole() == null || target.getRole().getLevel() == null
                ? 0 : target.getRole().getLevel();
        if (targetLevel >= currentLevel) {
            throw new BizException(1008, "不能删除级别不低于自己的用户");
        }
    }

    /**
     * 防提权校验：重置密码等于账号接管（操作者拿到新密码即可登录目标账号），
     * 目标用户角色 level ≥ 当前操作者 level 时拒绝（部长不能重置主任/指导老师的密码，
     * 也不能重置平级部长）。口径与 {@link #checkDeleteLevel} 一致。
     * currentLevel 为 null 时放行（保留旧行为）。
     */
    private void checkResetPasswordLevel(User target, Integer currentLevel) {
        if (currentLevel == null) return;
        Integer targetLevel = target.getRole() == null || target.getRole().getLevel() == null
                ? 0 : target.getRole().getLevel();
        if (targetLevel >= currentLevel) {
            throw new BizException(1008, "不能重置级别不低于自己的账号密码");
        }
    }

    @Transactional
    public void deleteUser(Long id, Integer currentLevel) {
        User u = userRepository.findById(id).orElseThrow(() -> new BizException(1004, "用户不存在"));
        checkDeleteLevel(u, currentLevel);
        u.setDeleted(u.getId());
        u.setUpdatedAt(LocalDateTime.now());
        userRepository.save(u);
    }

    /**
     * 仅测试使用，禁止生产调用：传 currentLevel=null，绕过防提权校验（1008）。
     * 生产代码必须走 {@link #deleteUser(Long, Integer)} 传真实操作者级别。
     */
    @Deprecated
    @Transactional
    public void deleteUser(Long id) {
        deleteUser(id, null);
    }

    @Transactional
    public void resetPassword(Long id, Integer currentLevel) {
        User u = userRepository.findById(id).orElseThrow(() -> new BizException(1004, "用户不存在"));
        checkResetPasswordLevel(u, currentLevel);
        u.setPassword(passwordEncoder.encode("123456"));
        // 审计 #3：改密即吊销该用户既有 token（推进失效水位），否则旧 token 仍可调用全部 API
        u.setTokenInvalidBefore(com.pams.security.JwtUtil.revocationWatermark(LocalDateTime.now(), u.getTokenInvalidBefore()));
        u.setUpdatedAt(LocalDateTime.now());
        userRepository.save(u);
    }

    /**
     * 仅测试使用，禁止生产调用：传 currentLevel=null，绕过防提权校验（1008）。
     * 生产代码必须走 {@link #resetPassword(Long, Integer)} 传真实操作者级别。
     */
    @Deprecated
    @Transactional
    public void resetPassword(Long id) {
        resetPassword(id, null);
    }

    /** 当前用户自行修改密码：校验旧密码 + 新密码长度 */
    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User u = userRepository.findById(userId).orElseThrow(() -> new BizException(1004, "用户不存在"));
        if (!passwordEncoder.matches(oldPassword, u.getPassword())) {
            throw new BizException(1009, "旧密码不正确");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw new BizException(1010, "新密码长度不能少于 6 位");
        }
        u.setPassword(passwordEncoder.encode(newPassword));
        // 审计 #3：改密即吊销该用户既有 token（含改密者当前 token，需重新登录）
        u.setTokenInvalidBefore(com.pams.security.JwtUtil.revocationWatermark(LocalDateTime.now(), u.getTokenInvalidBefore()));
        u.setUpdatedAt(LocalDateTime.now());
        userRepository.save(u);
    }
}
