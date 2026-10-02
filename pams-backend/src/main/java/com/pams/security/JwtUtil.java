package com.pams.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
        return generate(userId, username, roleCode, null);
    }

    /**
     * 生成 JWT。tokenInvalidBefore 为该用户当前的吊销水位（可空，审计 #3）：
     * JwtAuthenticationFilter 会拒绝 iat 早于水位的 token。iat 与水位同为秒粒度，
     * 若「当前整秒」早于水位（如登出后同一秒内立刻重新登录），把 iat 推进到水位处，
     * 避免新 token 签出即被当成旧 token 拒掉。
     */
    public String generate(Long userId, String username, String roleCode, LocalDateTime tokenInvalidBefore) {
        long iatEpochSecond = System.currentTimeMillis() / 1000;
        if (tokenInvalidBefore != null) {
            iatEpochSecond = Math.max(iatEpochSecond, epochSecond(tokenInvalidBefore));
        }
        Date iat = new Date(iatEpochSecond * 1000);
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .claim("role", roleCode)
                .issuedAt(iat)
                .expiration(new Date(iat.getTime() + expireMillis))
                .signWith(key)
                .compact();
    }

    /**
     * 吊销水位写入值（审计 #3）：向上取整到下一整秒。DATETIME 秒精度（MySQL 截断小数秒）
     * 下保证「登出时刻所在整秒内签发的旧 token」也被作废（iat ≤ 当前秒 < 水位），且 MySQL/H2 行为一致。
     * previous 为该用户现有水位：水位单调推进（至少 +1 秒），否则同一秒内「再次吊销」产生的水位
     * 与旧水位相同，无法作废 iat 已被 generate 推进到旧水位处的 token。
     * 语义：iat 早于该值的 token 一律吊销。
     */
    public static LocalDateTime revocationWatermark(LocalDateTime now, LocalDateTime previous) {
        LocalDateTime next = now.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
        if (previous != null && !next.isAfter(previous)) {
            next = previous.plusSeconds(1);
        }
        return next;
    }

    /** LocalDateTime → epoch 秒（与 JWT iat 同粒度）。 */
    public static long epochSecond(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    /** JWT iat（Date）→ epoch 秒。 */
    public static long epochSecond(Date issuedAt) {
        return issuedAt.getTime() / 1000;
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }
}
