package com.pams.module.activity;

import com.pams.common.BizException;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.activity.service.ActivityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

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
}
