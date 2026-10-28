package com.pams.module.activity.controller;

import com.pams.common.Result;
import com.pams.module.activity.dto.ProgressRequest;
import com.pams.module.activity.dto.TaskRequest;
import com.pams.module.activity.dto.TaskPlan;
import com.pams.module.activity.entity.Task;
import com.pams.module.activity.service.TaskService;
import com.pams.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskService service;

    public TaskController(TaskService service) { this.service = service; }

    @GetMapping
    public Result<List<Task>> list(@RequestParam Long activityId) {
        return Result.ok(service.listByActivity(activityId));
    }

    @PreAuthorize("hasAuthority('task:manage')")
    @PostMapping
    public Result<Task> create(@Valid @RequestBody TaskRequest req,
                               @AuthenticationPrincipal LoginUser user) {
        return Result.ok(service.create(req, user.getId()));
    }

    @PreAuthorize("hasAuthority('task:manage')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody TaskRequest req) {
        service.update(id, req);
        return Result.ok();
    }

    @PreAuthorize("hasAuthority('task:manage')")
    @DeleteMapping("/{id}")
    public Result<TaskPlan.Result> delete(@PathVariable Long id, @RequestParam(required = false) Long expectedRevision) {
        return Result.ok(service.deleteWithUndo(id, expectedRevision));
    }

    @PreAuthorize("hasAnyAuthority('task:progress', 'task:manage')")
    @PutMapping("/{id}/progress")
    public Result<Task> updateProgress(@PathVariable Long id, @Valid @RequestBody ProgressRequest req) {
        return Result.ok(service.updateProgress(id, req.getProgress(), req.getExpectedRevision()));
    }

    @PreAuthorize("hasAuthority('task:manage')")
    @PostMapping("/plan/preview")
    public Result<TaskPlan.Preview> preview(@Valid @RequestBody TaskPlan.Request request) {
        return Result.ok(service.preview(request));
    }

    @PreAuthorize("hasAuthority('task:manage')")
    @PostMapping("/plan")
    public Result<TaskPlan.Result> savePlan(@Valid @RequestBody TaskPlan.Request request,
                                          @AuthenticationPrincipal LoginUser user) {
        return Result.ok(service.savePlan(request, user.getId()));
    }

    @PreAuthorize("hasAuthority('task:manage')")
    @PostMapping("/plan/undo")
    public Result<TaskPlan.Result> undo(@Valid @RequestBody TaskPlan.Undo request) {
        return Result.ok(service.restore(request));
    }
}
