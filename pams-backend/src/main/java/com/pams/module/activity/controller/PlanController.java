package com.pams.module.activity.controller;

import com.pams.common.BizException;
import com.pams.common.Result;
import com.pams.module.activity.dto.PlanRequest;
import com.pams.module.activity.dto.PlanSubmitRequest;
import com.pams.module.activity.service.PlanAccessService;
import com.pams.module.agent.core.ToolContext;
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

    private final PlanAccessService access;
    public PlanController(PlanService service,PlanAccessService access) { this.service=service; this.access=access; }
    @GetMapping("/reviewers")
    @PreAuthorize("hasAuthority('activity:edit')")
    public Result<?> reviewers() { return Result.ok(access.candidates(ToolContext.current(null,null))); }
    @GetMapping("/workspace")
    public Result<?> workspace(@RequestParam Long activityId) { return Result.ok(service.workspace(activityId)); }
    @PostMapping("/{id}/revision")
    @PreAuthorize("hasAuthority('activity:edit')")
    public Result<ActivityPlan> revision(@PathVariable Long id) { return Result.ok(service.revise(id)); }


    @GetMapping
    public Result<ActivityPlan> latest(@RequestParam Long activityId) {
        return Result.ok(service.latest(activityId));
    }

    @GetMapping("/{id}")
    public Result<ActivityPlan> detail(@PathVariable Long id) {
        return Result.ok(service.getEntity(id));
    }

    @GetMapping("/{id}/document")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> document(@PathVariable Long id) {
        return org.springframework.http.ResponseEntity.ok()
            .contentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=plan.docx")
            .body(service.document(id));
    }

    @PreAuthorize("hasAuthority('activity:edit')")
    @PostMapping
    public Result<ActivityPlan> create(@Valid @RequestBody PlanRequest req) {
        return Result.ok(service.create(req));
    }

    @PreAuthorize("hasAuthority('activity:edit')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody PlanRequest req,
                               @AuthenticationPrincipal LoginUser current) {
        service.update(id, req, current == null ? null : current.getId());
        return Result.ok();
    }

    @PreAuthorize("hasAuthority('activity:edit')")
    @PutMapping("/{id}/submit")
    public Result<Void> submit(@PathVariable Long id, @Valid @RequestBody PlanSubmitRequest req,
                               @AuthenticationPrincipal LoginUser user) {
        service.submit(id, user.getId(),req.getReviewerIds());
        return Result.ok();
    }

    @PreAuthorize("hasAuthority('activity:review')")
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
