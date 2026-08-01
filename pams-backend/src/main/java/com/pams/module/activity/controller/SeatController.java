package com.pams.module.activity.controller;

import com.pams.common.Result;
import com.pams.module.activity.dto.SeatRequest;
import com.pams.module.activity.entity.SeatMap;
import com.pams.module.activity.service.SeatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/seats")
public class SeatController {
    private final SeatService service;
    public SeatController(SeatService service) { this.service = service; }

    /** 座位表：按 zone 分组返回 {"zone": [座位...]} */
    @GetMapping
    public Result<Map<String, List<SeatMap>>> list(@RequestParam Long activityId) {
        return Result.ok(service.listByActivity(activityId));
    }

    @PostMapping
    public Result<SeatMap> create(@Valid @RequestBody SeatRequest req) {
        return Result.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody SeatRequest req) {
        service.update(id, req);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
