package com.pams.module.party.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "party_register")
public class PartyRegister {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
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
    @Column(columnDefinition = "TEXT")
    private String conditionNote;
    @Column(columnDefinition = "TEXT")
    private String remark;
    private LocalDateTime createdAt;
}
