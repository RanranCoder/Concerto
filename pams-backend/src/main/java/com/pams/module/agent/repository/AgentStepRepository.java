package com.pams.module.agent.repository;

import com.pams.module.agent.entity.AgentStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentStepRepository extends JpaRepository<AgentStep, Long> {

    List<AgentStep> findByRunIdOrderBySeqAsc(Long runId);

    long countByRunId(Long runId);
}
