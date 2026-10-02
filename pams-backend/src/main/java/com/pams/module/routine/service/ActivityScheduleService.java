package com.pams.module.routine.service;

import com.pams.common.BizException;
import com.pams.entity.Department;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.routine.dto.ActivityScheduleGenerateRequest;
import com.pams.module.routine.dto.ActivityScheduleSaveRequest;
import com.pams.module.routine.dto.ScheduleRequest;
import com.pams.module.routine.entity.Attendance;
import com.pams.module.routine.entity.Schedule;
import com.pams.module.routine.entity.SchedulePerson;
import com.pams.module.routine.repository.AttendanceRepository;
import com.pams.module.routine.repository.SchedulePersonRepository;
import com.pams.module.routine.repository.ScheduleRepository;
import com.pams.module.schedule.service.FreeSlotMatcher;
import com.pams.repository.DepartmentRepository;
import com.pams.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 活动排班：基于无课表一键生成 / 手动维护 / 发布 / 取消 / 重新生成。
 * 仅 PUBLISHED 排班进入签到与考勤；发布时为其排班人员预建 PENDING 考勤行。
 */
@Service
public class ActivityScheduleService {

    private final FreeSlotMatcher matcher;
    private final ScheduleRepository scheduleRepository;
    private final SchedulePersonRepository schedulePersonRepository;
    private final AttendanceRepository attendanceRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final ActivityRepository activityRepository;

    public ActivityScheduleService(FreeSlotMatcher matcher,
                                   ScheduleRepository scheduleRepository,
                                   SchedulePersonRepository schedulePersonRepository,
                                   AttendanceRepository attendanceRepository,
                                   DepartmentRepository departmentRepository,
                                   UserRepository userRepository,
                                   ActivityRepository activityRepository) {
        this.matcher = matcher;
        this.scheduleRepository = scheduleRepository;
        this.schedulePersonRepository = schedulePersonRepository;
        this.attendanceRepository = attendanceRepository;
        this.departmentRepository = departmentRepository;
        this.userRepository = userRepository;
        this.activityRepository = activityRepository;
    }

    /**
     * 一键生成：对日期范围内每个日期按无课表筛选候选，创建 DRAFT 活动排班 + 排班人员。
     * 返回每日期命中明细。
     */
    @Transactional
    public List<Map<String, Object>> generate(ActivityScheduleGenerateRequest req) {
        validate(req.getStartDate() != null && req.getEndDate() != null, "请选择日期范围");
        validate(req.getStartTime() != null && req.getEndTime() != null, "请设置开始/结束时间");
        validate(!req.getEndDate().isBefore(req.getStartDate()), "结束日期不能早于开始日期");
        validate(req.getDeptIds() != null && !req.getDeptIds().isEmpty(), "请选择参与部门");

        Long activityId = resolveActivityId(req);

        List<String> deptNames = new ArrayList<>();
        for (Long deptId : req.getDeptIds()) {
            departmentRepository.findById(deptId).ifPresent(d -> deptNames.add(d.getName()));
        }
        String deptName = deptNames.isEmpty() ? null : String.join("/", deptNames);
        Long firstDeptId = req.getDeptIds().get(0);

        int limit = req.getPeoplePerSlot() == null || req.getPeoplePerSlot() <= 0 ? Integer.MAX_VALUE : req.getPeoplePerSlot();

        List<Map<String, Object>> details = new ArrayList<>();
        int created = 0;
        // 全局“已排次数”（按姓名），用于跨天/跨节次均衡分配，尽量不让同一人反复被排
        Map<String, Integer> usage = new java.util.HashMap<>();
        for (LocalDate d = req.getStartDate(); !d.isAfter(req.getEndDate()); d = d.plusDays(1)) {
            // 按节次拆分：每个被覆盖的节次单独建立一条排班，安排该节次空闲的人员
            List<FreeSlotMatcher.PeriodSlot> slots = matcher.matchByPeriod(
                    d, req.getStartTime(), req.getEndTime(), req.getDeptIds(), req.getSemester(), req.getSemesterStartDate());
            List<Map<String, Object>> slotDetails = new ArrayList<>();
            int dayTotal = 0;
            List<Map<String, Object>> dayPersons = new ArrayList<>();
            for (FreeSlotMatcher.PeriodSlot slot : slots) {
                // 本时段候选去重 + 按已排次数最少优先 + 限制人数
                List<FreeSlotMatcher.Candidate> picked = pickBalanced(slot.candidates(), limit, usage);
                if (picked.isEmpty()) continue;
                long id = createSchedule(activityId, d, slot.startTime(), slot.endTime(),
                        deptName, firstDeptId, req.getSemester(), picked, slot.label());
                if (id > 0) created++;
                for (FreeSlotMatcher.Candidate c : picked) {
                    usage.merge(c.name(), 1, Integer::sum);
                    dayPersons.add(personMap(c));
                }
                dayTotal += picked.size();
                slotDetails.add(slotMap(slot, picked));
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("date", d);
            detail.put("count", dayTotal);
            detail.put("persons", dayPersons);
            detail.put("slots", slotDetails);
            details.add(detail);
        }
        return details;
    }

    /**
     * 均衡选人：候选已在时段内去重，按“已排次数最少优先、姓名升序”取前 limit 人；
     * 同一人在全局（整个日期范围）内尽量不被反复安排。
     */
    private List<FreeSlotMatcher.Candidate> pickBalanced(List<FreeSlotMatcher.Candidate> cands,
                                                         int limit, Map<String, Integer> usage) {
        if (cands == null || cands.isEmpty()) return List.of();
        List<FreeSlotMatcher.Candidate> sorted = new ArrayList<>(cands);
        sorted.sort((a, b) -> {
            int ua = usage.getOrDefault(a.name(), 0);
            int ub = usage.getOrDefault(b.name(), 0);
            if (ua != ub) return ua - ub;
            return a.name().compareTo(b.name());
        });
        return sorted.size() > limit ? sorted.subList(0, limit) : sorted;
    }

    private Map<String, Object> slotMap(FreeSlotMatcher.PeriodSlot slot, List<FreeSlotMatcher.Candidate> picked) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("period", slot.period());
        m.put("label", slot.label());
        m.put("startTime", slot.startTime());
        m.put("endTime", slot.endTime());
        m.put("count", picked.size());
        List<Map<String, Object>> ps = new ArrayList<>();
        for (FreeSlotMatcher.Candidate c : picked) ps.add(personMap(c));
        m.put("persons", ps);
        return m;
    }

