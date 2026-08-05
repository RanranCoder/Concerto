package com.pams.module.chat.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLRestriction;
import java.time.LocalDateTime;

/**
 * 群聊（PRD F06）
 */
@Data
@Entity
@Table(name = "group_chat")
@SQLRestriction("deleted = 0")
public class GroupChat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "activity_id")
    private Long activityId;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "qr_code_url", length = 500)
    private String qrCodeUrl;

    @Column(length = 200)
    private String remark;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private Integer deleted;
}
