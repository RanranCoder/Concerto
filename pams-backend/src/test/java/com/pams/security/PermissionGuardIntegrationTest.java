package com.pams.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Task 26 角色守卫集成测试：
 * - 干事（STAFF）调用活动删除/改状态、公告发布、党务成员写操作 → 403
 * - 部长及以上调用上述写操作 → 放行
 * - 干事访问用户管理 → 403
 * - 部长不能创建主任/指导老师（level 防提权）→ 业务 400
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PermissionGuardIntegrationTest {

    @Autowired
    MockMvc mvc;

    private String login(String username) throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andReturn();
        String body = res.getResponse().getContentAsString();
        return body.split("\"token\":\"")[1].split("\"")[0];
    }

    private String bearer(String username) throws Exception {
        return "Bearer " + login(username);
    }

    @Test
    void staff_activityStatus_forbidden() throws Exception {
        mvc.perform(put("/api/activities/1/status")
                .header("Authorization", bearer("staff"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"PLANNING\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void staff_activityDelete_forbidden() throws Exception {
        mvc.perform(delete("/api/activities/1")
                .header("Authorization", bearer("staff")))
            .andExpect(status().isForbidden());
    }

    @Test
    void staff_announcementCreate_forbidden() throws Exception {
        mvc.perform(post("/api/announcements")
                .header("Authorization", bearer("staff"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"t\",\"content\":\"c\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void staff_announcementDelete_forbidden() throws Exception {
        mvc.perform(delete("/api/announcements/1")
                .header("Authorization", bearer("staff")))
            .andExpect(status().isForbidden());
    }

    @Test
    void staff_partyMemberCreate_forbidden() throws Exception {
        mvc.perform(post("/api/party/members")
                .header("Authorization", bearer("staff"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"某人\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void staff_partyMemberStage_forbidden() throws Exception {
        mvc.perform(put("/api/party/members/1/stage")
                .header("Authorization", bearer("staff"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"stage\":\"ACTIVE\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void staff_users_forbidden() throws Exception {
        mvc.perform(get("/api/users")
                .header("Authorization", bearer("staff")))
            .andExpect(status().isForbidden());
    }

    @Test
    void leader_activityStatus_allowed() throws Exception {
        // 先建一条活动（ASSIGNED），再推进到 PLANNING（合法状态机迁移）
        String token = login("orgleader");
        MvcResult created = mvc.perform(post("/api/activities")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"权限测试活动\",\"type\":\"MEETING\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn();
        String id = created.getResponse().getContentAsString().split("\"data\":")[1].split("[^0-9]")[0];
        mvc.perform(put("/api/activities/" + id + "/status")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"PLANNING\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void leader_partyMemberCreate_allowed() throws Exception {
        mvc.perform(post("/api/party/members")
                .header("Authorization", bearer("orgleader"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"测试成员\",\"studentNo\":\"PERM0001\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void leader_cannotCreateHigherRole() throws Exception {
        // PRD-v5 S2 后用户管理需 user:manage（仅 TEACHER/DIRECTOR）。
        // 主任（level 4）尝试创建指导老师（roleId=1，level 5）→ 1007 防提权
        String token = login("zhuren");
        String body = "{\"username\":\"permup\",\"realName\":\"测试\",\"roleId\":1}";
        MvcResult res = mvc.perform(post("/api/users")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(1007))
            .andReturn();
        // 幂等：成功后端拒绝，未写入
        org.assertj.core.api.Assertions.assertThat(res.getResponse().getContentAsString())
            .contains("不能授予高于自己级别的角色");
    }

    @Test
    void director_users_allowed() throws Exception {
        // user:view（V7 种子：仅 TEACHER/DIRECTOR）——主任可查用户列表
        mvc.perform(get("/api/users")
                .header("Authorization", bearer("zhuren")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== Task 27：deleteUser 防提权 + IllegalArgumentException → 400 ====================

    @Test
    void director_cannotDeleteHigherRole() throws Exception {
        // 主任（level 4，id=2）不能删除指导老师（level 5，id=1）
        mvc.perform(delete("/api/users/1")
                .header("Authorization", bearer("zhuren")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(1008))
            .andExpect(jsonPath("$.message").value("不能删除级别不低于自己的用户"));
    }

    // ==================== A1：resetPassword 防提权（重置密码即账号接管） ====================

    @Test
    void director_cannotResetHigherRolePassword() throws Exception {
        // 重置密码后新密码归操作者掌握，等同账号接管：主任（level 4）不能重置指导老师（level 5，id=1）
        // 的密码，口径与 deleteUser 一致（目标级别 ≥ 自己级别即拒绝，错误码同为 1008）。
        mvc.perform(post("/api/users/1/reset-password")
                .header("Authorization", bearer("zhuren")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(1008))
            .andExpect(jsonPath("$.message").value("不能重置级别不低于自己的账号密码"));
    }

    @Test
    void director_canResetStaffPassword() throws Exception {
        // 对照用例：主任可以重置干事（STAFF，种子固定第 8 个插入 → id=8）的密码。
        // 固定 id 与上方 /api/users/1 同一约定：DataSeeder 空库按固定顺序种入，
        // 测试套件只增不删种子账号，id 稳定；重置结果仍是默认密码 123456，天然幂等。
        mvc.perform(post("/api/users/8/reset-password")
                .header("Authorization", bearer("zhuren")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void changeStatus_invalidEnum_returns400() throws Exception {
        // 非法枚举：BizException(2009) 由全局异常处理返回 HTTP 200 + JSON code=2009
        mvc.perform(put("/api/activities/1/status")
                .header("Authorization", bearer("orgleader"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"BOGUS\"}"))
            .andExpect(jsonPath("$.code").value(2009));
    }

    // ===== RBAC 双轨（hasAuthority）回归 =====

    @Test
    void staff_creditsDelete_forbidden() throws Exception {
        // STAFF 无 quality:delete，素拓删除接口必须 403（DELETE 无 body 校验，@PreAuthorize 先于方法体）
        mvc.perform(delete("/api/credits/1")
                .header("Authorization", bearer("staff")))
            .andExpect(status().isForbidden());
    }

    @Test
    void staff_membersWrite_forbidden() throws Exception {
        // STAFF 无 member:view，MemberController 类级鉴权必须 403
        mvc.perform(post("/api/members")
                .header("Authorization", bearer("staff"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void teacher_permissionsApi_allowed() throws Exception {
        // TEACHER 有 user:permission，权限管理接口放行（验证 hasAuthority 链路对权限管理页生效）
        mvc.perform(get("/api/permissions")
                .header("Authorization", bearer("teacher")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== PRD-v5 S2：双轨权限收敛回归（角色宏 → 权限码） ====================

    @Test
    void orgleader_activityDelete_forbidden() throws Exception {
        // activity:delete 仅 TEACHER/DIRECTOR（V7 种子）：部长级删除活动 → 403
        mvc.perform(delete("/api/activities/1")
                .header("Authorization", bearer("orgleader")))
            .andExpect(status().isForbidden());
    }

    @Test
    void orgleader_materialDelete_forbidden() throws Exception {
        // material:delete 仅 TEACHER/DIRECTOR：部长级删除材料 → 403
        mvc.perform(delete("/api/materials/1")
                .header("Authorization", bearer("orgleader")))
            .andExpect(status().isForbidden());
    }

    @Test
    void orgleader_templateCreate_forbidden() throws Exception {
        // template:manage 仅 TEACHER/DIRECTOR：部长级上传模板 → 403
        mvc.perform(post("/api/templates")
                .header("Authorization", bearer("orgleader"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"t\",\"category\":\"PLAN\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void orgleader_chatCategory_forbidden() throws Exception {
        // chat:category 仅 TEACHER/DIRECTOR：部长级新增群聊分类 → 403
        mvc.perform(post("/api/chat/categories")
                .header("Authorization", bearer("orgleader"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"新分类\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void orgleader_users_forbidden() throws Exception {
        // user:view 仅 TEACHER/DIRECTOR（S2 收敛后部长不再可访问用户管理）
        mvc.perform(get("/api/users")
                .header("Authorization", bearer("orgleader")))
            .andExpect(status().isForbidden());
    }

    @Test
    void staff_signinList_allowed() throws Exception {
        // checkin:view（V7 种子：干事含签到查看）——干事可查签到记录
        mvc.perform(get("/api/signins")
                .param("activityId", "1")
                .header("Authorization", bearer("staff")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void staff_rosterList_allowed() throws Exception {
        // 类级 checkin:view：干事可查应签名单
        mvc.perform(get("/api/signins/roster")
                .param("activityId", "1")
                .header("Authorization", bearer("staff")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void orgleader_newsWrite_allowed() throws Exception {
        // 内容模块保留角色宏口径（部门分工设计）：部长可撰写新闻稿
        mvc.perform(post("/api/news")
                .header("Authorization", bearer("orgleader"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"权限回归新闻稿\",\"content\":\"正文\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }
}