    /** 候选人员转可序列化 Map（String 值显式装箱避免 Map.of 泛型推断问题） */
    private Map<String, Object> personMap(FreeSlotMatcher.Candidate c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", c.name());
        m.put("userId", c.userId() == null ? 0L : c.userId());
        return m;
    }

    /** 解析目标活动：优先用已存在 activityId；自定义活动按名称判重，已存在则复用。 */
    private Long resolveActivityId(ActivityScheduleGenerateRequest req) {
        if (req.getActivityId() != null) return req.getActivityId();
        validate(req.getCustomActivityName() != null && !req.getCustomActivityName().isBlank(), "请选择活动或填写自定义活动名称");
        String name = req.getCustomActivityName().trim();
        // 按名称判重：已存在同名活动则复用（避免重复创建）
        List<Activity> existing = activityRepository.findByName(name);
        if (!existing.isEmpty()) {
            return existing.get(0).getId();
        }
        Activity a = new Activity();
        a.setName(name);
        a.setType("OTHER");
        a.setStatus(ActivityStatus.ASSIGNED);
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        a.setDeleted(0);
        return activityRepository.save(a).getId();
    }

    private long createSchedule(Long activityId, LocalDate date, java.time.LocalTime start, java.time.LocalTime end,
                                String deptName, Long deptId, String semester,
                                List<FreeSlotMatcher.Candidate> cands, String sessionName) {
        if (cands == null || cands.isEmpty()) {
            return -1; // 无候选则不建排班（避免空排班）
        }
        Schedule s = new Schedule();
        s.setScheduleType(Schedule.TYPE_ACTIVITY);
        s.setActivityId(activityId);
        s.setSessionName(sessionName);
        s.setScheduleDate(date);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setStatus(Schedule.STATUS_DRAFT);
        s.setSource(Schedule.SOURCE_AUTO);
        s.setDeptId(deptId);
        s.setDeptName(deptName);
        s.setSemester(semester);
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        Schedule saved = scheduleRepository.save(s);
        for (FreeSlotMatcher.Candidate c : cands) {
            savePerson(saved.getId(), c.userId(), c.name());
        }
        return saved.getId();
    }

