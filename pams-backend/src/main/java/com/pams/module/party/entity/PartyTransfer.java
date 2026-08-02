package com.pams.module.party.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "party_transfer")
public class PartyTransfer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
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
    private LocalDateTime createdAt;
}
