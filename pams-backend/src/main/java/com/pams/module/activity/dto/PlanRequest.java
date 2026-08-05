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
    private String nameOverride;
    private String themeOverride;
    private String timeOverride;
    private String locationOverride;
    private String organizerOverride;
    private String targetOverride;
    /** 章节顺序 + 自定义节名 JSON，如 [{label,field,customLabel}] */
    private String sectionOrder;
    /** 是否同步更新活动基本信息（用户弹窗确认） */
    private boolean syncActivity;
}
