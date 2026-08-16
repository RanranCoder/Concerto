package com.pams.module.member.service;

import com.pams.common.BizException;
import com.pams.entity.Department;
import com.pams.entity.Role;
import com.pams.entity.User;
import com.pams.module.member.dto.AccountImportRequest;
import com.pams.module.member.dto.AccountImportResultVO;
import com.pams.module.member.dto.MemberEnums;
import com.pams.module.member.dto.UnregisteredMemberVO;
import com.pams.module.member.entity.Member;
import com.pams.module.member.repository.MemberRepository;
import com.pams.repository.DepartmentRepository;
import com.pams.repository.RoleRepository;
import com.pams.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MemberAccountImportService {
    private final MemberRepository memberRepo;
    private final UserRepository userRepo;
    private final RoleRepository roleRepo;
    private final PasswordEncoder passwordEncoder;
    private final DepartmentRepository deptRepo;

    public MemberAccountImportService(MemberRepository memberRepo, UserRepository userRepo,
                                      RoleRepository roleRepo, PasswordEncoder passwordEncoder) {
        this(memberRepo, userRepo, roleRepo, passwordEncoder, null);
    }
    @Autowired
    public MemberAccountImportService(MemberRepository memberRepo, UserRepository userRepo,
                                      RoleRepository roleRepo, PasswordEncoder passwordEncoder,
                                      DepartmentRepository deptRepo) {
        this.memberRepo = memberRepo; this.userRepo = userRepo; this.roleRepo = roleRepo;
        this.passwordEncoder = passwordEncoder; this.deptRepo = deptRepo;
    }

    private static final Map<String, String> DEPT_LEADER_ROLE = Map.of(
            "组织部", "ORG_LEADER", "文秘部", "SECRETARY_LEADER",
            "新媒体中心", "MEDIA_LEADER", "青年科技部", "TECH_LEADER");

    /** 该届未注册成员：有学号且学号在 sys_user 无匹配。 */
    public List<UnregisteredMemberVO> unregistered(Long sessionId) {
        return memberRepo.findBySessionId(sessionId).stream()
                .filter(m -> m.getStudentNo() != null && !m.getStudentNo().isBlank())
                .filter(m -> userRepo.findByStudentNo(m.getStudentNo()).isEmpty())
                .map(m -> new UnregisteredMemberVO(m.getId(), m.getName(), m.getStudentNo(),
                        deptName(m.getDeptId()),
                        MemberEnums.POSITION_LABELS.getOrDefault(m.getPosition(), m.getPosition())))
                .toList();
    }

    @Transactional
    public AccountImportResultVO importAccounts(AccountImportRequest req, Long currentUserId) {
        if (req.sessionId() == null || req.memberIds() == null || req.memberIds().isEmpty()) {
            throw new BizException(2811, "请选择要导入的成员");
        }
        int created = 0, skipped = 0;
        Map<Long, String> overrides = req.roleCodes() == null ? Map.of() : req.roleCodes();
        Map<Long, Member> sessionMembers = new HashMap<>();
        memberRepo.findBySessionId(req.sessionId()).forEach(m -> sessionMembers.put(m.getId(), m));
        for (Long memberId : req.memberIds()) {
            Member m = sessionMembers.get(memberId);
            if (m == null) { skipped++; continue; }
            if (m.getStudentNo() == null || m.getStudentNo().isBlank()) { skipped++; continue; }
            String username = m.getStudentNo().trim();
            if (userRepo.existsByUsername(username) || !userRepo.findByStudentNo(username).isEmpty()) { skipped++; continue; }

            String roleCode = overrides.getOrDefault(memberId, defaultRoleCode(m));
            Role role = roleRepo.findByCode(roleCode).orElse(null);
            if (role == null) { skipped++; continue; }

            User u = new User();
            u.setUsername(username);
            u.setPassword(passwordEncoder.encode("123456"));
            u.setRealName(m.getName());
            u.setStudentNo(username);
            u.setPhone(m.getPhone());
            u.setDept(m.getDeptId() == null ? null : deptRepo == null ? null
                    : deptRepo.findById(m.getDeptId()).orElse(null));
            u.setRole(role);
            u.setStatus(1);
            u.setCreatedAt(LocalDateTime.now());
            u.setUpdatedAt(LocalDateTime.now());
            u.setDeleted(0);
            userRepo.save(u);
            created++;
        }
        return new AccountImportResultVO(created, skipped);
    }

    /** 默认角色映射：主任/副主任→DIRECTOR；部长/副部长→本部门部长角色；干事→STAFF。 */
    private String defaultRoleCode(Member m) {
        switch (m.getPosition()) {
            case "DIRECTOR": case "SUB_DIRECTOR": return "DIRECTOR";
            case "DEPT_HEAD": case "SUB_DEPT_HEAD": {
                if (m.getDeptId() != null && deptRepo != null) {
                    String deptName = deptRepo.findById(m.getDeptId()).map(Department::getName).orElse(null);
                    String code = deptName == null ? null : DEPT_LEADER_ROLE.get(deptName);
                    if (code != null) return code;
                }
                return "STAFF";
            }
            default: return "STAFF";
        }
    }

    private String deptName(Long deptId) {
        if (deptId == null || deptRepo == null) return "主任室";
        return deptRepo.findById(deptId).map(Department::getName).orElse("主任室");
    }
}
