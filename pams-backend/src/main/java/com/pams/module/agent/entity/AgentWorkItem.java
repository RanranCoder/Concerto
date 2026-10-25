package com.pams.module.agent.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** Durable logical node. AgentStep records each immutable execution attempt. */
@Data
@Entity
@Table(name = "agent_work_item", uniqueConstraints = @UniqueConstraint(columnNames = {"run_id", "node_key"}))
public class AgentWorkItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long runId;
    private int nodeKey;
    private String agentName;
    @Column(columnDefinition = "TEXT", nullable = false)
    private String instruction;
    @Column(columnDefinition = "JSON", nullable = false)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String dependenciesJson;
    @Column(nullable = false, length = 24)
    private String status = "QUEUED";
    private int revision = 1;
    private int attempt;
    private String reviewStatus = "NOT_REVIEWED";
    @Column(columnDefinition = "TEXT")
    private String outputText;
    private Long actionTaskId;
    private LocalDateTime updatedAt;
}
