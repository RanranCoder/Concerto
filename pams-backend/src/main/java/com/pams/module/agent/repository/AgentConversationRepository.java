package com.pams.module.agent.repository;

import com.pams.module.agent.entity.AgentConversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AgentConversationRepository extends JpaRepository<AgentConversation, Long> {
    interface MemorySnapshot {
        String getSummary();
        long getCovered();
        long getRevision();
    }
    @Query("select c.contextSummaryJson as summary, c.coveredThroughMessageId as covered, c.contextSummaryRevision as revision from AgentConversation c where c.id=:id and c.userId=:userId and c.orgId=:orgId and c.deleted=0")
    Optional<MemorySnapshot> memory(@Param("id") Long id, @Param("userId") Long userId, @Param("orgId") Long orgId);

    @org.springframework.transaction.annotation.Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AgentConversation c set c.contextSummaryJson=:summary, c.coveredThroughMessageId=:covered, c.contextSummaryRevision=c.contextSummaryRevision+1 where c.id=:id and c.userId=:userId and c.orgId=:orgId and c.deleted=0 and c.contextSummaryRevision=:revision")
    int compareAndSetMemory(@Param("id") Long id, @Param("userId") Long userId, @Param("orgId") Long orgId,
                           @Param("revision") long revision, @Param("summary") String summary, @Param("covered") long covered);

    @org.springframework.transaction.annotation.Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AgentConversation c set c.deleted=1, c.updatedAt=:ts, c.contextSummaryJson=null, c.coveredThroughMessageId=0, c.contextSummaryRevision=c.contextSummaryRevision+1 where c.id=:id and c.userId=:userId and c.deleted=0")
    int deleteWithMemory(@Param("id") Long id, @Param("userId") Long userId, @Param("ts") LocalDateTime ts);

    Page<AgentConversation> findByUserIdAndDeletedOrderByUpdatedAtDesc(Long userId, Integer deleted, Pageable pageable);

    /** 严格按 user_id 隔离：他人会话查不出来（D8） */
    Optional<AgentConversation> findByIdAndUserIdAndDeleted(Long id, Long userId, Integer deleted);

    @Modifying
    @Query("update AgentConversation c set c.updatedAt = :ts where c.id = :id")
    void touch(@Param("id") Long id, @Param("ts") LocalDateTime ts);

    @Modifying
    @Query("update AgentConversation c set c.title = :title where c.id = :id and c.userId = :userId")
    int rename(@Param("id") Long id, @Param("userId") Long userId, @Param("title") String title);
}
