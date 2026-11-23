package com.pams.module.routine.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 考勤保存请求。status: PRESENT/ABSENT/LEAVE */
@Data
public class AttendanceRequest {
    @NotNull(message = "排班ID不能为空")
    private Long scheduleId;
    private Long personId;
    private String personName;
    private String status;
    private String remark;
}
