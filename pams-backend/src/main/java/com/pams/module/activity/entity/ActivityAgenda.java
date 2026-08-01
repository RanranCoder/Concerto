package com.pams.module.activity.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "activity_agenda")
public class ActivityAgenda {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long activityId;
    private Integer stepNo;
    private String title;
    private String remark;
    private LocalDateTime createdAt;
}
