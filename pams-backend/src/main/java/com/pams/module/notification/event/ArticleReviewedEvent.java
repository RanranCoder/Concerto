package com.pams.module.notification.event;

public class ArticleReviewedEvent {
    private final Long articleId;
    private final Long activityId;
    private final String title;
    private final boolean approved;
    private final String comment;
    private final Long authorId;
    private final Long reviewerId;

    public ArticleReviewedEvent(Long articleId, Long activityId, String title,
                                boolean approved, String comment, Long authorId, Long reviewerId) {
        this.articleId = articleId;
        this.activityId = activityId;
        this.title = title;
        this.approved = approved;
        this.comment = comment;
        this.authorId = authorId;
        this.reviewerId = reviewerId;
    }

    public Long getArticleId() { return articleId; }
    public Long getActivityId() { return activityId; }
    public String getTitle() { return title; }
    public boolean isApproved() { return approved; }
    public String getComment() { return comment; }
    public Long getAuthorId() { return authorId; }
    public Long getReviewerId() { return reviewerId; }
}
