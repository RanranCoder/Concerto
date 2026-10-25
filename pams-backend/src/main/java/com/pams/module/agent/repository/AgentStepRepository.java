package com.pams.module.agent.repository;

import com.pams.module.agent.entity.AgentStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentStepRepository extends JpaRepository<AgentStep, Long> {
    java.util.Optional<AgentStep> findByWorkItemIdAndRevisionAndAttempt(Long workItemId,Integer revision,Integer attempt);

    List<AgentStep> findByRunIdOrderBySeqAsc(Long runId);

    long countByRunId(Long runId);
}
