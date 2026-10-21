package com.pams.module.notification.event;

public class PlanSubmittedEvent {
    private final java.util.List<Long> reviewerIds;
    public java.util.List<Long> getReviewerIds() { return reviewerIds; }
    private final Long planId;
    private final Long activityId;
    private final String planTitle;
    private final Long submitterId;

    public PlanSubmittedEvent(Long planId, Long activityId, String planTitle, Long submitterId,java.util.List<Long> reviewerIds) {
        this.reviewerIds=java.util.List.copyOf(reviewerIds);
        this.planId = planId;
        this.activityId = activityId;
        this.planTitle = planTitle;
        this.submitterId = submitterId;
    }

    public Long getPlanId() { return planId; }
    public Long getActivityId() { return activityId; }
    public String getPlanTitle() { return planTitle; }
    public Long getSubmitterId() { return submitterId; }
}
