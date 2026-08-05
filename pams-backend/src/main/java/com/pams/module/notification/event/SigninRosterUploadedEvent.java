package com.pams.module.notification.event;

public class SigninRosterUploadedEvent {
    private final Long activityId;
    private final Long uploaderId;

    public SigninRosterUploadedEvent(Long activityId, Long uploaderId) {
        this.activityId = activityId;
        this.uploaderId = uploaderId;
    }

    public Long getActivityId() { return activityId; }
    public Long getUploaderId() { return uploaderId; }
}
