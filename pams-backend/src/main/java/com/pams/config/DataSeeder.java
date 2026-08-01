package com.pams.config;

import com.pams.entity.Department;
import com.pams.entity.Role;
import com.pams.entity.User;
import com.pams.repository.DepartmentRepository;
import com.pams.repository.RoleRepository;
import com.pams.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
public class DataSeeder implements ApplicationRunner {
    private final DepartmentRepository departmentRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(DepartmentRepository d, RoleRepository r, UserRepository u, PasswordEncoder p) {
        this.departmentRepository = d; this.roleRepository = r; this.userRepository = u; this.passwordEncoder = p;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (departmentRepository.count() > 0) return;
        Department[] depts = {
            mkDept("文秘部", 1), mkDept("组织部", 2), mkDept("新媒体中心", 3), mkDept("青年科技部", 4)
        };
        for (Department d : depts) departmentRepository.save(d);
        Department org = depts[1];

        Role[] roles = {
            mkRole("TEACHER", "指导老师", 5, "ALL"),
            mkRole("DIRECTOR", "主任", 4, "ALL"),
            mkRole("ORG_LEADER", "组织部长", 3, "ALL"),
            mkRole("SECRETARY_LEADER", "文秘部长", 3, "ALL"),
            mkRole("MEDIA_LEADER", "新媒体部长", 3, "ALL"),
            mkRole("TECH_LEADER", "青年科技部长", 3, "ALL"),
            mkRole("STAFF", "干事", 1, "DEPT")
        };
        for (Role r : roles) roleRepository.save(r);

        saveUser("teacher", "指导老师", null, roleByCode(roles, "TEACHER"));
        saveUser("zhuren", "主任", null, roleByCode(roles, "DIRECTOR"));
        saveUser("orgleader", "组织部长", org, roleByCode(roles, "ORG_LEADER"));
        saveUser("secleader", "文秘部长", depts[0], roleByCode(roles, "SECRETARY_LEADER"));
        saveUser("medialeader", "新媒体部长", depts[2], roleByCode(roles, "MEDIA_LEADER"));
        saveUser("techleader", "青年科技部长", depts[3], roleByCode(roles, "TECH_LEADER"));
        saveUser("admin", "系统管理员", null, roleByCode(roles, "DIRECTOR"));
    }

    private Department mkDept(String name, int sort) {
        Department d = new Department();
        d.setName(name); d.setSortOrder(sort);
        d.setCreatedAt(LocalDateTime.now()); d.setUpdatedAt(LocalDateTime.now());
        return d;
    }

    private Role mkRole(String code, String name, int level, String dataScope) {
        Role r = new Role();
        r.setCode(code); r.setName(name); r.setLevel(level); r.setDataScope(dataScope);
        r.setCreatedAt(LocalDateTime.now());
        return r;
    }

    private Role roleByCode(Role[] roles, String code) {
        for (Role r : roles) if (r.getCode().equals(code)) return r;
        throw new IllegalStateException("role not found: " + code);
    }

    private void saveUser(String username, String realName, Department dept, Role role) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("123456"));
        u.setRealName(realName);
        u.setDept(dept);
        u.setRole(role);
        u.setStatus(1);
        u.setCreatedAt(LocalDateTime.now());
        u.setUpdatedAt(LocalDateTime.now());
        u.setDeleted(0);
        userRepository.save(u);
    }
}
