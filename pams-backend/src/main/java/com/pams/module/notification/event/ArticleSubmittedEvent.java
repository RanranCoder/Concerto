package com.pams.module.notification.event;

public class ArticleSubmittedEvent {
    private final Long articleId;
    private final Long activityId;
    private final String title;
    private final Long submitterId;

    public ArticleSubmittedEvent(Long articleId, Long activityId, String title, Long submitterId) {
        this.articleId = articleId;
        this.activityId = activityId;
        this.title = title;
        this.submitterId = submitterId;
    }

    public Long getArticleId() { return articleId; }
    public Long getActivityId() { return activityId; }
    public String getTitle() { return title; }
    public Long getSubmitterId() { return submitterId; }
}
