package com.pams.module.routine.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 排班人员。本表无 deleted，不加 @SQLRestriction。 */
@Data
@Entity
@Table(name = "schedule_person")
public class SchedulePerson {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long scheduleId;
    private Long userId;
    private String personName;
    /** 1 主班 / 0 副班 */
    private Integer isPrimary;
    private LocalDateTime createdAt;
}
