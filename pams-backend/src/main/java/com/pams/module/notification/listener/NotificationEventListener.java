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
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.util.List;
import java.util.Set;

@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationService notificationService;
    private final UserRepository userRepo;
    private final ActivityRepository activityRepo;

    public NotificationEventListener(NotificationService notificationService,
                                     UserRepository userRepo,
                                     ActivityRepository activityRepo) {
        this.notificationService = notificationService;
        this.userRepo = userRepo;
        this.activityRepo = activityRepo;
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
