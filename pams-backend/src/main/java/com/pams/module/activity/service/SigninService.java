package com.pams.module.activity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.BizException;
import com.pams.module.activity.dto.SigninRequest;
import com.pams.module.activity.dto.SigninSummaryVO;
import com.pams.module.activity.dto.SigninTokenDTO;
import com.pams.module.activity.entity.Signin;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.activity.repository.SigninRepository;
import com.pams.module.notification.event.SigninCompletedEvent;
import com.pams.module.routine.entity.Attendance;
import com.pams.module.routine.entity.Schedule;
import com.pams.module.routine.entity.SchedulePerson;
import com.pams.module.routine.repository.AttendanceRepository;
import com.pams.module.routine.service.AttendanceWindow;
import com.pams.module.routine.repository.SchedulePersonRepository;
import com.pams.module.routine.repository.ScheduleRepository;
import com.pams.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Activity QR tokens and sign-in records. A scheduled sign-in belongs to one roster and time window. */
@Service
public class SigninService {
    private final SigninRepository repository;
    private final ActivityRepository activityRepository;
    private final SigninRosterService rosterService;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, TokenEntry> tokenStore = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 已发"签到完成"通知的活动（内存去重，重启即清空） */
    private final Set<Long> completedNotified = ConcurrentHashMap.newKeySet();

    private ScheduleRepository scheduleRepository;
    private SchedulePersonRepository schedulePersonRepository;
    private AttendanceRepository attendanceRepository;
    private UserRepository userRepository;
    @Autowired(required = false)
    private com.pams.module.routine.service.AttendanceManageService attendanceManagement;
    @Autowired(required=false)
    private org.springframework.jdbc.core.JdbcTemplate tenantJdbc;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public SigninService(SigninRepository repository, ActivityRepository activityRepository,
                         SigninRosterService rosterService) {
        this(repository, activityRepository, rosterService, null);
    }

    @Autowired
    public SigninService(SigninRepository repository, ActivityRepository activityRepository,
                         SigninRosterService rosterService, ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.activityRepository = activityRepository;
        this.rosterService = rosterService;
        this.eventPublisher = eventPublisher;
    }

    @Autowired
    public void setAttendanceDeps(ScheduleRepository scheduleRepository,
                                  SchedulePersonRepository schedulePersonRepository,
                                  AttendanceRepository attendanceRepository,
                                  UserRepository userRepository) {
        this.scheduleRepository = scheduleRepository;
        this.schedulePersonRepository = schedulePersonRepository;
        this.attendanceRepository = attendanceRepository;
        this.userRepository = userRepository;
    }

    public static class TokenEntry {
        private final Long activityId;
        private final Long orgId;
        private final LocalDateTime expiresAt;
        public TokenEntry(Long activityId, LocalDateTime expiresAt) {
            this(activityId,expiresAt,com.pams.common.OrgContext.getOrDefault());
        }
        public TokenEntry(Long activityId, LocalDateTime expiresAt, Long orgId) {
            this.activityId = activityId;
            this.expiresAt = expiresAt;
            this.orgId = orgId;
        }
        public Long getOrgId() { return orgId; }
        public Long getActivityId() { return activityId; }
        public LocalDateTime getExpiresAt() { return expiresAt; }
    }

    /** 生成活动唯一签到令牌（24h 有效）；刷新即作废该活动旧令牌（新码生效、旧码作废） */
    @Transactional
    public SigninTokenDTO generateToken(Long activityId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime expiresAt = AttendanceWindow.now().plusHours(24).withNano(0);
        if (tenantJdbc == null) {
            tokenStore.entrySet().removeIf(e -> e.getValue().getActivityId().equals(activityId));
            tokenStore.put(token, new TokenEntry(activityId, expiresAt));
        } else {
            var activity = activityRepository.lockById(activityId)
                    .orElseThrow(() -> new BizException(2001, "活动不存在"));
            activity.setCheckinToken(token);
            activity.setCheckinExpiresAt(expiresAt);
            activityRepository.save(activity);
        }
        SigninTokenDTO dto = new SigninTokenDTO();
        dto.setToken(token);
        dto.setActivityId(activityId);
        dto.setExpiresAt(expiresAt);
        return dto;
    }

