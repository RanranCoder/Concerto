package com.pams.module.routine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 考勤保存请求。status: PRESENT/ABSENT/LEAVE */
@Data
public class AttendanceRequest {
    @NotNull(message = "排班ID不能为空")
    private Long scheduleId;
    private Long personId;
    @NotBlank(message = "人员姓名不能为空")
    private String personName;
    @NotBlank(message = "考勤状态不能为空")
    private String status;
    private String remark;
}
