package com.pams.module.activity;

import com.pams.common.BizException;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.activity.service.ActivityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ActivityServiceTest {

    ActivityRepository repo;
    ActivityService service;

    @BeforeEach
    void setup() {
        repo = mock(ActivityRepository.class);
        service = new ActivityService(repo);
    }

    @Test
    void advance_ok_whenNextStatus() {
        Activity a = new Activity();
        a.setId(1L);
        a.setStatus(ActivityStatus.ASSIGNED);
        when(repo.findById(1L)).thenReturn(Optional.of(a));

        service.changeStatus(1L, ActivityStatus.PLANNING);

        assertThat(a.getStatus()).isEqualTo(ActivityStatus.PLANNING);
        verify(repo).save(a);
    }

    @Test
    void advance_skips_throws() {
        Activity a = new Activity();
        a.setId(2L);
        a.setStatus(ActivityStatus.ASSIGNED);
        when(repo.findById(2L)).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.changeStatus(2L, ActivityStatus.EXECUTING))
                .isInstanceOf(BizException.class);
    }

    @Test
    void changeStatus_unknownId_throws() {
        when(repo.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changeStatus(9L, ActivityStatus.PLANNING))
                .isInstanceOf(BizException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageAddsBatchedCountsAndUsesServerDateOrder() {
        Activity a = new Activity(); a.setId(7L); a.setName("主题学习"); a.setStatus(ActivityStatus.PLANNING);
        when(repo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(a)));
        when(repo.taskCounts(List.of(7L))).thenReturn(java.util.Collections.singletonList(new Object[]{7L, 4L, 3L}));
        when(repo.materialCounts(List.of(7L))).thenReturn(java.util.Collections.singletonList(new Object[]{7L, 2L}));
        var result = service.page(null, null, null, "组织部", "asc", 1, 10);
        assertThat(result.getRecords().get(0)).containsEntry("taskCount", 4L).containsEntry("completedTaskCount", 3L).containsEntry("materialCount", 2L);
        verify(repo).findAll(any(Specification.class), argThat((Pageable p) -> p.getSort().getOrderFor("startDate").isAscending()));
        verify(repo, times(1)).taskCounts(List.of(7L));
        verify(repo, times(1)).materialCounts(List.of(7L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyPageDoesNotRunAggregateQueries() {
        when(repo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        assertThat(service.page(null, null, null, 1, 10).getRecords()).isEmpty();
        verify(repo, never()).taskCounts(anyList());
        verify(repo, never()).materialCounts(anyList());
    }
}
