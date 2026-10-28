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
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate db;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.transaction.PlatformTransactionManager transactions;
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
    @Scheduled(cron = "0 30 8 * * ?", zone = "Asia/Shanghai")
    public void remindOverdue() {
        if (db != null) {
            for (Long org : db.queryForList("SELECT id FROM organization WHERE status=1 AND deleted=0", Long.class)) {
                Long previous = com.pams.common.OrgContext.get();
                com.pams.common.OrgContext.set(org);
                try { new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(s -> doRemind()); }
                catch (Exception e) { log.error("推文截止提醒失败 org={}", org, e); }
                finally { if (previous == null) com.pams.common.OrgContext.clear(); else com.pams.common.OrgContext.set(previous); }
            }
            return;
        }
        try {
            doRemind();
        } catch (Exception e) {
            // Independent monitoring must see failures even if the notification pipeline is unavailable.
            log.error("ArticleDeadlineTask 执行失败", e);
        }
    }

    private void doRemind() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        List<Article> candidates = repository.findOverdue(now.plusDays(3));
        int reminded = 0;
        for (Article a : candidates) {
            if (a.getStatus() == Article.ArticleStatus.PENDING || a.getStatus() == Article.ArticleStatus.APPROVED) continue;
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
