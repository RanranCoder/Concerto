package com.pams.module.activity.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "signin_field_config")
public class SigninFieldConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long activityId;
    private String fieldName;
    private String fieldKey;
    private Integer required;
    private String fieldType;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
