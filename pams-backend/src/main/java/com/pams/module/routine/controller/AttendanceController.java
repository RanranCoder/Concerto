package com.pams.module.routine.controller;

import com.pams.common.Result;
import com.pams.module.routine.dto.AttendanceRequest;
import com.pams.module.routine.dto.AttendanceStatusRequest;
import com.pams.module.routine.dto.AttendanceVO;
import com.pams.module.routine.entity.Attendance;
import com.pams.module.routine.entity.AttendanceAudit;
import com.pams.module.routine.service.AttendanceManageService;
import com.pams.module.routine.service.RoutineService;
import com.pams.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/attendances")
public class AttendanceController {
    private final RoutineService routineService;
    private final AttendanceManageService manageService;

    public AttendanceController(RoutineService routineService, AttendanceManageService manageService) {
        this.routineService = routineService;
        this.manageService = manageService;
    }

    /** 考勤中心列表（含活动/场次展开）。attendance:view 或 schedule:check 可见 */
    @PreAuthorize("hasAuthority('attendance:view')")
    @GetMapping
    public Result<List<AttendanceVO>> list(@RequestParam(required = false) Long activityId,
                                           @RequestParam(required = false) LocalDate date,
                                           @RequestParam(required = false) LocalDate dateFrom,
                                           @RequestParam(required = false) LocalDate dateTo,
                                           @RequestParam(required = false) String type,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String status) {
        Map<String, Object> filters = new HashMap<>();
        filters.put("activityId", activityId);
        filters.put("date", date);
        filters.put("dateFrom", dateFrom);
        filters.put("dateTo", dateTo);
        filters.put("type", type);
        filters.put("keyword", keyword);
        filters.put("status", status);
        return Result.ok(manageService.list(filters));
    }

    /** 考勤统计卡：todayActivities/shouldAttend/signed/normal/late/absent/leave/makeup/pending */
    @PreAuthorize("hasAuthority('attendance:view')")
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats(@RequestParam(required = false) Long activityId,
                                             @RequestParam(required = false) LocalDate date,
                                             @RequestParam(required = false) LocalDate dateFrom,
                                             @RequestParam(required = false) LocalDate dateTo,
                                             @RequestParam(required = false) String type,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String status) {
        Map<String, Object> filters = new HashMap<>();
        filters.put("activityId", activityId);
        filters.put("date", date);
        filters.put("dateFrom", dateFrom);
        filters.put("dateTo", dateTo);
        filters.put("type", type);
        filters.put("keyword", keyword);
        filters.put("status", status);
        return Result.ok(manageService.stats(filters));
    }

    /** 我的考勤 */
    @PreAuthorize("hasAuthority('attendance:self:view')")
    @GetMapping("/my")
    public Result<List<AttendanceVO>> my(@AuthenticationPrincipal LoginUser user) {
        return Result.ok(manageService.my(user.getId(), user.getRealName()));
    }

    /** 人工修改考勤状态（写审计，置 MANUAL） */
    @PreAuthorize("hasAuthority('attendance:manage')")
    @PutMapping("/{id}/status")
    public Result<Attendance> updateStatus(@PathVariable Long id, @Valid @RequestBody AttendanceStatusRequest req,
                                           @AuthenticationPrincipal LoginUser user) {
        return Result.ok(manageService.updateStatus(id, req, user.getId(), user.getRealName()));
    }

    /** 人工补签（置 MAKEUP） */
    @PreAuthorize("hasAuthority('attendance:manage')")
    @PostMapping("/{id}/makeup")
    public Result<Attendance> makeup(@PathVariable Long id,
                                     @RequestParam(required = false) String remark,
                                     @AuthenticationPrincipal LoginUser user) {
        return Result.ok(manageService.makeup(id, user.getId(), user.getRealName(), remark, null, null));
    }

    /** 考勤修改审计记录 */
    @PreAuthorize("hasAuthority('attendance:view')")
    @GetMapping("/{id}/audits")
    public Result<List<AttendanceAudit>> audits(@PathVariable Long id) {
        return Result.ok(manageService.audits(id));
    }

    /** 考勤登记（兼容既有值班打卡场景）：schedule:check */
    @PreAuthorize("hasAuthority('schedule:check')")
    @PostMapping
    public Result<Attendance> create(@Valid @RequestBody AttendanceRequest req) {
        return Result.ok(routineService.checkInSelf(req));
    }

    @PreAuthorize("hasAuthority('attendance:manage')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        routineService.deleteAttendance(id);
        return Result.ok();
    }

    /**
     * 按人汇总：应到/实到/请假/缺勤（兼容保留，改走新考勤列表）。
     */
    @PreAuthorize("hasAuthority('attendance:view')")
    @GetMapping("/summary")
    public Result<List<Map<String, Object>>> summary(@RequestParam(required = false) Integer weekNo,
                                                     @RequestParam(required = false) String type) {
        return Result.ok(routineService.summary(weekNo, type));
    }
}