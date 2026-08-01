package com.pams.module.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ScoreRuleRequest {
    @NotNull(message = "活动ID不能为空")
    private Long activityId;
    @NotBlank(message = "评分维度不能为空")
    private String dimensionName;
    @NotNull(message = "满分不能为空")
    private Integer fullMarks;
    private Integer sortOrder;
}
