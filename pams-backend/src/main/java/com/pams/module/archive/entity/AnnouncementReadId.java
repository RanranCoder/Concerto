package com.pams.module.archive.entity;

import java.io.Serializable;

/** announcement_read 复合主键 */
public class AnnouncementReadId implements Serializable {
    private Long announcementId;
    private Long userId;

    public AnnouncementReadId() {
    }

    public AnnouncementReadId(Long announcementId, Long userId) {
        this.announcementId = announcementId;
        this.userId = userId;
    }

    public Long getAnnouncementId() { return announcementId; }
    public Long getUserId() { return userId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AnnouncementReadId that)) return false;
        return java.util.Objects.equals(announcementId, that.announcementId)
                && java.util.Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(announcementId, userId);
    }
}
