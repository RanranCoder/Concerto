package com.pams.module.agent.entity;
import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 灵思审计日志（对应 V24 agent_audit_log）—— D9，写操作 100% 覆盖。 */
@Data
@Entity
@Table(name = "agent_audit_log")
public class AgentAuditLog implements OrgScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "conversation_id")
    private Long conversationId;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "tool_name", nullable = false, length = 80)
    private String toolName;

    @Column(name = "args_digest", length = 128)
    private String argsDigest;

    @Column(name = "side_effect", nullable = false, length = 10)
    private String sideEffect;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "result_status", length = 20)
    private String resultStatus;

    @Column(name = "affected_count")
    private Integer affectedCount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @org.hibernate.annotations.TenantId
    private Long orgId;
}
