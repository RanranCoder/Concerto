package com.pams.module.party.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "party_roster")
public class PartyRoster {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String rosterType;
    private String issueNo;
    private String name;
    private String gender;
    private String studentNo;
    private String className;
    private String branchName;
    private String remark;
    private LocalDateTime createdAt;
}
