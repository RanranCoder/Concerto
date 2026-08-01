package com.pams.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import com.pams.entity.User;
import com.pams.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, UserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtUtil.parse(header.substring(7));
                String username = claims.getSubject();
                User u = userRepository.findByUsername(username).orElse(null);
                if (u != null) {
                    LoginUser lu = new LoginUser(
                            u.getId(), u.getUsername(), u.getRealName(),
                            u.getRole().getCode(), u.getRole().getLevel(),
                            u.getDept() == null ? null : u.getDept().getId(),
                            u.getDept() == null ? null : u.getDept().getName());
                    // 权限用 "ROLE_" 前缀的角色码，配合 @PreAuthorize("hasAnyRole('DIRECTOR',...)") 使用
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(lu, null,
                                    List.of(new SimpleGrantedAuthority("ROLE_" + lu.getRoleCode())));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                // token 无效，保持匿名，后续 Security 会拦截
            }
        }
        chain.doFilter(request, response);
    }
}
