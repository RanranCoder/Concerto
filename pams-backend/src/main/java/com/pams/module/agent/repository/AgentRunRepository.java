package com.pams.module.agent.repository;

import com.pams.module.agent.entity.AgentRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {
    @org.springframework.data.jpa.repository.Query("select r from AgentRun r where r.id>:after and (r.finishedAt is null or r.dispatchToken is not null) order by r.id")
    List<AgentRun> recoverable(@org.springframework.data.repository.query.Param("after")Long after,org.springframework.data.domain.Pageable page);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from AgentRun r where r.id = :id")
    Optional<AgentRun> lockById(@org.springframework.data.repository.query.Param("id") Long id);

    List<AgentRun> findByConversationIdOrderByIdDesc(Long conversationId);

    /** 归属校验：只能看自己的 Run。 */
    Optional<AgentRun> findByIdAndUserId(Long id, Long userId);
}
