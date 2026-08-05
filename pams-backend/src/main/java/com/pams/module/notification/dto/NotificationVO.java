package com.pams.module.notification.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class NotificationVO {
    private Long id;
    private String type;
    private String title;
    private String content;
    private String entityType;
    private Long entityId;
    private String senderName;
    private boolean read;
    private LocalDateTime createdAt;
}
