package com.pams.module.archive.controller;

import com.pams.common.PageResult;
import com.pams.common.Result;
import com.pams.module.archive.dto.MaterialRequest;
import com.pams.module.archive.entity.Material;
import com.pams.module.archive.service.MaterialService;
import com.pams.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/materials")
public class MaterialController {
    private final MaterialService service;
    public MaterialController(MaterialService service) { this.service = service; }

    @GetMapping
    public Result<PageResult<Map<String, Object>>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) Long activityId,
            @RequestParam(required = false) Long deptId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(service.page(keyword, bizType, activityId, deptId, page, size));
    }

    @GetMapping("/tree")
    public Result<List<Map<String, Object>>> tree(@RequestParam(required = false) Long activityId) {
        return Result.ok(service.tree(activityId));
    }

    @PostMapping
    public Result<Material> create(@Valid @RequestBody MaterialRequest req,
                                   @AuthenticationPrincipal LoginUser current) {
        return Result.ok(service.create(current == null ? null : current.getId(), req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody MaterialRequest req) {
        service.update(id, req);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
