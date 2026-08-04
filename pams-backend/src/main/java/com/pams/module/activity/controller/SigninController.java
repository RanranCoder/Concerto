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

    /**
     * 扫码签到。兼容两种 body：
     *   旧格式：{token, name, studentNo}（保持向后兼容）
     *   新格式：{token, fields: {姓名, 学号, 手机号, 班级, 身份}}（支持核验字段动态校验 + 应签名单宽松匹配）
     */
    @PostMapping("/scan")
    public Result<Signin> scan(@RequestBody Map<String, Object> body) {
        String token = asString(body.get("token"));
        if (token == null || token.isBlank()) {
            throw new BizException(400, "签到码不能为空");
        }
        // 从 name/studentNo 或 fields 提取，统一拼成 fields Map 交给 service
        Map<String, String> fields = new HashMap<>();
        @SuppressWarnings("unchecked")
        Object rawFields = body.get("fields");
        if (rawFields instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) rawFields).entrySet()) {
                Object v = e.getValue();
                if (v != null) fields.put(String.valueOf(e.getKey()), String.valueOf(v));
            }
        }
        String name = nonNullString(fields.get("姓名"), asString(body.get("name")));
        String studentNo = nonNullString(fields.get("学号"), asString(body.get("studentNo")));
        if (name == null || name.isBlank()) {
            throw new BizException(400, "签到码或姓名不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 50) {
            throw new BizException(400, "姓名长度不能超过50个字符");
        }
        return Result.ok(service.scanSignin(token, trimmed, studentNo, fields));
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 取第一个非空值（新格式优先），全空则返回 null */
    private static String nonNullString(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
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
