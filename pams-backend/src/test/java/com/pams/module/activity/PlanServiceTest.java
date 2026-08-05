package com.pams.module.activity;

import com.pams.common.BizException;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.entity.ActivityPlan;
import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.repository.ActivityPlanRepository;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.activity.service.PlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PlanServiceTest {

    ActivityPlanRepository repo;
    PlanService service;

    @BeforeEach
    void setup() {
        repo = mock(ActivityPlanRepository.class);
        service = new PlanService(repo);
    }

    @Test
    void review_missingPlan_throws() {
        when(repo.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.review(1L, true, "ok", 100L))
                .isInstanceOf(BizException.class);
    }

    @Test
    void review_approve_setsStatus() {
        ActivityPlan p = new ActivityPlan();
        p.setId(2L);
        p.setStatus(ActivityPlan.PlanStatus.PENDING);
        when(repo.findById(2L)).thenReturn(Optional.of(p));

        service.review(2L, true, "ok", 100L);

        assertThat(p.getStatus()).isEqualTo(ActivityPlan.PlanStatus.APPROVED);
        assertThat(p.getReviewerId()).isEqualTo(100L);
        assertThat(p.getReviewComment()).isEqualTo("ok");
        verify(repo).save(p);
    }

    @Test
    void review_reject_setsStatus() {
        ActivityPlan p = new ActivityPlan();
        p.setId(3L);
        p.setStatus(ActivityPlan.PlanStatus.PENDING);
        when(repo.findById(3L)).thenReturn(Optional.of(p));

        service.review(3L, false, "no", 100L);

        assertThat(p.getStatus()).isEqualTo(ActivityPlan.PlanStatus.REJECTED);
        assertThat(p.getReviewComment()).isEqualTo("no");
        verify(repo).save(p);
    }

    @Test
    void review_nonPending_throws() {
        ActivityPlan p = new ActivityPlan();
        p.setId(7L);
        p.setStatus(ActivityPlan.PlanStatus.DRAFT);
        when(repo.findById(7L)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.review(7L, true, "ok", 100L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅待审核状态");
        verify(repo, never()).save(any());
    }

    @Test
    void submit_setsPending() {
        ActivityPlan p = new ActivityPlan();
        p.setId(4L);
        p.setStatus(ActivityPlan.PlanStatus.DRAFT);
        when(repo.findById(4L)).thenReturn(Optional.of(p));

        service.submit(4L, null);

        assertThat(p.getStatus()).isEqualTo(ActivityPlan.PlanStatus.PENDING);
        verify(repo).save(p);
    }

    @Test
    void submit_approved_throws() {
        ActivityPlan p = new ActivityPlan();
        p.setId(8L);
        p.setStatus(ActivityPlan.PlanStatus.APPROVED);
        when(repo.findById(8L)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.submit(8L, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不可提交审核");
        verify(repo, never()).save(any());
    }

    @Test
    void update_approved_throws() {
        ActivityPlan p = new ActivityPlan();
        p.setId(5L);
        p.setStatus(ActivityPlan.PlanStatus.APPROVED);
        when(repo.findById(5L)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.update(5L, new com.pams.module.activity.dto.PlanRequest()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不可修改");
    }

    @Test
    void update_draft_ok() {
        ActivityPlan p = new ActivityPlan();
        p.setId(6L);
        p.setStatus(ActivityPlan.PlanStatus.DRAFT);
        when(repo.findById(6L)).thenReturn(Optional.of(p));

        com.pams.module.activity.dto.PlanRequest req = new com.pams.module.activity.dto.PlanRequest();
        req.setBackground("bg");
        service.update(6L, req);

        assertThat(p.getBackground()).isEqualTo("bg");
        verify(repo).save(p);
    }

    @Test
    void review_approve_planningActivity_pushesToPlanReview() {
        ActivityPlan p = new ActivityPlan();
        p.setId(10L);
        p.setActivityId(20L);
        p.setStatus(ActivityPlan.PlanStatus.PENDING);
        when(repo.findById(10L)).thenReturn(Optional.of(p));

        Activity a = new Activity();
        a.setId(20L);
        a.setStatus(ActivityStatus.PLANNING);
        ActivityRepository activityRepo = mock(ActivityRepository.class);
        when(activityRepo.findById(20L)).thenReturn(Optional.of(a));

        PlanService linked = new PlanService(repo, activityRepo, null);
        linked.review(10L, true, "ok", 100L);

        assertThat(a.getStatus()).isEqualTo(ActivityStatus.PLAN_REVIEW);
        verify(activityRepo).save(a);
    }

    @Test
    void review_approve_nonPlanningActivity_keepsStatus() {
        ActivityPlan p = new ActivityPlan();
        p.setId(11L);
        p.setActivityId(21L);
        p.setStatus(ActivityPlan.PlanStatus.PENDING);
        when(repo.findById(11L)).thenReturn(Optional.of(p));

        Activity a = new Activity();
        a.setId(21L);
        a.setStatus(ActivityStatus.EXECUTING);
        ActivityRepository activityRepo = mock(ActivityRepository.class);
        when(activityRepo.findById(21L)).thenReturn(Optional.of(a));

        PlanService linked = new PlanService(repo, activityRepo, null);
        linked.review(11L, true, "ok", 100L);

        assertThat(a.getStatus()).isEqualTo(ActivityStatus.EXECUTING);
        verify(activityRepo, never()).save(any());
    }

    @Test
    void review_reject_doesNotTouchActivity() {
        ActivityPlan p = new ActivityPlan();
        p.setId(12L);
        p.setActivityId(22L);
        p.setStatus(ActivityPlan.PlanStatus.PENDING);
        when(repo.findById(12L)).thenReturn(Optional.of(p));

        Activity a = new Activity();
        a.setId(22L);
        a.setStatus(ActivityStatus.PLANNING);
        ActivityRepository activityRepo = mock(ActivityRepository.class);

        PlanService linked = new PlanService(repo, activityRepo, null);
        linked.review(12L, false, "no", 100L);

        assertThat(a.getStatus()).isEqualTo(ActivityStatus.PLANNING);
        verify(activityRepo, never()).findById(anyLong());
        verify(activityRepo, never()).save(any());
    }

    @Test
    void update_withSyncActivity_writesBackOverrides() {
        ActivityPlan p = new ActivityPlan();
        p.setId(13L);
        p.setActivityId(23L);
        p.setStatus(ActivityPlan.PlanStatus.DRAFT);
        when(repo.findById(13L)).thenReturn(Optional.of(p));

        Activity a = new Activity();
        a.setId(23L);
        a.setName("旧名");
        a.setTheme("旧主题");
        ActivityRepository activityRepo = mock(ActivityRepository.class);
        when(activityRepo.findById(23L)).thenReturn(Optional.of(a));

        PlanService linked = new PlanService(repo, activityRepo, null);
        com.pams.module.activity.dto.PlanRequest req = new com.pams.module.activity.dto.PlanRequest();
        req.setNameOverride("新活动名");
        req.setThemeOverride("新主题");
        req.setTimeOverride("2026-08-05|9:00-11:00");
        req.setLocationOverride("图书馆报告厅");
        req.setSyncActivity(true);
        linked.update(13L, req);

        assertThat(a.getName()).isEqualTo("新活动名");
        assertThat(a.getTheme()).isEqualTo("新主题");
        assertThat(a.getLocation()).isEqualTo("图书馆报告厅");
        assertThat(a.getStartDate()).isEqualTo(java.time.LocalDate.parse("2026-08-05"));
        assertThat(p.getNameOverride()).isEqualTo("新活动名");
        verify(activityRepo).save(a);
        verify(repo).save(p);
    }

    @Test
    void update_withoutSyncActivity_keepsActivityUntouched() {
        ActivityPlan p = new ActivityPlan();
        p.setId(14L);
        p.setActivityId(24L);
        p.setStatus(ActivityPlan.PlanStatus.DRAFT);
        when(repo.findById(14L)).thenReturn(Optional.of(p));

        Activity a = new Activity();
        a.setId(24L);
        a.setName("旧名");
        ActivityRepository activityRepo = mock(ActivityRepository.class);
        when(activityRepo.findById(24L)).thenReturn(Optional.of(a));

        PlanService linked = new PlanService(repo, activityRepo, null);
        com.pams.module.activity.dto.PlanRequest req = new com.pams.module.activity.dto.PlanRequest();
        req.setNameOverride("新活动名");
        req.setSyncActivity(false);
        linked.update(14L, req);

        assertThat(a.getName()).isEqualTo("旧名");
        verify(activityRepo, never()).save(any());
        assertThat(p.getNameOverride()).isEqualTo("新活动名");
    }
}
