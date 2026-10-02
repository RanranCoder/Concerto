package com.pams.module.agent.repository;

import com.pams.module.agent.entity.AgentDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentDefinitionRepository extends JpaRepository<AgentDefinition, Long> {

    List<AgentDefinition> findByEnabledOrderBySortOrderAsc(Integer enabled);

    Optional<AgentDefinition> findByName(String name);

    boolean existsByName(String name);
}
