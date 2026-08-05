package com.pams.module.seat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SeatLayoutRequest {

    private Long activityId;

    @NotBlank(message = "布局名称不能为空")
    @Size(max = 100)
    private String name;

    @Min(value = 1, message = "行数至少 1")
    @Max(value = 100, message = "行数最多 100")
    private Integer rows = 10;

    @Min(value = 1, message = "列数至少 1")
    @Max(value = 100, message = "列数最多 100")
    private Integer cols = 10;

    /** 过道列索引，逗号分隔，如 "5,12" */
    private String aisleCols;

    private java.math.BigDecimal aisleWidthRatio = new java.math.BigDecimal("1.5");

    /** 格子状态 JSON 数组字符串 */
    private String seatData;

    /** 自定义配色 JSON 数组字符串 [{color,label}] */
    private String colorLabels;

    /** 模板相关 */
    private Boolean asTemplate;
    private String templateCategory;
}
