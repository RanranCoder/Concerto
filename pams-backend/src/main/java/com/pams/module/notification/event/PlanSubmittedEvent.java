package com.pams.module.notification.event;

public class PlanSubmittedEvent {
    private final Long planId;
    private final Long activityId;
    private final String planTitle;
    private final Long submitterId;

    public PlanSubmittedEvent(Long planId, Long activityId, String planTitle, Long submitterId) {
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
