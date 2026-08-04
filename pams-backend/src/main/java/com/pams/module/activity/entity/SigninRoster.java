package com.pams.module.activity.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "signin_roster")
public class SigninRoster {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long activityId;
    @Column(columnDefinition = "TEXT")
    private String fieldsJson;
    private LocalDateTime createdAt;
}
