package com.pams.module.agent.entity;

import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对比评估实验的一条记录（对应 V33 {@code agent_benchmark}）。
 */
@Data
@Entity
@Table(name = "agent_benchmark")
public class AgentBenchmark implements OrgScoped {

    public static final String ARM_SINGLE = "SINGLE";
    public static final String ARM_MULTI = "MULTI";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String batchId;
    private String taskCode;
    private String taskText;
    private String arm;
    private String mode;
    private Long runId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private Integer steps = 0;

    @Column(nullable = false)
    private Integer agents = 0;

    @Column(name = "tool_calls", nullable = false)
    private Integer toolCalls = 0;

    @Column(nullable = false)
    private Integer artifacts = 0;

    @Column(name = "review_pass")
    private Integer reviewPass;

    @Column(name = "duration_ms")
    private Integer durationMs;

    private Long orgId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
