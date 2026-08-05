package com.pams.module.activity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class StatusChangeRequest {
    @NotBlank(message = "目标状态不能为空")
    private String status;
}
