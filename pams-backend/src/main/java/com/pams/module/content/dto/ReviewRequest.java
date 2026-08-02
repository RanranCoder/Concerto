package com.pams.module.content.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReviewRequest {
    @NotNull(message = "审核结论不能为空")
    private Boolean approved;
    private String comment;
}
