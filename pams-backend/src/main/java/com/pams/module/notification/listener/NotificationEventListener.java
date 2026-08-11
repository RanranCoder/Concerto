package com.pams.module.notification.listener;

import com.pams.entity.User;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.notification.entity.NotificationType;
import com.pams.module.notification.event.ArticleAssignedEvent;
import com.pams.module.notification.event.ArticleDeadlineReminderEvent;
import com.pams.module.notification.event.ArticlePublishedEvent;
import com.pams.module.notification.event.ArticleReviewedEvent;
import com.pams.module.notification.event.ArticleSubmittedEvent;
import com.pams.module.notification.event.ContentUploadedEvent;
import com.pams.module.notification.event.PlanEditedEvent;
import com.pams.module.notification.event.PlanReviewedEvent;
import com.pams.module.notification.event.PlanSubmittedEvent;
import com.pams.module.notification.event.SigninCompletedEvent;
import com.pams.module.notification.event.SigninRosterUploadedEvent;
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
    /** 链路1「通知所有部门（部长+主任）」的目标角色集合 */
    private static final Set<String> ALL_LEADER_ROLES = Set.of(
            "ORG_LEADER", "SECRETARY_LEADER", "MEDIA_LEADER", "TECH_LEADER", "TEACHER", "DIRECTOR");
    /** 链路3/4「通知所有部长角色」的目标角色集合 */
    private static final Set<String> DEPT_LEADER_ROLES = Set.of(
            "ORG_LEADER", "SECRETARY_LEADER", "MEDIA_LEADER", "TECH_LEADER");

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
            Long deptId = user.getDept() != null ? user.getDept().getId() : null;
            long unread = notificationService.countUnreadForUser(
                user.getId(), user.getRole().getCode(), deptId);
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

        // 向每个教师/主任发送 WebSocket 信号（去重，排除提交人自己）
        Set<Long> alreadyPushed = new java.util.LinkedHashSet<>();
        alreadyPushed.add(event.getSubmitterId()); // 排除提交人
        for (User u : teachers) {
            if (!alreadyPushed.contains(u.getId())) {
                pushToUser(u);
                alreadyPushed.add(u.getId());
            }
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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePlanEdited(PlanEditedEvent event) {
        String activityName = getActivityName(event.getActivityId());
        java.util.Optional<User> editor = userRepo.findById(event.getEditorId());
        String editorName = editor.map(User::getRealName).orElse("某用户");
        boolean isDirector = editor.map(u -> "DIRECTOR".equals(u.getRole().getCode())).orElse(false);
        if (isDirector) {
            // 链路2：主任修改策划书 → 通知组织部全员
            List<User> orgLeaders = userRepo.findByRoleCode("ORG_LEADER");
            for (User u : orgLeaders) {
                if (!u.getId().equals(event.getEditorId())) {
                    notificationService.createAndSave(
                        NotificationType.PLAN_MODIFIED,
                        "策划书已修改",
                        "主任修改了《" + activityName + "》策划书",
                        "PLAN", event.getPlanId(), event.getEditorId(),
                        u.getId(), null, null
                    );
                    pushToUser(u);
                }
            }
            log.info("PlanEdited(主任) 通知已发送给组织部 {} 名成员", orgLeaders.size());
        } else {
            // 链路1：策划书编辑完成 → 通知所有部门（部长+主任），排除编辑者本人
            Set<String> roles = new java.util.LinkedHashSet<>(ALL_LEADER_ROLES);
            editor.ifPresent(u -> roles.remove(u.getRole().getCode()));
            broadcastToRoles(
                NotificationType.PLAN_MODIFIED,
                "策划书待审核",
                editorName + " 已完成《" + activityName + "》策划书编辑，请审核",
                "PLAN", event.getPlanId(), event.getEditorId(), roles);
            log.info("PlanEdited 通知已发送给 {} 个角色", roles.size());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleContentUploaded(ContentUploadedEvent event) {
        String activityName = getActivityName(event.getActivityId());
        String label = "NEWS".equals(event.getContentType()) ? "新闻稿" : "推文";
        Set<String> roles = new java.util.LinkedHashSet<>(DEPT_LEADER_ROLES);
        userRepo.findById(event.getUploaderId()).ifPresent(u -> roles.remove(u.getRole().getCode()));
        broadcastToRoles(
            NotificationType.NEWS_UPLOADED,
            label + "已上传",
            label + "「" + event.getTitle() + "」已上传（活动：" + activityName + "），请审核",
            event.getContentType(), event.getContentId(), event.getUploaderId(), roles);
        log.info("ContentUploaded 通知已发送给 {} 个角色", roles.size());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSigninRosterUploaded(SigninRosterUploadedEvent event) {
        String activityName = getActivityName(event.getActivityId());
        // 通知活动创建者（排除上传者本人）
        activityRepo.findById(event.getActivityId())
            .map(Activity::getCreatedBy)
            .filter(creatorId -> creatorId != null && !creatorId.equals(event.getUploaderId()))
            .ifPresent(creatorId -> {
                notificationService.createAndSave(
                    NotificationType.SIGNIN_ROSTER_UPLOADED,
                    "签到表已上传",
                    "《" + activityName + "》签到表已上传",
                    "SIGNIN", event.getActivityId(), event.getUploaderId(),
                    creatorId, null, null
                );
                userRepo.findById(creatorId).ifPresent(this::pushToUser);
            });
        // 通知各部长
        Set<String> roles = new java.util.LinkedHashSet<>(DEPT_LEADER_ROLES);
        userRepo.findById(event.getUploaderId()).ifPresent(u -> roles.remove(u.getRole().getCode()));
        broadcastToRoles(
            NotificationType.SIGNIN_ROSTER_UPLOADED,
            "签到表已上传",
            "《" + activityName + "》签到表已上传",
            "SIGNIN", event.getActivityId(), event.getUploaderId(), roles);
        log.info("SigninRosterUploaded 通知已发送，activityId={}", event.getActivityId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSigninCompleted(SigninCompletedEvent event) {
        String activityName = getActivityName(event.getActivityId());
        String content = "《" + activityName + "》签到已完成，共 " + event.getSigned() + "/" + event.getExpected() + " 人";
        // 通知活动创建者
        activityRepo.findById(event.getActivityId())
            .map(Activity::getCreatedBy)
            .filter(creatorId -> creatorId != null)
            .ifPresent(creatorId -> {
                notificationService.createAndSave(
                    NotificationType.SIGNIN_COMPLETED,
                    "签到已完成",
                    content,
                    "SIGNIN", event.getActivityId(), null,
                    creatorId, null, null
                );
                userRepo.findById(creatorId).ifPresent(this::pushToUser);
            });
        // 通知各部长
        broadcastToRoles(
            NotificationType.SIGNIN_COMPLETED,
            "签到已完成",
            content,
            "SIGNIN", event.getActivityId(), null, DEPT_LEADER_ROLES);
        log.info("SigninCompleted 通知已发送，activityId={}", event.getActivityId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleArticleAssigned(ArticleAssignedEvent e) {
        String activityName = getActivityName(e.getActivityId());
        userRepo.findById(e.getAssigneeId()).ifPresent(u -> {
            notificationService.createAndSave(NotificationType.ARTICLE_ASSIGNED,
                "推文任务已指派",
                "你负责撰写推文《" + e.getTitle() + "》（活动：" + activityName + "），请按时完成",
                "ARTICLE", e.getArticleId(), e.getCreatorId(), u.getId(), null, null);
            pushToUser(u);
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleArticleSubmitted(ArticleSubmittedEvent e) {
        String activityName = getActivityName(e.getActivityId());
        // 推文提交审核 → 通知审核人（新媒体部长 + 老师/主任），排除提交人本人
        Set<String> roles = new java.util.LinkedHashSet<>(Set.of("MEDIA_LEADER", "TEACHER", "DIRECTOR"));
        userRepo.findById(e.getSubmitterId()).ifPresent(u -> roles.remove(u.getRole().getCode()));
        broadcastToRoles(NotificationType.ARTICLE_SUBMITTED, "推文待审核",
            "推文《" + e.getTitle() + "》（活动：" + activityName + "）已提交审核",
            "ARTICLE", e.getArticleId(), e.getSubmitterId(), roles);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleArticleReviewed(ArticleReviewedEvent e) {
        String activityName = getActivityName(e.getActivityId());
        NotificationType type = e.isApproved() ? NotificationType.ARTICLE_APPROVED : NotificationType.ARTICLE_REJECTED;
        String title = e.isApproved() ? "推文审核通过" : "推文被驳回";
        String content = "推文《" + e.getTitle() + "》（活动：" + activityName + "）"
            + (e.isApproved() ? "已通过审核，请发布" : "被驳回" + (e.getComment() != null ? "，原因：" + e.getComment() : ""));
        userRepo.findById(e.getAuthorId()).ifPresent(u -> {
            notificationService.createAndSave(type, title, content, "ARTICLE", e.getArticleId(),
                e.getReviewerId(), u.getId(), null, null);
            pushToUser(u);
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleArticlePublished(ArticlePublishedEvent e) {
        String activityName = getActivityName(e.getActivityId());
        broadcastToRoles(NotificationType.ARTICLE_PUBLISHED, "推文已发布",
            "推文《" + e.getTitle() + "》（活动：" + activityName + "）已发布",
            "ARTICLE", e.getArticleId(), e.getPublisherId(), ALL_LEADER_ROLES);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleArticleDeadlineReminder(ArticleDeadlineReminderEvent e) {
        String activityName = getActivityName(e.getActivityId());
        userRepo.findById(e.getAuthorId()).ifPresent(u -> {
            notificationService.createAndSave(NotificationType.ARTICLE_DEADLINE_REMINDER,
                "推文截止提醒",
                "推文《" + e.getTitle() + "》截止时间 " + e.getDeadline() + "（活动：" + activityName + "），请尽快完成",
                "ARTICLE", e.getArticleId(), null, u.getId(), null, null);
            pushToUser(u);
        });
    }

    /**
     * 按角色集合广播：每个角色写一条 recipientRole 通知，并向各角色下的用户推 WebSocket（排除发送者本人）。
     */
    private void broadcastToRoles(NotificationType type, String title, String content,
                                  String entityType, Long entityId, Long senderId,
                                  Set<String> roles) {
        for (String role : roles) {
            notificationService.createAndSave(
                type, title, content, entityType, entityId, senderId, null, role, null);
        }
        Set<Long> pushed = new java.util.LinkedHashSet<>();
        if (senderId != null) {
            pushed.add(senderId);
        }
        for (String role : roles) {
            for (User u : userRepo.findByRoleCode(role)) {
                if (!pushed.contains(u.getId())) {
                    pushToUser(u);
                    pushed.add(u.getId());
                }
            }
        }
    }

    private String getActivityName(Long activityId) {
        if (activityId == null) return "未知活动";
        return activityRepo.findById(activityId)
            .map(Activity::getName)
            .orElse("未知活动");
    }
}
