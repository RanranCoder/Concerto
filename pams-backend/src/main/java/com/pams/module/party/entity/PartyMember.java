package com.pams.module.party.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLRestriction;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "party_member")
@SQLRestriction("deleted = 0")
public class PartyMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
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
    @Column(columnDefinition = "TEXT")
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
}
