package com.pams.module.schedule.service;

import com.pams.common.BizException;
import com.pams.module.schedule.entity.CourseSchedule;
import com.pams.module.schedule.entity.ScheduleConfig;
import com.pams.module.schedule.repository.CourseScheduleRepository;
import com.pams.module.schedule.repository.ScheduleConfigRepository;
import com.pams.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 无课表制作（PRD F08）
 * 方案A：纯后端统计——遍历所选人员课程表，计算每个时间格空闲人数，前端热力图渲染
 */
@Service
public class CourseScheduleService {

    private final CourseScheduleRepository scheduleRepo;
    private final ScheduleConfigRepository configRepo;
    private final UserRepository userRepo;

    public CourseScheduleService(CourseScheduleRepository scheduleRepo,
                                 ScheduleConfigRepository configRepo,
                                 UserRepository userRepo) {
        this.scheduleRepo = scheduleRepo;
        this.configRepo = configRepo;
        this.userRepo = userRepo;
    }

    // ===== 时间格配置 =====

    public List<ScheduleConfig> getConfigs() {
        return configRepo.findAllByOrderByPeriodAsc();
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public void saveConfigs(List<Map<String, Object>> configs) {
        if (configs == null) return;
        configRepo.deleteAll();
        for (Map<String, Object> c : configs) {
            ScheduleConfig sc = new ScheduleConfig();
            sc.setPeriod(Integer.valueOf(String.valueOf(c.get("period"))));
            sc.setLabel((String) c.get("label"));
            if (c.get("startTime") != null) sc.setStartTime(java.time.LocalTime.parse((String) c.get("startTime")));
            if (c.get("endTime") != null) sc.setEndTime(java.time.LocalTime.parse((String) c.get("endTime")));
            configRepo.save(sc);
        }
    }

    // ===== 个人课程表 =====

    /** 获取某用户某学期的课程表（按天/节次展开为 boolean 矩阵） */
    public Map<String, Object> getMySchedule(Long userId, String semester) {
        List<CourseSchedule> list = scheduleRepo.findByUserIdAndSemester(userId, semester);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("semester", semester == null ? "" : semester);
        // 存储格式：{day: {period: courseName}}
        Map<Integer, Map<Integer, String>> matrix = new LinkedHashMap<>();
        for (CourseSchedule cs : list) {
            matrix.computeIfAbsent(cs.getDayOfWeek(), k -> new LinkedHashMap<>())
                    .put(cs.getPeriod(), cs.getCourseName() == null ? "有课" : cs.getCourseName());
        }
        result.put("matrix", matrix);
        result.put("count", list.size());
        return result;
    }

    /** 保存个人课程表：cells 为 [{dayOfWeek, period, courseName}]（覆盖式保存） */
    @Transactional
    @SuppressWarnings("unchecked")
    public void saveMySchedule(Long userId, String semester, List<Map<String, Object>> cells) {
        if (semester == null || semester.isBlank()) throw new BizException(2701, "请选择学期");
        scheduleRepo.deleteByUserIdAndSemester(userId, semester);
        if (cells == null) return;
        for (Map<String, Object> cell : cells) {
            CourseSchedule cs = new CourseSchedule();
            cs.setUserId(userId);
            cs.setSemester(semester);
            cs.setDayOfWeek(Integer.valueOf(String.valueOf(cell.get("dayOfWeek"))));
            cs.setPeriod(Integer.valueOf(String.valueOf(cell.get("period"))));
            Object name = cell.get("courseName");
            cs.setCourseName(name == null ? null : String.valueOf(name));
            cs.setCreatedAt(LocalDateTime.now());
            cs.setUpdatedAt(LocalDateTime.now());
            scheduleRepo.save(cs);
        }
    }

    // ===== 空闲统计（AI 无课表生成）=====

    /**
     * 计算所选人员的共同空闲热力图。
     * 返回：
     *  - periods: 时间格配置（含 label）
     *  - users: 参与人员 [{id, realName, deptName}]
     *  - heatmap: {day(1-7): {period: 空闲人数}}
     *  - optimal: 最优时间段列表（按空闲人数降序，标注是否全部到齐）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> analyzeFreeTime(String semester, List<Long> userIds) {
        List<ScheduleConfig> configs = configRepo.findAllByOrderByPeriodAsc();
        if (configs.isEmpty()) {
            // 兜底默认 5 节
            configs = List.of(
                    cfg(1, "第1-2节"), cfg(2, "第3-4节"), cfg(3, "第5-6节"),
                    cfg(4, "第7-8节"), cfg(5, "第9-10节"));
        }
        List<Map<String, Object>> users = new ArrayList<>();
        List<Long> ids = (userIds == null || userIds.isEmpty())
                ? userRepo.findAll().stream().map(u -> u.getId()).toList()
                : userIds;
        for (Long uid : ids) {
            userRepo.findById(uid).ifPresent(u -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", u.getId());
                m.put("realName", u.getRealName());
                m.put("deptName", u.getDept() == null ? "" : u.getDept().getName());
                users.add(m);
            });
        }
        int total = Math.max(users.size(), 1);

        Map<String, Object> heatmap = new LinkedHashMap<>();
        for (int day = 1; day <= 7; day++) {
            int curDay = day; // 显式赋值，保证 lambda 可捕获
            Map<Integer, Integer> dayMap = new LinkedHashMap<>();
            for (ScheduleConfig c : configs) {
                int curPeriod = c.getPeriod(); // 同理，enhanced for 变量在 lambda 中需显式捕获
                // 空闲人数 = 总人数 - 该天该节有课人数
                int free = 0;
                for (Long uid : ids) {
                    List<CourseSchedule> list = semester == null || semester.isBlank()
                            ? scheduleRepo.findByUserId(uid)
                            : scheduleRepo.findByUserIdAndSemester(uid, semester);
                    boolean isBusy = list.stream().anyMatch(cs ->
                            cs.getDayOfWeek() == curDay && cs.getPeriod().equals(curPeriod));
                    if (!isBusy) free++;
                }
                dayMap.put(c.getPeriod(), free);
            }
            heatmap.put(String.valueOf(day), dayMap);
        }

        // 最优推荐：按空闲人数降序
        List<Map<String, Object>> optimal = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            for (ScheduleConfig c : configs) {
                int free = ((Map<Integer, Integer>) heatmap.get(String.valueOf(day))).get(c.getPeriod());
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("dayOfWeek", day);
                o.put("period", c.getPeriod());
                o.put("label", c.getLabel());
                o.put("freeCount", free);
                o.put("allFree", free == total);
                optimal.add(o);
            }
        }
        optimal.sort((a, b) -> {
            int cmp = Integer.compare((int) b.get("freeCount"), (int) a.get("freeCount"));
            return cmp != 0 ? cmp : Integer.compare((int) a.get("dayOfWeek"), (int) b.get("dayOfWeek"));
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("semester", semester);
        result.put("periods", configs);
        result.put("users", users);
        result.put("heatmap", heatmap);
        result.put("optimal", optimal);
        return result;
    }

    private ScheduleConfig cfg(int period, String label) {
        ScheduleConfig c = new ScheduleConfig();
        c.setPeriod(period);
        c.setLabel(label);
        return c;
    }
}
