package com.pams.module.party.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PartyRosterRequest {
    @NotBlank(message = "名单类型不能为空")
    private String rosterType;
    private String issueNo;
    @NotBlank(message = "姓名不能为空")
    private String name;
    private String gender;
    private String studentNo;
    private String className;
    private String branchName;
    private String remark;
}
