package com.pams.module.agent.repository;

import com.pams.module.agent.entity.AgentToolCall;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentToolCallRepository extends JpaRepository<AgentToolCall, Long> {

    /** 过程区历史回看（灵思 §7.1）：刷新后从本表读回时间轴 */
    List<AgentToolCall> findByStreamIdOrderBySeqAsc(String streamId);

    /**
     * 取该流的第一条工具调用。
     *
     * <p>用于**流归属校验**：进程重启后内存里的 streamId→userId 映射会丢，
     * 此时只能回查数据库确认真实归属 —— 靠这个拿 conversationId，
     * 再由会话查出 userId（权限审计 A5）。
     */
    java.util.Optional<AgentToolCall> findFirstByStreamIdOrderBySeqAsc(String streamId);

    List<AgentToolCall> findByConversationIdOrderByIdAsc(Long conversationId);
}
