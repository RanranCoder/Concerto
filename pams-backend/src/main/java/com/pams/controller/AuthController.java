package com.pams.controller;

import com.pams.common.BizException;
import com.pams.common.Result;
import com.pams.dto.LoginRequest;
import com.pams.dto.LoginResponse;
import com.pams.entity.User;
import com.pams.module.org.repository.OrganizationRepository;
import com.pams.module.permission.service.PermissionService;
import com.pams.repository.UserRepository;
import com.pams.security.JwtUtil;
import com.pams.security.LoginRateLimiter;
import com.pams.security.LoginUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    @org.springframework.beans.factory.annotation.Autowired
    private com.pams.module.permission.service.TenantAuthorizationService authorization;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final PermissionService permissionService;
    private final LoginRateLimiter loginRateLimiter;
    private final long jwtExpireHours;
    /** pams_token Cookie 是否加 Secure（审计 #3）：经环境变量 PAMS_COOKIE_SECURE 配置，
     *  默认 false 不破坏本地 http 开发；https 部署置 true。 */
    private final boolean cookieSecure;
    private final OrganizationRepository organizationRepository;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil,
                          PermissionService permissionService, LoginRateLimiter loginRateLimiter,
                          OrganizationRepository organizationRepository,
                          @Value("${pams.jwt.expire-hours:24}") long jwtExpireHours,
                          @Value("${PAMS_COOKIE_SECURE:false}") boolean cookieSecure) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.permissionService = permissionService;
        this.loginRateLimiter = loginRateLimiter;
        this.organizationRepository = organizationRepository;
        this.jwtExpireHours = jwtExpireHours;
        this.cookieSecure = cookieSecure;
    }

    /** 组织名（不存在时返回 null，前端按默认展示）。 */
    private String orgName(Long orgId) {
        if (orgId == null) {
            return null;
        }
        return organizationRepository.findById(orgId).map(o -> o.getName()).orElse(null);
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req, HttpServletResponse response) {
        // 失败次数限流：同一用户名连续失败 5 次锁 5 分钟
        loginRateLimiter.checkLocked(req.getUsername());
        User u = userRepository.findByUsername(req.getUsername())
                .orElseThrow(() -> { loginRateLimiter.recordFailure(req.getUsername()); return new BizException(1001, "用户名或密码错误"); });
        if (!passwordEncoder.matches(req.getPassword(), u.getPassword())) {
            loginRateLimiter.recordFailure(req.getUsername());
            throw new BizException(1001, "用户名或密码错误");
        }
        loginRateLimiter.clear(req.getUsername());
        if (!authorization.valid(u.getId())) throw new BizException(403,"账号或组织已停用");
        if (u.getStatus() == null || u.getStatus() == 0) {
            throw new BizException(1002, "账号已禁用");
        }
        String roleCode = u.getRole().getCode();
        // 带上吊销水位签发（审计 #3）：iat 不早于 token_invalid_before，保证新 token 不被过滤器当旧 token 拒掉
        String token = jwtUtil.generate(u.getId(), u.getUsername(), roleCode, u.getTokenInvalidBefore());
        // HttpOnly Cookie：供 <img>/<a> 文件直链兜底鉴权（JwtAuthenticationFilter 仅在文件类 GET 上接受）。
        // Secure 由 PAMS_COOKIE_SECURE 开关控制（审计 #3，默认 false 保本地 http 可用）。
        ResponseCookie cookie = ResponseCookie.from("pams_token", token)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofHours(jwtExpireHours))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        LoginResponse resp = new LoginResponse();
        resp.setToken(token);
        Map<String, Object> user = new HashMap<>();
        user.put("id", u.getId());
        user.put("username", u.getUsername());
        user.put("realName", u.getRealName());
        user.put("roleCode", roleCode);
        user.put("roleLevel", u.getRole().getLevel());
        user.put("deptId", u.getDept() == null ? null : u.getDept().getId());
        user.put("deptName", u.getDept() == null ? null : u.getDept().getName());
        user.put("orgId", u.getOrgId());
        user.put("orgName", orgName(u.getOrgId()));
        // 下发该角色权限码集合，供前端按权限码显隐菜单/路由（与权限管理页配置一致）
        user.put("permissions", permissionService.permissionsOfUser(u.getId()));
        user.putAll(authorization.me(u.getId()));
        resp.setUser(user);
        return Result.ok(resp);
    }

    /** 登出：服务端吊销当前 token（审计 #3：推进用户级失效水位）+ 清理文件直链兜底 Cookie。 */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        revokeIfPresent(request);
        ResponseCookie cookie = ResponseCookie.from("pams_token", "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return Result.ok(null);
    }

    /**
     * 审计 #3：从请求取 token（Bearer 头或 pams_token Cookie），把签发该 token 的用户的
     * token_invalid_before 推进到当前时刻——此后该用户此前签发的全部 token 一律 401。
     * 登出接口免登录（permitAll）：拿不到/解析失败的 token 无从吊销，仅清 Cookie。
     */
    private void revokeIfPresent(HttpServletRequest request) {
        String token = resolveRevokeToken(request);
        if (token == null) {
            return;
        }
        try {
            Claims claims = jwtUtil.parse(token);
            User u = null;
            Long uid = claims.get("uid", Long.class);
            if (uid != null) {
                u = userRepository.findById(uid).orElse(null);
            }
            if (u == null && claims.getSubject() != null) {
                u = userRepository.findByUsername(claims.getSubject()).orElse(null);
            }
            if (u != null) {
                u.setTokenInvalidBefore(JwtUtil.revocationWatermark(LocalDateTime.now(), u.getTokenInvalidBefore()));
                userRepository.save(u);
            }
        } catch (JwtException | IllegalArgumentException ignored) {
            // token 无效/已过期：无须服务端吊销
        }
    }

    /** 登出吊销取 token：优先 Authorization: Bearer 头，其次登录下发的 pams_token Cookie。 */
    private String resolveRevokeToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie c : request.getCookies()) {
                if ("pams_token".equals(c.getName()) && !c.getValue().isBlank()) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    /** 当前登录用户信息（含最新权限码），供前端启动时刷新、权限管理页改完刷新即生效。 */
    @GetMapping("/me")
    public Result<Map<String, Object>> me(@AuthenticationPrincipal LoginUser lu) {
        if (lu == null) {
            throw new BizException(1001, "未登录");
        }
        Map<String, Object> user = new HashMap<>();
        user.put("id", lu.getId());
        user.put("username", lu.getUsername());
        user.put("realName", lu.getRealName());
        user.put("roleCode", lu.getRoleCode());
        user.put("roleLevel", lu.getRoleLevel());
        user.put("deptId", lu.getDeptId());
        user.put("deptName", lu.getDeptName());
        user.put("orgId", lu.getOrgId());
        user.put("orgName", orgName(lu.getOrgId()));
        user.put("permissions", permissionService.permissionsOfUser(lu.getId()));
        user.putAll(authorization.me(lu.getId()));
        return Result.ok(user);
    }
}
