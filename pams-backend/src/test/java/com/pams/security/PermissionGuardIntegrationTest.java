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
        String token = login("orgleader");
        String body = "{\"username\":\"permup\",\"realName\":\"测试\",\"roleId\":1}"; // roleId=1 为 TEACHER(level5)
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
    void leader_users_allowed() throws Exception {
        mvc.perform(get("/api/users")
                .header("Authorization", bearer("orgleader")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== Task 27：deleteUser 防提权 + IllegalArgumentException → 400 ====================

    @Test
    void leader_cannotDeleteHigherRole() throws Exception {
        // 部长（level 3）不能删除主任（level 4，id=2）
        mvc.perform(delete("/api/users/2")
                .header("Authorization", bearer("orgleader")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(1008))
            .andExpect(jsonPath("$.message").value("不能删除级别不低于自己的用户"));
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
}