    public List<Schedule> list(Long activityId, String status, LocalDate date) {
        List<Schedule> all = scheduleRepository.findAll().stream()
                .filter(s -> Schedule.TYPE_ACTIVITY.equals(s.getScheduleType()))
                .filter(s -> activityId == null || activityId.equals(s.getActivityId()))
                .filter(s -> status == null || status.isBlank() || status.equals(s.getStatus()))
                .filter(s -> date == null || date.equals(s.getScheduleDate()))
                .sorted((a, b) -> {
                    if (a.getScheduleDate() == null || b.getScheduleDate() == null) return 0;
                    return a.getScheduleDate().compareTo(b.getScheduleDate());
                })
                .collect(Collectors.toList());
        if (!all.isEmpty()) {
            List<Long> ids = all.stream().map(Schedule::getId).collect(Collectors.toList());
            Map<Long, List<SchedulePerson>> bySchedule = schedulePersonRepository
                    .findByScheduleIdIn(ids).stream()
                    .collect(Collectors.groupingBy(SchedulePerson::getScheduleId));
            all.forEach(s -> s.setPersons(bySchedule.getOrDefault(s.getId(), List.of())));
        }
        return all;
    }

    @Transactional
    public void publish(Long id) {
        Schedule s = getSchedule(id);
        validate(Schedule.STATUS_DRAFT.equals(s.getStatus()) || Schedule.STATUS_PUBLISHED.equals(s.getStatus()),
                "当前状态不可发布");
        s.setStatus(Schedule.STATUS_PUBLISHED);
        s.setUpdatedAt(LocalDateTime.now());
        scheduleRepository.save(s);
        ensureAttendanceRows(s);
    }

    @Transactional
    public void cancel(Long id) {
        Schedule s = getSchedule(id);
        s.setStatus(Schedule.STATUS_CANCELLED);
        s.setUpdatedAt(LocalDateTime.now());
        scheduleRepository.save(s);
        // 联动：该排班人员考勤置「取消」（不覆盖人工改过的行）
        for (Attendance a : attendanceRepository.findByScheduleId(id)) {
            if (Attendance.SOURCE_MANUAL.equals(a.getStatusSource())) continue;
            a.setStatus(Attendance.STATUS_CANCELED);
            a.setRecordTime(LocalDateTime.now());
            attendanceRepository.save(a);
        }
    }

    /** 一键发布：把当前范围内（可按活动过滤）全部草稿排班发布并预建考勤行，返回发布条数。 */
    @Transactional
    public int publishAll(Long activityId) {
        List<Schedule> drafts = scheduleRepository.findAll().stream()
                .filter(s -> Schedule.TYPE_ACTIVITY.equals(s.getScheduleType()))
                .filter(s -> Schedule.STATUS_DRAFT.equals(s.getStatus()))
                .filter(s -> activityId == null || activityId.equals(s.getActivityId()))
                .toList();
        int n = 0;
        for (Schedule s : drafts) {
            s.setStatus(Schedule.STATUS_PUBLISHED);
            s.setUpdatedAt(LocalDateTime.now());
            scheduleRepository.save(s);
            ensureAttendanceRows(s);
            n++;
        }
        return n;
    }

    /** 重新生成：仅 AUTO 来源可重新按无课表替换人员；已发布则同步刷新考勤行。 */
    @Transactional
    public int regenerate(Long id, Long deptId, String semester, LocalDate semesterStartDate) {
        Schedule s = getSchedule(id);
        validate(Schedule.SOURCE_AUTO.equals(s.getSource()), "仅无课表自动生成的排班可重新生成");
        if (s.getScheduleDate() == null || s.getStartTime() == null || s.getEndTime() == null) {
            throw new BizException(2406, "该排班缺少日期或时间，无法重新生成");
        }
        schedulePersonRepository.deleteByScheduleId(id);
        Long dpt = deptId != null ? deptId : s.getDeptId();
        String sem = semester != null && !semester.isBlank() ? semester : s.getSemester();
        List<FreeSlotMatcher.PeriodSlot> slots = matcher.matchByPeriod(s.getScheduleDate(), s.getStartTime(), s.getEndTime(),
                dpt == null ? List.of() : List.of(dpt), sem, semesterStartDate);
        // 命中与该排班时段一致的节次；否则取第一个可排节次
        FreeSlotMatcher.PeriodSlot target = null;
        for (FreeSlotMatcher.PeriodSlot slot : slots) {
            if (slot.startTime().equals(s.getStartTime()) && slot.endTime().equals(s.getEndTime())) { target = slot; break; }
        }
        if (target == null && !slots.isEmpty()) target = slots.get(0);
        List<FreeSlotMatcher.Candidate> cands = target == null ? List.of() : target.candidates();
        if (cands.isEmpty()) throw new BizException(2407, "该日期时段无符合无课表的候选人员");
        for (FreeSlotMatcher.Candidate c : cands) {
            savePerson(id, c.userId(), c.name());
        }
        s.setUpdatedAt(LocalDateTime.now());
        scheduleRepository.save(s);
        if (Schedule.STATUS_PUBLISHED.equals(s.getStatus())) {
            attendanceRepository.deleteByScheduleId(id);
            ensureAttendanceRows(s);
        }
        return cands.size();
    }

