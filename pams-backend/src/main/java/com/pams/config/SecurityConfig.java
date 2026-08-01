package com.pams.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Task 3 临时安全配置：pom 已引入 spring-boot-starter-security，若不加任何配置，
 * Spring Boot 默认安全策略会把 /api/ping 也 401（冒烟无法通过）。
 * 这里仅放行 /api/ping 与 CORS 预检，其余保持默认（需认证）。
 * Task 4 会以完整的 JWT SecurityConfig 覆盖本文件。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/ping").permitAll()
                .anyRequest().authenticated());
        return http.build();
    }
}
