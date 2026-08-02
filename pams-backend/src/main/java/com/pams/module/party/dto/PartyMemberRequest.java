package com.pams.module.party.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

@Data
public class PartyMemberRequest {
    @NotBlank(message = "姓名不能为空")
    private String name;
    private String gender;
    private String nation;
    private String idCard;
    private LocalDate birthDate;
    private String nativePlace;
    private String education;
    private String phone;
    private String homeAddress;
    private String className;
    private String college;
    private String branchName;
    private String politicalStatus;
    private String studentNo;
    private String remark;
}
