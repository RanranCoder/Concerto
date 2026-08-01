package com.pams.module.activity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PlanReviewRequest {
    @NotNull(message = "审核结论不能为空")
    private Boolean approved;
    private String comment;
}
