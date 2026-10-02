package com.pams.module.agent.repository;

import com.pams.module.agent.entity.AgentMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentMessageRepository extends JpaRepository<AgentMessage, Long> {

    List<AgentMessage> findByConversationIdOrderByIdAsc(Long conversationId);

    Page<AgentMessage> findByConversationIdOrderByIdDesc(Long conversationId, Pageable pageable);

    /** 上下文裁剪（D5）：只取最近 K 轮 */
    List<AgentMessage> findTop20ByConversationIdOrderByIdDesc(Long conversationId);
}
