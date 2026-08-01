package com.pams.module.routine.controller;

import com.pams.common.Result;
import com.pams.module.routine.dto.AttendanceRequest;
import com.pams.module.routine.entity.Attendance;
import com.pams.module.routine.service.RoutineService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/attendances")
public class AttendanceController {
    private final RoutineService service;
    public AttendanceController(RoutineService service) { this.service = service; }

    @GetMapping
    public Result<List<Attendance>> list(@RequestParam(required = false) Long scheduleId,
                                         @RequestParam(required = false) Integer weekNo,
                                         @RequestParam(required = false) String personName) {
        return Result.ok(service.listAttendances(scheduleId, weekNo, personName));
    }

    @PostMapping
    public Result<Attendance> create(@Valid @RequestBody AttendanceRequest req) {
        return Result.ok(service.createAttendance(req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.deleteAttendance(id);
        return Result.ok();
    }

    /**
     * 按人汇总：应到/实到/请假/缺勤/次数。
     * weekNo / type 均可选（null 或空串 = 不限制，都传时同时满足）。
     * weekNo 按考勤所属排班的周次过滤；type 按考勤所属排班的排班类型（scheduleType，
     * 如 SMOKING_CURB 禁烟/CLASS_DUTY 值班/BOOTH 摆摊）过滤，例如"本周禁烟值班考勤汇总"。
     * 传无效 type 不报错，找不到匹配排班的考勤即为空汇总。
     */
    @GetMapping("/summary")
    public Result<List<Map<String, Object>>> summary(@RequestParam(required = false) Integer weekNo,
                                                     @RequestParam(required = false) String type) {
        return Result.ok(service.summary(weekNo, type));
    }
}
