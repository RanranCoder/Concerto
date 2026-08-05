package com.pams.module.seat.controller;

import com.pams.common.Result;
import com.pams.module.seat.dto.SeatLayoutRequest;
import com.pams.module.seat.service.SeatLayoutService;
import com.pams.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 座位表可视化布局（PRD F01）
 * 权限：查看 seat:view（登录即可），编辑 seat:edit（部长+），模板管理 seat:template（部长+）
 */
@RestController
@RequestMapping("/api/seat-layouts")
public class SeatLayoutController {

    private static final String LEADER =
            "hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')";

    private final SeatLayoutService service;

    public SeatLayoutController(SeatLayoutService service) { this.service = service; }

    @GetMapping("/activity")
    public Result<Map<String, Object>> getByActivity(@RequestParam Long activityId) {
        return Result.ok(service.getByActivity(activityId));
    }

    @GetMapping("/activity/all")
    public Result<List<Map<String, Object>>> listByActivity(@RequestParam Long activityId) {
        return Result.ok(service.listByActivity(activityId));
    }

    @GetMapping("/templates")
    public Result<List<Map<String, Object>>> listTemplates() {
        return Result.ok(service.listTemplates());
    }

    @PreAuthorize(LEADER)
    @PostMapping
    public Result<Long> create(@Valid @RequestBody SeatLayoutRequest req,
                               @AuthenticationPrincipal LoginUser current) {
        return Result.ok(service.create(current == null ? null : current.getId(), req));
    }

    @PreAuthorize(LEADER)
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody SeatLayoutRequest req) {
        service.update(id, req);
        return Result.ok();
    }

    /** 保存当前布局为模板 */
    @PreAuthorize(LEADER)
    @PostMapping("/{id}/save-as-template")
    public Result<Long> saveAsTemplate(@PathVariable Long id,
                                       @RequestParam(required = false) String category,
                                       @AuthenticationPrincipal LoginUser current) {
        return Result.ok(service.saveAsTemplate(current == null ? null : current.getId(), id, category));
    }

    /** 从模板新建活动布局 */
    @PreAuthorize(LEADER)
    @PostMapping("/from-template")
    public Result<Long> createFromTemplate(@RequestParam Long templateId,
                                           @RequestParam Long activityId,
                                           @RequestParam(required = false) String name,
                                           @AuthenticationPrincipal LoginUser current) {
        return Result.ok(service.createFromTemplate(current == null ? null : current.getId(),
                templateId, activityId, name));
    }

    /** 删除模板 */
    @PreAuthorize(LEADER)
    @DeleteMapping("/templates/{id}")
    public Result<Void> deleteTemplate(@PathVariable Long id) {
        service.deleteTemplate(id);
        return Result.ok();
    }
}
