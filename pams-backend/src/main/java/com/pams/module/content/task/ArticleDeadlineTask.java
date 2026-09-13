package com.pams.module.content.task;

import com.pams.module.content.entity.Article;
import com.pams.module.content.repository.ArticleRepository;
import com.pams.module.notification.entity.NotificationType;
import com.pams.module.notification.event.ArticleDeadlineReminderEvent;
import com.pams.module.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class ArticleDeadlineTask {
    private static final Logger log = LoggerFactory.getLogger(ArticleDeadlineTask.class);
    private final ArticleRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationService notificationService;

    public ArticleDeadlineTask(ArticleRepository repository, ApplicationEventPublisher eventPublisher,
                               NotificationService notificationService) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.notificationService = notificationService;
    }

    /** 每天 8:30 扫描：未发布且 3 天内到期的推文，向负责人发截止提醒（每天一次，去重） */
    @Transactional
    @Scheduled(cron = "0 30 8 * * ?")
    public void remindOverdue() {
        try {
            doRemind();
        } catch (Exception e) {
            // X2：定时任务异常不再静默——结构化日志 + SYSTEM 通知指导老师（role 广播）
            log.error("ArticleDeadlineTask 执行失败", e);
            try {
                notificationService.createAndSave(NotificationType.SYSTEM,
                        "定时任务执行失败：推文截止提醒",
                        "每日 8:30 的推文截止提醒任务执行失败：" + e.getMessage()
                                + "。当日提醒可能不完整，请检查服务日志并手动核实临期推文。",
                        "TASK", null, null, null, "TEACHER", null);
            } catch (Exception notifyEx) {
                log.error("定时任务失败告警通知发送失败", notifyEx);
            }
        }
    }

    private void doRemind() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        List<Article> candidates = repository.findOverdue(now.plusDays(3));
        int reminded = 0;
        for (Article a : candidates) {
            if (a.getDeadlineRemindedAt() != null
                    && a.getDeadlineRemindedAt().toLocalDate().equals(today)) {
                continue; // 今天已提醒过
            }
            if (a.getAuthorId() != null) {
                eventPublisher.publishEvent(new ArticleDeadlineReminderEvent(
                        a.getId(), a.getActivityId(), a.getTitle(), a.getAuthorId(), a.getDeadline()));
                reminded++;
            }
            a.setDeadlineRemindedAt(now);
            repository.save(a);
        }
        if (reminded > 0) {
            log.info("ArticleDeadlineTask 提醒 {} 条截止推文", reminded);
        }
    }
}
