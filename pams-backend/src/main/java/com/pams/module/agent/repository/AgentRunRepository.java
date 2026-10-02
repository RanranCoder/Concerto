package com.pams.module.agent.repository;

import com.pams.module.agent.entity.AgentRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {

    List<AgentRun> findByConversationIdOrderByIdDesc(Long conversationId);

    /** 归属校验：只能看自己的 Run。 */
    Optional<AgentRun> findByIdAndUserId(Long id, Long userId);
}
