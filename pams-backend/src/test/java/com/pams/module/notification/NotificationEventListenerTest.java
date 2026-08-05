package com.pams.module.notification;

import com.pams.entity.Department;
import com.pams.entity.Role;
import com.pams.entity.User;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.notification.entity.NotificationType;
import com.pams.module.notification.event.TaskAssignedEvent;
import com.pams.module.notification.event.PlanSubmittedEvent;
import com.pams.module.notification.event.PlanReviewedEvent;
import com.pams.module.notification.listener.NotificationEventListener;
import com.pams.module.notification.service.NotificationService;
import com.pams.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

class NotificationEventListenerTest {

    NotificationService notificationService;
    UserRepository userRepo;
    ActivityRepository activityRepo;
    SimpMessagingTemplate messagingTemplate;
    NotificationEventListener listener;

    @BeforeEach
    void setup() {
        notificationService = mock(NotificationService.class);
        userRepo = mock(UserRepository.class);
        activityRepo = mock(ActivityRepository.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        // 默认 countUnreadForUser 返回 0，避免 WebSocket 推送 NPE
        when(notificationService.countUnreadForUser(anyLong(), anyString(), anyLong())).thenReturn(0L);
        listener = new NotificationEventListener(notificationService, userRepo, activityRepo, messagingTemplate);
    }

    private User userWithIdAndRole(Long id, String roleCode) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        Role role = new Role();
        role.setCode(roleCode);
        user.setRole(role);
        Department dept = new Department();
        dept.setId(1L);
        user.setDept(dept);
        return user;
    }

    private Activity activityWithName(String name) {
        Activity activity = new Activity();
        activity.setName(name);
        return activity;
    }

    @Test
    void handleTaskAssigned_notifiesDeptMembersExceptSender() {
        User member1 = userWithIdAndRole(2L, "MEMBER");
        User member2 = userWithIdAndRole(3L, "MEMBER");
        User sender = userWithIdAndRole(1L, "TEACHER");
        when(userRepo.findByDeptId(10L)).thenReturn(List.of(member1, member2, sender));
        when(activityRepo.findById(100L)).thenReturn(Optional.of(activityWithName("测试活动")));

        listener.handleTaskAssigned(new TaskAssignedEvent(1L, 100L, 10L, "布置会场", 1L));

        // should notify member1 and member2, but not sender
        verify(notificationService, times(2)).createAndSave(
            eq(NotificationType.TASK_ASSIGNED), anyString(), anyString(),
            eq("TASK"), eq(1L), eq(1L), anyLong(), isNull(), isNull()
        );
        verify(notificationService, never()).createAndSave(
            eq(NotificationType.TASK_ASSIGNED), anyString(), anyString(),
            eq("TASK"), eq(1L), eq(1L), eq(1L), isNull(), isNull()
        );
    }

