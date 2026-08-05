package com.pams.module.activity.controller;

import com.pams.common.BizException;
import com.pams.common.Result;
import com.pams.module.activity.dto.PlanRequest;
import com.pams.module.activity.dto.PlanReviewRequest;
import com.pams.module.activity.entity.ActivityPlan;
import com.pams.module.activity.service.PlanService;
import com.pams.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/plans")
public class PlanController {
    private final PlanService service;
    private static final String LEADER = "hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')";

    public PlanController(PlanService service) { this.service = service; }

    @GetMapping
    public Result<ActivityPlan> latest(@RequestParam Long activityId) {
        return Result.ok(service.latest(activityId));
    }

    @GetMapping("/{id}")
    public Result<ActivityPlan> detail(@PathVariable Long id) {
        return Result.ok(service.getEntity(id));
    }

    @PreAuthorize(LEADER)
    @PostMapping
    public Result<ActivityPlan> create(@Valid @RequestBody PlanRequest req) {
        return Result.ok(service.create(req));
    }

    @PreAuthorize(LEADER)
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody PlanRequest req,
                               @AuthenticationPrincipal LoginUser current) {
        service.update(id, req, current == null ? null : current.getId());
        return Result.ok();
    }

    @PreAuthorize(LEADER)
    @PutMapping("/{id}/submit")
    public Result<Void> submit(@PathVariable Long id,
                               @AuthenticationPrincipal LoginUser user) {
        service.submit(id, user.getId());
        return Result.ok();
    }

    @PreAuthorize(LEADER)
    @PutMapping("/{id}/review")
    public Result<Void> review(@PathVariable Long id, @Valid @RequestBody PlanReviewRequest req,
                               @AuthenticationPrincipal LoginUser current) {
        // B5 fix (PlanController): reviewerId 不允许 null — 当前用户必须存在
        if (current == null) {
            throw new BizException(401, "未登录，无法审核");
        }
        // approved 已由 @NotNull 保证非 null
        service.review(id, req.getApproved(), req.getComment(), current.getId());
        return Result.ok();
    }
}
