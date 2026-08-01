package com.pams.controller;

import com.pams.common.BizException;
import com.pams.common.Result;
import com.pams.dto.LoginRequest;
import com.pams.dto.LoginResponse;
import com.pams.entity.User;
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

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
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
        String token = jwtUtil.generate(u.getId(), u.getUsername(), u.getRole().getCode());
        LoginResponse resp = new LoginResponse();
        resp.setToken(token);
        Map<String, Object> user = new HashMap<>();
        user.put("id", u.getId());
        user.put("username", u.getUsername());
        user.put("realName", u.getRealName());
        user.put("roleCode", u.getRole().getCode());
        user.put("roleLevel", u.getRole().getLevel());
        user.put("deptId", u.getDept() == null ? null : u.getDept().getId());
        user.put("deptName", u.getDept() == null ? null : u.getDept().getName());
        resp.setUser(user);
        return Result.ok(resp);
    }
}
