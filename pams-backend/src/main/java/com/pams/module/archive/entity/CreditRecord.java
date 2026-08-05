package com.pams.module.archive.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "credit_record")
public class CreditRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    @Column(nullable = false, length = 50)
    private String personName;
    @Column(length = 20)
    private String studentNo;
    private Long activityId;
    private Long sourceActivityId;
    private String batchId;
    @Column(nullable = false, length = 100)
    private String project;
    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal credit;
    @Column(length = 30)
    private String basis;
    @Column(length = 200)
    private String remark;
    private Long recordBy;
    private LocalDateTime createdAt;
}
