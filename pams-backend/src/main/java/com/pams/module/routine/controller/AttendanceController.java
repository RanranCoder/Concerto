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

    /** 按人汇总：应到/实到/请假/缺勤/次数 */
    @GetMapping("/summary")
    public Result<List<Map<String, Object>>> summary(@RequestParam(required = false) Integer weekNo,
                                                     @RequestParam(required = false) String type) {
        return Result.ok(service.summary(weekNo, type));
    }
}
