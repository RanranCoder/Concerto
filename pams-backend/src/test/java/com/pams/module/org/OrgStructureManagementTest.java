package com.pams.module.org;

import com.pams.common.BizException;
import com.pams.common.OrgContext;
import com.pams.module.org.dto.OrgRegisterRequest;
import com.pams.module.org.dto.UnitSaveRequest;
import com.pams.module.org.service.OrgService;
import com.pams.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OrgStructureManagementTest {
    @Autowired OrgService service;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @AfterEach void clear() { OrgContext.clear(); }

    OrgRegisterRequest request(String suffix) {
        var req = new OrgRegisterRequest();
        var org = new OrgRegisterRequest.OrgInfo(); org.setName("架构管理测试" + suffix); org.setType("CLUB"); org.setThemeColor("#1668DC"); req.setOrg(org);
        var admin = new OrgRegisterRequest.AdminInfo(); admin.setUsername("org-structure-" + suffix); admin.setPassword("demo-password"); req.setAdmin(admin);
        var root = new OrgRegisterRequest.UnitNode(); root.setName("共同组织部"); root.setUnitType("部门");
        var child = new OrgRegisterRequest.UnitNode(); child.setName("共同培训组"); child.setUnitType("小组"); root.setChildren(List.of(child)); req.setUnits(List.of(root));
        return req;
    }
    Long register(String suffix) { return (Long) service.register(request(suffix)).get("orgId"); }
    UnitSaveRequest unit(String name, Long leader) { var req = new UnitSaveRequest(); req.setName(name); req.setUnitType("小组"); req.setLeaderUserId(leader); return req; }

    @Test void sameNamesAcrossTenantsAreAllowedButNotWithinTenant() {
        Long a = register("name-a"), b = register("name-b");
        em.flush();
        OrgContext.set(a);
        assertThat(service.listUnits().getFirst().getName()).isEqualTo("共同组织部");
        assertThatThrownBy(() -> service.createUnit(unit("共同培训组", null))).isInstanceOf(BizException.class);
        OrgContext.set(b);
        assertThat(service.listUnits().getFirst().getName()).isEqualTo("共同组织部");
    }
    @Test void directoryIsTenantScopedIncludesDisabledAndDoesNotGrantRolesWhenSettingLeader() {
        Long a = register("dir-a"), b = register("dir-b");
        var adminA = users.findByUsername("org-structure-dir-a").orElseThrow();
        var adminB = users.findByUsername("org-structure-dir-b").orElseThrow();
        var role = adminA.getRole().getId();
        OrgContext.set(a);
        var root = service.listUnits().getFirst();
        assertThat(service.structure().accounts()).extracting(x -> x.id()).containsExactly(adminA.getId());
        assertThatThrownBy(() -> service.createUnit(unit("外部负责人", adminB.getId()))).isInstanceOf(BizException.class).hasMessageContaining("当前组织");
        service.updateUnit(root.getId(), unit(root.getName(), adminA.getId()));
        assertThat(users.findById(adminA.getId()).orElseThrow().getRole().getId()).isEqualTo(role);
        adminA.setStatus(0); users.saveAndFlush(adminA);
        assertThat(service.structure().accounts()).singleElement().satisfies(x -> assertThat(x.status()).isZero());
        assertThatThrownBy(() -> service.createUnit(unit("停用负责人", adminA.getId()))).isInstanceOf(BizException.class).hasMessageContaining("正常启用");
        OrgContext.set(b);
        assertThat(service.structure().accounts()).extracting(x -> x.id()).containsExactly(adminB.getId());
        assertThatThrownBy(() -> service.updateUnit(root.getId(), unit("跨组织修改", null))).isInstanceOf(BizException.class);
    }
    @Test void registrationRejectsEmptyDuplicateOrExternalLeaderTreesBeforeCreatingAnything() {
        var req = request("invalid"); req.setUnits(List.of());
        assertThatThrownBy(() -> service.register(req)).isInstanceOf(BizException.class);
        req.setUnits(request("invalid").getUnits()); req.getUnits().getFirst().getChildren().getFirst().setName("共同组织部");
        assertThatThrownBy(() -> service.register(req)).isInstanceOf(BizException.class).hasMessageContaining("重复");
        req.setUnits(request("invalid").getUnits()); req.getUnits().getFirst().setLeaderUserId(1L);
        assertThatThrownBy(() -> service.register(req)).isInstanceOf(BizException.class).hasMessageContaining("创建后");
        assertThat(users.findByUsername("org-structure-invalid")).isEmpty();
    }
    @Test void deletedAccountsAndHistoricalRecordsPreventDeletingTheirDepartment() {
        OrgContext.set(register("history"));
        Long historical = service.createUnit(unit("历史节点", null));
        em.flush();
        jdbc.update("INSERT INTO no_class_schedule(dept_id, dept_name, semester, grid_json, org_id) VALUES(?,?,?,?,?)", historical, "历史节点", "2026", "[]", OrgContext.get());
        assertThatThrownBy(() -> service.deleteUnit(historical)).isInstanceOf(BizException.class).hasMessageContaining("历史");
        service.updateUnit(historical, unit("历史节点改名", null));
        assertThat(jdbc.queryForObject("SELECT dept_name FROM no_class_schedule WHERE dept_id=?", String.class, historical)).isEqualTo("历史节点改名");
        Long accountNode = service.createUnit(unit("停用账号节点", null));
        var admin = users.findByUsername("org-structure-history").orElseThrow();
        jdbc.update("UPDATE sys_user SET dept_id=?, status=0, deleted=99 WHERE id=?", accountNode, admin.getId());
        assertThatThrownBy(() -> service.deleteUnit(accountNode)).isInstanceOf(BizException.class).hasMessageContaining("成员");
        Long empty = service.createUnit(unit("可删除节点", null)); em.flush();
        service.deleteUnit(empty); em.flush();
        assertThat(service.listUnits()).noneMatch(n -> n.getId().equals(empty));
    }
}
