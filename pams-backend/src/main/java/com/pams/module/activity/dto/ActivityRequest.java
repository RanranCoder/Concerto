package com.pams.module.activity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

@Data
public class ActivityRequest {
    @NotBlank(message = "活动名称不能为空")
    private String name;
    private String theme;
    private String type = "OTHER";
    private LocalDate startDate;
    private LocalDate endDate;
    private String location;
    private String organizer;
    private String targetAudience;
    private String host;
    private String leader;
    private String description;
}
