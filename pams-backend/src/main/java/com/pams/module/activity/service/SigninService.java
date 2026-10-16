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
import com.pams.module.routine.repository.SchedulePersonRepository;
import com.pams.module.routine.repository.ScheduleRepository;
import com.pams.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活动级单码签到：一个活动一个签到码（token 只绑定 activityId，刷新即作废旧码）。
 *
 * 扫码自动判时：以「浏览器当前时间」为签到时间，匹配该活动当天已发布活动排班，
 * 归属到包含/最近的时段；时段内（<= 结束时间）= 正常，超过结束时间 = 迟到；
 * 无该天活动排班则保持宽松记录（不判考勤）。重复签到拒绝，非排班人员默认拒绝。
 */
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
    @Autowired(required=false)
    private org.springframework.jdbc.core.JdbcTemplate tenantJdbc;

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
    public SigninTokenDTO generateToken(Long activityId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(24);
        tokenStore.entrySet().removeIf(e -> e.getValue().getActivityId().equals(activityId));
        tokenStore.put(token, new TokenEntry(activityId, expiresAt));
        SigninTokenDTO dto = new SigninTokenDTO();
        dto.setToken(token);
        dto.setActivityId(activityId);
        dto.setExpiresAt(expiresAt);
        return dto;
    }

    /** 关闭签到：使该活动当前码立即失效（后续自动考勤照常判缺席），重开即重新生成新码 */
    public void revokeToken(Long activityId) {
        tokenStore.entrySet().removeIf(e -> e.getValue().getActivityId().equals(activityId));
        tokenStore.put(UUID.randomUUID().toString().replace("-", ""),
                new TokenEntry(activityId, LocalDateTime.now().minusSeconds(1)));
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
        return scanSignin(token, name, studentNo, null, null);
    }

    @Transactional
    public Signin scanSignin(String token, String name, String studentNo, Map<String, String> fields) {
        return scanSignin(token, name, studentNo, fields, null);
    }

    /**
     * 扫码签到（免登录，浏览器时间判时）：
     * 校验 token/活动 → 取签到时间（默认服务器当前时间）→ 若活动当天有已发布活动排班则
     * 判时归属（正常/迟到）并落考勤，否则宽松记录。重复签到与缺排班人员分别拦截。
     */
    @Transactional
    public Signin scanSignin(String token, String name, String studentNo, Map<String, String> fields, LocalDateTime browserTime) {
        Long activityId = validateAndResolveActivity(token);
        LocalDateTime st = browserTime == null ? LocalDateTime.now() : browserTime;
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

        // 活动当天已发布活动排班的人员；无则宽松记录（不判考勤，兼容纯签到活动）
        List<Schedule> daySchedules = daySchedules(activityId, st.toLocalDate());
        List<SchedulePerson> persons = personsOf(daySchedules);
        if (!persons.isEmpty()) {
            // 重复签到
            if (hasSigned(activityId, name, studentNo)) {
                throw new BizException(2305, "你已完成签到");
            }
            // 匹配排班人员（学号优先，姓名兜底）
            SchedulePerson person = matchPerson(persons, name, studentNo);
            if (person == null) {
                throw new BizException(2306, "当前活动暂无你的排班记录");
            }
            s.setPersonId(person.getUserId());
            // 判定并落考勤
            judgeAndUpdateAttendance(daySchedules, person, s, st);
        }

        Signin saved = repository.save(s);
        maybeNotifyCompleted(activityId);
        return saved;
    }

    /** 活动某天的已发布活动排班（按开始时间排序） */
    private List<Schedule> daySchedules(Long activityId, LocalDate date) {
        if (scheduleRepository == null) return List.of();
        List<Schedule> all = scheduleRepository.findByScheduleTypeAndActivityIdAndStatus(
                Schedule.TYPE_ACTIVITY, activityId, Schedule.STATUS_PUBLISHED);
        List<Schedule> result = new ArrayList<>();
        for (Schedule sch : all) {
            if (sch.getScheduleDate() != null && sch.getScheduleDate().equals(date)) result.add(sch);
        }
        result.sort(Comparator.comparing(sch -> sch.getStartTime() == null ? LocalTime.MIN : sch.getStartTime()));
        return result;
    }

    private List<SchedulePerson> personsOf(List<Schedule> schedules) {
        if (schedulePersonRepository == null) return List.of();
        List<SchedulePerson> result = new ArrayList<>();
        for (Schedule sch : schedules) {
            result.addAll(schedulePersonRepository.findByScheduleId(sch.getId()));
        }
        return result;
    }

    private boolean hasSigned(Long activityId, String name, String studentNo) {
        List<Signin> existing = repository.findByActivityId(activityId);
        for (Signin e : existing) {
            if (studentNo != null && !studentNo.isBlank() && studentNo.equals(e.getStudentNo())) return true;
            if (name != null && name.equalsIgnoreCase(e.getName())) return true;
        }
        return false;
    }

    private SchedulePerson matchPerson(List<SchedulePerson> persons, String name, String studentNo) {
        Long userId = null;
        if (studentNo != null && !studentNo.isBlank() && userRepository != null) {
            userId = userRepository.findByStudentNo(studentNo).stream()
                    .filter(u -> u.getDeleted() == null || u.getDeleted() == 0)
                    .findFirst().map(u -> u.getId()).orElse(null);
        }
        if (userId != null) {
            for (SchedulePerson p : persons) {
                if (userId.equals(p.getUserId())) return p;
            }
        }
        if (name != null) {
            for (SchedulePerson p : persons) {
                if (name.equalsIgnoreCase(p.getPersonName())) return p;
            }
        }
        return null;
    }

    /**
     * 时段归属：优先返回“包含签到时刻”的时段；否则返回“结束时间 >= 签到时刻”中结束最早的时段；
     * 全部已结束则返回结束最晚的时段（据此记迟到）。
     */
    private Schedule judgeSlot(List<Schedule> daySchedules, LocalDateTime st) {
        if (daySchedules == null || daySchedules.isEmpty()) return null;
        LocalTime t = st.toLocalTime();
        // 1) 命中包含此刻的时段
        for (Schedule sch : daySchedules) {
            if (sch.getStartTime() != null && sch.getEndTime() != null
                    && !t.isBefore(sch.getStartTime()) && !t.isAfter(sch.getEndTime())) {
                return sch;
            }
        }
        // 2) 未结束中结束最早的时段
        Schedule upcoming = null;
        Schedule last = null;
        for (Schedule sch : daySchedules) {
            if (sch.getEndTime() == null) continue;
            if (last == null || sch.getEndTime().isAfter(last.getEndTime())) last = sch;
            if (!t.isAfter(sch.getEndTime())) {
                if (upcoming == null || sch.getEndTime().isBefore(upcoming.getEndTime())) upcoming = sch;
            }
        }
        return upcoming != null ? upcoming : last; // 全部已结束 → 最晚时段记迟到
    }

    private void judgeAndUpdateAttendance(List<Schedule> daySchedules, SchedulePerson person, Signin s, LocalDateTime st) {
        if (attendanceRepository == null || daySchedules.isEmpty()) return;
        Schedule slot = judgeSlot(daySchedules, st);
        if (slot == null) return;
        boolean late = slot.getEndTime() != null && st.toLocalTime().isAfter(slot.getEndTime());
        String status = late ? Attendance.STATUS_LATE : Attendance.STATUS_NORMAL;

        SchedulePerson slotPerson = null;
        for (SchedulePerson p : schedulePersonRepository.findByScheduleId(slot.getId())) {
            if (person.getUserId() != null && person.getUserId().equals(p.getUserId())) { slotPerson = p; break; }
            if (person.getUserId() == null && person.getPersonName().equalsIgnoreCase(p.getPersonName())) { slotPerson = p; break; }
        }
        if (slotPerson == null) return;

        Attendance existing = personOfAttendance(slot.getId(), slotPerson);
        Attendance a;
        if (existing != null) {
            if (Attendance.SOURCE_MANUAL.equals(existing.getStatusSource())) return; // 人工改过不自动覆盖
            a = existing;
        } else {
            a = new Attendance();
            a.setScheduleId(slot.getId());
            a.setPersonId(slotPerson.getUserId());
            a.setPersonName(slotPerson.getPersonName());
            a.setCreatedAt(LocalDateTime.now());
        }
        a.setStatus(status);
        a.setStatusSource(Attendance.SOURCE_AUTO);
        a.setStudentNo(s.getStudentNo());
        a.setCheckedInAt(st);
        a.setRecordTime(LocalDateTime.now());
        attendanceRepository.save(a);
    }

    private Attendance personOfAttendance(Long scheduleId, SchedulePerson p) {
        if (p.getUserId() != null) {
            return attendanceRepository.findByScheduleIdAndPersonId(scheduleId, p.getUserId()).orElse(null);
        }
        return attendanceRepository.findByScheduleIdAndPersonName(scheduleId, p.getPersonName()).orElse(null);
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
        TokenEntry e = tokenStore.get(token);
        if (e == null) throw new BizException(2302, "签到码无效或已失效");
        if (e.getExpiresAt().isBefore(LocalDateTime.now())) {
            tokenStore.remove(token);
            throw new BizException(2303, "签到码已过期，请刷新");
        }
        return e.getActivityId();
    }

    /** Must run before the public controller invokes a transactional tenant service. */
    public Long resolveOrgByToken(String token) {
        resolveActivityByToken(token);
        TokenEntry entry = tokenStore.get(token);
        if (entry == null) throw new BizException(2302,"签到码无效或已失效");
        Long org = entry.getOrgId();
        if (tenantJdbc != null && tenantJdbc.queryForObject("SELECT COUNT(*) FROM organization WHERE id=? AND status=1 AND deleted=0",Integer.class,org) != 1) throw new BizException(403,"组织不可用");
        return org;
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
        s.setSignType(req.getSignType() == null ? Signin.SignType.MANUAL : req.getSignType());
        s.setSignTime(req.getSignTime());
        s.setLocation(req.getLocation());
        s.setPhone(req.getPhone());
        s.setRemark(req.getRemark());
        s.setCreatedAt(LocalDateTime.now());
        return repository.save(s);
    }

    @Transactional
    public void delete(Long id) {
        getEntity(id);
        repository.deleteById(id);
    }

    public Signin getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(2301, "签到记录不存在"));
    }
}
