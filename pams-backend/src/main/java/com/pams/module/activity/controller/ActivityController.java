package com.pams.module.activity.controller;

import com.pams.common.BizException;
import com.pams.common.PageResult;
import com.pams.common.Result;
import com.pams.module.activity.dto.ActivityRequest;
import com.pams.module.activity.dto.StatusChangeRequest;
import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.service.ActivityService;
import com.pams.security.RoleExprs;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/activities")
public class ActivityController {
    private final ActivityService service;
    public ActivityController(ActivityService service) { this.service = service; }

    /**
     * 部长及以上角色宏（历史遗留，逐步收敛为权限码——PRD-v5 S2）。
     * 新代码请使用下方权限码常量，与 V7 权限种子（附录B）一致。
     *
     * <p>2026-09-14：字面量已收敛到 {@link RoleExprs#LEADER}，避免各模块各写一份。
     */
    public static final String LEADER = RoleExprs.LEADER;

    /**
     * 活动列表。
     *
     * <p><b>2026-09-14 补注解（权限审计 A1 配套）</b>：此前无 {@code @PreAuthorize}，
     * 而前端 `MainLayout` 用 {@code hasPerm('activity:view')} 决定菜单显隐 ——
     * 即"只在前端挡"。补齐后**行为不变**（7 个角色的权限种子全都含 {@code activity:view}），
     * 目的是把这道门变成真的：一旦将来活动改为受限，不会有未被覆盖的调用方。
     *
     * <p>同样重要的是：{@code ChatRefsResolver}（输入框 `+` 附加上下文）曾直调
     * {@code ActivityService.getEntity(id)} 且不校验权限 —— 那条链路不经过本 Controller。
     * 两处必须同口径，见该方法注释。
     */
    @PreAuthorize("hasAuthority('activity:view')")
    @GetMapping
    public Result<PageResult<Map<String, Object>>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String organizer,
            @RequestParam(required = false) String dateOrder,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(service.page(keyword, status, type, organizer, dateOrder, page, size));
    }

    @PreAuthorize("hasAuthority('activity:view')")
    @GetMapping("/organizers")
    public Result<java.util.List<String>> organizers() { return Result.ok(service.organizers()); }

    /** 活动详情。注解理由同上（名单口径与列表一致，避免"列表能看详情不能看"的裂缝）。 */
    @PreAuthorize("hasAuthority('activity:view')")
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(service.detail(id));
    }

    @PreAuthorize("hasAuthority('activity:create')")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody ActivityRequest req) {
        return Result.ok(service.create(req));
    }

    @PreAuthorize("hasAuthority('activity:edit')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ActivityRequest req) {
        service.update(id, req);
        return Result.ok();
    }

    @PreAuthorize("hasAuthority('activity:edit')")
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @Valid @RequestBody StatusChangeRequest req) {
        // B2 fix: 用 DTO + @NotBlank + try-catch 替代裸 Map，消除 NPE
        ActivityStatus target;
        try {
            target = ActivityStatus.valueOf(req.getStatus());
        } catch (IllegalArgumentException e) {
            throw new BizException(2009, "无效的目标状态: " + req.getStatus());
        }
        service.changeStatus(id, target);
        return Result.ok();
    }

    /** 删除为高破坏性操作：按 V7 设计仅 TEACHER/DIRECTOR（部长级种子不含 activity:delete） */
    @PreAuthorize("hasAuthority('activity:delete')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
