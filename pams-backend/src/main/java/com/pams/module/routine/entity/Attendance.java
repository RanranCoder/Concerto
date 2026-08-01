package com.pams.module.routine.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 考勤。本表无 deleted，不加 @SQLRestriction。status: PRESENT/ABSENT/LEAVE */
@Data
@Entity
@Table(name = "attendance")
public class Attendance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long scheduleId;
    private Long personId;
    private String personName;
    /** PRESENT/ABSENT/LEAVE */
    private String status;
    private String remark;
    private LocalDateTime recordTime;
    private LocalDateTime createdAt;
}
