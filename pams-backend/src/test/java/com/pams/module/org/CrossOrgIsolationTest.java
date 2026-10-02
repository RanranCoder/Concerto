package com.pams.module.org;

import com.pams.common.OrgContext;
import com.pams.entity.Department;
import com.pams.entity.Role;
import com.pams.entity.User;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.repository.DepartmentRepository;
import com.pams.repository.RoleRepository;
import com.pams.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨组织隔离矩阵（A3 收尾）：业务实体（{@code @TenantId} 自动过滤）
 * 与结构/账号表（显式按组织过滤）都不得跨组织可见。
 *
 * <p>刻意不加 {@code @Transactional}：每次调用各自成事务，切换 {@link OrgContext} 才生效。
 * 用完即删。
 */
@SpringBootTest
@ActiveProfiles("test")
class CrossOrgIsolationTest {

    private static final Long ORG_A = 1L;
    private static final Long ORG_B = 2L;

    @Autowired ActivityRepository activityRepository;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;

    @AfterEach
    void clear() {
        OrgContext.clear();
    }

    @Test
    void businessEntity_isolatedByTenantFilter() {
        OrgContext.set(ORG_B);
        Activity a = new Activity();
        a.setName("跨组织矩阵-活动B");
        a.setType("OTHER");
        a.setStatus(ActivityStatus.ASSIGNED);
        a.setStartDate(LocalDate.now());
        a.setDeleted(0);
        a.setCreatedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        Long id = activityRepository.save(a).getId();
        try {
            OrgContext.set(ORG_A);
            assertThat(activityRepository.findById(id)).isEmpty();
            OrgContext.set(ORG_B);
            assertThat(activityRepository.findById(id)).isPresent();
        } finally {
            OrgContext.set(ORG_B);
            activityRepository.deleteById(id);
        }
    }

    @Test
    void structureTable_scopedExplicitly() {
        // sys_department 不参与 @TenantId；由查询方法显式按组织过滤
        Department d = new Department();
        d.setOrgId(ORG_B);
        d.setName("跨组织矩阵-部门B");
        d.setUnitType("部门");
        d.setLevel(1);
        d.setCreatedAt(LocalDateTime.now());
        d.setUpdatedAt(LocalDateTime.now());
        Long id = departmentRepository.save(d).getId();
        try {
            assertThat(departmentRepository.findByOrgIdOrderBySortOrderAscIdAsc(ORG_A))
                    .extracting(Department::getId).doesNotContain(id);
            assertThat(departmentRepository.findByOrgIdOrderBySortOrderAscIdAsc(ORG_B))
                    .extracting(Department::getId).contains(id);
        } finally {
            departmentRepository.deleteById(id);
        }
    }

    @Test
    void accountTable_scopedExplicitly() {
        Role director = roleRepository.findAll().stream()
                .filter(r -> "DIRECTOR".equals(r.getCode())).findFirst().orElseThrow();
        User u = new User();
        u.setUsername("crossorg_matrix_user");
        u.setPassword("x");
        u.setRealName("跨组织矩阵-账号B");
        u.setRole(director);
        u.setStatus(1);
        u.setOrgId(ORG_B);
        u.setDeleted(0L);
        u.setCreatedAt(LocalDateTime.now());
        u.setUpdatedAt(LocalDateTime.now());
        Long id = userRepository.save(u).getId();
        try {
            // 通知广播等按组织取收件人：A 组织取不到 B 组织的人
            assertThat(userRepository.findByOrgIdAndRoleCode(ORG_A, "DIRECTOR"))
                    .extracting(User::getId).doesNotContain(id);
            assertThat(userRepository.findByOrgIdAndRoleCode(ORG_B, "DIRECTOR"))
                    .extracting(User::getId).contains(id);
        } finally {
            userRepository.deleteById(id);
        }
    }
}
