package com.pams.module.agent.repository;

import com.pams.module.agent.entity.AgentAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentAuditLogRepository extends JpaRepository<AgentAuditLog, Long> {

    Page<AgentAuditLog> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    List<AgentAuditLog> findByTaskId(Long taskId);
}
