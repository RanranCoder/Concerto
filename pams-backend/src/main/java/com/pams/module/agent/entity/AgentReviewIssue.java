package com.pams.module.agent.entity;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Entity @Table(name="agent_review_issue")
public class AgentReviewIssue {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private Long runId;
    private Long workItemId;
    private int revision;
    private int attempt;
    private String severity;
    private String code;
    private String location;
    @Column(columnDefinition="TEXT") private String quoteText;
    @Column(columnDefinition="TEXT") private String reason;
    private String sourceRef;
    private LocalDateTime createdAt;
}
