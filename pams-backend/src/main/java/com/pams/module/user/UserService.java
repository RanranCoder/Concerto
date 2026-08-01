package com.pams.module.user;

import com.pams.common.BizException;
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
     * 判断角色数据范围是否为本部门（DEPT）。
     * Task 5 兜底实现：按角色 code 约定 STAFF 即本部门范围；
     * Task 26 将改为按 {@link Role#getDataScope()} == "DEPT" 判断，此方法可直接复用。
     */
    public boolean isDeptScoped(Role role) {
        return role != null && "DEPT".equals(role.getDataScope());
    }

    /**
     * 判断角色 code 是否为干事（STAFF）。干事强制只能查看本部门。
     */
    public boolean isStaff(String roleCode) {
        return "STAFF".equals(roleCode);
    }

    public PageResult<Map<String, Object>> page(String keyword, Long deptId, Long currentDeptId,
                                                boolean forceOwnDept, int page, int size) {
        Long filterDept = forceOwnDept ? currentDeptId : deptId;
        Page<User> p = userRepository.findAll((root, q, cb) -> {
            var preds = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
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
    public Long createUser(UserSaveRequest req) {
        if (userRepository.existsByUsername(req.getUsername())) {
            throw new BizException(1003, "用户名已存在");
        }
        User u = new User();
        u.setUsername(req.getUsername());
        u.setPassword(passwordEncoder.encode(
                req.getPassword() == null || req.getPassword().isBlank() ? "123456" : req.getPassword()));
        apply(u, req);
        u.setDeleted(0);
        return userRepository.save(u).getId();
    }

    @Transactional
    public void updateUser(Long id, UserSaveRequest req) {
        User u = userRepository.findById(id).orElseThrow(() -> new BizException(1004, "用户不存在"));
        apply(u, req);
        userRepository.save(u);
    }

    private void apply(User u, UserSaveRequest req) {
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
        u.setRole(role);
        u.setUpdatedAt(LocalDateTime.now());
    }

    @Transactional
    public void deleteUser(Long id) {
        User u = userRepository.findById(id).orElseThrow(() -> new BizException(1004, "用户不存在"));
        u.setDeleted(1);
        u.setUpdatedAt(LocalDateTime.now());
        userRepository.save(u);
    }

    @Transactional
    public void resetPassword(Long id) {
        User u = userRepository.findById(id).orElseThrow(() -> new BizException(1004, "用户不存在"));
        u.setPassword(passwordEncoder.encode("123456"));
        u.setUpdatedAt(LocalDateTime.now());
        userRepository.save(u);
    }
}
