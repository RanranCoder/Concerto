package com.pams.module.agent.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Entity @Table(name="agent_artifact_version")
public class AgentArtifactVersion {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private Long artifactId;
    private int version;
    private Long taskId;
    private Integer workItemRevision;
    private Integer attempt;
    private String origin;
    @Column(columnDefinition="MEDIUMTEXT") private String preview;
    @Column(columnDefinition="JSON") @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON) private String fieldsJson;
    @Column(columnDefinition="JSON") @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON) private String planJson;
    private String reviewStatus;
    @Column(columnDefinition="JSON") @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON) private String sourcesJson;
    private LocalDateTime createdAt;
}
