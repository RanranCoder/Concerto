package com.pams.module.routine.service;

import com.pams.common.BizException;
import com.pams.module.routine.dto.AttendanceRequest;
import com.pams.module.routine.dto.ScheduleRequest;
import com.pams.module.routine.entity.Attendance;
import com.pams.module.routine.entity.Schedule;
import com.pams.module.routine.entity.SchedulePerson;
import com.pams.module.routine.repository.AttendanceRepository;
import com.pams.module.routine.repository.SchedulePersonRepository;
import com.pams.module.routine.repository.ScheduleRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 例行事务：排班 / 考勤。
 * 生产用全参构造器注入 3 个 repo；1 参构造器仅用于测试（其余 repo 为 null）。
 */
@Service
public class RoutineService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.pams.module.notification.service.NotificationBusinessEvents notifications;
    @Autowired
    private ActivityScheduleService publication;
    @Autowired
    private com.pams.module.activity.service.SigninService signinService;
    private final ScheduleRepository scheduleRepository;
    private final SchedulePersonRepository schedulePersonRepository;
    private final AttendanceRepository attendanceRepository;

    private String uploadDir;

    @Autowired
    public RoutineService(ScheduleRepository scheduleRepository,
                          SchedulePersonRepository schedulePersonRepository,
                          AttendanceRepository attendanceRepository) {
        this.scheduleRepository = scheduleRepository;
        this.schedulePersonRepository = schedulePersonRepository;
        this.attendanceRepository = attendanceRepository;
    }

    /** 测试用：仅注入 AttendanceRepository，其余为 null */
    public RoutineService(AttendanceRepository attendanceRepository) {
        this(null, null, attendanceRepository);
    }

    /** 测试用：仅注入排班与考勤 repo，其余为 null（测 summary 的 weekNo/type 过滤需走排班关联） */
    public RoutineService(ScheduleRepository scheduleRepository, AttendanceRepository attendanceRepository) {
        this(scheduleRepository, null, attendanceRepository);
    }

    @Autowired
    public void setUploadDir(@Value("${pams.upload-dir:./uploads}") String uploadDir) {
        this.uploadDir = uploadDir;
    }

    // ==================== 排班 ====================

    @Transactional
    public Long createSchedule(ScheduleRequest req) {
        validateRegularRequest(req);
        Schedule s = new Schedule();
        applySchedule(s, req);
        s.setStatus(Schedule.STATUS_DRAFT);
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        Schedule saved = scheduleRepository.save(s);
        savePersons(saved.getId(), req.getPersons());
        return saved.getId();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void updateSchedule(Long id, ScheduleRequest req) {
        publication.lockPublicationOrg();
        Schedule s = scheduleRepository.lockById(id).orElseThrow(() -> new BizException(404,"排班不存在"));
        validateRegularRequest(req);
        requireRegular(s);
        requirePublishedMutation(s);
        if (Schedule.STATUS_PUBLISHED.equals(s.getStatus())) publication.requireUnchangedRecordedTime(s,
                req.getScheduleDate(), req.getStartTime(), req.getEndTime());
        var notificationBefore=notifications==null?null:notifications.snapshot(s);
        applySchedule(s, req);
        scheduleRepository.save(s);
        schedulePersonRepository.deleteByScheduleId(id);
        savePersons(id, req.getPersons());
        if (Schedule.STATUS_PUBLISHED.equals(s.getStatus())) {
            publication.validateForPublication(s);
            publication.syncAttendanceRows(s);
        }
        if(notifications!=null)notifications.scheduleChanged(notificationBefore,s);
    }

    @Transactional
    public void deleteSchedule(Long id) {
        Schedule s=scheduleRepository.lockById(id).orElseThrow(() -> new BizException(404,"排班不存在"));
        requireRegular(s);
        if (!Schedule.STATUS_DRAFT.equals(s.getStatus())) throw new BizException(409, "仅草稿可删除，请取消已发布班次");
        var notificationBefore=notifications==null?null:notifications.snapshot(s);
        schedulePersonRepository.deleteByScheduleId(id);
        // attendance.schedule_id 有 FK 约束，先清掉该排班的考勤记录再删排班
        attendanceRepository.deleteByScheduleId(id);
        scheduleRepository.deleteById(id);
        if(notifications!=null)notifications.scheduleChanged(notificationBefore,null);
    }

    /** 按 type/weekNo/weekday/activityId 过滤排班列表，并组装每个排班的人员数组 */
    public List<Schedule> listSchedules(String type, Integer weekNo, Integer weekday, Long activityId) {
        List<Schedule> all = scheduleRepository.findAll();
        List<Schedule> filtered = all.stream()
                .filter(s -> type == null || type.isBlank() || type.equals(s.getScheduleType()))
                .filter(s -> weekNo == null || weekNo.equals(s.getWeekNo()))
                .filter(s -> weekday == null || weekday.equals(s.getWeekday()))
                .filter(s -> activityId == null || activityId.equals(s.getActivityId()))
                .collect(Collectors.toList());
        // C3 fix: 批量加载排班人员替代逐条 findByScheduleId，避免 N+1
        if (!filtered.isEmpty() && schedulePersonRepository != null) {
            List<Long> ids = filtered.stream().map(Schedule::getId).collect(Collectors.toList());
            Map<Long, List<SchedulePerson>> personsBySchedule = schedulePersonRepository
                    .findByScheduleIdIn(ids).stream()
                    .collect(Collectors.groupingBy(SchedulePerson::getScheduleId));
            filtered.forEach(s -> s.setPersons(personsBySchedule.getOrDefault(s.getId(), List.of())));
        }
        return filtered;
    }

    public static boolean canReviewDrafts() {
        var actor = com.pams.module.agent.core.ToolContext.current(null, null);
        return actor.hasPermission("schedule:manage") || actor.hasPermission("schedule:publish")
                || actor.hasPermission("schedule:generate");
    }

    /** 我的排班：返回当前用户在排班人员中的排班（含已组装 persons）。 */
    public List<Schedule> listMySchedules(Long userId, String realName) {
        List<Schedule> all = listSchedules(null, null, null, null);
        return all.stream()
                .filter(s -> Schedule.STATUS_PUBLISHED.equals(s.getStatus()) || Schedule.STATUS_ENDED.equals(s.getStatus()))
                .filter(s -> s.getPersons().stream().anyMatch(p ->
                        (userId != null && p.getUserId() != null && userId.equals(p.getUserId()))
                                ))
                .collect(Collectors.toList());
    }

    /** 导出值班表 xlsx，返回文件路径（控制器负责读 bytes 下载） */
    public String exportExcel(String type, Integer weekNo, Integer weekday, Long activityId) {
        return exportExcel(type, weekNo, weekday, activityId, null, null, null);
    }

    public String exportExcel(String type, Integer weekNo, Integer weekday, Long activityId,
                              LocalDate dateFrom, LocalDate dateTo, String keyword) {
        String query = keyword == null ? "" : keyword.trim();
        boolean hasDateRange = dateFrom != null || dateTo != null;
        List<Schedule> schedules = listSchedules(type, weekNo, weekday, activityId).stream()
                .filter(s -> !Schedule.TYPE_ACTIVITY.equals(s.getScheduleType()))
                .filter(s -> canReviewDrafts() || !Schedule.STATUS_DRAFT.equals(s.getStatus()))
                .filter(s -> !hasDateRange || (s.getScheduleDate() == null ? weekNo != null
                        : (dateFrom == null || !s.getScheduleDate().isBefore(dateFrom))
                        && (dateTo == null || !s.getScheduleDate().isAfter(dateTo))))
                .filter(s -> query.isEmpty() || ((s.getLocation() == null ? "" : s.getLocation()) + " " +
                        s.getPersons().stream().map(SchedulePerson::getPersonName).collect(Collectors.joining(" ")))
                        .contains(query))
                .toList();

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("值班表");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            String[] headers = {"周次", "星期", "节次(时间段)", "地点", "值班人员", "班次日期", "开始时间", "结束时间", "状态"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
            }

            int r = 1;
            for (Schedule s : schedules) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(s.getWeekNo() == null ? "" : s.getWeekNo().toString());
                row.createCell(1).setCellValue(weekdayName(s.getWeekday()));
                row.createCell(2).setCellValue(s.getSessionName() == null ? "" : s.getSessionName());
                row.createCell(3).setCellValue(s.getLocation() == null ? "" : s.getLocation());
                String names = s.getPersons().stream()
                        .map(SchedulePerson::getPersonName)
                        .filter(n -> n != null && !n.isBlank())
                        .collect(Collectors.joining("、"));
                row.createCell(4).setCellValue(names);
                row.createCell(5).setCellValue(s.getScheduleDate() == null ? "" : s.getScheduleDate().toString());
                row.createCell(6).setCellValue(s.getStartTime() == null ? "" : s.getStartTime().toString());
                row.createCell(7).setCellValue(s.getEndTime() == null ? "" : s.getEndTime().toString());
                row.createCell(8).setCellValue(s.getStatus() == null ? "" : switch (s.getStatus()) {
                    case Schedule.STATUS_DRAFT -> "草稿";
                    case Schedule.STATUS_PUBLISHED -> "已发布";
                    case Schedule.STATUS_ENDED -> "已结束";
                    case Schedule.STATUS_CANCELLED -> "已取消";
                    default -> s.getStatus();
                });
            }
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            Path dir = Path.of(uploadDir == null ? "uploads" : uploadDir);
            Files.createDirectories(dir);
            Path file = Files.createTempFile(dir, "schedule-", ".xlsx");
            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                wb.write(out);
            }
            return file.toAbsolutePath().normalize().toString();
        } catch (IOException e) {
            throw new BizException(2405, "导出 Excel 失败: " + e.getMessage());
        }
    }

    private void applySchedule(Schedule s, ScheduleRequest req) {
        s.setScheduleType(req.getScheduleType());
        s.setActivityId(req.getActivityId());
        s.setWeekNo(req.getWeekNo());
        s.setWeekday(req.getScheduleDate() == null ? req.getWeekday() : req.getScheduleDate().getDayOfWeek().getValue());
        s.setSessionName(req.getSessionName());
        s.setLocation(req.getLocation());
        s.setScheduleDate(req.getScheduleDate());
        s.setStartTime(req.getStartTime());
        s.setEndTime(req.getEndTime());
        s.setNotes(req.getNotes());
        s.setUpdatedAt(LocalDateTime.now());
    }

    private void savePersons(Long scheduleId, List<ScheduleRequest.SchedulePersonItem> persons) {
        if (persons == null) return;
        for (ScheduleRequest.SchedulePersonItem p : persons) {
            SchedulePerson sp = new SchedulePerson();
            sp.setScheduleId(scheduleId);
            sp.setUserId(p.getUserId());
            sp.setPersonName(p.getPersonName());
            sp.setIsPrimary(p.getIsPrimary() == null ? 1 : p.getIsPrimary());
            sp.setCreatedAt(LocalDateTime.now());
            schedulePersonRepository.save(sp);
        }
    }

    private static final Pattern LEGACY_TIME_RANGE = Pattern.compile(
            "(?:^|/)\\s*(\\d{1,2}):(\\d{2})\\s*[-–—~至]\\s*(\\d{1,2}):(\\d{2})\\s*$");

    private void validateRegularRequest(ScheduleRequest req) {
        if (Schedule.TYPE_ACTIVITY.equals(req.getScheduleType()))
            throw new BizException(400, "请在活动排班中维护活动班次");
        if (req.getStartTime() == null && req.getEndTime() == null && req.getSessionName() != null) {
            var time = LEGACY_TIME_RANGE.matcher(req.getSessionName());
            if (time.find()) {
                try {
                    req.setStartTime(LocalTime.of(Integer.parseInt(time.group(1)), Integer.parseInt(time.group(2))));
                    req.setEndTime(LocalTime.of(Integer.parseInt(time.group(3)), Integer.parseInt(time.group(4))));
                } catch (java.time.DateTimeException e) {
                    throw new BizException(400, "班次时间格式无效");
                }
            }
        }
        if ((req.getStartTime() == null) != (req.getEndTime() == null)
                || req.getStartTime() != null && !req.getEndTime().isAfter(req.getStartTime()))
            throw new BizException(400, "结束时间必须晚于开始时间");
    }

    private void requireRegular(Schedule schedule) {
        if (Schedule.TYPE_ACTIVITY.equals(schedule.getScheduleType())) throw new BizException(404, "例行班次不存在");
    }

    private void requirePublishedMutation(Schedule s) {
        if (!Set.of(Schedule.STATUS_PUBLISHED, Schedule.STATUS_DRAFT).contains(s.getStatus() == null ? "" : s.getStatus()))
            throw new BizException(409, "已结束或已取消班次不可编辑");
        if ((s.getStatus() == null || Schedule.STATUS_PUBLISHED.equals(s.getStatus()))
                && !com.pams.module.agent.core.ToolContext.current(null,null).hasPermission("schedule:publish"))
            throw new BizException(403,"修改已生效班次需要排班发布权限");
    }
    private Schedule getSchedule(Long id) {
        return scheduleRepository.findById(id).orElseThrow(() -> new BizException(2401, "排班不存在"));
    }

    private static String weekdayName(Integer weekday) {
        if (weekday == null) return "";
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return weekday >= 1 && weekday <= 7 ? names[weekday - 1] : "";
    }

    // ==================== 考勤 ====================

    /** Public self-service never trusts the submitted person, result status or a matching name. */
    @Transactional
    public Attendance checkInSelf(AttendanceRequest req) {
        var ctx = com.pams.module.agent.core.ToolContext.current(null, null);
        if (ctx.getUserId() == null || !ctx.hasPermission("schedule:check"))
            throw new BizException(403, "没有本人打卡权限");
        Schedule schedule = scheduleRepository.lockById(req.getScheduleId()).orElseThrow(() -> new BizException(404,"班次不存在或不可见"));
        if (!Schedule.STATUS_PUBLISHED.equals(schedule.getStatus()))
            throw new BizException(409, "仅已发布班次可打卡");
        var now = AttendanceWindow.now();
        var person = schedulePersonRepository.findByScheduleId(schedule.getId()).stream()
                .filter(p -> ctx.getUserId().equals(p.getUserId())).findFirst()
                .orElseThrow(() -> new BizException(403, "本班次未关联你的账号，请联系排班人员核对"));
        var existing = attendanceRepository.findByScheduleId(schedule.getId()).stream()
                .filter(a -> ctx.getUserId().equals(a.getPersonId())).findFirst();
        Attendance attendance = existing.orElseGet(Attendance::new);
        if (existing.isPresent() && Attendance.isSigned(attendance.getStatus())) return attendance;
        if (existing.isPresent() && (Attendance.SOURCE_MANUAL.equals(attendance.getStatusSource())
                || !Attendance.STATUS_PENDING.equals(Attendance.normalizedStatus(attendance.getStatus()))))
            throw new BizException(409, "本班次已有考勤结论，请联系管理员核对");
        if (!AttendanceWindow.isOpen(schedule, now)) throw new BizException(409, "不在本班次打卡时间内");
        attendance.setScheduleId(schedule.getId());
        attendance.setPersonId(ctx.getUserId());
        attendance.setPersonName(person.getPersonName());
        attendance.setStatus(AttendanceWindow.status(schedule, now));
        attendance.setStatusSource(Attendance.SOURCE_AUTO);
        attendance.setCheckedInAt(now);
        attendance.setRecordTime(now);
        if (attendance.getCreatedAt() == null) attendance.setCreatedAt(now);
        Attendance saved = attendanceRepository.save(attendance);
        if (Schedule.TYPE_ACTIVITY.equals(schedule.getScheduleType()) && schedule.getActivityId() != null) {
            signinService.recordSelfCheckin(schedule, saved);
        }
        return saved;
    }

    @Transactional
    public Attendance createAttendance(AttendanceRequest req) {
        Attendance a = new Attendance();
        a.setScheduleId(req.getScheduleId());
        a.setPersonId(req.getPersonId());
        a.setPersonName(req.getPersonName());
        a.setStatus(req.getStatus());
        a.setRemark(req.getRemark());
        a.setRecordTime(LocalDateTime.now());
        a.setCreatedAt(LocalDateTime.now());
        return attendanceRepository.save(a);
    }

    /** 按 scheduleId/weekNo/personName 过滤考勤列表 */
    public List<Attendance> listAttendances(Long scheduleId, Integer weekNo, String personName) {
        List<Attendance> all = attendanceRepository.findAll();
        // 仅当按周次过滤时才需要排班数据；批量加载替代逐条 findById 的 N+1
        Map<Long, Schedule> scheduleById = weekNo != null ? loadScheduleByIds(all) : Map.of();
        return all.stream()
                .filter(a -> scheduleId == null || scheduleId.equals(a.getScheduleId()))
                .filter(a -> weekNo == null || matchesWeekNo(a, weekNo, scheduleById))
                .filter(a -> personName == null || personName.isBlank() || a.getPersonName().contains(personName))
                .collect(Collectors.toList());
    }

    /**
     * 汇总：遍历考勤按人聚合，返回 人员名/应到/实到/请假/缺勤/次数。
     * weekNo / type 均为可选过滤（null 或空串视为不限制），两者都传时同时满足。
     * weekNo 按考勤所属排班的周次匹配，type 按考勤所属排班的排班类型（scheduleType，如 SMOKING_CURB）匹配；
     * 未关联排班的考勤不匹配任何 weekNo/type。
     */
    public List<Map<String, Object>> summary(Integer weekNo, String type) {
        List<Attendance> all = attendanceRepository.findAll();
        // 仅当按 weekNo/type 过滤时才需要排班数据；批量加载替代逐条 findById 的 N+1
        boolean filterBySchedule = weekNo != null || (type != null && !type.isBlank());
        Map<Long, Schedule> scheduleById = filterBySchedule ? loadScheduleByIds(all) : Map.of();
        Map<String, Map<String, Object>> acc = new LinkedHashMap<>();
        for (Attendance a : all) {
            if (weekNo != null && !matchesWeekNo(a, weekNo, scheduleById)) continue;
            if (type != null && !type.isBlank() && !matchesScheduleType(a, type, scheduleById)) continue;
            String status = Attendance.normalizedStatus(a.getStatus());
            String personKey = a.getPersonId() != null ? "id:" + a.getPersonId()
                    : a.getStudentNo() != null && !a.getStudentNo().isBlank() ? "student:" + a.getStudentNo()
                    : "name:" + a.getPersonName();
            Map<String, Object> row = acc.computeIfAbsent(personKey, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("personName", a.getPersonName());
                m.put("personId", a.getPersonId());
                m.put("shouldAttend", 0);
                m.put("present", 0);
                m.put("leave", 0);
                m.put("absent", 0);
                m.put("count", 0);
                m.put("pending", 0);
                m.put("canceled", 0);
                return m;
            });
            row.put("count", (Integer) row.get("count") + 1);
            if (!Attendance.STATUS_CANCELED.equals(status)) {
                row.put("shouldAttend", (Integer) row.get("shouldAttend") + 1);
            }
            String bucket = Attendance.isSigned(status) ? "present" : switch (status) {
                case Attendance.STATUS_LEAVE -> "leave";
                case Attendance.STATUS_ABSENT -> "absent";
                case Attendance.STATUS_CANCELED -> "canceled";
                default -> "pending";
            };
            row.put(bucket, (Integer) row.get(bucket) + 1);
        }
        return new ArrayList<>(acc.values());
    }

    @Transactional
    public void deleteAttendance(Long id) {
        Attendance row = attendanceRepository.findById(id)
                .orElseThrow(() -> new BizException(2402, "考勤记录不存在"));
        if (row.getScheduleId() != null || Attendance.isSigned(row.getStatus())
                || Attendance.SOURCE_MANUAL.equals(row.getStatusSource())) {
            throw new BizException(409, "需要保留此考勤记录，请使用人工修正");
        }
        attendanceRepository.delete(row);
    }

    /** 记录所属排班周次是否匹配。未关联排班（scheduleId 为空或排班不存在）时按 0 处理，不匹配任何 weekNo */
    private boolean matchesWeekNo(Attendance a, Integer weekNo, Map<Long, Schedule> byId) {
        return scheduleOf(a, byId).map(s -> weekNo.equals(s.getWeekNo())).orElse(false);
    }

    /** 记录所属排班类型（scheduleType）是否匹配。未关联排班时不匹配任何 type */
    private boolean matchesScheduleType(Attendance a, String type, Map<Long, Schedule> byId) {
        return scheduleOf(a, byId).map(s -> type.equals(s.getScheduleType())).orElse(false);
    }

    /** 考勤所属排班；无 scheduleId、repo 未注入（测试构造器）或未命中批量索引时返回 Optional.empty */
    private Optional<Schedule> scheduleOf(Attendance a, Map<Long, Schedule> byId) {
        Long sid = a.getScheduleId();
        if (sid == null || byId.isEmpty()) return Optional.empty();
        return Optional.ofNullable(byId.get(sid));
    }

    /** C3: 批量加载一批考勤所属排班并按 id 建索引，替代逐条 findById 的 N+1 */
    private Map<Long, Schedule> loadScheduleByIds(List<Attendance> attendances) {
        if (scheduleRepository == null) return Map.of();
        Set<Long> ids = new LinkedHashSet<>();
        for (Attendance a : attendances) {
            if (a.getScheduleId() != null) ids.add(a.getScheduleId());
        }
        if (ids.isEmpty()) return Map.of();
        return scheduleRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Schedule::getId, s -> s));
    }

}
