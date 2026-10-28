package com.pams.module.routine.service;

import com.pams.common.BizException;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.entity.Signin;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.activity.repository.SigninRepository;
import com.pams.module.routine.dto.AttendanceStatusRequest;
import com.pams.module.routine.dto.AttendanceVO;
import com.pams.module.routine.entity.Attendance;
import com.pams.module.routine.entity.AttendanceAudit;
import com.pams.module.routine.entity.Schedule;
import com.pams.module.routine.repository.AttendanceAuditRepository;
import com.pams.module.routine.repository.AttendanceRepository;
import com.pams.module.routine.repository.ScheduleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** 考勤中心：列表/统计/人工改状态(含审计)/补签/我的考勤。 */
@Service
public class AttendanceManageService {

    private final AttendanceRepository attendanceRepository;
    private final AttendanceAuditRepository auditRepository;
    private final ScheduleRepository scheduleRepository;
    private final SigninRepository signinRepository;
    private final ActivityRepository activityRepository;

    public AttendanceManageService(AttendanceRepository attendanceRepository,
                                   AttendanceAuditRepository auditRepository,
                                   ScheduleRepository scheduleRepository,
                                   SigninRepository signinRepository,
                                   ActivityRepository activityRepository) {
        this.attendanceRepository = attendanceRepository;
        this.auditRepository = auditRepository;
        this.scheduleRepository = scheduleRepository;
        this.signinRepository = signinRepository;
        this.activityRepository = activityRepository;
    }

    /** 考勤中心列表。filters: activityId/date/type/name(studentNo)/status */
    public List<AttendanceVO> list(Map<String, Object> filters) {
        Long activityId = asLong(filters.get("activityId"));
        LocalDate date = asDate(filters.get("date"));
        LocalDate dateFrom = asDate(filters.get("dateFrom"));
        LocalDate dateTo = asDate(filters.get("dateTo"));
        String type = asStr(filters.get("type"));
        String keyword = asStr(filters.get("keyword"));
        String status = asStr(filters.get("status"));

        List<Attendance> all = attendanceRepository.findAll();
        Map<Long, Schedule> scheduleById = new HashMap<>();
        Set<Long> scheduleIds = new java.util.LinkedHashSet<>();
        for (Attendance a : all) if (a.getScheduleId() != null) scheduleIds.add(a.getScheduleId());
        if (!scheduleIds.isEmpty()) {
            for (Schedule s : scheduleRepository.findAllById(scheduleIds)) scheduleById.put(s.getId(), s);
        }
        Map<Long, Activity> activityById = new HashMap<>();
        Set<Long> activityIds = new java.util.LinkedHashSet<>();
        for (Schedule s : scheduleById.values()) if (s.getActivityId() != null) activityIds.add(s.getActivityId());
        if (!activityIds.isEmpty()) {
            for (Activity a : activityRepository.findAllById(activityIds)) activityById.put(a.getId(), a);
        }

        List<AttendanceVO> out = new ArrayList<>();
        for (Attendance a : all) {
            Schedule sch = a.getScheduleId() == null ? null : scheduleById.get(a.getScheduleId());
            Activity act = sch == null || sch.getActivityId() == null ? null : activityById.get(sch.getActivityId());
            if (activityId != null && act != null && !activityId.equals(act.getId())) continue;
            if (date != null && sch != null && !date.equals(sch.getScheduleDate())) continue;
            if (dateFrom != null && sch != null && sch.getScheduleDate() != null && sch.getScheduleDate().isBefore(dateFrom)) continue;
            if (dateTo != null && sch != null && sch.getScheduleDate() != null && sch.getScheduleDate().isAfter(dateTo)) continue;
            if (type != null && !type.isBlank() && (act == null || !type.equals(act.getType()))) continue;
            if (status != null && !status.isBlank() && !status.equals(a.getStatus())) continue;
            if (keyword != null && !keyword.isBlank()
                    && !contains(a.getPersonName(), keyword)
                    && !contains(a.getStudentNo(), keyword)) continue;
            out.add(toVO(a, sch, act));
        }
        out.sort((x, y) -> {
            LocalDate dx = x.getActivityDate(); LocalDate dy = y.getActivityDate();
            if (dx != null && dy != null) return dy.compareTo(dx);
            return Long.compare(y.getId(), x.getId());
        });
        return out;
    }

