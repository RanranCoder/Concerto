package com.pams.module.activity.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLRestriction;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "activity")
@SQLRestriction("deleted = 0")
public class Activity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String theme;
    private String type;
    @Enumerated(EnumType.STRING)
    private ActivityStatus status;
    private LocalDate startDate;
    private LocalDate endDate;
    private String location;
    private String organizer;
    private String targetAudience;
    private String host;
    private String leader;
    @Column(columnDefinition = "TEXT")
    private String description;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
}
