package com.pams.module.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AgendaRequest {
    @NotNull(message = "活动ID不能为空")
    private Long activityId;
    @NotNull(message = "步骤序号不能为空")
    private Integer stepNo;
    @NotBlank(message = "标题不能为空")
    private String title;
    private String remark;
}
