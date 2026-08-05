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
    @Column(columnDefinition = "TEXT")
    private String nameOverride;
    @Column(columnDefinition = "TEXT")
    private String themeOverride;
    @Column(columnDefinition = "TEXT")
    private String timeOverride;
    @Column(columnDefinition = "TEXT")
    private String locationOverride;
    @Column(columnDefinition = "TEXT")
    private String organizerOverride;
    @Column(columnDefinition = "TEXT")
    private String targetOverride;
    @Column(columnDefinition = "TEXT")
    private String sectionOrder;
    @Enumerated(EnumType.STRING)
    private PlanStatus status;
    private Long submitterId;
    private Long reviewerId;
    @Column(columnDefinition = "TEXT")
    private String reviewComment;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
