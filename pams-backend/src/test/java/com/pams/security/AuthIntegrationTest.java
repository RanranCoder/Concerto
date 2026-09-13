package com.pams.security;

import com.pams.entity.Role;
import com.pams.entity.User;
import com.pams.repository.RoleRepository;
import com.pams.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    RoleRepository roleRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JwtUtil jwtUtil;

    @Test
    void login_success_returnsToken() throws Exception {
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"zhuren\",\"password\":\"123456\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void login_wrongPassword_fails() throws Exception {
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"zhuren\",\"password\":\"wrong\"}"))
            .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void noToken_returns401() throws Exception {
        // 说明：Task 5 已实现 /api/users，未认证访问应被 Security 拦截返回 401。
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void disabledUser_withValidToken_returns401() throws Exception {
        // 注册一个已禁用（status=0）账号并为其签发有效 JWT，访问受保护端点应被拦截 → 401
        // （JwtAuthenticationFilter 不放行禁用用户，保持匿名；原缺陷是持有效 JWT 仍可访问最长 72h）。
        Role role = roleRepository.findByCode("STAFF").orElseThrow();
        String name = "disabled_" + System.nanoTime();
        User u = new User();
        u.setUsername(name);
        u.setPassword(passwordEncoder.encode("123456"));
        u.setRealName("禁用测试");
        u.setRole(role);
        u.setStatus(0);
        // 关键：User 实体有 @SQLRestriction("deleted = 0")。若 deleted 不显式置 0（DB 落 NULL），
        // deleted = 0 为 NULL 恒假，findByUsername 查不到该行 → u==null 误走"用户不存在"的 401，
        // 该测试即使回退禁用用户校验也仍通过，无法隔离 status 分支。显式置 0 后才会真正命中
        // JwtAuthenticationFilter 的 status != 0 放行判定，形成有效回归保护。
        u.setDeleted(0L);
        userRepository.save(u);

        String token = jwtUtil.generate(u.getId(), u.getUsername(), u.getRole().getCode());
        mvc.perform(get("/api/users")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void enabledUser_withValidToken_returns200() throws Exception {
        // 对照：deleted=0 且 status=1 的正常用户持有效 JWT 应放行 → 200。
        // 与 disabledUser_... 成对证明"禁用才拦截、正常放行"；
        // 若哪天禁用用户检查被回退，本测试会继续通过而 disabledUser_... 会失败。
        // PRD-v5 S2 后 /api/users 需 user:view（仅 TEACHER/DIRECTOR），故用主任角色验证。
        Role role = roleRepository.findByCode("DIRECTOR").orElseThrow();
        String name = "enabled_" + System.nanoTime();
        User u = new User();
        u.setUsername(name);
        u.setPassword(passwordEncoder.encode("123456"));
        u.setRealName("正常测试");
        u.setRole(role);
        u.setStatus(1);
        u.setDeleted(0L);
        userRepository.save(u);

        String token = jwtUtil.generate(u.getId(), u.getUsername(), u.getRole().getCode());
        mvc.perform(get("/api/users")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void login_success_downloadsPermissions() throws Exception {
        // RBAC 双轨：登录响应必须下发 user.permissions，前端菜单/路由按权限码显隐
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"teacher\",\"password\":\"123456\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.user.permissions").isArray())
            .andExpect(jsonPath("$.data.user.permissions").isNotEmpty())
            .andExpect(jsonPath("$.data.user.permissions").value(org.hamcrest.Matchers.hasItem("activity:view")))
            .andExpect(jsonPath("$.data.user.permissions").value(org.hamcrest.Matchers.hasItem("member:view")));
    }

    // ===================== /uploads 收紧 + 文件直链 Cookie 兜底（S1） =====================

    @Test
    void login_success_setsHttpOnlyFileCookie() throws Exception {
        // 登录同时下发 pams_token HttpOnly Cookie（<img>/<a> 文件直链兜底）
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"zhuren\",\"password\":\"123456\"}"))
            .andExpect(status().isOk())
            .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                    org.hamcrest.Matchers.containsString("pams_token="),
                    org.hamcrest.Matchers.containsString("Path=/"),
                    org.hamcrest.Matchers.containsString("Max-Age="),
                    org.hamcrest.Matchers.containsString("HttpOnly"),
                    org.hamcrest.Matchers.containsString("SameSite=Lax"))));
    }

    @Test
    void uploads_anonymous_returns401() throws Exception {
        // 2026-08 审计 M4：/uploads/** 不再匿名直出
        mvc.perform(get("/uploads/2026-01-01/not-exist.png"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void uploads_withFileCookie_authenticated_not401() throws Exception {
        // 持 pams_token Cookie 的文件直链 GET 应通过鉴权（文件不存在 → 404 而非 401）
        String body = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"zhuren\",\"password\":\"123456\"}"))
            .andReturn().getResponse().getContentAsString();
        String jwt = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).path("data").path("token").asText();
        mvc.perform(get("/uploads/2026-01-01/not-exist.png").cookie(
                        new jakarta.servlet.http.Cookie("pams_token", jwt)))
                .andExpect(status().isNotFound());
    }

    @Test
    void fileCookie_notAcceptedOnNonFilePaths() throws Exception {
        // Cookie 兜底只开放文件类 GET：常规 API 仅凭 Cookie 不得放行（防 Cookie 成为通用认证通道）
        String body = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"zhuren\",\"password\":\"123456\"}"))
            .andReturn().getResponse().getContentAsString();
        String jwt = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).path("data").path("token").asText();
        mvc.perform(get("/api/users").cookie(new jakarta.servlet.http.Cookie("pams_token", jwt)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_clearsFileCookie() throws Exception {
        mvc.perform(post("/api/auth/logout"))
            .andExpect(status().isOk())
            .andExpect(header().string("Set-Cookie",
                    org.hamcrest.Matchers.containsString("Max-Age=0")));
    }
}
