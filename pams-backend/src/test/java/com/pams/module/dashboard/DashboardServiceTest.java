package com.pams.module.dashboard;

import com.pams.module.activity.entity.Activity;
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
import com.pams.module.routine.entity.Schedule;
import com.pams.module.routine.repository.ScheduleRepository;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardServiceTest {

    ActivityRepository activityRepository;
    ScheduleRepository scheduleRepository;
    ArticleRepository articleRepository;
    MaterialRepository materialRepository;
    AnnouncementRepository announcementRepository;
    TaskRepository taskRepository;
    DashboardService service;

    @BeforeEach
    void setup() {
        activityRepository = mock(ActivityRepository.class);
        scheduleRepository = mock(ScheduleRepository.class);
        articleRepository = mock(ArticleRepository.class);
        materialRepository = mock(MaterialRepository.class);
        announcementRepository = mock(AnnouncementRepository.class);
        taskRepository = mock(TaskRepository.class);
        service = new DashboardService(activityRepository, scheduleRepository,
                articleRepository, materialRepository, announcementRepository, taskRepository);
    }

    @Test
    void activityStats_groupsByStatus_withZeroFill() {
        Activity a1 = new Activity(); a1.setStatus(ActivityStatus.EXECUTING);
        Activity a2 = new Activity(); a2.setStatus(ActivityStatus.EXECUTING);
        Activity a3 = new Activity(); a3.setStatus(ActivityStatus.ARCHIVED);
        when(activityRepository.findAll()).thenReturn(List.of(a1, a2, a3));

        Map<String, Object> stats = service.activityStats();

        assertThat(stats).hasSize(6);
        assertThat(stats.get("EXECUTING")).isEqualTo(2);
        assertThat(stats.get("ARCHIVED")).isEqualTo(1);
        assertThat(stats.get("ASSIGNED")).isEqualTo(0);
        assertThat(stats.get("PLANNING")).isEqualTo(0);
        assertThat(stats.get("PLAN_REVIEW")).isEqualTo(0);
        assertThat(stats.get("FINISHED")).isEqualTo(0);
    }

    @Test
    void weekScheduleCount_countsCurrentWeekByScheduleDate() {
        // 以与实现一致的 ISO 周边界构造数据：今天为周日时 today.plusDays(2) 会落入下周，故用周内确定日期
        LocalDate today = LocalDate.now();
        LocalDate monday = today.with(java.time.DayOfWeek.MONDAY);
        Schedule inWeek = new Schedule(); inWeek.setScheduleDate(monday);
        Schedule inWeek2 = new Schedule(); inWeek2.setScheduleDate(today);
        Schedule otherWeek = new Schedule(); otherWeek.setScheduleDate(monday.minusWeeks(1));
        Schedule noDate = new Schedule(); noDate.setScheduleDate(null);
        when(scheduleRepository.findAll()).thenReturn(List.of(inWeek, inWeek2, otherWeek, noDate));

        assertThat(service.weekScheduleCount()).isEqualTo(2);
    }

    @Test
    void recentArticles_filtersPublishedAndLimits5() {
        Article pub1 = article(1L, Article.ArticleStatus.PUBLISHED, LocalDateTime.of(2026, 8, 1, 10, 0));
        Article draft = article(2L, Article.ArticleStatus.DRAFT, LocalDateTime.of(2026, 8, 2, 10, 0));
        Article pending = article(3L, Article.ArticleStatus.PENDING, LocalDateTime.of(2026, 8, 2, 11, 0));
        Article pub2 = article(4L, Article.ArticleStatus.PUBLISHED, LocalDateTime.of(2026, 8, 2, 12, 0));
        when(articleRepository.findAll()).thenReturn(List.of(pub1, draft, pending, pub2));

        List<Map<String, Object>> list = service.recentArticles();

        assertThat(list).hasSize(2);
        assertThat((Long) list.get(0).get("id")).isEqualTo(4L);
        assertThat((Long) list.get(1).get("id")).isEqualTo(1L);
    }

    @Test
    void recentMaterials_limits5_andNewestFirst() {
        Material m1 = material(1L, LocalDateTime.of(2026, 8, 1, 9, 0));
        Material m2 = material(2L, LocalDateTime.of(2026, 8, 2, 9, 0));
        Material m3 = material(3L, LocalDateTime.of(2026, 8, 2, 8, 0));
        when(materialRepository.findAll()).thenReturn(List.of(m1, m2, m3));

        List<Map<String, Object>> list = service.recentMaterials();

        assertThat(list).hasSize(3);
        assertThat((Long) list.get(0).get("id")).isEqualTo(2L);
        assertThat((Long) list.get(2).get("id")).isEqualTo(1L);
    }

    @Test
    void myTasks_matchesCurrentUserRealNameIgnoreCase() {
        Task mine = task(1L, "张三");
        Task another = task(2L, "李四");
        Task spaced = task(3L, "  张三  ");
        when(taskRepository.findAll()).thenReturn(List.of(mine, another, spaced));

        LoginUser current = new LoginUser(9L, "zhangsan", "张三", "STAFF", 1, 1L, "文秘部");
        List<Task> tasks = service.myTasks(current);

        assertThat(tasks).extracting(Task::getId).containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void myTasks_nullUser_returnsEmpty() {
        when(taskRepository.findAll()).thenReturn(List.of(task(1L, "张三")));
        assertThat(service.myTasks(null)).isEmpty();
    }

    @Test
    void dashboard_assemblesAllSections() {
        Activity a = new Activity(); a.setStatus(ActivityStatus.EXECUTING);
        when(activityRepository.findAll()).thenReturn(List.of(a));
        when(scheduleRepository.findAll()).thenReturn(List.of());
        when(articleRepository.findAll()).thenReturn(List.of());
        when(materialRepository.findAll()).thenReturn(List.of());
        when(announcementRepository.findAll()).thenReturn(List.of());
        when(taskRepository.findAll()).thenReturn(List.of());

        LoginUser current = new LoginUser(9L, "zhangsan", "张三", "STAFF", 1, 1L, "文秘部");
        Map<String, Object> map = service.dashboard(current);

        assertThat(map).containsKeys("activityStats", "weekSchedules", "recentArticles",
                "recentMaterials", "recentAnnouncements", "myTasks");
        assertThat(map.get("activityStats")).isInstanceOf(Map.class);
        assertThat(map.get("recentArticles")).isInstanceOf(List.class);
    }

    private static Article article(Long id, Article.ArticleStatus status, LocalDateTime publishTime) {
        Article a = new Article();
        a.setId(id);
        a.setTitle("t" + id);
        a.setStatus(status);
        a.setPublishTime(publishTime);
        return a;
    }

    private static Material material(Long id, LocalDateTime createdAt) {
        Material m = new Material();
        m.setId(id);
        m.setName("m" + id);
        m.setBizType("OTHER");
        m.setCreatedAt(createdAt);
        return m;
    }

    private static Task task(Long id, String assignee) {
        Task t = new Task();
        t.setId(id);
        t.setName("task" + id);
        t.setAssignee(assignee);
        return t;
    }
}