    @Transactional
    public Long createManual(ActivityScheduleSaveRequest req) {
        validate(req.getActivityId() != null, "活动ID不能为空");
        validate(req.getScheduleDate() != null, "请选择日期");
        validate(req.getStartTime() != null && req.getEndTime() != null, "请设置开始/结束时间");
        Schedule s = new Schedule();
        s.setScheduleType(Schedule.TYPE_ACTIVITY);
        s.setActivityId(req.getActivityId());
        s.setScheduleDate(req.getScheduleDate());
        s.setStartTime(req.getStartTime());
        s.setEndTime(req.getEndTime());
        s.setLocation(req.getLocation());
        s.setNotes(req.getNote());
        s.setStatus(Schedule.STATUS_DRAFT);
        s.setSource(Schedule.SOURCE_MANUAL);
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        Schedule saved = scheduleRepository.save(s);
        savePersons(saved.getId(), req.getPersons());
        return saved.getId();
    }

    @Transactional
    public void updateManual(Long id, ActivityScheduleSaveRequest req) {
        Schedule s = getSchedule(id);
        s.setScheduleDate(req.getScheduleDate());
        s.setStartTime(req.getStartTime());
        s.setEndTime(req.getEndTime());
        s.setLocation(req.getLocation());
        s.setNotes(req.getNote());
        s.setUpdatedAt(LocalDateTime.now());
        scheduleRepository.save(s);
        schedulePersonRepository.deleteByScheduleId(id);
        savePersons(id, req.getPersons());
        if (Schedule.STATUS_PUBLISHED.equals(s.getStatus())) {
            attendanceRepository.deleteByScheduleId(id);
            ensureAttendanceRows(s);
        }
    }

    @Transactional
    public void delete(Long id) {
        getSchedule(id);
        schedulePersonRepository.deleteByScheduleId(id);
        attendanceRepository.deleteByScheduleId(id);
        scheduleRepository.deleteById(id);
    }

    /** 发布后为每位排班人员预建 PENDING 考勤行（自动判断引擎将据此落考勤）。 */
    private void ensureAttendanceRows(Schedule s) {
        List<SchedulePerson> persons = schedulePersonRepository.findByScheduleId(s.getId());
        for (SchedulePerson p : persons) {
            Attendance a = new Attendance();
            a.setScheduleId(s.getId());
            a.setPersonId(p.getUserId());
            a.setPersonName(p.getPersonName());
            a.setStatus(Attendance.STATUS_PENDING);
            a.setStatusSource(Attendance.SOURCE_AUTO);
            if (p.getUserId() != null) {
                userRepository.findById(p.getUserId()).ifPresent(u -> a.setStudentNo(u.getStudentNo()));
            }
            a.setRecordTime(LocalDateTime.now());
            a.setCreatedAt(LocalDateTime.now());
            attendanceRepository.save(a);
        }
    }

    private void savePersons(Long scheduleId, List<ScheduleRequest.SchedulePersonItem> persons) {
        if (persons == null) return;
        for (ScheduleRequest.SchedulePersonItem p : persons) {
            if (p.getPersonName() == null || p.getPersonName().isBlank()) continue;
            savePerson(scheduleId, p.getUserId(), p.getPersonName());
        }
    }

    private void savePerson(Long scheduleId, Long userId, String personName) {
        SchedulePerson sp = new SchedulePerson();
        sp.setScheduleId(scheduleId);
        sp.setUserId(userId);
        sp.setPersonName(personName);
        sp.setIsPrimary(1);
        sp.setCreatedAt(LocalDateTime.now());
        schedulePersonRepository.save(sp);
    }

    private Schedule getSchedule(Long id) {
        return scheduleRepository.findById(id)
                .filter(s -> Schedule.TYPE_ACTIVITY.equals(s.getScheduleType()))
                .orElseThrow(() -> new BizException(2401, "活动排班不存在"));
    }

    private void validate(boolean cond, String msg) {
        if (!cond) throw new BizException(400, msg);
    }
}