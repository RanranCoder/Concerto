package com.pams.module.archive.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreditRequest {
    private Long userId;
    @NotBlank(message = "姓名不能为空")
    private String personName;
    private String studentNo;
    private Long activityId;
    @NotBlank(message = "加分项目不能为空")
    private String project;
    @NotNull(message = "分数不能为空")
    private BigDecimal credit;
    private String basis;
    private String remark;
}
