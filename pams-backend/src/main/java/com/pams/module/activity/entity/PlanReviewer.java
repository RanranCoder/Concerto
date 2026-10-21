package com.pams.module.activity.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** A submission's fixed reviewer list and individual decisions. */
@Data
@Embeddable
public class PlanReviewer {
    @Column(name = "reviewer_id", nullable = false)
    private Long userId;
    private String name;
    private Boolean approved;
    @Column(columnDefinition = "TEXT")
    private String comment;
    private LocalDateTime reviewedAt;
}
