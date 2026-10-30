package com.pams.module.agent.entity;
import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 灵思会话（对应 V24 agent_conversation）。按 user_id 强隔离，他人会话不可见。 */
@Data
@Entity
@Table(name = "agent_conversation")
public class AgentConversation implements OrgScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 120)
    private String title = "新对话";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(nullable = false)
    private Integer deleted = 0;
    @Column(name = "context_summary_json", columnDefinition = "MEDIUMTEXT")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String contextSummaryJson;
    @Column(name = "covered_through_message_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private long coveredThroughMessageId;
    @Column(name = "context_summary_revision", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private long contextSummaryRevision;
    @org.hibernate.annotations.TenantId
    private Long orgId;
}
