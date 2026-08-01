package com.pams.module.activity;

import com.pams.common.BizException;
import com.pams.module.activity.entity.Task;
import com.pams.module.activity.repository.TaskRepository;
import com.pams.module.activity.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TaskServiceTest {

    TaskRepository repo;
    TaskService service;

    @BeforeEach
    void setup() {
        repo = mock(TaskRepository.class);
        service = new TaskService(repo);
    }

    @Test
    void updateProgress_outOfRange_throws() {
        Task t = new Task();
        t.setId(1L);
        t.setProgress(0);
        when(repo.findById(1L)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.updateProgress(1L, 101))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("进度需在0-100");
        assertThatThrownBy(() -> service.updateProgress(1L, -1))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("进度需在0-100");
        verify(repo, never()).save(any());
    }

    @Test
    void updateProgress_null_throws() {
        Task t = new Task();
        t.setId(2L);
        when(repo.findById(2L)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.updateProgress(2L, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("进度需在0-100");
        verify(repo, never()).save(any());
    }

    @Test
    void updateProgress_valid_setsProgress() {
        Task t = new Task();
        t.setId(3L);
        t.setProgress(0);
        when(repo.findById(3L)).thenReturn(Optional.of(t));

        service.updateProgress(3L, 60);

        assertThat(t.getProgress()).isEqualTo(60);
        verify(repo).save(t);
    }

    @Test
    void updateProgress_boundary_zeroAndHundred_ok() {
        Task t = new Task();
        t.setId(4L);
        t.setProgress(0);
        when(repo.findById(4L)).thenReturn(Optional.of(t));

        service.updateProgress(4L, 0);
        assertThat(t.getProgress()).isEqualTo(0);
        service.updateProgress(4L, 100);
        assertThat(t.getProgress()).isEqualTo(100);
        verify(repo, times(2)).save(t);
    }

    @Test
    void updateProgress_missingTask_throws() {
        when(repo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateProgress(99L, 50))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("任务不存在");
        verify(repo, never()).save(any());
    }
}
