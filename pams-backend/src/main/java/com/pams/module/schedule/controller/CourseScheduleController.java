package com.pams.module.schedule.controller;

import com.pams.common.Result;
import com.pams.module.schedule.entity.ScheduleConfig;
import com.pams.module.schedule.service.CourseScheduleService;
import com.pams.security.LoginUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 无课表制作（PRD F08）
 * 权限：schedule:free_table（全部用户可录入自己的课程表；部长+可配置时间格）
 */
@RestController
@RequestMapping("/api/course-schedules")
public class CourseScheduleController {

    private static final String LEADER =
            "hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')";

    private final CourseScheduleService service;

    public CourseScheduleController(CourseScheduleService service) { this.service = service; }

    // ===== 时间格配置 =====

    @GetMapping("/configs")
    public Result<List<ScheduleConfig>> configs() {
        return Result.ok(service.getConfigs());
    }

    @PreAuthorize(LEADER)
    @PutMapping("/configs")
    public Result<Void> saveConfigs(@RequestBody List<Map<String, Object>> configs) {
        service.saveConfigs(configs);
        return Result.ok();
    }

    // ===== 个人课程表 =====

    /** 查看自己的课程表 */
    @GetMapping("/mine")
    public Result<Map<String, Object>> mine(@RequestParam(required = false) String semester,
                                            @AuthenticationPrincipal LoginUser current) {
        return Result.ok(service.getMySchedule(current.getId(), semester));
    }

    /** 保存自己的课程表（覆盖式）：body {semester, cells:[{dayOfWeek, period, courseName}]} */
    @PostMapping("/mine")
    public Result<Void> saveMine(@RequestBody Map<String, Object> body,
                                 @AuthenticationPrincipal LoginUser current) {
        String semester = (String) body.get("semester");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cells = (List<Map<String, Object>>) body.get("cells");
        service.saveMySchedule(current.getId(), semester, cells);
        return Result.ok();
    }

    // ===== 空闲统计 =====

    /** 生成共同空闲热力图：?semester=xxx&userIds=1,2,3（不传则全员） */
    @GetMapping("/analyze")
    public Result<Map<String, Object>> analyze(@RequestParam(required = false) String semester,
                                               @RequestParam(required = false) List<Long> userIds) {
        return Result.ok(service.analyzeFreeTime(semester, userIds));
    }
}
