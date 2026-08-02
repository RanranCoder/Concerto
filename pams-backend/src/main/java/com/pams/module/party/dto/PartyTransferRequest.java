package com.pams.module.party.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class PartyTransferRequest {
    @NotNull(message = "成员不能为空")
    private Long memberId;
    private String className;
    private String name;
    private String gender;
    private String nation;
    private Integer isProbationary;
    private String idCard;
    private String receiveOrg;
    private String phone;
    private String wechat;
    private Integer isOnline;
    private LocalDate signDate;
    private String remark;
}
