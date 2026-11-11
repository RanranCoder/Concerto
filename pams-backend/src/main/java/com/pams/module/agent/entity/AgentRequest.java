package com.pams.module.agent.entity;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** Durable request identity, cancellation and user instructions received during execution. */
@Data @Entity @Table(name="agent_request")
public class AgentRequest {
    @Id @Column(length=64) private String id;
    private Long userId;
    private Long orgId;
    private String streamId;
    private Long conversationId;
    private Long runId;
    private Long delegatedTaskId;
    private long authzVersion;
    private String status;
    private boolean cancelled;
    private boolean steeringOpen;
    private int steeringConsumed;
    @Column(columnDefinition="TEXT") private String steeringJson;
    private LocalDateTime updatedAt;
}
