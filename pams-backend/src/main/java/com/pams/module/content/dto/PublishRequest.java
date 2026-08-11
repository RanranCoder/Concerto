package com.pams.module.content.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PublishRequest {
    @NotBlank(message = "公众号链接不能为空")
    private String wxUrl;
}
