package com.pams.module.archive.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TemplateRequest {
    @NotBlank(message = "模板名称不能为空")
    private String name;
    @NotBlank(message = "模板分类不能为空")
    private String category;
    private String description;
    private Long fileId;
}
