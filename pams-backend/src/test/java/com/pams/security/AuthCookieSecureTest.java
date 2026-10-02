package com.pams.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计 #3 附属：登录下发的 pams_token Cookie 缺少 Secure 属性。
 * Secure 由环境变量开关控制（PAMS_COOKIE_SECURE），默认 false 不破坏本地 http 开发，
 * 置 true 的 https 部署必须带上 Secure。本测试验证开关打开时 Cookie 带 Secure 且保留 HttpOnly。
 */
@SpringBootTest(properties = "PAMS_COOKIE_SECURE=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthCookieSecureTest {

    @Autowired
    MockMvc mvc;

    @Test
    void login_fileCookie_markedSecureAndHttpOnly_whenCookieSecureEnabled() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"zhuren\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", allOf(
                        containsString("pams_token="),
                        containsString("; Secure"),
                        containsString("HttpOnly"))));
    }
}
