package com.pams.module.content.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ArticleRequest {
    @NotBlank(message = "推文标题不能为空")
    private String title;
    private String summary;
    private String content;
    private String coverUrl;
    private Long activityId;
    private String articleType;
}
