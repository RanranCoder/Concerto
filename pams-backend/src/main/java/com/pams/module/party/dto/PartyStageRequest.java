package com.pams.module.party.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PartyStageRequest {
    @NotBlank(message = "阶段不能为空")
    private String stage;
    private String issueNo;
    private String startDate;
    private String endDate;
    private String remark;
}
