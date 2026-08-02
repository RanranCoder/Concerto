package com.pams.module.content.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class NewsRequest {
    @NotBlank(message = "新闻稿标题不能为空")
    private String title;
    private String subtitle;
    private String content;
    private Long activityId;
}
