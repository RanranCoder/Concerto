package com.pams.module.activity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PlanRequest {
    @NotNull(message = "活动ID不能为空")
    private Long activityId;
    private Integer version;
    private String background;
    private String purpose;
    private String content;
    /** JSON 字符串，如 [{step,detail}] */
    private String flow;
    private String notice;
    private String emergency;
    /** JSON 字符串，如 [{item,quantity,unitPrice,totalPrice}] */
    private String budget;
}
