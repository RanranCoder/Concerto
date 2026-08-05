package com.pams.module.notification.event;

public class PlanReviewedEvent {
    private final Long planId;
    private final Long activityId;
    private final String planTitle;
    private final Long reviewerId;
    private final boolean approved;
    private final String comment;
    private final Long submitterId;

    public PlanReviewedEvent(Long planId, Long activityId, String planTitle,
                             Long reviewerId, boolean approved, String comment, Long submitterId) {
        this.planId = planId;
        this.activityId = activityId;
        this.planTitle = planTitle;
        this.reviewerId = reviewerId;
        this.approved = approved;
        this.comment = comment;
        this.submitterId = submitterId;
    }

    public Long getPlanId() { return planId; }
    public Long getActivityId() { return activityId; }
    public String getPlanTitle() { return planTitle; }
    public Long getReviewerId() { return reviewerId; }
    public boolean isApproved() { return approved; }
    public String getComment() { return comment; }
    public Long getSubmitterId() { return submitterId; }
}
