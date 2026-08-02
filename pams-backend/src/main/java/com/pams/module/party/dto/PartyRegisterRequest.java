package com.pams.module.party.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class PartyRegisterRequest {
    @NotNull(message = "成员不能为空")
    private Long memberId;
    private String college;
    private String branch;
    private String className;
    private String name;
    private String gender;
    private LocalDate birthDate;
    private String nativePlace;
    private String nation;
    private String idCard;
    private String phone;
    private String homeAddress;
    private LocalDate applyDate;
    private String education;
    private String talkPerson;
    private String conditionNote;
    private String remark;
}