    /** 统计卡：{totalActivities, shouldAttend, signed, normal, late, absent, leave, makeup, pending} */
    public Map<String, Object> stats(Map<String, Object> filters) {
        Map<String, Object> result = new LinkedHashMap<>();
        long todayActivities = scheduleRepository.findAll().stream()
                .filter(s -> Schedule.TYPE_ACTIVITY.equals(s.getScheduleType()))
                .filter(s -> LocalDate.now().equals(s.getScheduleDate()))
                .filter(s -> Schedule.STATUS_PUBLISHED.equals(s.getStatus()))
                .count();
        result.put("totalActivities", todayActivities);

        List<AttendanceVO> rows = list(filters);
        long should = rows.size();
        long signed = rows.stream().filter(a -> isSigned(a.getStatus())).count();
        long normal = rows.stream().filter(a -> Attendance.STATUS_NORMAL.equals(a.getStatus())).count();
        long late = rows.stream().filter(a -> Attendance.STATUS_LATE.equals(a.getStatus())).count();
        long absent = rows.stream().filter(a -> Attendance.STATUS_ABSENT.equals(a.getStatus())).count();
        long leave = rows.stream().filter(a -> Attendance.STATUS_LEAVE.equals(a.getStatus())).count();
        long makeup = rows.stream().filter(a -> Attendance.STATUS_MAKEUP.equals(a.getStatus())).count();
        long pending = rows.stream().filter(a -> Attendance.STATUS_PENDING.equals(a.getStatus())).count();
        result.put("shouldAttend", should);
        result.put("signed", signed);
        result.put("normal", normal);
        result.put("late", late);
        result.put("absent", absent);
        result.put("leave", leave);
        result.put("makeup", makeup);
        result.put("pending", pending);
        return result;
    }

    /** 我的考勤：按当前用户 id 或姓名匹配。 */
    public List<AttendanceVO> my(Long userId, String realName) {
        return list(new HashMap<>()).stream()
                .filter(vo -> (vo.getPersonId() != null && vo.getPersonId().equals(userId))
                        )
                .toList();
    }

    /** 人工修改考勤状态：写 audit，置 MANUAL（自动任务不覆盖）。 */
    @Transactional
    public Attendance updateStatus(Long id, AttendanceStatusRequest req, Long operatorId, String operatorName) {
        Attendance a = getAttendance(id);
        if (req.getNewStatus() == null || req.getNewStatus().isBlank()) throw new BizException(400, "新状态不能为空");
        String old = a.getStatus();
        a.setStatus(req.getNewStatus());
        a.setStatusSource(Attendance.SOURCE_MANUAL);
        a.setRemark(req.getRemark());
        a.setRecordTime(LocalDateTime.now());
        Attendance saved = attendanceRepository.save(a);
        if (!Objects.equals(old, req.getNewStatus())) {
            writeAudit(saved.getId(), old, req.getNewStatus(), operatorId, operatorName, req.getRemark());
        }
        return saved;
    }

    /** 人工补签：建手动签到 + 置 MAKEUP + 审计。 */
    @Transactional
    public Attendance makeup(Long id, Long operatorId, String operatorName, String remark, String studentNo, String personName) {
        Attendance a = getAttendance(id);
        // 建手动签到记录（若有场次则绑定；无活动上下文时跳过落签，仅改考勤）
        Long activityId = a.getScheduleId() == null ? null
                : scheduleRepository.findById(a.getScheduleId()).map(Schedule::getActivityId).orElse(null);
        if (activityId != null) {
            Signin s = new Signin();
            s.setActivityId(activityId);
            s.setSessionId(a.getSessionId());
            s.setPersonId(a.getPersonId());
            s.setName(personName == null ? a.getPersonName() : personName);
            s.setStudentNo(studentNo == null ? a.getStudentNo() : studentNo);
            s.setSignType(Signin.SignType.MANUAL);
            s.setSignTime(LocalDateTime.now());
            s.setCreatedAt(LocalDateTime.now());
            signinRepository.save(s);
        }

        String old = a.getStatus();
        a.setStatus(Attendance.STATUS_MAKEUP);
        a.setStatusSource(Attendance.SOURCE_MANUAL);
        a.setCheckedInAt(LocalDateTime.now());
        a.setRemark(remark);
        a.setRecordTime(LocalDateTime.now());
        Attendance saved = attendanceRepository.save(a);
        writeAudit(saved.getId(), old, Attendance.STATUS_MAKEUP, operatorId, operatorName, remark);
        return saved;
    }

