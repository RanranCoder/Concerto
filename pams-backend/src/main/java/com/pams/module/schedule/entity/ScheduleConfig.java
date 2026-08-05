package com.pams.module.schedule.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalTime;

/**
 * 时间格配置（PRD F08.4）
 */
@Data
@Entity
@Table(name = "schedule_config")
public class ScheduleConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Integer period;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(length = 20)
    private String label;
}
