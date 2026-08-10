package com.pams.module.schedule.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 批量导入生成的无课表（按部门+学期只保留最新一份）。gridJson 为 NoClassScheduleRowVO 列表的 JSON。 */
@Data
@Entity
@Table(name = "no_class_schedule")
public class NoClassScheduleRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long deptId;
    private String deptName;
    @Column(nullable = false, length = 20)
    private String semester;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String gridJson;
    private LocalDateTime createdAt;
}
