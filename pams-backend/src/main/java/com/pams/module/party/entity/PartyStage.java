package com.pams.module.party.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "party_stage")
public class PartyStage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long memberId;
    @Enumerated(EnumType.STRING)
    private PartyStageType stage;
    private String issueNo;
    private String status;
    private LocalDate startDate;
    private LocalDate endDate;
    private String remark;
    private LocalDateTime createdAt;
}
