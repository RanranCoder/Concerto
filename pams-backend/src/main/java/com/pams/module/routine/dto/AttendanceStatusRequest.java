package com.pams.module.routine.dto;

import lombok.Data;

/** 人工修改考勤状态请求。 */
@Data
public class AttendanceStatusRequest {
    private String newStatus;
    private String remark;
}