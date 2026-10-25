package com.pams.module.agent.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Entity @Table(name="agent_artifact")
public class AgentArtifact {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private Long userId;
    private Long orgId;
    private Long conversationId;
    private Long runId;
    private Long workItemId;
    private String title;
    private String toolName;
    private String kind;
    private int currentVersion;
    private LocalDateTime createdAt;
}
