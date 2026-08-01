package com.pams.module.activity.controller;

import com.pams.common.Result;
import com.pams.module.activity.dto.TaskRequest;
import com.pams.module.activity.entity.Task;
import com.pams.module.activity.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskService service;
    public TaskController(TaskService service) { this.service = service; }

    @GetMapping
    public Result<List<Task>> list(@RequestParam Long activityId) {
        return Result.ok(service.listByActivity(activityId));
    }

    @PostMapping
    public Result<Task> create(@Valid @RequestBody TaskRequest req) {
        return Result.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody TaskRequest req) {
        service.update(id, req);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }

    @PutMapping("/{id}/progress")
    public Result<Void> updateProgress(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        service.updateProgress(id, body.get("progress"));
        return Result.ok();
    }
}
