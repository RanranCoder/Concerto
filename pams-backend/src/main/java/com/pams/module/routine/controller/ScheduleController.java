package com.pams.module.routine.controller;

import com.pams.common.Result;
import com.pams.module.routine.dto.ScheduleRequest;
import com.pams.module.routine.entity.Schedule;
import com.pams.module.routine.service.RoutineService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {
    private final RoutineService service;
    public ScheduleController(RoutineService service) { this.service = service; }

    @GetMapping
    public Result<List<Schedule>> list(@RequestParam(required = false) String type,
                                       @RequestParam(required = false) Integer weekNo,
                                       @RequestParam(required = false) Integer weekday,
                                       @RequestParam(required = false) Long activityId) {
        return Result.ok(service.listSchedules(type, weekNo, weekday, activityId));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody ScheduleRequest req) {
        return Result.ok(service.createSchedule(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ScheduleRequest req) {
        service.updateSchedule(id, req);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.deleteSchedule(id);
        return Result.ok();
    }

    /** 导出值班表 xlsx（POI 生成，Content-Disposition 下载） */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String type,
                                         @RequestParam(required = false) Integer weekNo,
                                         @RequestParam(required = false) Integer weekday,
                                         @RequestParam(required = false) Long activityId) {
        String path = service.exportExcel(type, weekNo, weekday, activityId);
        try {
            byte[] bytes = Files.readAllBytes(Path.of(path));
            String fileName = "schedule-" + System.currentTimeMillis() + ".xlsx";
            String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
