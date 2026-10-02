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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
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
        // 数据库端 GROUP BY 返回 [status, count] 行；null status 归入 ASSIGNED
        when(activityRepository.countGroupByStatus()).thenReturn(List.<Object[]>of(
                new Object[]{ActivityStatus.EXECUTING, 2L},
                new Object[]{ActivityStatus.ARCHIVED, 1L}
        ));

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
    void activityStats_nullStatusMapsToAssigned() {
        when(activityRepository.countGroupByStatus()).thenReturn(List.<Object[]>of(
                new Object[]{null, 3L}
        ));

        Map<String, Object> stats = service.activityStats();

        assertThat(stats.get("ASSIGNED")).isEqualTo(3);
    }

    @Test
    void weekScheduleCount_countsCurrentWeekByScheduleDate() {
        LocalDate today = LocalDate.now();
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        LocalDate sunday = today.with(DayOfWeek.SUNDAY);
        // 数据库端 BETWEEN 计数；验证服务用本周 [monday, sunday] 调用
        when(scheduleRepository.countByScheduleDateBetween(monday, sunday)).thenReturn(2L);

        assertThat(service.weekScheduleCount()).isEqualTo(2);
    }

    @Test
    void recentArticles_mapsRepoResultToVo() {
        // 数据库端已过滤 PUBLISHED 并按 publishTime 倒序返回 Top5
        Article pub2 = article(4L, Article.ArticleStatus.PUBLISHED, LocalDateTime.of(2026, 8, 2, 12, 0));
        Article pub1 = article(1L, Article.ArticleStatus.PUBLISHED, LocalDateTime.of(2026, 8, 1, 10, 0));
        when(articleRepository.findTop5ByStatusOrderByPublishTimeDesc(Article.ArticleStatus.PUBLISHED))
                .thenReturn(List.of(pub2, pub1));

        List<Map<String, Object>> list = service.recentArticles();

        assertThat(list).hasSize(2);
        assertThat((Long) list.get(0).get("id")).isEqualTo(4L);
        assertThat((Long) list.get(1).get("id")).isEqualTo(1L);
        assertThat(list.get(0).get("title")).isEqualTo("t4");
    }

    @Test
    void recentMaterials_mapsRepoResultToVo() {
        Material m2 = material(2L, LocalDateTime.of(2026, 8, 2, 9, 0));
        Material m3 = material(3L, LocalDateTime.of(2026, 8, 2, 8, 0));
        Material m1 = material(1L, LocalDateTime.of(2026, 8, 1, 9, 0));
        when(materialRepository.findTop5ByOrderByCreatedAtDesc()).thenReturn(List.of(m2, m3, m1));

        List<Map<String, Object>> list = service.recentMaterials();

        assertThat(list).hasSize(3);
        assertThat((Long) list.get(0).get("id")).isEqualTo(2L);
        assertThat((Long) list.get(2).get("id")).isEqualTo(1L);
    }

    @Test
    void recentAnnouncements_mapsRepoResultToVo() {
        Announcement a1 = announcement(1L, LocalDateTime.of(2026, 8, 2, 9, 0));
        Announcement a2 = announcement(2L, LocalDateTime.of(2026, 8, 1, 9, 0));
        when(announcementRepository.findTop5ByOrderByPublishTimeDescCreatedAtDesc()).thenReturn(List.of(a1, a2));

        List<Map<String, Object>> list = service.recentAnnouncements();

        assertThat(list).hasSize(2);
        assertThat((Long) list.get(0).get("id")).isEqualTo(1L);
        assertThat((Long) list.get(1).get("id")).isEqualTo(2L);
    }

    @Test
    void myTasks_delegatesToAssigneeMatchQuery() {
        Task mine = task(1L, "张三");
        Task spaced = task(3L, "  张三  ");
        // 数据库端 TRIM+LOWER 等值过滤；服务再按 endDate 升序排序
        when(taskRepository.findByAssigneeMatching("张三")).thenReturn(List.of(mine, spaced));

        LoginUser current = new LoginUser(9L, "zhangsan", "张三", "STAFF", 1, 1L, "文秘部", 1L);
        List<Task> tasks = service.myTasks(current);

        assertThat(tasks).extracting(Task::getId).containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void myTasks_nullUser_returnsEmpty() {
        assertThat(service.myTasks(null)).isEmpty();
    }

    @Test
    void dashboard_assemblesAllSections() {
        when(activityRepository.countGroupByStatus()).thenReturn(List.<Object[]>of(
                new Object[]{ActivityStatus.EXECUTING, 1L}
        ));
        when(scheduleRepository.countByScheduleDateBetween(
                LocalDate.now().with(DayOfWeek.MONDAY), LocalDate.now().with(DayOfWeek.SUNDAY)))
                .thenReturn(0L);
        when(articleRepository.findTop5ByStatusOrderByPublishTimeDesc(Article.ArticleStatus.PUBLISHED))
                .thenReturn(List.of());
        when(materialRepository.findTop5ByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(announcementRepository.findTop5ByOrderByPublishTimeDescCreatedAtDesc()).thenReturn(List.of());
        when(taskRepository.findByAssigneeMatching("张三")).thenReturn(List.of());

        LoginUser current = new LoginUser(9L, "zhangsan", "张三", "STAFF", 1, 1L, "文秘部", 1L);
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

    private static Announcement announcement(Long id, LocalDateTime publishTime) {
        Announcement a = new Announcement();
        a.setId(id);
        a.setTitle("a" + id);
        a.setPublishTime(publishTime);
        return a;
    }

    private static Task task(Long id, String assignee) {
        Task t = new Task();
        t.setId(id);
        t.setName("task" + id);
        t.setAssignee(assignee);
        return t;
    }
}
