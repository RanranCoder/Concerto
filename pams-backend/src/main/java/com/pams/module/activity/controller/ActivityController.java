package com.pams.module.activity.controller;

import com.pams.common.PageResult;
import com.pams.common.Result;
import com.pams.module.activity.dto.ActivityRequest;
import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.service.ActivityService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/activities")
public class ActivityController {
    private final ActivityService service;
    public ActivityController(ActivityService service) { this.service = service; }

    @GetMapping
    public Result<PageResult<Map<String, Object>>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(service.page(keyword, status, type, page, size));
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(service.detail(id));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody ActivityRequest req) {
        return Result.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ActivityRequest req) {
        service.update(id, req);
        return Result.ok();
    }

    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        service.changeStatus(id, ActivityStatus.valueOf(body.get("status")));
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
