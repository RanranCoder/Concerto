package com.pams.module.routine.entity;
import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 考勤。本表无 deleted，不加 @SQLRestriction。
 * status 状态：PENDING待签到/NORMAL正常/LATE迟到/ABSENT缺勤/LEAVE请假/
 * EARLY_LEAVE早退/MAKEUP补签/CANCELED取消。
 * statusSource：AUTO 自动判断 / MANUAL 人工修改（MANUAL 不被自动任务覆盖）。
 */
@Data
@Entity
@Table(name = "attendance")
public class Attendance implements OrgScoped {
    /** 兼容老数据：PRESENT 视作 NORMAL */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_NORMAL = "NORMAL";
    public static final String STATUS_LATE = "LATE";
    public static final String STATUS_ABSENT = "ABSENT";
    public static final String STATUS_LEAVE = "LEAVE";
    public static final String STATUS_EARLY_LEAVE = "EARLY_LEAVE";
    public static final String STATUS_MAKEUP = "MAKEUP";
    public static final String STATUS_CANCELED = "CANCELED";

    public static final String SOURCE_AUTO = "AUTO";
    public static final String SOURCE_MANUAL = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long scheduleId;
    private Long sessionId;
    private Long personId;
    private String personName;
    private String studentNo;
    /** 见类注释 */
    private String status;
    /** AUTO/MANUAL */
    private String statusSource;
    private String remark;
    /** 有效签到时间（判定正常/迟到） */
    private LocalDateTime checkedInAt;
    private LocalDateTime recordTime;
    private LocalDateTime createdAt;
    private Long orgId;
}
