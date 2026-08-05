package com.pams.module.notification.event;

public class ContentUploadedEvent {
    private final Long contentId;
    private final Long activityId;
    private final String title;
    private final String contentType;
    private final Long uploaderId;

    public ContentUploadedEvent(Long contentId, Long activityId, String title,
                                String contentType, Long uploaderId) {
        this.contentId = contentId;
        this.activityId = activityId;
        this.title = title;
        this.contentType = contentType;
        this.uploaderId = uploaderId;
    }

    public Long getContentId() { return contentId; }
    public Long getActivityId() { return activityId; }
    public String getTitle() { return title; }
    public String getContentType() { return contentType; }
    public Long getUploaderId() { return uploaderId; }
}
