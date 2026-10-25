package com.pams.module.agent.repository;
import com.pams.module.agent.entity.AgentReviewIssue;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface AgentReviewIssueRepository extends JpaRepository<AgentReviewIssue,Long> {
    List<AgentReviewIssue> findByRunIdOrderByIdAsc(Long runId);
    boolean existsByWorkItemIdAndRevisionAndAttemptAndSeverity(Long workItemId,int revision,int attempt,String severity);
}
