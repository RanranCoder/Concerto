package com.pams.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {
    private final SecretKey key;
    private final long expireMillis;

    public JwtUtil(@Value("${pams.jwt.secret}") String secret,
                   @Value("${pams.jwt.expire-hours}") long expireHours) {
        // 2026-08 审计 P1 修复：仓库内不再有固定默认密钥。正常本地启动由 application.yml
        // 回退为每次启动随机密钥；此处守卫仅拦截「环境变量被显式设置为空串」的配置错误，
        // 避免空密钥静默签发 token。
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT 签名密钥为空：请检查 PAMS_JWT_SECRET 环境变量是否被设置为空字符串"
                            + "（未设置时本地会自动使用随机密钥，生产部署必须显式注入）");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireHours * 3600_000L;
    }

    public String generate(Long userId, String username, String roleCode) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .claim("role", roleCode)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }
}
