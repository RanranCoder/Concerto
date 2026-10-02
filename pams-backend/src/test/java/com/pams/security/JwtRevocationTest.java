package com.pams.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.entity.Role;
import com.pams.entity.User;
import com.pams.repository.RoleRepository;
import com.pams.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计 #3：JWT 无吊销机制。
 * 登出/改密必须产生「服务端吊销」——用户级失效水位（sys_user.token_invalid_before），
 * 签发时刻（iat）早于水位的 token 一律 401，而不是只清前端存储/Cookie 让旧 token 活满 24h。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtRevocationTest {

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

    @Value("${pams.jwt.secret}")
    String secret;

    private String loginToken(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).path("data").path("token").asText();
    }

    private User freshUser(String prefix) {
        Role role = roleRepository.findByCode("STAFF").orElseThrow();
        User u = new User();
        u.setUsername(prefix + System.nanoTime());
        u.setPassword(passwordEncoder.encode("123456"));
        u.setRealName("吊销测试");
        u.setRole(role);
        u.setStatus(1);
        // User 实体有 @SQLRestriction("deleted = 0")，deleted 落 NULL 会导致查询不到该行
        u.setDeleted(0L);
        return userRepository.save(u);
    }

    /** 用测试密钥手工签发指定 iat 的 token（模拟「水位之前/之后签发」的会话）。
     *  exp 固定为当前时间 +2h（远在未来）：确保被拒只可能来自吊销校验，而非过期。 */
    private String craftToken(User u, Date iat) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(u.getUsername())
                .claim("uid", u.getId())
                .claim("role", u.getRole().getCode())
                .issuedAt(iat)
                .expiration(new Date(System.currentTimeMillis() + 7200_000L))
                .signWith(key)
                .compact();
    }

    // ===================== ① 登出即吊销：同一 token 再调用返回 401 =====================

    @Test
    void logout_revokesIssuedToken_subsequentRequestsUnauthorized() throws Exception {
        String token = loginToken("zhuren", "123456");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 修复前：logout 只清前端存储和 Cookie，Bearer 旧 token 仍可调用全部 API 直至 24h 过期
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    // ===================== ② 登出后新登录的 token 正常（吊销不殃及之后签发的会话） =====================

    @Test
    void loginAfterLogout_oldTokenStaysDead_newTokenWorks() throws Exception {
        String oldToken = loginToken("zhuren", "123456");
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isOk());

        String newToken = loginToken("zhuren", "123456");
        // 新登录签发的 token 必须正常可用
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk());
        // 旧 token 保持吊销
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());
    }

    // ===================== ③ 吊销水位边界：水位前签发被拒、之后签发放行 =====================

    @Test
    void tokenIssuedBeforeRevocationWatermark_rejected_afterWatermark_accepted() throws Exception {
        User u = freshUser("wm_");
        // 登出产生服务端吊销水位（约等于当前时刻）
        String sessionToken = jwtUtil.generate(u.getId(), u.getUsername(), u.getRole().getCode());
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + sessionToken))
                .andExpect(status().isOk());

        long nowSec = System.currentTimeMillis() / 1000;
        String oldToken = craftToken(u, new Date((nowSec - 3600) * 1000));   // 水位前签发
        String lateToken = craftToken(u, new Date((nowSec + 3600) * 1000));  // 水位后签发

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + lateToken))
                .andExpect(status().isOk());
    }

    // ===================== 改密/重置密码 同样推进水位，旧 token 吊销 =====================

    @Test
    void changePassword_revokesPreviouslyIssuedToken() throws Exception {
        User u = freshUser("pw_");
        String token = jwtUtil.generate(u.getId(), u.getUsername(), u.getRole().getCode());

        mvc.perform(post("/api/users/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"123456\",\"newPassword\":\"654321\"}"))
                .andExpect(status().isOk());

        // 修复前：改密后旧 token 仍可用（攻击者持旧 token 不受影响）
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminResetPassword_revokesTargetUsersTokens() throws Exception {
        User target = freshUser("rp_");
        String targetToken = jwtUtil.generate(target.getId(), target.getUsername(), target.getRole().getCode());
        String adminToken = loginToken("zhuren", "123456");

        mvc.perform(post("/api/users/" + target.getId() + "/reset-password")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // 修复前：管理员重置密码后，目标用户旧 token 仍可调用全部 API
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isUnauthorized());
    }
}
