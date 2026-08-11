package com.pams.module.notification.event;

public class ArticleAssignedEvent {
    private final Long articleId;
    private final Long activityId;
    private final String title;
    private final Long assigneeId;
    private final Long creatorId;

    public ArticleAssignedEvent(Long articleId, Long activityId, String title,
                                Long assigneeId, Long creatorId) {
        this.articleId = articleId;
        this.activityId = activityId;
        this.title = title;
        this.assigneeId = assigneeId;
        this.creatorId = creatorId;
    }

    public Long getArticleId() { return articleId; }
    public Long getActivityId() { return activityId; }
    public String getTitle() { return title; }
    public Long getAssigneeId() { return assigneeId; }
    public Long getCreatorId() { return creatorId; }
}
