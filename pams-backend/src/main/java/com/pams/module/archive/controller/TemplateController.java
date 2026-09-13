package com.pams.module.archive.controller;

import com.pams.common.Result;
import com.pams.module.archive.dto.TemplateRequest;
import com.pams.module.archive.entity.TemplateAsset;
import com.pams.module.archive.service.TemplateService;
import com.pams.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {
    private final TemplateService service;
    public TemplateController(TemplateService service) { this.service = service; }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) String category) {
        return Result.ok(service.list(category));
    }

    /** 模板资产的增删改：template:manage（V7 种子：仅 TEACHER/DIRECTOR；部长/干事仅查看与使用） */
    @PreAuthorize("hasAuthority('template:manage')")
    @PostMapping
    public Result<TemplateAsset> create(@Valid @RequestBody TemplateRequest req,
                                        @AuthenticationPrincipal LoginUser current) {
        return Result.ok(service.create(current == null ? null : current.getId(), req));
    }

    @PreAuthorize("hasAuthority('template:manage')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody TemplateRequest req) {
        service.update(id, req);
        return Result.ok();
    }

    @PreAuthorize("hasAuthority('template:manage')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
