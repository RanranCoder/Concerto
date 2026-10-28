package com.pams.module.notification.listener;

import com.pams.entity.User;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.notification.entity.NotificationType;
import com.pams.module.notification.event.AnnouncementPublishedEvent;
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

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    /** 链路1「通知所有部门（部长+主任）」的目标角色集合 */
    private static final Set<String> ALL_LEADER_ROLES = Set.of(
            "ORG_LEADER", "SECRETARY_LEADER", "MEDIA_LEADER", "TECH_LEADER", "TEACHER", "DIRECTOR");
    /** 链路3/4「通知所有部长角色」的目标角色集合 */
    private static final Set<String> DEPT_LEADER_ROLES = Set.of(
            "ORG_LEADER", "SECRETARY_LEADER", "MEDIA_LEADER", "TECH_LEADER");

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.pams.module.permission.service.TenantAuthorizationService authorization;
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

    // BEFORE_COMMIT records facts and frozen recipients; the worker owns SSE delivery.

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handleTaskAssigned(TaskAssignedEvent event) {
        String activityName = getActivityName(event.getActivityId());
        List<User> members = userRepo.findByOrgIdAndDeptId(com.pams.common.OrgContext.getOrDefault(), event.getDeptId());
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

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handlePlanSubmitted(PlanSubmittedEvent event) {
        for (Long uid:event.getReviewerIds()) {
            notificationService.createAndSave(NotificationType.PLAN_SUBMITTED,"策划书待审核",
                "您被选为《"+event.getPlanTitle()+"》的审核人，请查看本次提交版本",
                "PLAN",event.getPlanId(),event.getSubmitterId(),uid,null,null);
        }
    }
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handlePlanReviewed(PlanReviewedEvent event) {
        notificationService.createAndSave(event.isApproved()?NotificationType.PLAN_APPROVED:NotificationType.PLAN_REJECTED,
            event.isApproved()?"策划书审核通过":"策划书已退回",
            event.isApproved()?"所选审核人已全部通过《"+event.getPlanTitle()+"》":"修改意见："+event.getComment(),
            "PLAN",event.getPlanId(),event.getReviewerId(),event.getSubmitterId(),null,null);
    }
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handlePlanEdited(PlanEditedEvent event) {
        // Draft saves do not broadcast private versions. KnowledgeIndexListener still receives this event.
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handleContentUploaded(ContentUploadedEvent event) {
        String activityName = getActivityName(event.getActivityId());
        String label = "NEWS".equals(event.getContentType()) ? "新闻稿" : "推文";
        Set<String> roles = new java.util.LinkedHashSet<>(DEPT_LEADER_ROLES);
        userRepo.findById(event.getUploaderId()).ifPresent(u -> roles.remove(u.getRole().getCode()));
        broadcastToRoles(
            NotificationType.NEWS_UPLOADED,
            label + "已上传",
            label + "「" + event.getTitle() + "」已上传（活动：" + activityName + "），请查看",
            event.getContentType(), event.getContentId(), event.getUploaderId(), roles);
        log.info("ContentUploaded 通知已发送给 {} 个角色", roles.size());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
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

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
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
            });
        // 通知各部长
        broadcastToRoles(
            NotificationType.SIGNIN_COMPLETED,
            "签到已完成",
            content,
            "SIGNIN", event.getActivityId(), null, DEPT_LEADER_ROLES);
        log.info("SigninCompleted 通知已发送，activityId={}", event.getActivityId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handleArticleAssigned(ArticleAssignedEvent e) {
        String activityName = getActivityName(e.getActivityId());
        userRepo.findById(e.getAssigneeId()).ifPresent(u -> {
            notificationService.createAndSave(NotificationType.ARTICLE_ASSIGNED,
                "推文任务已指派",
                "你负责撰写推文《" + e.getTitle() + "》（活动：" + activityName + "），请按时完成",
                "ARTICLE", e.getArticleId(), e.getCreatorId(), u.getId(), null, null);
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handleArticleSubmitted(ArticleSubmittedEvent e) {
        String activityName = getActivityName(e.getActivityId());
        // 推文提交审核 → 通知审核人（新媒体部长 + 老师/主任），排除提交人本人
        Set<String> roles = new java.util.LinkedHashSet<>(Set.of("MEDIA_LEADER", "TEACHER", "DIRECTOR"));
        userRepo.findById(e.getSubmitterId()).ifPresent(u -> roles.remove(u.getRole().getCode()));
        broadcastToRoles(NotificationType.ARTICLE_SUBMITTED, "推文待审核",
            "推文《" + e.getTitle() + "》（活动：" + activityName + "）已提交审核",
            "ARTICLE", e.getArticleId(), e.getSubmitterId(), roles);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handleArticleReviewed(ArticleReviewedEvent e) {
        String activityName = getActivityName(e.getActivityId());
        NotificationType type = e.isApproved() ? NotificationType.ARTICLE_APPROVED : NotificationType.ARTICLE_REJECTED;
        String title = e.isApproved() ? "推文审核通过" : "推文被驳回";
        String content = "推文《" + e.getTitle() + "》（活动：" + activityName + "）"
            + (e.isApproved() ? "已通过审核，请发布" : "被驳回" + (e.getComment() != null ? "，原因：" + e.getComment() : ""));
        userRepo.findById(e.getAuthorId()).ifPresent(u -> {
            notificationService.createAndSave(type, title, content, "ARTICLE", e.getArticleId(),
                e.getReviewerId(), u.getId(), null, null);
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handleArticlePublished(ArticlePublishedEvent e) {
        String activityName = getActivityName(e.getActivityId());
        broadcastToRoles(NotificationType.ARTICLE_PUBLISHED, "推文已发布",
            "推文《" + e.getTitle() + "》（活动：" + activityName + "）已发布",
            "ARTICLE", e.getArticleId(), e.getPublisherId(), ALL_LEADER_ROLES);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handleArticleDeadlineReminder(ArticleDeadlineReminderEvent e) {
        String activityName = getActivityName(e.getActivityId());
        userRepo.findById(e.getAuthorId()).ifPresent(u -> {
            notificationService.createAndSave(NotificationType.ARTICLE_DEADLINE_REMINDER,
                "推文截止提醒",
                "推文《" + e.getTitle() + "》截止时间 " + e.getDeadline() + "（活动：" + activityName + "），请尽快完成",
                "ARTICLE", e.getArticleId(), null, u.getId(), null, null);
        });
    }

    /** PRD-v5 F4：新公告发布 → 全员广播（含干事，通知中心可见） */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handleAnnouncementPublished(AnnouncementPublishedEvent e) {
        Set<String> roles = new java.util.LinkedHashSet<>(ALL_LEADER_ROLES);
        roles.add("STAFF");
        broadcastToRoles(NotificationType.ANNOUNCEMENT, "新公告",
            "发布新公告《" + e.getTitle() + "》，请前往「通知公告」查看",
            "ANNOUNCEMENT", e.getAnnouncementId(), e.getPublisherId(), roles);
        log.info("AnnouncementPublished 通知已广播给 {} 个角色", roles.size());
    }

    /**
     * 按角色集合广播：发送时展开用户快照，异步生成个人收件箱（排除发送者本人）。
     */
    private void broadcastToRoles(NotificationType type, String title, String content,
                                  String entityType, Long entityId, Long senderId,
                                  Set<String> roles) {
        if (authorization != null) {
            String permission = switch(entityType) {
                case "ARTICLE" -> type == NotificationType.ARTICLE_SUBMITTED ? "article:review" : "content:view";
                case "SIGNIN" -> "checkin:view";
                case "ANNOUNCEMENT" -> "notice:view";
                default -> "activity:view";
            };
            for (User user:userRepo.findByOrgIdOrderByIdAsc(com.pams.common.OrgContext.getOrDefault())) {
                if (!java.util.Objects.equals(user.getId(),senderId) && authorization.business(user.getId()).contains(permission)) {
                    notificationService.createAndSave(type,title,content,entityType,entityId,senderId,user.getId(),null,null);
                }
            }
            return;
        }
        for (String role : roles) {
            notificationService.createAndSave(
                type, title, content, entityType, entityId, senderId, null, role, null);
        }
        Set<Long> pushed = new java.util.LinkedHashSet<>();
        if (senderId != null) {
            pushed.add(senderId);
        }
        for (String role : roles) {
            for (User u : userRepo.findByOrgIdAndRoleCode(com.pams.common.OrgContext.getOrDefault(), role)) {
                if (!pushed.contains(u.getId())) {
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
