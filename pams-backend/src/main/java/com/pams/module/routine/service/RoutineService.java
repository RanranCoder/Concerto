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
import org.springframework.transaction.annotation.Transactional;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 例行事务：排班 / 考勤。
 * 生产用全参构造器注入 3 个 repo；1 参构造器仅用于测试（其余 repo 为 null）。
 */
@Service
public class RoutineService {
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
        Schedule s = new Schedule();
        applySchedule(s, req);
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        Schedule saved = scheduleRepository.save(s);
        savePersons(saved.getId(), req.getPersons());
        return saved.getId();
    }

    @Transactional
    public void updateSchedule(Long id, ScheduleRequest req) {
        Schedule s = getSchedule(id);
        applySchedule(s, req);
        scheduleRepository.save(s);
        schedulePersonRepository.deleteByScheduleId(id);
        savePersons(id, req.getPersons());
    }

    @Transactional
    public void deleteSchedule(Long id) {
        getSchedule(id);
        schedulePersonRepository.deleteByScheduleId(id);
        // attendance.schedule_id 有 FK 约束，先清掉该排班的考勤记录再删排班
        attendanceRepository.deleteByScheduleId(id);
        scheduleRepository.deleteById(id);
    }

    /** 按 type/weekNo/weekday/activityId 过滤排班列表，并组装每个排班的人员数组 */
    public List<Schedule> listSchedules(String type, Integer weekNo, Integer weekday, Long activityId) {
        List<Schedule> all = scheduleRepository.findAll();
        return all.stream()
                .filter(s -> type == null || type.isBlank() || type.equals(s.getScheduleType()))
                .filter(s -> weekNo == null || weekNo.equals(s.getWeekNo()))
                .filter(s -> weekday == null || weekday.equals(s.getWeekday()))
                .filter(s -> activityId == null || activityId.equals(s.getActivityId()))
                .peek(s -> s.setPersons(schedulePersonRepository.findByScheduleId(s.getId())))
                .collect(Collectors.toList());
    }

    /** 导出值班表 xlsx，返回文件路径（控制器负责读 bytes 下载） */
    public String exportExcel(String type, Integer weekNo, Integer weekday, Long activityId) {
        List<Schedule> schedules = listSchedules(type, weekNo, weekday, activityId);

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("值班表");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            String[] headers = {"周次", "星期", "节次(时间段)", "地点", "值班人员"};
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
            }
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            Path dir = Path.of(uploadDir == null ? "uploads" : uploadDir);
            Files.createDirectories(dir);
            String fileName = "export-" + System.currentTimeMillis() + ".xlsx";
            Path file = dir.resolve(fileName);
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
        s.setWeekday(req.getWeekday());
        s.setSessionName(req.getSessionName());
        s.setLocation(req.getLocation());
        s.setScheduleDate(req.getScheduleDate());
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

    private Schedule getSchedule(Long id) {
        return scheduleRepository.findById(id).orElseThrow(() -> new BizException(2401, "排班不存在"));
    }

    private static String weekdayName(Integer weekday) {
        if (weekday == null) return "";
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return weekday >= 1 && weekday <= 7 ? names[weekday - 1] : "";
    }

    // ==================== 考勤 ====================

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
        return all.stream()
                .filter(a -> scheduleId == null || scheduleId.equals(a.getScheduleId()))
                .filter(a -> weekNo == null || matchesWeekNo(a, weekNo))
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
        Map<String, Map<String, Object>> acc = new LinkedHashMap<>();
        for (Attendance a : attendanceRepository.findAll()) {
            if (weekNo != null && !matchesWeekNo(a, weekNo)) continue;
            if (type != null && !type.isBlank() && !matchesScheduleType(a, type)) continue;
            Map<String, Object> row = acc.computeIfAbsent(a.getPersonName(), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("personName", k);
                m.put("shouldAttend", 0);
                m.put("present", 0);
                m.put("leave", 0);
                m.put("absent", 0);
                m.put("count", 0);
                return m;
            });
            row.put("shouldAttend", (Integer) row.get("shouldAttend") + 1);
            row.put("count", (Integer) row.get("count") + 1);
            switch (statusOf(a)) {
                case "PRESENT" -> row.put("present", (Integer) row.get("present") + 1);
                case "LEAVE" -> row.put("leave", (Integer) row.get("leave") + 1);
                default -> row.put("absent", (Integer) row.get("absent") + 1);
            }
        }
        return new ArrayList<>(acc.values());
    }

    @Transactional
    public void deleteAttendance(Long id) {
        attendanceRepository.findById(id).orElseThrow(() -> new BizException(2402, "考勤记录不存在"));
        attendanceRepository.deleteById(id);
    }

    /** 记录所属排班周次是否匹配。未关联排班（scheduleId 为空或排班不存在）时按 0 处理，不匹配任何 weekNo */
    private boolean matchesWeekNo(Attendance a, Integer weekNo) {
        return scheduleOf(a).map(s -> weekNo.equals(s.getWeekNo())).orElse(false);
    }

    /** 记录所属排班类型（scheduleType）是否匹配。未关联排班时不匹配任何 type */
    private boolean matchesScheduleType(Attendance a, String type) {
        return scheduleOf(a).map(s -> type.equals(s.getScheduleType())).orElse(false);
    }

    /** 考勤所属排班；无 scheduleId 或 repo 未注入（测试构造器）时返回 Optional.empty */
    private Optional<Schedule> scheduleOf(Attendance a) {
        if (a.getScheduleId() == null || scheduleRepository == null) return Optional.empty();
        return scheduleRepository.findById(a.getScheduleId());
    }

    private static String statusOf(Attendance a) {
        return a.getStatus() == null ? "ABSENT" : a.getStatus();
    }
}
