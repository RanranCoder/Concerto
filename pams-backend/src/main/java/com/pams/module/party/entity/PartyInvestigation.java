package com.pams.module.party.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "party_investigation")
public class PartyInvestigation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
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
    private LocalDateTime createdAt;
}
