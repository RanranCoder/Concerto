package com.pams.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
        // 说明：简报原版访问 /api/users，但该接口属 Task 5 用户管理，本任务尚未实现。
        // 这里改访问不存在的受保护路径 /api/nonexistent 验证未认证请求被 Security 拦截返回 401，
        // Task 5 补齐 /api/users 后可恢复为 get("/api/users")。
        mvc.perform(get("/api/nonexistent")).andExpect(status().isUnauthorized());
    }
}
