package com.pams.module.notification.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLRestriction;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "notification")
@SQLRestriction("deleted = 0")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 500)
    private String content;

    @Column(name = "entity_type", length = 20)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "sender_id")
    private Long senderId;

    @Column(name = "recipient_id")
    private Long recipientId;

    @Column(name = "recipient_role", length = 30)
    private String recipientRole;

    @Column(name = "recipient_dept_id")
    private Long recipientDeptId;

    @Column(name = "is_read")
    private Integer isRead;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    private Integer deleted;
}