    public List<AttendanceAudit> audits(Long id) {
        getAttendance(id);
        return auditRepository.findByAttendanceIdOrderByOperateTimeDesc(id);
    }

    private void writeAudit(Long attendanceId, String oldStatus, String newStatus,
                            Long operatorId, String operatorName, String remark) {
        AttendanceAudit audit = new AttendanceAudit();
        audit.setAttendanceId(attendanceId);
        audit.setOldStatus(oldStatus);
        audit.setNewStatus(newStatus);
        audit.setOperatorId(operatorId);
        audit.setOperatorName(operatorName);
        audit.setOperateTime(LocalDateTime.now());
        audit.setRemark(remark);
        audit.setCreatedAt(LocalDateTime.now());
        auditRepository.save(audit);
    }

    private AttendanceVO toVO(Attendance a, Schedule sch, Activity act) {
        AttendanceVO vo = new AttendanceVO();
        vo.setId(a.getId());
        vo.setScheduleId(a.getScheduleId());
        vo.setSessionId(a.getSessionId());
        vo.setPersonId(a.getPersonId());
        vo.setPersonName(a.getPersonName());
        vo.setStudentNo(a.getStudentNo());
        vo.setStatus(a.getStatus() == null || a.getStatus().isBlank() ? Attendance.STATUS_PENDING : a.getStatus());
        vo.setStatusSource(a.getStatusSource() == null ? Attendance.SOURCE_AUTO : a.getStatusSource());
        vo.setRemark(a.getRemark());
        vo.setCheckedInAt(a.getCheckedInAt());
        vo.setRecordTime(a.getRecordTime());
        if (sch != null) {
            vo.setActivityDate(sch.getScheduleDate());
            vo.setActivityStart(sch.getStartTime());
            vo.setActivityEnd(sch.getEndTime());
            if (sch.getActivityId() != null && act != null) {
                vo.setActivityId(act.getId());
                vo.setActivityName(act.getName());
                vo.setActivityType(act.getType());
            }
        }
        vo.setSignCount(calcSignCount(a));
        return vo;
    }

    private long calcSignCount(Attendance a) {
        if (a.getScheduleId() != null) {
            return signinRepository.findAll().stream()
                    .filter(s -> matchesPerson(s, a)).count();
        }
        return 0;
    }

    private boolean matchesPerson(Signin s, Attendance a) {
        if (a.getPersonId() != null && s.getPersonId() != null) return a.getPersonId().equals(s.getPersonId());
        return a.getPersonName() != null && a.getPersonName().equalsIgnoreCase(s.getName());
    }

    private Attendance getAttendance(Long id) {
        return attendanceRepository.findById(id).orElseThrow(() -> new BizException(2402, "考勤记录不存在"));
    }

    private boolean isSigned(String status) {
        return Attendance.STATUS_NORMAL.equals(status) || Attendance.STATUS_LATE.equals(status)
                || Attendance.STATUS_MAKEUP.equals(status);
    }

    private static boolean contains(String v, String kw) {
        return v != null && kw != null && v.toLowerCase().contains(kw.toLowerCase());
    }
    private static String asStr(Object o) { return o == null ? null : String.valueOf(o); }
    private static Long asLong(Object o) {
        if (o == null) return null;
        try { return Long.parseLong(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }
    private static LocalDate asDate(Object o) {
        if (o == null) return null;
        try { return LocalDate.parse(String.valueOf(o)); } catch (Exception e) { return null; }
    }
}