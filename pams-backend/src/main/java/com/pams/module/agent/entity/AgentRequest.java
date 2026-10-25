package com.pams.module.agent.entity;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** Durable request identity, including cancellation before the first stream event. No prompt or tokens. */
@Data @Entity @Table(name="agent_request")
public class AgentRequest {
    @Id @Column(length=64) private String id;
    private Long userId;
    private Long orgId;
    private String streamId;
    private Long conversationId;
    private Long runId;
    private long authzVersion;
    private String status;
    private boolean cancelled;
    private LocalDateTime updatedAt;
}
