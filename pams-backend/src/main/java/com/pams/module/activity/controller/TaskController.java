package com.pams.module.activity.controller;

import com.pams.common.Result;
import com.pams.module.activity.dto.ProgressRequest;
import com.pams.module.activity.dto.TaskRequest;
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
    private static final String LEADER = "hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')";

    public TaskController(TaskService service) { this.service = service; }

    @GetMapping
    public Result<List<Task>> list(@RequestParam Long activityId) {
        return Result.ok(service.listByActivity(activityId));
    }

    @PreAuthorize(LEADER)
    @PostMapping
    public Result<Task> create(@Valid @RequestBody TaskRequest req,
                               @AuthenticationPrincipal LoginUser user) {
        return Result.ok(service.create(req, user.getId()));
    }

    @PreAuthorize(LEADER)
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody TaskRequest req) {
        service.update(id, req);
        return Result.ok();
    }

    @PreAuthorize(LEADER)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }

    @PreAuthorize(LEADER)
    @PutMapping("/{id}/progress")
    public Result<Void> updateProgress(@PathVariable Long id, @Valid @RequestBody ProgressRequest req) {
        service.updateProgress(id, req.getProgress());
        return Result.ok();
    }
}
