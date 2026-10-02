package com.pams.module.schedule.service;

import com.pams.module.schedule.dto.NoClassScheduleCellVO;
import com.pams.module.schedule.dto.NoClassScheduleGeneratedVO;
import com.pams.module.schedule.dto.NoClassScheduleRowVO;
import com.pams.module.schedule.entity.ScheduleConfig;
import com.pams.module.schedule.generator.WeekRangeParser;
import com.pams.module.schedule.repository.ScheduleConfigRepository;
import com.pams.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 基于无课表匹配“目标日期 + 时间段内全程空闲”的候选人员。
 *
 * 无课表（gridJson）结构：按节次(period) × 星期(day1-5) 记录每格空闲人员 {name, freeWeeks}。
 * 步骤：date→星期；start/end→覆盖节次（与 schedule_config 时段相交）；对每个覆盖节次取该星期空闲人员；
 * 取“全程空闲”交集；freeWeeks 按可选的开课周起点换算周次过滤。
 *
 * 周末(day>=6) 或无课表记录 → 返回空候选（由前端提示先上传无课表/选择工作日）。
 */
@Component
public class FreeSlotMatcher {

    private final NoClassScheduleImportService importService;
    private final ScheduleConfigRepository configRepo;
    private final UserRepository userRepo;

    public FreeSlotMatcher(NoClassScheduleImportService importService,
                           ScheduleConfigRepository configRepo,
                           UserRepository userRepo) {
        this.importService = importService;
        this.configRepo = configRepo;
        this.userRepo = userRepo;
    }

    public record Candidate(String name, Long userId) {}

    /** 一个可排节次：时段 + 该节次空闲候选人员（按节次拆分后每个节次单独排班）。 */
    public record PeriodSlot(int period, String label, LocalTime startTime, LocalTime endTime, List<Candidate> candidates) {}

    /**
     * 按节次拆分：活动时间段覆盖的每个节次，返回该节次当天空闲的人员（多部门取并集）。
     * 不再取“全程无课”交集——每个节次各自独立成候选，对应无课表每格列出的没课人员。
     *
     * @param date              活动日期（需为周一~周五）
     * @param start,end         活动时间段
     * @param deptIds           参与部门集合（多部门取无课表并集）
     * @param semester          学期，如 2025-2026-2
     * @param semesterStartDate 开学（第一周周一）日期，可为 null（null 则跳过周次过滤）
     */
    public List<PeriodSlot> matchByPeriod(LocalDate date, LocalTime start, LocalTime end,
                                          List<Long> deptIds, String semester, LocalDate semesterStartDate) {
        if (date == null || start == null || end == null) return List.of();
        if (deptIds == null || deptIds.isEmpty()) return List.of();
        int weekday = date.getDayOfWeek().getValue();
        if (weekday > 5) return List.of(); // 无课表仅覆盖周一~周五

        List<ScheduleConfig> configs = configRepo.findAllByOrderByPeriodAsc();
        // 预加载各部门的无课表（一次读取）
        List<NoClassScheduleGeneratedVO> generatedList = new ArrayList<>();
        for (Long deptId : deptIds) {
            NoClassScheduleGeneratedVO g = importService.getGenerated(deptId, semester);
            if (g != null && g.getRows() != null && !g.getRows().isEmpty()) generatedList.add(g);
        }
        if (generatedList.isEmpty()) return List.of();

        Integer week = semesterStartDate != null ? weekOf(date, semesterStartDate) : null;

        List<PeriodSlot> slots = new ArrayList<>();
        for (ScheduleConfig c : configs) {
            if (c.getStartTime() == null || c.getEndTime() == null) continue;
            // 只保留与活动时间段相交的节次
            boolean overlaps = c.getEndTime().isAfter(start) && c.getStartTime().isBefore(end);
            if (!overlaps) continue;
            Set<String> periodFree = new LinkedHashSet<>();
            for (NoClassScheduleGeneratedVO g : generatedList) {
                List<NoClassScheduleCellVO> cells = cellsOf(g, c.getPeriod(), weekday);
                if (cells == null) continue;
                for (NoClassScheduleCellVO cell : cells) {
                    if (cell.getName() == null || cell.getName().isBlank()) continue;
                    if (week != null && !isFreeOnWeek(cell.getFreeWeeks(), week)) continue;
                    periodFree.add(cell.getName());
                }
            }
            if (periodFree.isEmpty()) continue;
            List<Candidate> cands = new ArrayList<>();
            for (String name : periodFree) {
                cands.add(new Candidate(name, resolveUniqueUserId(name)));
            }
            cands.sort((a, b) -> a.name().compareTo(b.name()));
            slots.add(new PeriodSlot(c.getPeriod(), c.getLabel(), c.getStartTime(), c.getEndTime(), cands));
        }
        return slots;
    }

    /** 某部门无课表指定节次/星期的空闲人员；无则返回 null。 */
    private List<NoClassScheduleCellVO> cellsOf(NoClassScheduleGeneratedVO g, int period, int weekday) {
        if (g == null || g.getRows() == null) return null;
        for (NoClassScheduleRowVO r : g.getRows()) {
            if (r.getPeriod() == period) {
                if (r.getDays() == null) return null;
                return r.getDays().get(String.valueOf(weekday));
            }
        }
        return null;
    }

    /** 按姓名唯一命中回填 userId；同名多人或无匹配返回 null（宁缺勿错，参照 V20 迁移策略）。 */
    private Long resolveUniqueUserId(String name) {
        List<com.pams.entity.User> matched = userRepo.findAll().stream()
                .filter(u -> name.equals(u.getRealName()) && (u.getDeleted() == null || u.getDeleted() == 0))
                .toList();
        return matched.size() == 1 ? matched.get(0).getId() : null;
    }

    private boolean isFreeOnWeek(String freeWeeks, int week) {
        if (freeWeeks == null || freeWeeks.isBlank()) return true; // 未标注周次视为该格有空
        return WeekRangeParser.parse(freeWeeks, 30).contains(week);
    }

    private int weekOf(LocalDate date, LocalDate semesterStart) {
        long days = ChronoUnit.DAYS.between(semesterStart, date);
        if (days < 0) return 0;
        return (int) (days / 7) + 1;
    }
}