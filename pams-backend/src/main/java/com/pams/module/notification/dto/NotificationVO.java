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
    private Long activityId;
    private String actionState;
    private boolean notify;
    private String senderName;
    private String priority;
    private boolean read;
    private LocalDateTime createdAt;
}
