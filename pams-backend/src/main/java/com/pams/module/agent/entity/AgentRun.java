package com.pams.module.agent.entity;

import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次多 Agent 协作（对应 V32 {@code agent_run}）。
 * {@code Run = <Goal, Mode, Blackboard, Steps[]>}；黑板书整体落库以支持协作过程回放。
 */
@Data
@Entity
@Table(name = "agent_run")
public class AgentRun implements OrgScoped {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long conversationId;
    private Long userId;
    private Long orgId;
    private long eventSeq;
    private boolean cancelRequested;
    private String requestId;
    private String dispatchOwner;
    private String streamId;
    @Column(nullable = false, length = 10)
    private String toolMode = "standard";
    @Column(columnDefinition = "JSON")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String authorityJson;
    private String dispatchToken;
    private LocalDateTime dispatchStartedAt;

    @Column(columnDefinition = "TEXT")
    private String goal;

    /** P1 串行 / P2 并行 / P3 反思 / P4 委派 */
    @Column(length = 10)
    private String mode;

    @Column(nullable = false, length = 20)
    private String status = STATUS_RUNNING;

    @Column(name = "blackboard_json", columnDefinition = "JSON")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String blackboardJson;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
