package com.pams.module.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SeatRequest {
    @NotNull(message = "活动ID不能为空")
    private Long activityId;
    private String roomName;
    @NotBlank(message = "区域不能为空")
    private String zone;
    private Integer rowNo;
    private Integer colNo;
    private String personName;
    private String seatType;
}
