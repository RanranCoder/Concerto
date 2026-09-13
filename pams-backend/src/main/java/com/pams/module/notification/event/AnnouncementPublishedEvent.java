package com.pams.module.notification.event;

/** 新公告发布（PRD-v5 F4：发布公告触发通知中心广播） */
public class AnnouncementPublishedEvent {
    private final Long announcementId;
    private final String title;
    private final Long publisherId;

    public AnnouncementPublishedEvent(Long announcementId, String title, Long publisherId) {
        this.announcementId = announcementId;
        this.title = title;
        this.publisherId = publisherId;
    }

    public Long getAnnouncementId() { return announcementId; }
    public String getTitle() { return title; }
    public Long getPublisherId() { return publisherId; }
}
