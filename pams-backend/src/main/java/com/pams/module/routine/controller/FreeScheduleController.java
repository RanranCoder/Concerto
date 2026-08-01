package com.pams.module.routine.controller;

import com.pams.common.Result;
import com.pams.module.routine.dto.FreeScheduleRequest;
import com.pams.module.routine.entity.FreeSchedule;
import com.pams.module.routine.service.RoutineService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/free-schedules")
public class FreeScheduleController {
    private final RoutineService service;
    public FreeScheduleController(RoutineService service) { this.service = service; }

    @GetMapping
    public Result<List<FreeSchedule>> list(@RequestParam(required = false) Long deptId) {
        return Result.ok(service.listFreeSchedules(deptId));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody FreeScheduleRequest req) {
        return Result.ok(service.createFreeSchedule(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody FreeScheduleRequest req) {
        service.updateFreeSchedule(id, req);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.deleteFreeSchedule(id);
        return Result.ok();
    }
}
