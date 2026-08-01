package com.pams.module.routine.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 排班。本表无 deleted，不加 @SQLRestriction。
 * persons 为服务层组装（@Transient），避免懒加载问题。
 */
@Data
@Entity
@Table(name = "schedule")
public class Schedule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /** SMOKING_CURB/CLASS_DUTY/BOOTH/ARCHIVE/STAMP/CLASS_CHECK */
    private String scheduleType;
    private Long activityId;
    /** 周次 */
    private Integer weekNo;
    /** 1-7 周一~周日 */
    private Integer weekday;
    /** 节次或时间段，如 上午第1-2节 / 9:00-9:10 */
    private String sessionName;
    private String location;
    private LocalDate scheduleDate;
    @Column(columnDefinition = "TEXT")
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 值班人员（服务层组装，非持久化） */
    @Transient
    private List<SchedulePerson> persons = new ArrayList<>();
}