    /** 关闭签到：使该活动当前码立即失效（后续自动考勤照常判缺席），重开即重新生成新码 */
    @Transactional
    public void revokeToken(Long activityId) {
        if (tenantJdbc == null) {
            tokenStore.entrySet().removeIf(e -> e.getValue().getActivityId().equals(activityId));
            return;
        }
        var activity = activityRepository.lockById(activityId)
                .orElseThrow(() -> new BizException(2001, "活动不存在"));
        activity.setCheckinToken(null);
        activity.setCheckinExpiresAt(null);
        activityRepository.save(activity);
    }

    public SigninTokenDTO currentToken(Long activityId) {
        var activity = activityRepository.findById(activityId)
                .filter(a -> com.pams.common.OrgContext.getOrDefault().equals(a.getOrgId()))
                .orElseThrow(() -> new BizException(2001, "活动不存在"));
        if (activity.getCheckinToken() == null || activity.getCheckinExpiresAt() == null
                || !AttendanceWindow.now().isBefore(activity.getCheckinExpiresAt())) return null;
        var dto = new SigninTokenDTO();
        dto.setActivityId(activityId);
        dto.setToken(activity.getCheckinToken());
        dto.setExpiresAt(activity.getCheckinExpiresAt());
        return dto;
    }

    /** 测试辅助：将 token 置为已过期 */
    public void forceExpire(String token) {
        TokenEntry e = tokenStore.get(token);
        if (e != null) {
            tokenStore.put(token, new TokenEntry(e.getActivityId(), LocalDateTime.now().minusSeconds(1), e.getOrgId()));
        }
    }

    @Transactional
    public Signin scanSignin(String token, String name, String studentNo) {
        return scanSignin(token, name, studentNo, null);
    }

    @Transactional
    public Signin scanSignin(String token, String name, String studentNo, Map<String, String> fields) {
        Long activityId = validateAndResolveActivity(token);
        LocalDateTime st = AttendanceWindow.now();
        Signin s = new Signin();
        s.setActivityId(activityId);
        s.setName(name);
        s.setStudentNo(studentNo);
        if (fields != null) {
            s.setPhone(fields.get("手机号"));
            s.setClassName(fields.get("班级"));
            s.setIdentityType(fields.get("身份"));
            // 自定义字段值整体落库，供签到记录回显
            if (!fields.isEmpty()) {
                try {
                    s.setFieldsJson(objectMapper.writeValueAsString(fields));
                } catch (Exception ignored) {
                    // 序列化失败仅丢失扩展字段值，不影响签到
                }
            }
        }
        s.setSignType(Signin.SignType.SCAN);
        s.setSignTime(st);
        s.setCreatedAt(LocalDateTime.now());
        if (rosterService != null && rosterService.isInRoster(activityId, fields)) {
            s.setRemark("（应签名单）");
        }

        bindScan(s, st);

        Signin saved = repository.save(s);
        maybeNotifyCompleted(activityId);
        return saved;
    }

    private record Assignment(Schedule schedule, SchedulePerson person) {}

