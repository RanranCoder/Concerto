package com.pams.module.activity.controller;

import com.pams.common.BizException;
import com.pams.common.Result;
import com.pams.module.activity.dto.SigninRequest;
import com.pams.module.activity.entity.Signin;
import com.pams.module.activity.service.SigninService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/signins")
public class SigninController {
    private final SigninService service;
    public SigninController(SigninService service) { this.service = service; }

    @GetMapping
    public Result<List<Signin>> list(@RequestParam Long activityId,
                                     @RequestParam(required = false) String keyword) {
        return Result.ok(service.listByActivity(activityId, keyword));
    }

    @PostMapping
    public Result<Signin> create(@Valid @RequestBody SigninRequest req) {
        return Result.ok(service.create(req));
    }

    @PostMapping("/token")
    public Result<Map<String, Object>> generateToken(@RequestBody Map<String, Long> body,
                                                     HttpServletRequest request) {
        Long activityId = body.get("activityId");
        if (activityId == null) throw new BizException(400, "活动ID不能为空");
        var t = service.generateToken(activityId);
        // 依据当前请求的 scheme + Host 拼扫码 URL origin（部署在反向代理后时取 X-Forwarded-Host，回退到 Host）
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        String host = (forwardedHost != null && !forwardedHost.isBlank()) ? forwardedHost : request.getHeader("Host");
        String origin = request.getScheme() + "://" + (host != null ? host : "localhost");
        t.setQrContent(origin + "/signin/" + t.getToken());
        Map<String, Object> resp = new HashMap<>();
        resp.put("token", t.getToken());
        resp.put("activityId", activityId);
        resp.put("expiresAt", t.getExpiresAt());
        resp.put("qrContent", t.getQrContent());
        return Result.ok(resp);
    }

    @PostMapping("/scan")
    public Result<Signin> scan(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        String name = body.get("name");
        String studentNo = body.get("studentNo");
        if (token == null || name == null || name.isBlank()) {
            throw new BizException(400, "签到码或姓名不能为空");
        }
        return Result.ok(service.scanSignin(token, name.trim(), studentNo));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }

    @GetMapping("/{activityId}/count")
    public Result<Long> count(@PathVariable Long activityId) {
        return Result.ok(service.count(activityId));
    }
}
