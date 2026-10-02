package com.pams.module.agent.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 协作步骤（对应 V32 {@code agent_step}）。编排拓扑与协作视图的数据源。
 */
@Data
@Entity
@Table(name = "agent_step")
public class AgentStep {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long runId;
    private String agentName;
    private Integer seq;
    private Long parentStepId;

    @Column(name = "input_json", columnDefinition = "JSON")
    private String inputJson;

    @Column(name = "output_json", columnDefinition = "JSON")
    private String outputJson;

    @Column(nullable = false, length = 20)
    private String status = STATUS_RUNNING;

    private Integer durationMs;
    private Integer tokenIn;
    private Integer tokenOut;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
