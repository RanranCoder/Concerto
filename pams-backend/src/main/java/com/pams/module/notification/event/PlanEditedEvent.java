package com.pams.module.notification.event;

public class PlanEditedEvent {
    private final Long planId;
    private final Long activityId;
    private final String planTitle;
    private final Long editorId;

    public PlanEditedEvent(Long planId, Long activityId, String planTitle, Long editorId) {
        this.planId = planId;
        this.activityId = activityId;
        this.planTitle = planTitle;
        this.editorId = editorId;
    }

    public Long getPlanId() { return planId; }
    public Long getActivityId() { return activityId; }
    public String getPlanTitle() { return planTitle; }
    public Long getEditorId() { return editorId; }
}
