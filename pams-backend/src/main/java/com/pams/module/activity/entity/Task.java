package com.pams.module.activity.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLRestriction;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "task")
@SQLRestriction("deleted = 0")
public class Task {
    public enum TaskStatus { TODO, DOING, DONE, DELAYED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long activityId;
    private String name;
    private Long deptId;
    private String assignee;
    private LocalDate startDate;
    private LocalDate endDate;
    private Long dependsOn;
    private Integer isMilestone;
    private Integer progress;
    @Enumerated(EnumType.STRING)
    private TaskStatus status;
    private Integer priority;
    @Column(columnDefinition = "TEXT")
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
}
