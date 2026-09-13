package com.pams.module.archive.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 公告已读记录（PRD-v5 F4：已读状态落库，跨设备一致） */
@Data
@Entity
@Table(name = "announcement_read")
@IdClass(AnnouncementReadId.class)
public class AnnouncementRead {
    @Id
    private Long announcementId;
    @Id
    private Long userId;
    @Column(insertable = false, updatable = false)
    private LocalDateTime readAt;
}
