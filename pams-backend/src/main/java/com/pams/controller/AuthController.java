package com.pams.controller;

import com.pams.common.BizException;
import com.pams.common.Result;
import com.pams.dto.LoginRequest;
import com.pams.dto.LoginResponse;
import com.pams.entity.User;
import com.pams.module.permission.service.PermissionService;
import com.pams.repository.UserRepository;
import com.pams.security.JwtUtil;
import jakarta.validation.Valid;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final PermissionService permissionService;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil,
                          PermissionService permissionService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.permissionService = permissionService;
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        User u = userRepository.findByUsername(req.getUsername())
                .orElseThrow(() -> new BizException(1001, "用户名或密码错误"));
        if (!passwordEncoder.matches(req.getPassword(), u.getPassword())) {
            throw new BizException(1001, "用户名或密码错误");
        }
        if (u.getStatus() == null || u.getStatus() == 0) {
            throw new BizException(1002, "账号已禁用");
        }
        String roleCode = u.getRole().getCode();
        String token = jwtUtil.generate(u.getId(), u.getUsername(), roleCode);
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
        // 下发该角色权限码集合，供前端按权限码显隐菜单/路由（与权限管理页配置一致）
        user.put("permissions", permissionService.permissionsOfRole(roleCode));
        resp.setUser(user);
        return Result.ok(resp);
    }
}
