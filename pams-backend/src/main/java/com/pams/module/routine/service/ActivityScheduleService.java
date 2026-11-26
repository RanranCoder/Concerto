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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 活动排班：基于无课表一键生成 / 手动维护 / 发布 / 取消 / 重新生成。
 * 仅 PUBLISHED 排班进入签到与考勤；发布时为其排班人员预建 PENDING 考勤行。
 */
@Service
public class ActivityScheduleService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.pams.module.notification.service.NotificationBusinessEvents notifications;


    private final FreeSlotMatcher matcher;
    private final ScheduleRepository scheduleRepository;
    private final SchedulePersonRepository schedulePersonRepository;
    private final AttendanceRepository attendanceRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final ActivityRepository activityRepository;
    private final com.pams.module.org.repository.OrganizationRepository organizations;

    public ActivityScheduleService(FreeSlotMatcher matcher,
                                   ScheduleRepository scheduleRepository,
                                   SchedulePersonRepository schedulePersonRepository,
                                   AttendanceRepository attendanceRepository,
                                   DepartmentRepository departmentRepository,
                                   UserRepository userRepository,
                                   ActivityRepository activityRepository,
                                   com.pams.module.org.repository.OrganizationRepository organizations) {
        this.matcher = matcher;
        this.scheduleRepository = scheduleRepository;
        this.schedulePersonRepository = schedulePersonRepository;
        this.attendanceRepository = attendanceRepository;
        this.departmentRepository = departmentRepository;
        this.userRepository = userRepository;
        this.activityRepository = activityRepository;
        this.organizations = organizations;
    }

    /**
     * 一键生成：对日期范围内每个日期按无课表筛选候选，创建 DRAFT 活动排班 + 排班人员。
     * 返回每日期命中明细。
     */
    @Transactional
    public List<Map<String, Object>> generate(ActivityScheduleGenerateRequest req) {
        return generateReviewed(req, null);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate generationJdbc;

    public record GenerationReview(String fingerprint, List<Map<String, Object>> days, int draftCount) {}

    @Transactional(readOnly = true)
    public GenerationReview reviewGeneration(ActivityScheduleGenerateRequest request) {
        Long target = existingTarget(request);
        var calculation = calculate(request, true, generationConflicts(request, target));
        return review(request, target, calculation);
    }

    @Transactional
    public GenerationReview confirmGeneration(ActivityScheduleGenerateRequest request, String fingerprint) {
        validate(fingerprint != null && !fingerprint.isBlank(), "请先预览并核对排班");
        if (generationJdbc != null) {
            generationJdbc.queryForObject("SELECT id FROM organization WHERE id = ? FOR UPDATE",
                    Long.class, com.pams.common.OrgContext.getOrDefault());
        }
        Long target = existingTarget(request);
        if (target != null) {
            activityRepository.lockById(target).filter(a -> a.getStatus() != ActivityStatus.ARCHIVED)
                    .orElseThrow(() -> new BizException(2408, "关联活动不可用"));
        }
        var calculation = calculate(request, true, generationConflicts(request, target));
        var current = review(request, target, calculation);
        if (!fingerprint.equals(current.fingerprint())) {
            throw new BizException(409, "排班预览已变化，请重新预览并核对");
        }
        validate(current.draftCount() > 0, "暂无可生成的班次，请调整排班条件");
        Long activityId = target == null ? resolveActivityId(request) : target;
        saveDrafts(request, activityId, calculation);
        return current;
    }

    private Long existingTarget(ActivityScheduleGenerateRequest request) {
        if (request.getActivityId() != null) {
            return activityRepository.findById(request.getActivityId())
                    .filter(a -> com.pams.common.OrgContext.getOrDefault().equals(a.getOrgId())
                            && a.getStatus() != ActivityStatus.ARCHIVED)
                    .map(Activity::getId)
                    .orElseThrow(() -> new BizException(2408, "关联活动不可用"));
        }
        validate(request.getCustomActivityName() != null && !request.getCustomActivityName().isBlank(),
                "请选择活动或填写自定义活动名称");
        var matches = activityRepository.findByName(request.getCustomActivityName().trim());
        if (matches.isEmpty()) return null;
        return matches.stream().filter(a -> a.getStatus() != ActivityStatus.ARCHIVED)
                .map(Activity::getId).min(Long::compareTo)
                .orElseThrow(() -> new BizException(2408, "同名活动已归档，请使用其他名称"));
    }

    private GenerationReview review(ActivityScheduleGenerateRequest request, Long target, Calculation calculation) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("org", com.pams.common.OrgContext.getOrDefault());
        scope.put("activity", target);
        scope.put("custom", request.getCustomActivityName());
        scope.put("start", request.getStartDate());
        scope.put("end", request.getEndDate());
        scope.put("startTime", request.getStartTime());
        scope.put("endTime", request.getEndTime());
        scope.put("departments", request.getDeptIds());
        scope.put("semester", request.getSemester());
        scope.put("semesterStart", request.getSemesterStartDate());
        scope.put("people", request.getPeoplePerSlot());
        scope.put("days", calculation.details());
        return new GenerationReview(previewFingerprint(List.of(scope)), calculation.details(), calculation.assignments().size());
    }

    private record Conflicts(Long activityId, List<Schedule> schedules, Map<Long, List<SchedulePerson>> rosters) {
        private static final Conflicts NONE = new Conflicts(null, List.of(), Map.of());

        List<Schedule> overlapping(LocalDate date, FreeSlotMatcher.PeriodSlot slot) {
            return schedules.stream().filter(s -> date.equals(s.getScheduleDate()))
                    .filter(s -> s.getStartTime() == null || s.getEndTime() == null
                            || s.getStartTime().isBefore(slot.endTime()) && s.getEndTime().isAfter(slot.startTime()))
                    .toList();
        }
    }

    private Conflicts generationConflicts(ActivityScheduleGenerateRequest request, Long target) {
        if (request.getStartDate() == null || request.getEndDate() == null) return Conflicts.NONE;
        List<Schedule> schedules = scheduleRepository
                .findByScheduleDateBetweenOrderByIdAsc(request.getStartDate(), request.getEndDate()).stream()
                .filter(s -> !Schedule.STATUS_CANCELLED.equals(s.getStatus()))
                .filter(s -> Schedule.STATUS_PUBLISHED.equals(s.getStatus())
                        || target != null && target.equals(s.getActivityId()) && Schedule.TYPE_ACTIVITY.equals(s.getScheduleType()))
                .toList();
        Map<Long, List<SchedulePerson>> rosters = schedules.isEmpty() ? Map.of()
                : schedulePersonRepository.findByScheduleIdIn(schedules.stream().map(Schedule::getId).toList())
                        .stream().collect(Collectors.groupingBy(SchedulePerson::getScheduleId));
        return new Conflicts(target, schedules, rosters);
    }

    /** 同一计算器用于只读预览及写入；预览不创建活动、排班、人员或考勤。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> preview(ActivityScheduleGenerateRequest req) {
        return calculate(req).details();
    }

    /** 确认时若无课表改变，拒绝写入，让用户重新核对清单。 */
    @Transactional
    public List<Map<String, Object>> generateReviewed(ActivityScheduleGenerateRequest req, String expected) {
        Calculation calculation = calculate(req, expected == null);
        if (expected != null && !expected.equals(previewFingerprint(calculation.details()))) {
            throw new BizException(2408, "排班候选人员已变化，请重新生成清单并核对后确认。");
        }
        if (calculation.assignments().isEmpty()) return calculation.details();
        Long activityId = resolveActivityId(req);
        activityRepository.lockById(activityId)
                .filter(a -> a.getStatus() != ActivityStatus.ARCHIVED)
                .orElseThrow(() -> new BizException(2408, "关联活动不可用"));
        var existing = scheduleRepository.findByScheduleTypeAndActivityIdAndScheduleDateBetween(
                Schedule.TYPE_ACTIVITY, activityId, req.getStartDate(), req.getEndDate());
        for (Assignment assignment : calculation.assignments()) {
            validate(existing.stream().noneMatch(s -> !Schedule.STATUS_CANCELLED.equals(s.getStatus())
                    && assignment.date().equals(s.getScheduleDate())
                    && (s.getStartTime() == null || s.getEndTime() == null
                    || s.getStartTime().isBefore(assignment.slot().endTime())
                    && s.getEndTime().isAfter(assignment.slot().startTime()))), "活动已有重叠班次，请先核对已有排班");
        }
        return saveDrafts(req, activityId, calculation);
    }

    /** Saves only fully matched unpublished drafts inside the explicitly delegated scope. */
    @Transactional
    public List<Map<String, Object>> generateDelegatedDraft(ActivityScheduleGenerateRequest req, String expected) {
        Long orgId = com.pams.common.OrgContext.get();
        validate(orgId != null && req.getActivityId() != null && req.getDeptIds() != null
                && req.getDeptIds().size() == 1 && req.getPeoplePerSlot() != null
                && req.getPeoplePerSlot() >= 1 && req.getPeoplePerSlot() <= 10
                && req.getSemester() == null && req.getSemesterStartDate() == null
                && req.getStartDate() != null && req.getEndDate() != null
                && !req.getEndDate().isBefore(req.getStartDate()) && !req.getEndDate().isAfter(req.getStartDate().plusDays(6))
                && req.getStartTime() != null && req.getEndTime() != null && req.getEndTime().isAfter(req.getStartTime()),
                "授权仅覆盖一个活动、一个部门和七天内的明确排班草稿");
        var activity = activityRepository.lockById(req.getActivityId()).filter(a -> orgId.equals(a.getOrgId())
                && a.getStatus() != ActivityStatus.ARCHIVED).orElseThrow(() -> new BizException(2408, "关联活动不可用"));
        Long deptId = req.getDeptIds().getFirst();
        validate(departmentRepository.findById(deptId).filter(dept -> orgId.equals(dept.getOrgId())).isPresent(),
                "参与部门不可用");
        var overlaps = scheduleRepository.findByScheduleTypeAndActivityIdAndScheduleDateBetween(
                Schedule.TYPE_ACTIVITY, activity.getId(), req.getStartDate(), req.getEndDate());
        validate(overlaps.stream().noneMatch(schedule ->
                schedule.getStartTime() == null || schedule.getEndTime() == null
                        || (schedule.getStartTime().isBefore(req.getEndTime())
                        && schedule.getEndTime().isAfter(req.getStartTime()))),
                "指定范围已有重叠排班，请先核对已有记录");

        Calculation calculation = calculate(req);
        validate(expected != null && expected.equals(previewFingerprint(calculation.details())),
                "排班候选人员已变化，请重新核对");
        long coveredDays = calculation.assignments().stream().map(Assignment::date).distinct().count();
        validate(coveredDays == java.time.temporal.ChronoUnit.DAYS.between(req.getStartDate(), req.getEndDate()) + 1,
                "部分日期没有完整班次，请先核对候选");
        var assignedIds = new java.util.HashSet<Long>();
        for (Assignment assignment : calculation.assignments()) {
            validate(!assignment.slot().startTime().isBefore(req.getStartTime())
                            && !assignment.slot().endTime().isAfter(req.getEndTime()),
                    "候选班次超出授权时段，请调整时段后核对");
            validate(assignment.people().size() == req.getPeoplePerSlot()
                            && assignment.people().stream().allMatch(candidate -> candidate.userId() != null),
                    "候选人数不足或账号身份不明确，请先核对");
            assignment.people().forEach(candidate -> assignedIds.add(candidate.userId()));
        }
        var accounts = userRepository.findAllById(assignedIds);
        validate(accounts.size() == assignedIds.size() && accounts.stream().allMatch(user ->
                orgId.equals(user.getOrgId()) && Objects.equals(user.getStatus(), 1)
                        && user.getDept() != null && deptId.equals(user.getDept().getId())),
                "候选账号已失效或不在授权部门，请先核对");
        return saveDrafts(req, activity.getId(), calculation);
    }

    private List<Map<String, Object>> saveDrafts(ActivityScheduleGenerateRequest req, Long activityId, Calculation calculation) {
        List<String> deptNames = new ArrayList<>();
        for (Long deptId : req.getDeptIds()) {
            departmentRepository.findById(deptId).ifPresent(d -> deptNames.add(d.getName()));
        }
        String deptName = deptNames.isEmpty() ? null : String.join("/", deptNames);
        for (Assignment a : calculation.assignments()) {
            createSchedule(activityId, req, a, deptName);
        }
        return calculation.details();
    }

    public static String previewFingerprint(List<Map<String, Object>> details) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(details.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Assignment(LocalDate date, FreeSlotMatcher.PeriodSlot slot,
                              List<FreeSlotMatcher.Candidate> people) {}
    private record Calculation(List<Map<String, Object>> details, List<Assignment> assignments) {}

    private Calculation calculate(ActivityScheduleGenerateRequest req) {
        return calculate(req, false);
    }

    private Calculation calculate(ActivityScheduleGenerateRequest req, boolean clipPeriods) {
        return calculate(req, clipPeriods, Conflicts.NONE);
    }

    private Calculation calculate(ActivityScheduleGenerateRequest req, boolean clipPeriods, Conflicts conflicts) {
        validate(req.getStartDate() != null && req.getEndDate() != null, "请选择日期范围");
        validate(req.getStartTime() != null && req.getEndTime() != null, "请设置开始/结束时间");
        validate(req.getEndTime().isAfter(req.getStartTime()), "结束时间必须晚于开始时间");
        validate(!req.getEndDate().isBefore(req.getStartDate()), "结束日期不能早于开始日期");
        validate(req.getDeptIds() != null && !req.getDeptIds().isEmpty(), "请选择参与部门");

        int limit = req.getPeoplePerSlot() == null || req.getPeoplePerSlot() <= 0 ? Integer.MAX_VALUE : req.getPeoplePerSlot();

        List<Map<String, Object>> details = new ArrayList<>();
        List<Assignment> assignments = new ArrayList<>();
        // 全局“已排次数”（按姓名），用于跨天/跨节次均衡分配，尽量不让同一人反复被排
        Map<String, Integer> usage = new java.util.HashMap<>();
        for (LocalDate d = req.getStartDate(); !d.isAfter(req.getEndDate()); d = d.plusDays(1)) {
            // 按节次拆分：每个被覆盖的节次单独建立一条排班，安排该节次空闲的人员
            List<FreeSlotMatcher.PeriodSlot> slots = matcher.matchByPeriod(
                    d, req.getStartTime(), req.getEndTime(), req.getDeptIds(), req.getSemester(), req.getSemesterStartDate());
            List<Map<String, Object>> slotDetails = new ArrayList<>();
            int dayTotal = 0;
            List<Map<String, Object>> dayPersons = new ArrayList<>();
            for (FreeSlotMatcher.PeriodSlot matched : slots) {
                var slot = clipPeriods ? new FreeSlotMatcher.PeriodSlot(matched.period(), matched.label(),
                        matched.startTime().isBefore(req.getStartTime()) ? req.getStartTime() : matched.startTime(),
                        matched.endTime().isAfter(req.getEndTime()) ? req.getEndTime() : matched.endTime(),
                        matched.candidates()) : matched;
                List<String> unbound = slot.candidates().stream().filter(c -> c.userId() == null)
                        .map(FreeSlotMatcher.Candidate::name).distinct().toList();
                List<Schedule> overlaps = conflicts.overlapping(d, slot);
                List<Long> existingShifts = overlaps.stream()
                        .filter(s -> conflicts.activityId() != null && conflicts.activityId().equals(s.getActivityId())
                                && Schedule.TYPE_ACTIVITY.equals(s.getScheduleType()))
                        .map(Schedule::getId).toList();
                Set<Long> busy = overlaps.stream().filter(s -> Schedule.STATUS_PUBLISHED.equals(s.getStatus()))
                        .flatMap(s -> conflicts.rosters().getOrDefault(s.getId(), List.of()).stream())
                        .map(SchedulePerson::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
                List<String> busyNames = slot.candidates().stream().filter(c -> c.userId() != null && busy.contains(c.userId()))
                        .map(FreeSlotMatcher.Candidate::name).distinct().toList();
                List<FreeSlotMatcher.Candidate> picked = existingShifts.isEmpty()
                        ? pickBalanced(slot.candidates().stream()
                                .filter(c -> c.userId() != null && !busy.contains(c.userId())).toList(), limit, usage)
                        : List.of();
                if (!picked.isEmpty()) assignments.add(new Assignment(d, slot, picked));
                for (FreeSlotMatcher.Candidate c : picked) {
                    usage.merge(c.userId().toString(), 1, Integer::sum);
                    dayPersons.add(personMap(c));
                }
                dayTotal += picked.size();
                Map<String, Object> slotDetail = slotMap(slot, picked);
                slotDetail.put("unboundNames", unbound);
                slotDetail.put("conflictingNames", busyNames);
                slotDetail.put("existingShiftIds", existingShifts);
                slotDetail.put("missingCount", limit == Integer.MAX_VALUE ? 0 : Math.max(0, limit - picked.size()));
                slotDetails.add(slotDetail);
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("date", d);
            detail.put("count", dayTotal);
            detail.put("persons", dayPersons);
            detail.put("slots", slotDetails);
            details.add(detail);
        }
        return new Calculation(details, assignments);
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
            int ua = usage.getOrDefault(a.userId().toString(), 0);
            int ub = usage.getOrDefault(b.userId().toString(), 0);
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

    private void createSchedule(Long activityId, ActivityScheduleGenerateRequest request, Assignment assignment,
                                String deptName) {
        var cands = assignment.people();
        var slot = assignment.slot();
        Schedule s = new Schedule();
        s.setScheduleType(Schedule.TYPE_ACTIVITY);
        s.setActivityId(activityId);
        s.setSessionName(slot.label());
        s.setScheduleDate(assignment.date());
        s.setStartTime(slot.startTime());
        s.setEndTime(slot.endTime());
        s.setStatus(Schedule.STATUS_DRAFT);
        s.setSource(Schedule.SOURCE_AUTO);
        s.setDeptId(request.getDeptIds().getFirst());
        s.setDeptName(deptName);
        s.setSemester(request.getSemester());
        s.setGenerationDeptIds(request.getDeptIds().stream().distinct().map(String::valueOf).collect(Collectors.joining(",")));
        s.setGenerationPeopleLimit(request.getPeoplePerSlot());
        s.setGenerationSemesterStart(request.getSemesterStartDate());
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        Schedule saved = scheduleRepository.save(s);
        for (int i = 0; i < cands.size(); i++) {
            FreeSlotMatcher.Candidate c = cands.get(i);
            savePerson(saved.getId(), c.userId(), c.name(), i == 0 ? 1 : 0);
        }
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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void publish(Long id) {
        publish(id, true);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void publish(Long id, boolean activity) {
        lockPublicationOrg();
        publishLocked(lockSchedule(id, activity));
    }

    /** Take the shared lock before any schedule lock or conflict read. */
    void lockPublicationOrg() {
        organizations.findForUpdate(com.pams.common.OrgContext.getOrDefault())
                .orElseThrow(() -> new BizException(404, "组织不存在"));
    }

    private void publishLocked(Schedule s) {
        validate(Schedule.STATUS_DRAFT.equals(s.getStatus()) || Schedule.STATUS_PUBLISHED.equals(s.getStatus()),
                "当前状态不可发布");
        if (Schedule.STATUS_DRAFT.equals(s.getStatus())) validateForPublication(s);
        var notificationBefore = notifications == null ? null : notifications.snapshot(s);
        s.setStatus(Schedule.STATUS_PUBLISHED);
        s.setUpdatedAt(LocalDateTime.now());
        scheduleRepository.save(s);
        ensureAttendanceRows(s);
        if (notifications != null) notifications.scheduleChanged(notificationBefore, s);
    }

    @Transactional
    public void cancel(Long id) {
        cancel(id, true);
    }

    @Transactional
    public void cancel(Long id, boolean activity) {
        Schedule s = lockSchedule(id, activity);
        if (Schedule.STATUS_CANCELLED.equals(s.getStatus())) return;
        validate(Schedule.STATUS_PUBLISHED.equals(s.getStatus()), "仅已发布班次可取消");
        var notificationBefore = notifications == null ? null : notifications.snapshot(s);
        s.setStatus(Schedule.STATUS_CANCELLED);
        s.setUpdatedAt(LocalDateTime.now());
        scheduleRepository.save(s);
        if (notifications != null) notifications.scheduleChanged(notificationBefore, s);
        // Preserve completed and manually reviewed outcomes. Only open expectations are canceled.
        for (Attendance a : attendanceRepository.findByScheduleId(id)) {
            if (Attendance.SOURCE_MANUAL.equals(a.getStatusSource())
                    || !Attendance.STATUS_PENDING.equals(Attendance.normalizedStatus(a.getStatus()))) continue;
            a.setStatus(Attendance.STATUS_CANCELED);
            a.setRecordTime(LocalDateTime.now());
            attendanceRepository.save(a);
        }
    }

    /** 一键发布：把当前范围内（可按活动过滤）全部草稿排班发布并预建考勤行，返回发布条数。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int publishAll(Long activityId) {
        lockPublicationOrg();
        int n = 0;
        for (Long id : scheduleRepository.findDraftActivityIds(activityId)) {
            Schedule draft = lockSchedule(id, true);
            if (!Schedule.STATUS_DRAFT.equals(draft.getStatus())) continue;
            publishLocked(draft);
            n++;
        }
        return n;
    }

    /** 重新生成：仅 AUTO 来源可重新按无课表替换人员；已发布则同步刷新考勤行。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int regenerate(Long id, Long deptId, String semester, LocalDate semesterStartDate) {
        lockPublicationOrg();
        Schedule s = lockSchedule(id, true);
        requirePublishedMutation(s);
        validate(Schedule.SOURCE_AUTO.equals(s.getSource()), "仅无课表自动生成的排班可重新生成");
        if (s.getScheduleDate() == null || s.getStartTime() == null || s.getEndTime() == null) {
            throw new BizException(2406, "该排班缺少日期或时间，无法重新生成");
        }
        List<Long> departments = deptId != null ? List.of(deptId)
                : s.getGenerationDeptIds() != null ? java.util.Arrays.stream(s.getGenerationDeptIds().split(",")).map(Long::valueOf).toList()
                : s.getDeptId() == null ? List.of() : List.of(s.getDeptId());
        String sem = semester != null && !semester.isBlank() ? semester : s.getSemester();
        LocalDate semesterStart = semesterStartDate == null ? s.getGenerationSemesterStart() : semesterStartDate;
        List<FreeSlotMatcher.PeriodSlot> slots = matcher.matchByPeriod(s.getScheduleDate(), s.getStartTime(), s.getEndTime(),
                departments, sem, semesterStart);
        // 命中与该排班时段一致的节次；否则取第一个可排节次
        FreeSlotMatcher.PeriodSlot target = null;
        for (FreeSlotMatcher.PeriodSlot slot : slots) {
            if (slot.startTime().equals(s.getStartTime()) && slot.endTime().equals(s.getEndTime())) { target = slot; break; }
        }
        if (target == null && !slots.isEmpty()) target = slots.get(0);
        int previousCount = schedulePersonRepository.findByScheduleId(id).size();
        int peopleLimit = s.getGenerationPeopleLimit() == null
                ? (previousCount == 0 ? Integer.MAX_VALUE : previousCount)
                : (s.getGenerationPeopleLimit() <= 0 ? Integer.MAX_VALUE : s.getGenerationPeopleLimit());
        List<FreeSlotMatcher.Candidate> cands = target == null ? List.of() : target.candidates().stream()
                .filter(c -> c.userId() != null).limit(peopleLimit).toList();
        if (cands.isEmpty()) throw new BizException(2407, "该日期时段无符合无课表的候选人员");
        var notificationBefore = notifications == null ? null : notifications.snapshot(s);
        schedulePersonRepository.deleteByScheduleId(id);
        for (int i = 0; i < cands.size(); i++) {
            FreeSlotMatcher.Candidate c = cands.get(i);
            savePerson(id, c.userId(), c.name(), i == 0 ? 1 : 0);
        }
        s.setSemester(sem);
        s.setGenerationSemesterStart(semesterStart);
        if (deptId != null) {
            s.setDeptId(deptId);
            s.setDeptName(departmentRepository.findById(deptId).map(d -> d.getName()).orElse(null));
            s.setGenerationDeptIds(deptId.toString());
        }
        s.setUpdatedAt(LocalDateTime.now());
        scheduleRepository.save(s);
        if (Schedule.STATUS_PUBLISHED.equals(s.getStatus())) {
            validateForPublication(s);
            syncAttendanceRows(s);
        }
        if (notifications != null) notifications.scheduleChanged(notificationBefore, s);
        return cands.size();
    }

    @Transactional
    public Long createManual(ActivityScheduleSaveRequest req) {
        validate(req.getActivityId() != null, "活动ID不能为空");
        validate(req.getScheduleDate() != null, "请选择日期");
        validate(req.getStartTime() != null && req.getEndTime() != null && req.getEndTime().isAfter(req.getStartTime()),
                "请选择有效的班次起止时间");
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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void updateManual(Long id, ActivityScheduleSaveRequest req) {
        lockPublicationOrg();
        Schedule s = lockSchedule(id, true);
        requirePublishedMutation(s);
        validate(req.getScheduleDate() != null && req.getStartTime() != null && req.getEndTime() != null
                && req.getEndTime().isAfter(req.getStartTime()), "请选择有效的班次日期和时间");
        if (Schedule.STATUS_PUBLISHED.equals(s.getStatus())) {
            requireUnchangedRecordedTime(s, req.getScheduleDate(), req.getStartTime(), req.getEndTime());
        }
        var notificationBefore = notifications == null ? null : notifications.snapshot(s);
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
            validateForPublication(s);
            syncAttendanceRows(s);
        }
        if (notifications != null) notifications.scheduleChanged(notificationBefore, s);
    }

    @Transactional
    public void delete(Long id) {
        Schedule s = lockSchedule(id, true);
        validate(Schedule.STATUS_DRAFT.equals(s.getStatus()), "仅草稿可删除，请取消已发布班次");
        var notificationBefore = notifications == null ? null : notifications.snapshot(s);
        schedulePersonRepository.deleteByScheduleId(id);
        attendanceRepository.deleteByScheduleId(id);
        scheduleRepository.deleteById(id);
        if (notifications != null) notifications.scheduleChanged(notificationBefore, null);
    }

    /** 发布后为每位排班人员预建 PENDING 考勤行（自动判断引擎将据此落考勤）。 */
    private void requirePublishedMutation(Schedule s) {
        validate(Schedule.STATUS_DRAFT.equals(s.getStatus()) || Schedule.STATUS_PUBLISHED.equals(s.getStatus()),
                "已结束或已取消班次不可修改");
        if (Schedule.STATUS_PUBLISHED.equals(s.getStatus())
                && !com.pams.module.agent.core.ToolContext.current(null,null).hasPermission("schedule:publish"))
            throw new BizException(403,"修改已发布班次需要排班发布权限");
    }
    void validateForPublication(Schedule s) {
        validate(s.getScheduleDate() != null && s.getStartTime() != null && s.getEndTime() != null
                && s.getEndTime().isAfter(s.getStartTime()), "发布前请设置有效的班次日期和起止时间");
        List<SchedulePerson> persons = schedulePersonRepository.findByScheduleId(s.getId());
        validate(!persons.isEmpty() && persons.stream().allMatch(p -> p.getUserId() != null),
                "发布前请为每位排班人员关联账号");
        List<Long> ids = persons.stream().map(SchedulePerson::getUserId).distinct().toList();
        validate(ids.size() == persons.size(), "同一成员不能重复安排在一个班次");
        Long orgId = com.pams.common.OrgContext.getOrDefault();
        var accounts = userRepository.findAllById(ids);
        validate(accounts.size() == ids.size() && accounts.stream().allMatch(u ->
                orgId.equals(u.getOrgId()) && Integer.valueOf(1).equals(u.getStatus())
                        && !Integer.valueOf(1).equals(u.getDeleted())),
                "请核对班次人员，名单含停用或不属于本组织的账号");
        validate(scheduleRepository.countRosterConflicts(s.getId(), s.getScheduleDate(), s.getStartTime(),
                s.getEndTime(), ids) == 0, "有成员已在此时段排班，请更换人员或调整时间");
    }

    void requireUnchangedRecordedTime(Schedule schedule, LocalDate date, java.time.LocalTime start, java.time.LocalTime end) {
        if (Objects.equals(date, schedule.getScheduleDate()) && Objects.equals(start, schedule.getStartTime())
                && Objects.equals(end, schedule.getEndTime())) return;
        validate(attendanceRepository.findByScheduleId(schedule.getId()).stream()
                .allMatch(a -> Attendance.STATUS_PENDING.equals(Attendance.normalizedStatus(a.getStatus()))
                        && !Attendance.SOURCE_MANUAL.equals(a.getStatusSource())),
                "班次已有考勤结论，不能修改日期或时间");
    }

    void ensureUpcomingAttendanceRows(Schedule schedule) {
        List<SchedulePerson> roster = schedulePersonRepository.findByScheduleId(schedule.getId());
        List<Long> userIds = roster.stream().map(SchedulePerson::getUserId).filter(Objects::nonNull).distinct().toList();
        if (userIds.isEmpty()) return;
        Long orgId = com.pams.common.OrgContext.getOrDefault();
        Set<Long> active = userRepository.findAllById(userIds).stream()
                .filter(u -> orgId.equals(u.getOrgId()) && Integer.valueOf(1).equals(u.getStatus())
                        && !Integer.valueOf(1).equals(u.getDeleted()))
                .map(com.pams.entity.User::getId).collect(Collectors.toSet());
        ensureAttendanceRows(schedule, roster.stream().filter(person -> active.contains(person.getUserId())).toList());
    }

    private void ensureAttendanceRows(Schedule s) {
        ensureAttendanceRows(s, schedulePersonRepository.findByScheduleId(s.getId()));
    }

    private void ensureAttendanceRows(Schedule s, List<SchedulePerson> persons) {
        List<Attendance> existing = new ArrayList<>(attendanceRepository.findByScheduleId(s.getId()));
        for (SchedulePerson p : persons) {
            if (existing.stream().anyMatch(a -> samePerson(a, p))) continue;
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
            existing.add(a);
        }
    }

    /** 人员调整只移除不再排班的自动记录；人工修正及其审计保持原 ID。 */
    void syncAttendanceRows(Schedule s) {
        List<SchedulePerson> persons = schedulePersonRepository.findByScheduleId(s.getId());
        for (Attendance a : attendanceRepository.findByScheduleId(s.getId())) {
            if (Attendance.SOURCE_MANUAL.equals(a.getStatusSource())
                    || !Attendance.STATUS_PENDING.equals(Attendance.normalizedStatus(a.getStatus()))) continue;
            if (persons.stream().noneMatch(p -> samePerson(a, p))) attendanceRepository.deleteById(a.getId());
        }
        ensureAttendanceRows(s);
    }

    private boolean samePerson(Attendance a, SchedulePerson p) {
        if (p.getUserId() != null) return Objects.equals(a.getPersonId(), p.getUserId());
        return a.getPersonId() == null && Objects.equals(a.getPersonName(), p.getPersonName());
    }

    private void savePersons(Long scheduleId, List<ScheduleRequest.SchedulePersonItem> persons) {
        if (persons == null) return;
        int index = 0;
        for (ScheduleRequest.SchedulePersonItem p : persons) {
            if (p.getPersonName() == null || p.getPersonName().isBlank()) continue;
            int primary = p.getIsPrimary() == null ? (index == 0 ? 1 : 0) : (p.getIsPrimary() == 1 ? 1 : 0);
            savePerson(scheduleId, p.getUserId(), p.getPersonName(), primary);
            index++;
        }
    }

    private void savePerson(Long scheduleId, Long userId, String personName, int isPrimary) {
        SchedulePerson sp = new SchedulePerson();
        sp.setScheduleId(scheduleId);
        sp.setUserId(userId);
        sp.setPersonName(personName);
        sp.setIsPrimary(isPrimary);
        sp.setCreatedAt(LocalDateTime.now());
        schedulePersonRepository.save(sp);
    }

    private Schedule lockSchedule(Long id, boolean activity) {
        return scheduleRepository.lockById(id)
                .filter(s -> activity == Schedule.TYPE_ACTIVITY.equals(s.getScheduleType()))
                .orElseThrow(() -> new BizException(2401, "排班不存在"));
    }

    private void validate(boolean cond, String msg) {
        if (!cond) throw new BizException(400, msg);
    }
}