    private void bindScan(Signin signin, LocalDateTime now) {
        List<Schedule> schedules = activitySchedules(signin.getActivityId());
        if (schedules.isEmpty()) return; // Activities without a published roster still accept general sign-ins.
        List<Assignment> matches = assignments(schedules.stream().filter(s -> AttendanceWindow.isOpen(s, now)).toList(), signin);
        List<Assignment> started = matches.stream().filter(a ->
                !now.isBefore(a.schedule().getScheduleDate().atTime(a.schedule().getStartTime()))).toList();
        if (!started.isEmpty()) matches = started;
        if (matches.isEmpty()) throw new BizException(2306, "当前没有属于你的可签到班次，请核对排班时间");
        if (matches.size() > 1) throw new BizException(409, "同一时间有多个匹配班次，请联系排班负责人核对");
        Assignment assignment = matches.getFirst();
        Schedule slot = lockAssignment(assignment);
        if (!AttendanceWindow.isOpen(slot, now)) throw new BizException(409, "班次已变更，请重新签到");
        Attendance existing = personOfAttendance(slot.getId(), assignment.person());
        if (existing != null && Attendance.isSigned(existing.getStatus()))
            throw new BizException(2305, "你已完成本班次签到");
        if (existing != null && (Attendance.SOURCE_MANUAL.equals(existing.getStatusSource())
                || !Attendance.STATUS_PENDING.equals(Attendance.normalizedStatus(existing.getStatus()))))
            throw new BizException(409, "本班次已有考勤结论，请联系管理员核对");
        applyAssignment(signin, slot, assignment.person(), existing, AttendanceWindow.status(slot, now), false);
    }

    private List<Schedule> activitySchedules(Long activityId) {
        if (scheduleRepository == null) return List.of();
        return scheduleRepository.findByScheduleTypeAndActivityId(Schedule.TYPE_ACTIVITY, activityId).stream()
                .filter(s -> !Schedule.STATUS_DRAFT.equals(s.getStatus())).toList();
    }

    private List<Assignment> assignments(List<Schedule> schedules, Signin signin) {
        if (schedules.isEmpty()) return List.of();
        var people = schedulePersonRepository.findByScheduleIdIn(schedules.stream().map(Schedule::getId).toList());
        Long id = signin.getPersonId();
        if (id == null && signin.getStudentNo() != null && !signin.getStudentNo().isBlank() && userRepository != null) {
            Long orgId = com.pams.common.OrgContext.getOrDefault();
            var ids = userRepository.findByStudentNo(signin.getStudentNo()).stream()
                    .filter(u -> orgId.equals(u.getOrgId()) && Integer.valueOf(1).equals(u.getStatus())
                            && !Integer.valueOf(1).equals(u.getDeleted()))
                    .map(com.pams.entity.User::getId).distinct().toList();
            if (ids.size() != 1) return List.of();
            id = ids.getFirst();
        }
        if (id == null) {
            var ids = people.stream().filter(p -> signin.getName() != null && signin.getName().equalsIgnoreCase(p.getPersonName()))
                    .map(SchedulePerson::getUserId).filter(java.util.Objects::nonNull).distinct().toList();
            if (ids.size() != 1) return List.of();
            id = ids.getFirst();
        }
        Long userId = id;
        Map<Long, Schedule> byId = new HashMap<>();
        schedules.forEach(s -> byId.put(s.getId(), s));
        Map<Long, SchedulePerson> bySchedule = new java.util.LinkedHashMap<>();
        people.stream().filter(p -> userId.equals(p.getUserId())).forEach(p -> bySchedule.putIfAbsent(p.getScheduleId(), p));
        return bySchedule.values().stream().map(p -> new Assignment(byId.get(p.getScheduleId()), p)).toList();
    }

