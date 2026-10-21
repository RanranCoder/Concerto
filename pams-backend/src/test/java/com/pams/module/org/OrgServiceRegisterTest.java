package com.pams.module.org;

import com.pams.common.BizException;
import com.pams.common.OrgContext;
import com.pams.module.org.dto.OrgRegisterRequest;
import com.pams.module.org.dto.OrgUnitVO;
import com.pams.module.org.dto.UnitSaveRequest;
import com.pams.module.org.entity.OrgPosition;
import com.pams.module.org.service.OrgService;
import com.pams.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 组织注册与架构（阶段 1）集成测试。
 *
 * <p>V39 统一为组织内节点名唯一；跨组织同名、负责人归属与历史引用见 OrgStructureManagementTest。
 * 每个用例 {@code @Transactional} 回滚，互不影响。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OrgServiceRegisterTest {

    @Autowired
    OrgService orgService;
    @Autowired
    UserRepository userRepository;

    private OrgRegisterRequest sampleRequest(String orgName, String adminUsername) {
        OrgRegisterRequest r = new OrgRegisterRequest();
        OrgRegisterRequest.OrgInfo info = new OrgRegisterRequest.OrgInfo();
        info.setName(orgName);
        info.setType("STUDENT_UNION");
        info.setThemeColor("#123456");
        r.setOrg(info);

        OrgRegisterRequest.AdminInfo admin = new OrgRegisterRequest.AdminInfo();
        admin.setUsername(adminUsername);
        admin.setPassword("123456");
        admin.setRealName("测试管理员");
        r.setAdmin(admin);

        OrgRegisterRequest.UnitNode root = new OrgRegisterRequest.UnitNode();
        root.setName("主席团");
        root.setUnitType("中心");
        OrgRegisterRequest.UnitNode child = new OrgRegisterRequest.UnitNode();
        child.setName("秘书处");
        child.setUnitType("部门");
        root.setChildren(List.of(child));
        r.setUnits(List.of(root));
        return r;
    }

    @Test
    void register_createsOrgUnitsPositionsAndAdmin() {
        Map<String, Object> res = orgService.register(sampleRequest("测试学生会", "orgadmin"));
        Long orgId = (Long) res.get("orgId");
        assertThat(orgId).isNotNull();
        assertThat(res.get("adminUsername")).isEqualTo("orgadmin");

        // 管理员落库且 orgId 正确（显式归属，不依赖默认组织兜底）
        var admin = userRepository.findByUsername("orgadmin").orElseThrow();
        assertThat(admin.getOrgId()).isEqualTo(orgId);

        OrgContext.set(orgId);
        try {
            // 架构树
            List<OrgUnitVO> tree = orgService.listUnits();
            assertThat(tree).hasSize(1);
            assertThat(tree.get(0).getName()).isEqualTo("主席团");
            assertThat(tree.get(0).getUnitType()).isEqualTo("中心");
            assertThat(tree.get(0).getChildren()).hasSize(1);
            assertThat(tree.get(0).getChildren().get(0).getName()).isEqualTo("秘书处");
            assertThat(tree.get(0).getPath()).startsWith("/");
            assertThat(tree.get(0).getLevel()).isEqualTo(1);
            assertThat(tree.get(0).getChildren().get(0).getLevel()).isEqualTo(2);

            // 岗位映射：中心→DIRECTOR、部门→ORG_LEADER
            List<OrgPosition> positions = orgService.listPositions();
            assertThat(positions).extracting(OrgPosition::getName)
                    .contains("总负责人", "中心负责人", "部门负责人");
            assertThat(positions).filteredOn(p -> "中心负责人".equals(p.getName()))
                    .extracting(OrgPosition::getRoleCode).containsExactly("DIRECTOR");
            assertThat(positions).filteredOn(p -> "部门负责人".equals(p.getName()))
                    .extracting(OrgPosition::getRoleCode).containsExactly("ORG_LEADER");
        } finally {
            OrgContext.clear();
        }
    }

    @Test
    void register_duplicateOrgName_rejected() {
        orgService.register(sampleRequest("重复组织", "dupadmin1"));
        assertThatThrownBy(() -> orgService.register(sampleRequest("重复组织", "dupadmin2")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void register_duplicateUsername_rejected() {
        orgService.register(sampleRequest("组织甲", "sameuser"));
        assertThatThrownBy(() -> orgService.register(sampleRequest("组织乙", "sameuser")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void register_missingNameOrAdmin_rejected() {
        OrgRegisterRequest blankName = sampleRequest("组织丙", "adminc");
        blankName.getOrg().setName("   ");
        assertThatThrownBy(() -> orgService.register(blankName)).isInstanceOf(BizException.class);

        OrgRegisterRequest noAdmin = sampleRequest("组织丁", null);
        noAdmin.getAdmin().setPassword(null);
        assertThatThrownBy(() -> orgService.register(noAdmin)).isInstanceOf(BizException.class);
    }

    @Test
    void unitCrud_flow() {
        Map<String, Object> res = orgService.register(sampleRequest("架构测试组织", "archadmin"));
        Long orgId = (Long) res.get("orgId");
        OrgContext.set(orgId);
        try {
            UnitSaveRequest add = new UnitSaveRequest();
            add.setName("宣传部");
            add.setUnitType("部门");
            Long newId = orgService.createUnit(add);
            assertThat(newId).isNotNull();

            // 同名拒绝
            assertThatThrownBy(() -> orgService.createUnit(add)).isInstanceOf(BizException.class);

            // 有子节点时删除父节点被拒
            Long rootId = orgService.listUnits().get(0).getId();
            assertThatThrownBy(() -> orgService.deleteUnit(rootId)).isInstanceOf(BizException.class);

            // 删除叶子节点成功
            orgService.deleteUnit(newId);
            assertThat(orgService.listUnits()).flatExtracting(OrgUnitVO::getName).doesNotContain("宣传部");
        } finally {
            OrgContext.clear();
        }
    }
}
