package com.pams.module.routine.controller;

import com.pams.common.Result;
import com.pams.module.routine.dto.ActivityScheduleGenerateRequest;
import com.pams.module.routine.dto.ActivityScheduleSaveRequest;
import com.pams.module.routine.entity.Schedule;
import com.pams.module.routine.service.ActivityScheduleService;
import com.pams.module.schedule.repository.NoClassScheduleRecordRepository;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 活动排班：一键生成（无课表）/ 手动维护 / 发布 / 取消 / 重新生成。
 * 生成、发布、取消、删除、修改需 schedule:manage 或 schedule:generate（一键生成按钮）。
 */
@RestController
@RequestMapping("/api/activity-schedules")
public class ActivityScheduleController {
    private final ActivityScheduleService service;
    private final NoClassScheduleRecordRepository noClassRecordRepository;
    public ActivityScheduleController(ActivityScheduleService service, NoClassScheduleRecordRepository noClassRecordRepository) {
        this.service = service;
        this.noClassRecordRepository = noClassRecordRepository;
    }

    @GetMapping
    public Result<List<Schedule>> list(@RequestParam(required = false) Long activityId,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) LocalDate date) {
        return Result.ok(service.list(activityId, status, date));
    }

    /** 已上传无课表的学期列表（供生成弹窗下拉选择） */
    @GetMapping("/semesters")
    public Result<List<String>> semesters() {
        return Result.ok(noClassRecordRepository.findDistinctSemesters());
    }

    /** 一键生成：需 schedule:manage 或 schedule:generate */
    @PreAuthorize("hasAuthority('schedule:generate')")
    @PostMapping("/generate")
    public Result<List<Map<String, Object>>> generate(@Valid @RequestBody ActivityScheduleGenerateRequest req) {
        return Result.ok(service.generate(req));
    }

    @PreAuthorize("hasAuthority('schedule:manage')")
    @PostMapping
    public Result<Long> createManual(@Valid @RequestBody ActivityScheduleSaveRequest req) {
        return Result.ok(service.createManual(req));
    }

    @PreAuthorize("hasAuthority('schedule:manage')")
    @PutMapping("/{id}")
    public Result<Void> updateManual(@PathVariable Long id, @Valid @RequestBody ActivityScheduleSaveRequest req) {
        service.updateManual(id, req);
        return Result.ok();
    }

    @PreAuthorize("hasAuthority('schedule:manage')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }

    @PreAuthorize("hasAuthority('schedule:publish')")
    @PostMapping("/{id}/publish")
    public Result<Void> publish(@PathVariable Long id) {
        service.publish(id);
        return Result.ok();
    }

    /** 一键发布：发布当前范围内全部草稿排班（可按活动过滤） */
    @PreAuthorize("hasAuthority('schedule:publish')")
    @PostMapping("/publish-all")
    public Result<Integer> publishAll(@RequestParam(required = false) Long activityId) {
        return Result.ok(service.publishAll(activityId));
    }

    @PreAuthorize("hasAuthority('schedule:publish')")
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        service.cancel(id);
        return Result.ok();
    }

    @PreAuthorize("hasAuthority('schedule:generate')")
    @PostMapping("/{id}/re-generate")
    public Result<Integer> regenerate(@PathVariable Long id,
                                      @RequestParam(required = false) Long deptId,
                                      @RequestParam(required = false) String semester,
                                      @RequestParam(required = false) LocalDate semesterStartDate) {
        return Result.ok(service.regenerate(id, deptId, semester, semesterStartDate));
    }
}