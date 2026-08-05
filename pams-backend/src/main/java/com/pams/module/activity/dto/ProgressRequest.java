package com.pams.module.activity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ProgressRequest {
    @NotNull(message = "进度不能为空")
    @Min(value = 0, message = "进度不小于0")
    @Max(value = 100, message = "进度不超过100")
    private Integer progress;
}
