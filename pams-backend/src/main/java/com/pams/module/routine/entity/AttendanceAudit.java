package com.pams.module.routine.entity;
import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 人工考勤修改审计：记录每次人工改状态的原/新状态、操作人、时间、备注。 */
@Data
@Entity
@Table(name = "attendance_audit")
public class AttendanceAudit implements OrgScoped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long attendanceId;
    private String oldStatus;
    private String newStatus;
    private Long operatorId;
    private String operatorName;
    private LocalDateTime operateTime;
    private String remark;
    private LocalDateTime createdAt;
    @org.hibernate.annotations.TenantId
    private Long orgId;
}