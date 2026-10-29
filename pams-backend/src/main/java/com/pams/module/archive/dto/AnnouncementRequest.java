package com.pams.module.archive.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AnnouncementRequest {
    @NotBlank(message = "公告标题不能为空")
    private String title;
    @NotBlank(message = "公告内容不能为空")
    private String content;
    private LocalDateTime publishTime;
    private boolean important;
    private boolean requireConfirmation;
    private boolean importantCorrection;
    // Legacy callers retain organization-wide notification audience; new UI makes it explicit.
    private boolean audienceAll = true;
    private java.util.List<Long> audienceUserIds = java.util.List.of();
    private java.util.List<Long> audienceDeptIds = java.util.List.of();
}