    private Schedule lockAssignment(Assignment assignment) {
        Schedule candidate = assignment.schedule();
        Schedule current = scheduleRepository.lockById(candidate.getId())
                .orElseThrow(() -> new BizException(409, "班次已变更，请刷新"));
        if (entityManager != null) entityManager.refresh(current, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        boolean stillAssigned = schedulePersonRepository.findCurrentRoster(current.getId()).stream()
                .anyMatch(p -> assignment.person().getUserId().equals(p.getUserId()));
        if (!stillAssigned) throw new BizException(409, "排班人员已变更，请刷新");
        return current;
    }

    /** Binds manual entries only when there is an unambiguous scheduled assignment. */
    private void bindManual(Signin signin) {
        List<Assignment> matches = assignments(activitySchedules(signin.getActivityId()).stream()
                .filter(s -> Schedule.STATUS_PUBLISHED.equals(s.getStatus()) || Schedule.STATUS_ENDED.equals(s.getStatus()))
                .toList(), signin);
        var time = signin.getSignTime();
        List<Assignment> timed = matches.stream().filter(a -> {
            Schedule s = a.schedule();
            return s.getScheduleDate() != null && s.getStartTime() != null && s.getEndTime() != null
                    && !time.isBefore(s.getScheduleDate().atTime(s.getStartTime()).minusMinutes(30))
                    && time.isBefore(s.getScheduleDate().atTime(s.getEndTime()));
        }).toList();
        if (!timed.isEmpty()) matches = timed;
        if (matches.isEmpty()) return;
        if (matches.size() != 1) throw new BizException(409, "该成员有多个班次，请指定实际签到时间或从考勤明细补签");
        Assignment assignment = matches.getFirst();
        Schedule slot = lockAssignment(assignment);
        if (!Schedule.STATUS_PUBLISHED.equals(slot.getStatus()) && !Schedule.STATUS_ENDED.equals(slot.getStatus()))
            throw new BizException(409, "班次已变更，请刷新");
        Attendance existing = personOfAttendance(slot.getId(), assignment.person());
        if (existing != null && (Attendance.isSigned(existing.getStatus())
                || Attendance.SOURCE_MANUAL.equals(existing.getStatusSource())))
            throw new BizException(409, "本班次已有考勤结论，请在考勤中心核对");
        applyAssignment(signin, slot, assignment.person(), existing, Attendance.STATUS_MAKEUP, true);
    }

    private void applyAssignment(Signin signin, Schedule slot, SchedulePerson person, Attendance existing,
                                 String status, boolean manual) {
        signin.setScheduleId(slot.getId());
        signin.setPersonId(person.getUserId());
        Attendance row = existing == null ? new Attendance() : existing;
        if (existing == null) {
            row.setScheduleId(slot.getId());
            row.setPersonId(person.getUserId());
            row.setPersonName(person.getPersonName());
            row.setCreatedAt(LocalDateTime.now());
        }
        String previousStatus = Attendance.normalizedStatus(row.getStatus());
        row.setStatus(status);
        row.setStatusSource(manual ? Attendance.SOURCE_MANUAL : Attendance.SOURCE_AUTO);
        if (signin.getStudentNo() != null && !signin.getStudentNo().isBlank()) row.setStudentNo(signin.getStudentNo());
        row.setCheckedInAt(signin.getSignTime());
        row.setRecordTime(LocalDateTime.now());
        if (manual && attendanceManagement != null) {
            var actor = com.pams.module.agent.core.ToolContext.current(null, null).getLoginUser();
            attendanceManagement.recordManualSignin(row, existing == null ? Attendance.STATUS_PENDING : previousStatus,
                    actor, signin.getRemark());
        } else attendanceRepository.save(row);
    }

    private Attendance personOfAttendance(Long scheduleId, SchedulePerson p) {
        return attendanceRepository.findByScheduleIdAndPersonId(scheduleId, p.getUserId()).orElse(null);
    }

    /**
     * 签到完成后通知（链路4）：有应签名单且实到达到应签人数时，发一次 SIGNIN_COMPLETED 事件。
     * 用内存 Set 去重，避免后续超额扫码重复触发。无名单/未达人数时不发。
     */
    private void maybeNotifyCompleted(Long activityId) {
        if (eventPublisher == null || rosterService == null) {
            return;
        }
        SigninSummaryVO summary = rosterService.summary(activityId);
        long expected = summary.getExpected();
        long signed = summary.getSigned();
        if (expected > 0 && signed >= expected && completedNotified.add(activityId)) {
            eventPublisher.publishEvent(new SigninCompletedEvent(activityId, signed, expected));
        }
    }

    private Long validateAndResolveActivity(String token) {
        Long activityId = resolveActivityByToken(token);
        if (!activityRepository.existsById(activityId)) {
            tokenStore.remove(token);
            throw new BizException(2001, "活动不存在");
        }
        return activityId;
    }

    /** 校验 token 有效并解析活动 id（供公开接口读取核验字段配置，不校验活动存在，返回原始值） */
    public Long resolveActivityByToken(String token) {
        return resolveTokenEntry(token).getActivityId();
    }

    /** Must run before the public controller invokes a transactional tenant service. */
    public Long resolveOrgByToken(String token) {
        return resolveTokenEntry(token).getOrgId();
    }

    private TokenEntry resolveTokenEntry(String token) {
        TokenEntry entry = tenantJdbc == null ? tokenStore.get(token) : tenantJdbc.query("""
                SELECT a.id, a.org_id, a.checkin_expires_at FROM activity a
                JOIN organization o ON o.id = a.org_id
                WHERE a.checkin_token = ? AND a.deleted = 0 AND o.status = 1 AND o.deleted = 0
                """, (rs, n) -> new TokenEntry(rs.getLong(1), rs.getObject(3, LocalDateTime.class),
                        rs.getLong(2)), token).stream().findFirst().orElse(null);
        if (entry == null) throw new BizException(2302, "签到码无效或已失效");
        if (entry.getExpiresAt() == null || !AttendanceWindow.now().isBefore(entry.getExpiresAt()))
            throw new BizException(2303, "签到码已过期，请刷新");
        return entry;
    }

    public List<Signin> listByActivity(Long activityId, String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            return repository.findByActivityIdAndNameContaining(activityId, keyword.trim());
        }
        return repository.findByActivityId(activityId);
    }