    @Test
    void handleTaskAssigned_noDeptMembers_noNotifications() {
        when(userRepo.findByDeptId(10L)).thenReturn(List.of());
        when(activityRepo.findById(100L)).thenReturn(Optional.of(activityWithName("测试活动")));

        listener.handleTaskAssigned(new TaskAssignedEvent(1L, 100L, 10L, "布置会场", 1L));

        verify(notificationService, never()).createAndSave(
            any(), anyString(), anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void handlePlanSubmitted_notifiesTeacherAndDirectorRoles() {
        User teacher = userWithIdAndRole(10L, "TEACHER");
        User director = userWithIdAndRole(11L, "DIRECTOR");
        when(userRepo.findByRoleCode("TEACHER")).thenReturn(List.of(teacher));
        when(userRepo.findByRoleCode("DIRECTOR")).thenReturn(List.of(director));
        when(activityRepo.findById(100L)).thenReturn(Optional.of(activityWithName("测试活动")));

        listener.handlePlanSubmitted(new PlanSubmittedEvent(1L, 100L, "策划书", 5L));

        // one notification for TEACHER role
        verify(notificationService, times(1)).createAndSave(
            eq(NotificationType.PLAN_SUBMITTED), anyString(), anyString(),
            eq("PLAN"), eq(1L), eq(5L), isNull(), eq("TEACHER"), isNull()
        );
        // one notification for DIRECTOR role
        verify(notificationService, times(1)).createAndSave(
            eq(NotificationType.PLAN_SUBMITTED), anyString(), anyString(),
            eq("PLAN"), eq(1L), eq(5L), isNull(), eq("DIRECTOR"), isNull()
        );
    }

    @Test
    void handlePlanSubmitted_noReviewers_noNotifications() {
        when(userRepo.findByRoleCode("TEACHER")).thenReturn(List.of());
        when(userRepo.findByRoleCode("DIRECTOR")).thenReturn(List.of());
        when(activityRepo.findById(100L)).thenReturn(Optional.of(activityWithName("测试活动")));

        listener.handlePlanSubmitted(new PlanSubmittedEvent(1L, 100L, "策划书", 5L));

        verify(notificationService, never()).createAndSave(
            any(), anyString(), anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void handlePlanApproved_notifiesAllUsersExceptReviewer() {
        User reviewer = userWithIdAndRole(1L, "TEACHER");
        User user1 = userWithIdAndRole(2L, "MEMBER");
        User user2 = userWithIdAndRole(3L, "DIRECTOR");
        when(userRepo.findAll()).thenReturn(List.of(reviewer, user1, user2));
        when(activityRepo.findById(100L)).thenReturn(Optional.of(activityWithName("测试活动")));

        listener.handlePlanReviewed(new PlanReviewedEvent(
            1L, 100L, "策划书", 1L, true, null, 5L));

        // should notify user1 and user2, but not reviewer
        verify(notificationService, times(2)).createAndSave(
            eq(NotificationType.PLAN_APPROVED), anyString(), anyString(),
            eq("PLAN"), eq(1L), eq(1L), anyLong(), isNull(), isNull()
        );
        verify(notificationService, never()).createAndSave(
            eq(NotificationType.PLAN_APPROVED), anyString(), anyString(),
            eq("PLAN"), eq(1L), eq(1L), eq(1L), isNull(), isNull()
        );
    }

    @Test
    void handlePlanRejected_notifiesSubmitterOnly() {
        User submitter = userWithIdAndRole(5L, "MEMBER");
        when(userRepo.findById(5L)).thenReturn(Optional.of(submitter));
        when(activityRepo.findById(100L)).thenReturn(Optional.of(activityWithName("测试活动")));

        listener.handlePlanReviewed(new PlanReviewedEvent(
            1L, 100L, "策划书", 11L, false, "内容不完整", 5L));

        verify(notificationService).createAndSave(
            eq(NotificationType.PLAN_REJECTED), anyString(), contains("内容不完整"),
            eq("PLAN"), eq(1L), eq(11L), eq(5L), isNull(), isNull()
        );
        // 验证 WebSocket 推送给提交人
        verify(messagingTemplate).convertAndSendToUser(eq("user5"), eq("/queue/notifications"), any());
    }

    @Test
    void handlePlanRejected_nullComment_noReasonAppended() {
        User submitter = userWithIdAndRole(5L, "MEMBER");
        when(userRepo.findById(5L)).thenReturn(Optional.of(submitter));
        when(activityRepo.findById(100L)).thenReturn(Optional.of(activityWithName("测试活动")));

        listener.handlePlanReviewed(new PlanReviewedEvent(
            1L, 100L, "策划书", 11L, false, null, 5L));

        verify(notificationService).createAndSave(
            eq(NotificationType.PLAN_REJECTED), anyString(), contains("已驳回"),
            eq("PLAN"), eq(1L), eq(11L), eq(5L), isNull(), isNull()
        );
    }

    @Test
    void handleTaskAssigned_activityNotFound_usesFallbackName() {
        User member = userWithIdAndRole(2L, "MEMBER");
        when(userRepo.findByDeptId(10L)).thenReturn(List.of(member));
        when(activityRepo.findById(999L)).thenReturn(Optional.empty());

        listener.handleTaskAssigned(new TaskAssignedEvent(1L, 999L, 10L, "布置会场", 1L));

        verify(notificationService).createAndSave(
            eq(NotificationType.TASK_ASSIGNED), anyString(), contains("未知活动"),
            eq("TASK"), eq(1L), eq(1L), eq(2L), isNull(), isNull()
        );
    }
}
