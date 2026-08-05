package com.pams.module.notification;

import com.pams.common.BizException;
import com.pams.entity.User;
import com.pams.module.notification.entity.Notification;
import com.pams.module.notification.entity.NotificationType;
import com.pams.module.notification.repository.NotificationRepository;
import com.pams.module.notification.service.NotificationService;
import com.pams.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class NotificationServiceTest {

    NotificationRepository repo;
    UserRepository userRepo;
    NotificationService service;

    @BeforeEach
    void setup() {
        repo = mock(NotificationRepository.class);
        userRepo = mock(UserRepository.class);
        service = new NotificationService(repo, userRepo);
    }

    @Test
    void createAndSave_savesEntity() {
        Notification n = new Notification();
        n.setId(1L);
        when(repo.save(any(Notification.class))).thenReturn(n);

        Notification result = service.createAndSave(
            NotificationType.TASK_ASSIGNED, "标题", "内容",
            "TASK", 10L, 1L, 2L, null, null);

        assertThat(result.getId()).isEqualTo(1L);
        verify(repo).save(any(Notification.class));
    }

    @Test
    void markAsRead_notFound_throws() {
        when(repo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markAsRead(99L, 1L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("通知不存在");
    }

    @Test
    void markAsRead_setsReadAndTime() {
        Notification n = new Notification();
        n.setId(1L);
        n.setIsRead(0);
        when(repo.findById(1L)).thenReturn(Optional.of(n));
        when(repo.save(any())).thenReturn(n);

        service.markAsRead(1L, 1L);

        assertThat(n.getIsRead()).isEqualTo(1);
        assertThat(n.getReadAt()).isNotNull();
        verify(repo).save(n);
    }

    @Test
    void markAsRead_alreadyRead_noop() {
        Notification n = new Notification();
        n.setId(2L);
        n.setIsRead(1);
        n.setReadAt(LocalDateTime.of(2025, 1, 1, 0, 0));
        when(repo.findById(2L)).thenReturn(Optional.of(n));

        service.markAsRead(2L, 1L);

        // Should not save again since already read
        verify(repo, never()).save(any());
    }

    @Test
    void findForUser_mapsToVO_withSenderName() {
        Notification n = new Notification();
        n.setId(1L);
        n.setType(NotificationType.TASK_ASSIGNED);
        n.setTitle("标题");
        n.setContent("内容");
        n.setEntityType("TASK");
        n.setEntityId(10L);
        n.setSenderId(1L);
        n.setIsRead(0);
        n.setCreatedAt(LocalDateTime.of(2025, 6, 1, 10, 0));

        User sender = new User();
        sender.setRealName("张三");

        when(repo.findForUser(2L, null, null)).thenReturn(List.of(n));
        when(userRepo.findById(1L)).thenReturn(Optional.of(sender));

        var result = service.findForUser(2L, null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("标题");
        assertThat(result.get(0).getType()).isEqualTo("TASK_ASSIGNED");
        assertThat(result.get(0).getSenderName()).isEqualTo("张三");
        assertThat(result.get(0).isRead()).isFalse();
    }

    @Test
    void findForUser_senderNotSet_senderNameNull() {
        Notification n = new Notification();
        n.setId(2L);
        n.setType(NotificationType.PLAN_SUBMITTED);
        n.setTitle("t");
        n.setContent("c");
        n.setIsRead(1);
        n.setCreatedAt(LocalDateTime.now());

        when(repo.findForUser(2L, null, null)).thenReturn(List.of(n));

        var result = service.findForUser(2L, null, null);

        assertThat(result.get(0).getSenderName()).isNull();
        verify(userRepo, never()).findById(any());
    }

    @Test
    void countUnreadForUser_delegatesToRepo() {
        when(repo.countUnreadForUser(2L, "ROLE_USER", 10L)).thenReturn(5L);

        long count = service.countUnreadForUser(2L, "ROLE_USER", 10L);

        assertThat(count).isEqualTo(5L);
    }

    @Test
    void markAllAsRead_delegatesToRepo() {
        service.markAllAsRead(2L, "ROLE_USER", 10L);

        verify(repo).markAllAsRead(eq(2L), eq("ROLE_USER"), eq(10L), any(LocalDateTime.class));
    }
}
