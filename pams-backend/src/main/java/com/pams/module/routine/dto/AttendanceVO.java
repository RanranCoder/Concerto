package com.pams.module.routine.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 考勤中心列表行（含活动/场次展开）。 */
@Data
public class AttendanceVO {
    private Long id;
    private Long scheduleId;
    private Long sessionId;
    private Long personId;
    private String personName;
    private String studentNo;
    /** PENDING/NORMAL/LATE/ABSENT/LEAVE/EARLY_LEAVE/MAKEUP/CANCELED */
    private String status;
    /** AUTO/MANUAL */
    private String statusSource;
    private String remark;
    private LocalDateTime checkedInAt;
    private LocalDateTime recordTime;
    /** 活动信息展开 */
    private Long activityId;
    private String activityName;
    private String activityType;
    private LocalDate activityDate;
    private LocalTime activityStart;
    private LocalTime activityEnd;
    private String sessionName;
    /** 该场次/排班的签到次数 */
    private long signCount;
}