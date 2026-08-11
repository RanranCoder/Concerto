package com.pams.module.notification.event;

import java.time.LocalDateTime;

public class ArticleDeadlineReminderEvent {
    private final Long articleId;
    private final Long activityId;
    private final String title;
    private final Long authorId;
    private final LocalDateTime deadline;

    public ArticleDeadlineReminderEvent(Long articleId, Long activityId, String title,
                                        Long authorId, LocalDateTime deadline) {
        this.articleId = articleId;
        this.activityId = activityId;
        this.title = title;
        this.authorId = authorId;
        this.deadline = deadline;
    }

    public Long getArticleId() { return articleId; }
    public Long getActivityId() { return activityId; }
    public String getTitle() { return title; }
    public Long getAuthorId() { return authorId; }
    public LocalDateTime getDeadline() { return deadline; }
}
