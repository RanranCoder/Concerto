package com.pams.module.notification.listener;

import com.pams.entity.User;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.notification.entity.NotificationType;
import com.pams.module.notification.event.PlanReviewedEvent;
import com.pams.module.notification.event.PlanSubmittedEvent;
import com.pams.module.notification.event.TaskAssignedEvent;
import com.pams.module.notification.service.NotificationService;
import com.pams.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);
    private static final String WS_DESTINATION = "/queue/notifications";

    private final NotificationService notificationService;
    private final UserRepository userRepo;
    private final ActivityRepository activityRepo;
    private final SimpMessagingTemplate messagingTemplate;

    public NotificationEventListener(NotificationService notificationService,
                                     UserRepository userRepo,
                                     ActivityRepository activityRepo,
                                     SimpMessagingTemplate messagingTemplate) {
        this.notificationService = notificationService;
        this.userRepo = userRepo;
        this.activityRepo = activityRepo;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 向指定用户发送 WebSocket 通知信号。
     * payload 仅包含类型和未读计数，前端收到后调 REST API 刷新。
     */
    private void pushToUser(User user) {
        try {
            long unread = notificationService.countUnreadForUser(
                user.getId(), user.getRole().getCode(), user.getDept().getId());
            messagingTemplate.convertAndSendToUser(
                user.getUsername(), WS_DESTINATION,
                Map.of("type", "NEW_NOTIFICATION", "unreadCount", unread));
        } catch (Exception e) {
            log.warn("WebSocket 推送失败: username={}", user.getUsername(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTaskAssigned(TaskAssignedEvent event) {
        String activityName = getActivityName(event.getActivityId());
        List<User> members = userRepo.findByDeptId(event.getDeptId());
        for (User member : members) {
            if (!member.getId().equals(event.getSenderId())) {
                notificationService.createAndSave(
                    NotificationType.TASK_ASSIGNED,
                    "新任务指派",
                    "为您所在部门指派了任务「" + event.getTaskName() + "」（活动：" + activityName + "）",
                    "TASK", event.getTaskId(), event.getSenderId(),
                    member.getId(), null, null
                );
                pushToUser(member);
            }
        }
        log.info("TaskAssigned 通知已发送给部门 {} 的 {} 名成员", event.getDeptId(), members.size());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePlanSubmitted(PlanSubmittedEvent event) {
        String activityName = getActivityName(event.getActivityId());
        List<User> teachers = userRepo.findByRoleCode("TEACHER");
        List<User> directors = userRepo.findByRoleCode("DIRECTOR");

        Set<String> roles = new java.util.LinkedHashSet<>();
        for (User u : teachers) {
            roles.add(u.getRole().getCode());
        }
        for (User u : directors) {
            roles.add(u.getRole().getCode());
        }

        for (String role : roles) {
            notificationService.createAndSave(
                NotificationType.PLAN_SUBMITTED,
                "策划书待审核",
                "提交了活动「" + activityName + "」的策划书，请审核",
                "PLAN", event.getPlanId(), event.getSubmitterId(),
                null, role, null
            );
        }

        // 向每个教师/主任发送 WebSocket 信号（去重）
        Set<Long> alreadyPushed = new java.util.LinkedHashSet<>();
        for (User u : teachers) {
            pushToUser(u);
            alreadyPushed.add(u.getId());
        }
        for (User u : directors) {
            if (!alreadyPushed.contains(u.getId())) {
                pushToUser(u);
                alreadyPushed.add(u.getId());
            }
        }

        log.info("PlanSubmitted 通知已发送给 {} 个角色", roles.size());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePlanReviewed(PlanReviewedEvent event) {
        String activityName = getActivityName(event.getActivityId());
        if (event.isApproved()) {
            List<User> allUsers = userRepo.findAll();
            for (User user : allUsers) {
                if (!user.getId().equals(event.getReviewerId())) {
                    notificationService.createAndSave(
                        NotificationType.PLAN_APPROVED,
                        "策划书审核通过",
                        "活动「" + activityName + "」的策划书已审核通过",
                        "PLAN", event.getPlanId(), event.getReviewerId(),
                        user.getId(), null, null
                    );
                    pushToUser(user);
                }
            }
            log.info("PlanApproved 通知已发送给 {} 名用户", allUsers.size() - 1);
        } else {
            notificationService.createAndSave(
                NotificationType.PLAN_REJECTED,
                "策划书已驳回",
                "活动「" + activityName + "」的策划书已驳回" +
                    (event.getComment() != null ? "，原因：" + event.getComment() : ""),
                "PLAN", event.getPlanId(), event.getReviewerId(),
                event.getSubmitterId(), null, null
            );
            // 向提交人推送 WebSocket
            userRepo.findById(event.getSubmitterId()).ifPresent(this::pushToUser);
            log.info("PlanRejected 通知已发送给提交人 {}", event.getSubmitterId());
        }
    }

    private String getActivityName(Long activityId) {
        if (activityId == null) return "未知活动";
        return activityRepo.findById(activityId)
            .map(Activity::getName)
            .orElse("未知活动");
    }
}
