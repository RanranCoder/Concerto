package com.pams.module.agent.repository;

import com.pams.module.agent.entity.AgentWorkItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AgentWorkItemRepository extends JpaRepository<AgentWorkItem, Long> {
    List<AgentWorkItem> findByRunIdOrderByNodeKeyAsc(Long runId);
}
