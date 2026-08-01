package com.pams.module.activity.dto;

import lombok.Data;

@Data
public class PlanReviewRequest {
    private Boolean approved;
    private String comment;
}
