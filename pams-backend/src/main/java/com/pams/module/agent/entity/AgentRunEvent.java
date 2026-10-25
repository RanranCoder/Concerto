package com.pams.module.agent.entity;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** Transactional outbox and ordered durable run cursor. Payloads are intentionally metadata only. */
@Data @Entity @Table(name="agent_run_event",uniqueConstraints=@UniqueConstraint(columnNames={"run_id","event_seq"}))
public class AgentRunEvent {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private Long runId;
    private Long orgId;
    private Long userId;
    private long eventSeq;
    private String status;
    private boolean wakeRequired;
    private LocalDateTime createdAt;
    private LocalDateTime deliveredAt;
    private LocalDateTime leaseUntil;
    private String deliveryToken;
    private int attempts;
}
