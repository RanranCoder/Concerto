package com.pams.module.activity.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "score_record")
public class ScoreRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long activityId;
    private String teamName;
    private String groupName;
    @Column(columnDefinition = "TEXT")
    private String dimensionScores;
    private Integer total;
    private Integer rankNo;
    private String remark;
    private LocalDateTime createdAt;
}
