package com.pams.module.notification.event;

public class ArticlePublishedEvent {
    private final Long articleId;
    private final Long activityId;
    private final String title;
    private final Long publisherId;

    public ArticlePublishedEvent(Long articleId, Long activityId, String title, Long publisherId) {
        this.articleId = articleId;
        this.activityId = activityId;
        this.title = title;
        this.publisherId = publisherId;
    }

    public Long getArticleId() { return articleId; }
    public Long getActivityId() { return activityId; }
    public String getTitle() { return title; }
    public Long getPublisherId() { return publisherId; }
}
