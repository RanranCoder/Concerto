package com.pams.module.activity.controller;

import com.pams.common.Result;
import com.pams.module.activity.dto.SigninRequest;
import com.pams.module.activity.entity.Signin;
import com.pams.module.activity.service.SigninService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
