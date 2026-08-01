package com.pams.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class JwtUtilTest {

    @Autowired
    JwtUtil jwtUtil;

    @Value("${pams.jwt.secret}")
    String secret;

    private SecretKey secretKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void generateAndParse_returnsSubjectAndClaims() {
        String token = jwtUtil.generate(42L, "zhuren", "DIRECTOR");
        Claims claims = jwtUtil.parse(token);
        assertThat(claims.getSubject()).isEqualTo("zhuren");
        assertThat(claims.get("uid", Long.class)).isEqualTo(42L);
        assertThat(claims.get("role", String.class)).isEqualTo("DIRECTOR");
    }

    @Test
    void parse_tamperedToken_throws() {
        String token = jwtUtil.generate(42L, "zhuren", "DIRECTOR");
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThatThrownBy(() -> jwtUtil.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void parse_expiredToken_throws() {
        long now = System.currentTimeMillis();
        String expired = Jwts.builder()
                .subject("zhuren")
                .claim("uid", 42L)
                .claim("role", "DIRECTOR")
                .issuedAt(new Date(now - 200_000L))
                .expiration(new Date(now - 100_000L))
                .signWith(secretKey())
                .compact();
        assertThatThrownBy(() -> jwtUtil.parse(expired)).isInstanceOf(JwtException.class);
    }
}
