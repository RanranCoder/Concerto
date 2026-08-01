package com.pams.module.routine.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 无课表。本表无 deleted，不加 @SQLRestriction。freeWeeks 为 JSON 文本，如 [1,3,5] 或 {start:1,end:18} */
@Data
@Entity
@Table(name = "free_schedule")
public class FreeSchedule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    private String personName;
    private String className;
    private Long deptId;
    @Column(columnDefinition = "TEXT")
    private String freeWeeks;
    private String note;
    private LocalDateTime createdAt;
}
