package com.pams.module.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ArticleRequest {
    @NotBlank(message = "推文标题不能为空")
    private String title;
    private String summary;
    private String content;
    private String coverUrl;
    @NotNull(message = "推文必须关联活动")
    private Long activityId;
    @NotNull(message = "请设置任务截止时间")
    private LocalDateTime deadline;
    private Long authorId;               // 负责人
    private List<String> imageUrls;      // 长图 URL 列表
    private String articleType;
}
