package com.pams.module.routine.controller;

import com.pams.common.Result;
import com.pams.module.routine.dto.ScheduleRequest;
import com.pams.module.routine.entity.Schedule;
import com.pams.module.routine.service.RoutineService;
import com.pams.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    private final com.pams.module.routine.service.ActivityScheduleService publication;
    public ScheduleController(RoutineService service, com.pams.module.routine.service.ActivityScheduleService publication) { this.service = service; this.publication=publication; }

    @GetMapping
    public Result<List<Schedule>> list(@RequestParam(required = false) String type,
                                       @RequestParam(required = false) Integer weekNo,
                                       @RequestParam(required = false) Integer weekday,
                                       @RequestParam(required = false) Long activityId) {
        return Result.ok(service.listSchedules(type, weekNo, weekday, activityId));
    }

    /** 我的排班：当前用户被排到的班次 */
    @PreAuthorize("hasAuthority('schedule:self:view')")
    @GetMapping("/my")
    public Result<List<Schedule>> my(@AuthenticationPrincipal LoginUser user) {
        return Result.ok(service.listMySchedules(user.getId(), user.getRealName()));
    }

    /** 值班表的增删改与导出需 schedule:manage（V7 种子：部长级；导出含全员值班信息），登录即可查询 */
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody ScheduleRequest req) {
        return Result.ok(service.createSchedule(req));
    }

    @PreAuthorize("hasAuthority('schedule:manage')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ScheduleRequest req) {
        service.updateSchedule(id, req);
        return Result.ok();
    }

    @PreAuthorize("hasAuthority('schedule:manage')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.deleteSchedule(id);
        return Result.ok();
    }

    @PreAuthorize("hasAuthority('schedule:publish')")
    @PostMapping("/{id}/publish")
    public Result<Void> publish(@PathVariable Long id) { publication.publish(id); return Result.ok(); }

    @PreAuthorize("hasAuthority('schedule:publish')")
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) { publication.cancel(id); return Result.ok(); }

    /** 导出值班表 xlsx（POI 生成，Content-Disposition 下载） */
    @PreAuthorize("hasAuthority('schedule:export')")
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String type,
                                         @RequestParam(required = false) Integer weekNo,
                                         @RequestParam(required = false) Integer weekday,
                                         @RequestParam(required = false) Long activityId) {
        String path = service.exportExcel(type, weekNo, weekday, activityId);
        Path file = Path.of(path);
        try {
            byte[] bytes = Files.readAllBytes(file);
            String fileName = "schedule-" + System.currentTimeMillis() + ".xlsx";
            String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            // B6 fix: 导出临时 xlsx 仅在读取字节时使用，返回后立即清理，避免 uploads 目录文件堆积
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // 清理失败不阻断下载响应，最多遗留单次临时文件
            }
        }
    }
}
