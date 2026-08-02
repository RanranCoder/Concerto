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
        userRepository.save(u);

        String token = jwtUtil.generate(u.getId(), u.getUsername(), u.getRole().getCode());
        mvc.perform(get("/api/users")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized());
    }
}
