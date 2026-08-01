package com.pams.module.activity;

import com.pams.common.BizException;
import com.pams.module.activity.entity.ActivityPlan;
import com.pams.module.activity.repository.ActivityPlanRepository;
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
        p.setStatus(ActivityPlan.PlanStatus.DRAFT);
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
    void submit_setsPending() {
        ActivityPlan p = new ActivityPlan();
        p.setId(4L);
        p.setStatus(ActivityPlan.PlanStatus.DRAFT);
        when(repo.findById(4L)).thenReturn(Optional.of(p));

        service.submit(4L);

        assertThat(p.getStatus()).isEqualTo(ActivityPlan.PlanStatus.PENDING);
        verify(repo).save(p);
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
}
