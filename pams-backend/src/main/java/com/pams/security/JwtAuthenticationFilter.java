package com.pams.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import com.pams.entity.User;
import com.pams.module.permission.service.PermissionService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final PermissionService permissionService;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, UserRepository userRepository, PermissionService permissionService) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.permissionService = permissionService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null) {
            try {
                Claims claims = jwtUtil.parse(token);
                String username = claims.getSubject();
                User u = userRepository.findByUsername(username).orElse(null);
                // 已禁用用户（status=0 或 null）即使持有有效 JWT 也不放行，保持匿名走 401。
                // 与 AuthController.login 的 status 判定保持一致。
                if (u != null && u.getStatus() != null && u.getStatus() != 0) {
                    String roleCode = u.getRole().getCode();
                    LoginUser lu = new LoginUser(
                            u.getId(), u.getUsername(), u.getRealName(),
                            roleCode, u.getRole().getLevel(),
                            u.getDept() == null ? null : u.getDept().getId(),
                            u.getDept() == null ? null : u.getDept().getName());
                    // 双轨注入：ROLE_ 角色码（现有 hasRole/hasAnyRole 继续生效）+ 该角色权限码（hasAuthority 生效）。
                    // 权限码来自 role_permission 表，由权限管理页配置；每请求查一次 DB，dev 可接受。
                    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + roleCode));
                    Set<String> codes = permissionService.permissionsOfRole(roleCode);
                    for (String code : codes) {
                        authorities.add(new SimpleGrantedAuthority(code));
                    }
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(lu, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                // token 无效，保持匿名，后续 Security 会拦截
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * 解析请求凭证：优先 Authorization: Bearer 头（常规 API）。
     * <img>/<a> 直链（文章长图、策划书插图、群二维码等）无法携带请求头，
     * 此时对「文件类 GET」降级读取登录时下发的 pams_token Cookie（HttpOnly）。
     * Cookie 兜底只开放给文件读取路径，避免其成为通用认证通道。
     */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        if (!"GET".equalsIgnoreCase(request.getMethod()) || !isFileGetPath(request.getRequestURI())) {
            return null;
        }
        if (request.getCookies() == null) {
            return null;
        }
        for (jakarta.servlet.http.Cookie c : request.getCookies()) {
            if ("pams_token".equals(c.getName()) && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }

    private boolean isFileGetPath(String uri) {
        if (uri == null) return false;
        // /uploads/2026-08-02/xxx.png（历史直链）与 /api/files/{id}/download
        return uri.startsWith("/uploads/")
                || FILE_DOWNLOAD_PATTERN.matcher(uri).matches();
    }

    private static final java.util.regex.Pattern FILE_DOWNLOAD_PATTERN =
            java.util.regex.Pattern.compile("^/api/files/\\d+/download$");
}
