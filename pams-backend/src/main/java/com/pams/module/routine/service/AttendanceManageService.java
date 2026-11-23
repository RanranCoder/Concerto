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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 考勤中心：列表/统计/人工改状态(含审计)/补签/我的考勤。 */
@Service
public class AttendanceManageService {

    private final AttendanceRepository attendanceRepository;
    private final AttendanceAuditRepository auditRepository;
    private final ScheduleRepository scheduleRepository;
    private final SigninRepository signinRepository;
    private final ActivityRepository activityRepository;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

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
        return list(filters, attendanceRepository.findAll());
    }

    private List<AttendanceVO> list(Map<String, Object> filters, List<Attendance> all) {
        Long activityId = asLong(filters.get("activityId"));
        LocalDate date = asDate(filters.get("date"));
        LocalDate dateFrom = asDate(filters.get("dateFrom"));
        LocalDate dateTo = asDate(filters.get("dateTo"));
        String type = asStr(filters.get("type"));
        String keyword = asStr(filters.get("keyword"));
        String status = asStr(filters.get("status"));

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

        Map<Long, List<Signin>> signsByActivity = activityIds.isEmpty() ? Map.of()
                : signinRepository.findByActivityIdIn(activityIds).stream()
                    .collect(Collectors.groupingBy(Signin::getActivityId));
        List<AttendanceVO> out = new ArrayList<>();
        for (Attendance a : all) {
            Schedule sch = a.getScheduleId() == null ? null : scheduleById.get(a.getScheduleId());
            Activity act = sch == null || sch.getActivityId() == null ? null : activityById.get(sch.getActivityId());
            LocalDate scheduleDate = sch == null ? null : sch.getScheduleDate();
            if (activityId != null && (act == null || !activityId.equals(act.getId()))) continue;
            if (date != null && !date.equals(scheduleDate)) continue;
            if (dateFrom != null && (scheduleDate == null || scheduleDate.isBefore(dateFrom))) continue;
            if (dateTo != null && (scheduleDate == null || scheduleDate.isAfter(dateTo))) continue;
            if (type != null && !type.isBlank() && (act == null || !type.equals(act.getType()))) continue;
            if (status != null && !status.isBlank() && !Attendance.normalizedStatus(status).equals(Attendance.normalizedStatus(a.getStatus()))) continue;
            if (keyword != null && !keyword.isBlank()
                    && !contains(a.getPersonName(), keyword)
                    && !contains(a.getStudentNo(), keyword)) continue;
            List<Signin> signs = act == null ? List.of() : signsByActivity.getOrDefault(act.getId(), List.of());
            out.add(toVO(a, sch, act, signs));
        }
        out.sort(Comparator.comparing(AttendanceVO::getActivityDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(AttendanceVO::getId, Comparator.reverseOrder()));
        return out;
    }

    /** 统计卡：{totalActivities, shouldAttend, signed, normal, late, absent, leave, makeup, pending} */
    public Map<String, Object> stats(Map<String, Object> filters) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<AttendanceVO> rows = list(filters);
        long activities = rows.stream().map(AttendanceVO::getActivityId).filter(Objects::nonNull).distinct().count();
        result.put("totalActivities", activities);
        long should = rows.stream().filter(a -> !Attendance.STATUS_CANCELED.equals(a.getStatus())).count();
        long signed = rows.stream().filter(a -> Attendance.isSigned(a.getStatus())).count();
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
        result.put("earlyLeave", rows.stream().filter(a -> Attendance.STATUS_EARLY_LEAVE.equals(a.getStatus())).count());
        result.put("canceled", rows.stream().filter(a -> Attendance.STATUS_CANCELED.equals(a.getStatus())).count());
        return result;
    }

    public List<AttendanceVO> my(Long userId, String realName) {
        return list(Map.of(), attendanceRepository.findByPersonId(userId));
    }

    /** 人工修改考勤状态：写 audit，置 MANUAL（自动任务不覆盖）。 */
    @Transactional
    public Attendance updateStatus(Long id, AttendanceStatusRequest req, Long operatorId, String operatorName) {
        requireReason(req.getRemark());
        if (Attendance.STATUS_MAKEUP.equals(req.getNewStatus())) return makeup(id, operatorId, operatorName, req.getRemark(), null, null);
        if (req.getNewStatus() == null || !Set.of(Attendance.STATUS_NORMAL, Attendance.STATUS_LATE,
                Attendance.STATUS_ABSENT, Attendance.STATUS_LEAVE, Attendance.STATUS_EARLY_LEAVE).contains(req.getNewStatus()))
            throw new BizException(400, "请选择有效的修正状态");
        Attendance a = lockAttendance(id);
        String old = Attendance.normalizedStatus(a.getStatus());
        if (Objects.equals(old, req.getNewStatus())) return a;
        a.setStatus(req.getNewStatus());
        a.setStatusSource(Attendance.SOURCE_MANUAL);
        a.setRemark(req.getRemark().trim());
        a.setRecordTime(AttendanceWindow.now());
        Attendance saved = attendanceRepository.save(a);
        writeAudit(saved.getId(), old, req.getNewStatus(), operatorId, operatorName, a.getRemark());
        return saved;
    }

    /** 人工补签：建手动签到 + 置 MAKEUP + 审计。 */
    @Transactional
    public Attendance makeup(Long id, Long operatorId, String operatorName, String remark, String studentNo, String personName) {
        requireReason(remark);
        Attendance a = lockAttendance(id);
        if (Attendance.STATUS_MAKEUP.equals(a.getStatus())) return a;
        // 建手动签到记录（若有场次则绑定；无活动上下文时跳过落签，仅改考勤）
        Long activityId = a.getScheduleId() == null ? null
                : scheduleRepository.findById(a.getScheduleId()).map(Schedule::getActivityId).orElse(null);
        if (activityId != null && a.getCheckedInAt() == null && signinRepository.findByScheduleId(a.getScheduleId())
                .stream().noneMatch(sign -> matchesPerson(sign, a))) {
            Signin s = new Signin();
            s.setActivityId(activityId);
            s.setScheduleId(a.getScheduleId());
            s.setSessionId(a.getSessionId());
            s.setPersonId(a.getPersonId());
            s.setName(personName == null ? a.getPersonName() : personName);
            s.setStudentNo(studentNo == null ? a.getStudentNo() : studentNo);
            s.setSignType(Signin.SignType.MANUAL);
            s.setSignTime(AttendanceWindow.now());
            s.setCreatedAt(AttendanceWindow.now());
            signinRepository.save(s);
        }

        String old = a.getStatus();
        a.setStatus(Attendance.STATUS_MAKEUP);
        a.setStatusSource(Attendance.SOURCE_MANUAL);
        if (a.getCheckedInAt() == null) a.setCheckedInAt(AttendanceWindow.now());
        a.setRemark(remark.trim());
        a.setRecordTime(AttendanceWindow.now());
        Attendance saved = attendanceRepository.save(a);
        writeAudit(saved.getId(), Attendance.normalizedStatus(old), Attendance.STATUS_MAKEUP, operatorId, operatorName, a.getRemark());
        return saved;
    }

    public List<AttendanceAudit> audits(Long id) {
        getAttendance(id);
        return auditRepository.findByAttendanceIdOrderByOperateTimeDesc(id);
    }

    @Transactional
    public void recordManualSignin(Attendance row, String oldStatus, com.pams.security.LoginUser actor, String remark) {
        String reason = remark == null || remark.isBlank() ? "活动签到补录" : remark.trim();
        requireReason(reason);
        row.setRemark(reason);
        Attendance saved = attendanceRepository.save(row);
        writeAudit(saved.getId(), oldStatus, Attendance.STATUS_MAKEUP,
                actor == null ? null : actor.getId(), actor == null ? "系统" : actor.getRealName(), reason);
    }

    private Attendance lockAttendance(Long id) {
        Attendance row = getAttendance(id);
        if (row.getScheduleId() != null) scheduleRepository.lockById(row.getScheduleId())
                .orElseThrow(() -> new BizException(409, "关联班次已变更，请刷新"));
        if (entityManager != null) entityManager.refresh(row, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (Attendance.STATUS_CANCELED.equals(row.getStatus()))
            throw new BizException(409, "已取消的考勤不能修正");
        return row;
    }

    private void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 255)
            throw new BizException(400, "请填写 1 到 255 个字符的修正原因");
    }

    private void writeAudit(Long attendanceId, String oldStatus, String newStatus,
                            Long operatorId, String operatorName, String remark) {
        AttendanceAudit audit = new AttendanceAudit();
        audit.setAttendanceId(attendanceId);
        audit.setOldStatus(oldStatus);
        audit.setNewStatus(newStatus);
        audit.setOperatorId(operatorId);
        audit.setOperatorName(operatorName);
        audit.setOperateTime(AttendanceWindow.now());
        audit.setRemark(remark);
        audit.setCreatedAt(AttendanceWindow.now());
        auditRepository.save(audit);
    }

    private AttendanceVO toVO(Attendance a, Schedule sch, Activity act, List<Signin> signs) {
        AttendanceVO vo = new AttendanceVO();
        vo.setId(a.getId());
        vo.setScheduleId(a.getScheduleId());
        vo.setSessionId(a.getSessionId());
        vo.setPersonId(a.getPersonId());
        vo.setPersonName(a.getPersonName());
        vo.setStudentNo(a.getStudentNo());
        vo.setStatus(Attendance.normalizedStatus(a.getStatus()));
        vo.setStatusSource(a.getStatusSource() == null ? Attendance.SOURCE_AUTO : a.getStatusSource());
        vo.setRemark(a.getRemark());
        vo.setCheckedInAt(a.getCheckedInAt());
        vo.setRecordTime(a.getRecordTime());
        if (sch != null) {
            vo.setActivityDate(sch.getScheduleDate());
            vo.setActivityStart(sch.getStartTime());
            vo.setActivityEnd(sch.getEndTime());
            vo.setSessionName(sch.getSessionName());
            if (sch.getActivityId() != null && act != null) {
                vo.setActivityId(act.getId());
                vo.setActivityName(act.getName());
                vo.setActivityType(act.getType());
            }
        }
        long signCount = sch == null ? 0 : signs.stream().filter(sign -> matchesPerson(sign, a))
                .filter(sign -> sch.getId().equals(sign.getScheduleId()) ||
                        sign.getScheduleId() == null && sign.getSignTime() != null
                                && Objects.equals(a.getCheckedInAt(), sign.getSignTime()))
                .count();
        vo.setSignCount(signCount == 0 && a.getCheckedInAt() != null ? 1 : signCount);
        return vo;
    }

    private boolean matchesPerson(Signin sign, Attendance row) {
        if (row.getPersonId() != null && sign.getPersonId() != null) return row.getPersonId().equals(sign.getPersonId());
        if (row.getStudentNo() != null && !row.getStudentNo().isBlank()
                && sign.getStudentNo() != null && !sign.getStudentNo().isBlank())
            return row.getStudentNo().equals(sign.getStudentNo());
        return false;
    }

    private Attendance getAttendance(Long id) {
        return attendanceRepository.findById(id).orElseThrow(() -> new BizException(2402, "考勤记录不存在"));
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