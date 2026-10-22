package com.pams.module.activity.entity;
import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "activity_plan")
public class ActivityPlan implements OrgScoped {
    public enum PlanStatus { DRAFT, PENDING, APPROVED, REJECTED, WITHDRAWN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long activityId;
    private Integer version;
    /** Immutable DOCX upload; rich text columns are derived indexes for legacy tools/search. */
    private Long documentFileId;
    @Column(columnDefinition = "LONGTEXT")
    private String documentText;
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
    private Long ownerId;
    @Version
    private Long revision;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "activity_plan_review", joinColumns = @JoinColumn(name = "plan_id"))
    private java.util.List<PlanReviewer> reviewers = new java.util.ArrayList<>();
    private Long reviewerId;
    @Column(columnDefinition = "TEXT")
    private String reviewComment;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @org.hibernate.annotations.TenantId
    private Long orgId;
}
