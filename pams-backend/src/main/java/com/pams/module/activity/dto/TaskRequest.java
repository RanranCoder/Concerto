package com.pams.module.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class TaskRequest {
    @NotNull(message = "活动ID不能为空")
    private Long activityId;
    @NotBlank(message = "任务名称不能为空")
    private String name;
    private Long deptId;
    private String assignee;
    private LocalDate startDate;
    private LocalDate endDate;
    private Long dependsOn;
    private Integer isMilestone;
    private Integer progress;
    private String status;
    private Integer priority;
    private String description;
}
