package com.pams.module.activity.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "sign_in_group")
public class SignInGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long activityId;
    private String groupName;
    private String sourceFilename;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
