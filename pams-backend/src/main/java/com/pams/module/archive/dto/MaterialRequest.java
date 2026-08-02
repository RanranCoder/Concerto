package com.pams.module.archive.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MaterialRequest {
    @NotBlank(message = "材料名称不能为空")
    private String name;
    @NotBlank(message = "材料类型不能为空")
    private String bizType;
    private Long activityId;
    private Long deptId;
    private String tag;
    private String description;
    private Long fileId;
}
