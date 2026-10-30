package com.pams.module.agent.repository;

import com.pams.module.agent.entity.AgentMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AgentMessageRepository extends JpaRepository<AgentMessage, Long> {
    interface SourceSnapshot {
        Long getId(); String getRole(); String getContent(); String getCitations(); String getSensitivity();
    }
    @Query("select m.id as id,m.role as role,m.content as content,m.citationsJson as citations,m.sensitivity as sensitivity from AgentMessage m where m.conversationId=:conversationId and m.id in :ids")
    List<SourceSnapshot> sourceSnapshots(@Param("conversationId") Long conversationId,@Param("ids") List<Long> ids);
    org.springframework.data.domain.Slice<AgentMessage> findByConversationIdAndIdGreaterThanAndIdLessThanAndRoleInOrderByIdAsc(
            Long conversationId, Long after, Long before, List<String> roles, Pageable page);
    java.util.Optional<AgentMessage> findByIdAndConversationId(Long id, Long conversationId);
    Page<AgentMessage> findByConversationIdAndRoleInOrderByIdDesc(Long conversationId, List<String> roles, Pageable page);
    Page<AgentMessage> findByConversationIdAndIdLessThanAndRoleOrderByIdAsc(Long conversationId, Long before, String role, Pageable page);
    Page<AgentMessage> findByConversationIdAndIdLessThanAndRoleOrderByIdDesc(Long conversationId, Long before, String role, Pageable page);
    Page<AgentMessage> findByConversationIdAndIdLessThanAndContentContainingIgnoreCaseAndRoleInOrderByIdDesc(
            Long conversationId, Long before, String keyword, List<String> roles, Pageable page);
    List<AgentMessage> findByStreamIdAndRole(String streamId, String role);

    List<AgentMessage> findByConversationIdOrderByIdAsc(Long conversationId);

    Page<AgentMessage> findByConversationIdOrderByIdDesc(Long conversationId, Pageable pageable);

    /** 上下文裁剪（D5）：只取最近 K 轮 */
    List<AgentMessage> findTop20ByConversationIdOrderByIdDesc(Long conversationId);

    @Query("select m from AgentMessage m where m.conversationId in :ids and m.id = "
            + "(select max(last.id) from AgentMessage last where last.conversationId = m.conversationId)")
    List<AgentMessage> findLatestByConversationIds(@Param("ids") List<Long> ids);
}
