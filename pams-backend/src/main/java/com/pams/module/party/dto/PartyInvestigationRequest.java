package com.pams.module.party.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PartyInvestigationRequest {
    @NotNull(message = "成员不能为空")
    private Long memberId;
    private String fatherName;
    private String fatherBranch;
    private String fatherBranchAddr;
    private String motherName;
    private String motherBranch;
    private String motherBranchAddr;
    private String relativeName;
    private String relativeBranch;
    private String relativeBranchAddr;
}
