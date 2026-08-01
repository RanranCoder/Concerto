package com.pams.module.activity.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "activity_plan")
public class ActivityPlan {
    public enum PlanStatus { DRAFT, PENDING, APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long activityId;
    private Integer version;
    @Column(columnDefinition = "TEXT")
    private String background;
    @Column(columnDefinition = "TEXT")
    private String purpose;
    @Column(columnDefinition = "TEXT")
    private String content;
    @Column(columnDefinition = "TEXT")
    private String flow;
    @Column(columnDefinition = "TEXT")
    private String notice;
    @Column(columnDefinition = "TEXT")
    private String emergency;
    @Column(columnDefinition = "TEXT")
    private String budget;
    @Enumerated(EnumType.STRING)
    private PlanStatus status;
    private Long submitterId;
    private Long reviewerId;
    @Column(columnDefinition = "TEXT")
    private String reviewComment;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
