package com.pams.module.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class ActivityRequest {
    @NotBlank(message = "活动名称不能为空")
    @Size(max = 100, message = "活动名称不超过100字符")
    private String name;
    @Size(max = 100, message = "主题不超过100字符")
    private String theme;
    @Size(max = 50, message = "类型不超过50字符")
    private String type = "OTHER";
    private LocalDate startDate;
    private LocalDate endDate;
    @Size(max = 200, message = "地点不超过200字符")
    private String location;
    @Size(max = 100, message = "组织方不超过100字符")
    private String organizer;
    @Size(max = 200, message = "对象不超过200字符")
    private String targetAudience;
    @Size(max = 100, message = "主持人不超过100字符")
    private String host;
    @Size(max = 100, message = "负责人不超过100字符")
    private String leader;
    @Size(max = 2000, message = "描述不超过2000字符")
    private String description;
}
