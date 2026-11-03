package com.pams.module.routine.entity;
import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 排班。本表无 deleted，不加 @SQLRestriction。
 * persons 为服务层组装（@Transient），避免懒加载问题。
 */
@Data
@Entity
@Table(name = "schedule")
public class Schedule implements OrgScoped {
    /** 排班类型常量：活动排班（type=ACTIVITY，使用 activityId+scheduleDate+startTime/endTime） */
    public static final String TYPE_ACTIVITY = "ACTIVITY";
    /** 排班状态 */
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_ENDED = "ENDED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    /** 生成来源 */
    public static final String SOURCE_AUTO = "AUTO";
    public static final String SOURCE_MANUAL = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version
    private Long revision = 0L;
    /** SMOKING_CURB/CLASS_DUTY/BOOTH/ARCHIVE/STAMP/CLASS_CHECK/ACTIVITY */
    private String scheduleType;
    private Long activityId;
    /** 周次 */
    private Integer weekNo;
    /** 1-7 周一~周日 */
    private Integer weekday;
    /** 节次或时间段，如 上午第1-2节 / 9:00-9:10 */
    private String sessionName;
    private LocalTime startTime;
    private LocalTime endTime;
    /** 排班状态：DRAFT/PUBLISHED/ENDED/CANCELLED（仅 PUBLISHED 进入签到考勤） */
    private String status;
    /** 生成来源：AUTO 无课表自动 / MANUAL 手动 */
    private String source;
    private String semester;
    private Long deptId;
    private String deptName;
    private String location;
    private LocalDate scheduleDate;
    @Column(columnDefinition = "TEXT")
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 值班人员（服务层组装，非持久化） */
    @Transient
    private List<SchedulePerson> persons = new ArrayList<>();
    @org.hibernate.annotations.TenantId
    private Long orgId;
}
