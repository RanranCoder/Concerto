package com.pams.module.activity.controller;

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
    public PlanController(PlanService service) { this.service = service; }

    @GetMapping
    public Result<ActivityPlan> latest(@RequestParam Long activityId) {
        return Result.ok(service.latest(activityId));
    }

    @GetMapping("/{id}")
    public Result<ActivityPlan> detail(@PathVariable Long id) {
        return Result.ok(service.getEntity(id));
    }

    @PostMapping
    public Result<ActivityPlan> create(@Valid @RequestBody PlanRequest req) {
        return Result.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody PlanRequest req) {
        service.update(id, req);
        return Result.ok();
    }

    @PutMapping("/{id}/submit")
    public Result<Void> submit(@PathVariable Long id) {
        service.submit(id);
        return Result.ok();
    }

    @PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')")
    @PutMapping("/{id}/review")
    public Result<Void> review(@PathVariable Long id, @RequestBody PlanReviewRequest req,
                               @AuthenticationPrincipal LoginUser current) {
        boolean approved = req.getApproved() != null && req.getApproved();
        service.review(id, approved, req.getComment(), current == null ? null : current.getId());
        return Result.ok();
    }
}
