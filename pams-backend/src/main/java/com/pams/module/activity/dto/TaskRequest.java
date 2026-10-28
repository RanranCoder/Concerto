package com.pams.module.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class TaskRequest {
    @NotNull(message = "活动ID不能为空")
    private Long activityId;
    @NotBlank(message = "任务名称不能为空")
    @Size(max = 100, message = "任务名称不超过100字")
    private String name;
    private Long deptId;
    @Size(max = 50, message = "负责人不超过50字")
    private String assignee;
    private Long assigneeUserId;
    private LocalDate startDate;
    private LocalDate endDate;
    private Long dependsOn;
    private Integer isMilestone;
    private Integer progress;
    private String status;
    private Integer priority;
    @Size(max = 2000, message = "任务说明不超过2000字")
    private String description;
    private Long expectedRevision;
}
