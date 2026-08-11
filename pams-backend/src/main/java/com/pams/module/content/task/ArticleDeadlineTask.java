package com.pams.module.content.task;

import com.pams.module.content.entity.Article;
import com.pams.module.content.repository.ArticleRepository;
import com.pams.module.notification.event.ArticleDeadlineReminderEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class ArticleDeadlineTask {
    private static final Logger log = LoggerFactory.getLogger(ArticleDeadlineTask.class);
    private final ArticleRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    public ArticleDeadlineTask(ArticleRepository repository, ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    /** 每天 8:30 扫描：未发布且 3 天内到期的推文，向负责人发截止提醒（每天一次，去重） */
    @Scheduled(cron = "0 30 8 * * ?")
    public void remindOverdue() {
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