    public long count(Long activityId) {
        return repository.countByActivityId(activityId);
    }

    @Transactional
    public Signin create(SigninRequest req) {
        Signin s = new Signin();
        s.setActivityId(req.getActivityId());
        s.setPersonId(req.getPersonId());
        s.setName(req.getName());
        s.setStudentNo(req.getStudentNo());
        s.setClassName(req.getClassName());
        s.setIdentityType(req.getIdentityType());
        s.setSignType(req.getSignType() == Signin.SignType.SCAN ? Signin.SignType.SCAN : Signin.SignType.MANUAL);
        s.setSignTime(req.getSignTime() == null ? LocalDateTime.now() : req.getSignTime());
        s.setLocation(req.getLocation());
        s.setPhone(req.getPhone());
        s.setRemark(req.getRemark());
        s.setCreatedAt(LocalDateTime.now());
        return saveManualRecord(s);
    }

    @Transactional
    public void recordSelfCheckin(Schedule schedule, Attendance attendance) {
        Signin signin = new Signin();
        signin.setScheduleId(schedule.getId());
        signin.setActivityId(schedule.getActivityId());
        signin.setPersonId(attendance.getPersonId());
        signin.setName(attendance.getPersonName());
        signin.setStudentNo(attendance.getStudentNo());
        signin.setSignType(Signin.SignType.SELF);
        signin.setSignTime(attendance.getCheckedInAt());
        signin.setCreatedAt(AttendanceWindow.now());
        repository.save(signin);
        maybeNotifyCompleted(schedule.getActivityId());
    }

    @Transactional
    public Signin saveManualRecord(Signin signin) {
        bindManual(signin);
        return repository.save(signin);
    }

    @Transactional
    public void delete(Long id) {
        Signin signin = getEntity(id);
        if (signin.getScheduleId() != null || isLegacyAttendanceEvidence(signin)) {
            throw new BizException(409, "此记录已关联班次，请在考勤中心修正并保留原始签到记录");
        }
        repository.delete(signin);
    }

    private boolean isLegacyAttendanceEvidence(Signin signin) {
        if (signin.getSignTime() == null || signin.getPersonId() == null) return false;
        return activitySchedules(signin.getActivityId()).stream()
                .filter(s -> signin.getSignTime().toLocalDate().equals(s.getScheduleDate()))
                .anyMatch(s -> attendanceRepository.findByScheduleIdAndPersonId(s.getId(), signin.getPersonId())
                        .filter(a -> signin.getSignTime().equals(a.getCheckedInAt())).isPresent());
    }

    public Signin getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(2301, "签到记录不存在"));
    }
}
