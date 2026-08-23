package com.pams.module.dashboard;

import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.entity.Task;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.activity.repository.TaskRepository;
import com.pams.module.archive.entity.Announcement;
import com.pams.module.archive.entity.Material;
import com.pams.module.archive.repository.AnnouncementRepository;
import com.pams.module.archive.repository.MaterialRepository;
import com.pams.module.content.entity.Article;
import com.pams.module.content.repository.ArticleRepository;
import com.pams.module.routine.repository.ScheduleRepository;
import com.pams.security.LoginUser;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仪表盘聚合：活动统计 / 本周排班 / 最新推文 / 最新材料 / 最新公告 / 我的待办。
 * 返回轻量 Map（recentArticles 等不含大字段如 article.content），值可能为 null/空，
 * 全部用可空的组装方式，保证首页拿到的是安全结构。
 *
 * 全部子查询已下沉到数据库端（分组计数 / BETWEEN 计数 / Top-N 派生 / 等值过滤），
 * 不再 findAll 全表加载到内存。返回 VO 结构保持不变（前端契约不变）。
 */
@Service
public class DashboardService {
    private final ActivityRepository activityRepository;
    private final ScheduleRepository scheduleRepository;
    private final ArticleRepository articleRepository;
    private final MaterialRepository materialRepository;
    private final AnnouncementRepository announcementRepository;
    private final TaskRepository taskRepository;

    public DashboardService(ActivityRepository activityRepository,
                            ScheduleRepository scheduleRepository,
                            ArticleRepository articleRepository,
                            MaterialRepository materialRepository,
                            AnnouncementRepository announcementRepository,
                            TaskRepository taskRepository) {
        this.activityRepository = activityRepository;
        this.scheduleRepository = scheduleRepository;
        this.articleRepository = articleRepository;
        this.materialRepository = materialRepository;
        this.announcementRepository = announcementRepository;
        this.taskRepository = taskRepository;
    }

    public Map<String, Object> dashboard(LoginUser current) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("activityStats", activityStats());
        map.put("weekSchedules", weekScheduleCount());
        map.put("recentArticles", recentArticles());
        map.put("recentMaterials", recentMaterials());
        map.put("recentAnnouncements", recentAnnouncements());
        map.put("myTasks", myTasks(current));
        return map;
    }

    /** 按 6 态分组计数（status 为 null 归入 ASSIGNED，未出现的状态补 0） */
    public Map<String, Object> activityStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        for (ActivityStatus s : ActivityStatus.values()) stats.put(s.name(), 0);
        // 数据库端 GROUP BY status 聚合，替代 findAll 遍历
        for (Object[] row : activityRepository.countGroupByStatus()) {
            ActivityStatus s = (ActivityStatus) row[0];
            String key = s == null ? ActivityStatus.ASSIGNED.name() : s.name();
            stats.put(key, ((Number) stats.get(key)).intValue() + ((Number) row[1]).intValue());
        }
        return stats;
    }

    /** 本周（周一起）排班条数：以 schedule_date 落入 [周一, 周日] 为准 */
    public int weekScheduleCount() {
        LocalDate today = LocalDate.now();
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        LocalDate sunday = today.with(DayOfWeek.SUNDAY);
        // 数据库端 BETWEEN 计数，替代 findAll 遍历后内存过滤
        return (int) scheduleRepository.countByScheduleDateBetween(monday, sunday);
    }

    /** 最近 5 篇已发布推文（publishTime 倒序，空值垫底） */
    public List<Map<String, Object>> recentArticles() {
        // 数据库端过滤+排序+Top5，替代 findAll 后内存过滤/排序/截断
        return articleRepository.findTop5ByStatusOrderByPublishTimeDesc(Article.ArticleStatus.PUBLISHED).stream()
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", a.getId());
                    m.put("title", a.getTitle());
                    m.put("summary", a.getSummary());
                    m.put("articleType", a.getArticleType() == null ? null : a.getArticleType().name());
                    m.put("publishTime", a.getPublishTime());
                    return m;
                })
                .toList();
    }

    /** 最近 5 条材料（createdAt 倒序） */
    public List<Map<String, Object>> recentMaterials() {
        // 数据库端排序+Top5，替代 findAll 后内存排序/截断
        return materialRepository.findTop5ByOrderByCreatedAtDesc().stream()
                .map(m -> {
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("id", m.getId());
                    mm.put("name", m.getName());
                    mm.put("bizType", m.getBizType());
                    mm.put("createdAt", m.getCreatedAt());
                    return mm;
                })
                .toList();
    }

    /** 最近 5 条公告（publishTime 倒序，空值垫底再按 createdAt） */
    public List<Map<String, Object>> recentAnnouncements() {
        // 数据库端排序+Top5，替代 findAll 后内存排序/截断
        return announcementRepository.findTop5ByOrderByPublishTimeDescCreatedAtDesc().stream()
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", a.getId());
                    m.put("title", a.getTitle());
                    m.put("publishTime", a.getPublishTime());
                    m.put("createdAt", a.getCreatedAt());
                    return m;
                })
                .toList();
    }

    /** 当前用户负责的任务（task.assignee 与当前用户 realName 匹配，忽略大小写与首尾空格），按截止日期升序 */
    public List<Task> myTasks(LoginUser current) {
        String me = current == null ? null : current.getRealName();
        if (me == null || me.isBlank()) return List.of();
        // 数据库端等值过滤（TRIM+LOWER），替代 findAll 后内存匹配；截止日期排序保留在内存（结果集小）
        return taskRepository.findByAssigneeMatching(me).stream()
                .sorted(Comparator.comparing(Task::getEndDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }
}
