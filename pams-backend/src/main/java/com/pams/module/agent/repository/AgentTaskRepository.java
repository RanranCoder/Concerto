package com.pams.module.agent.repository;

import com.pams.module.agent.entity.AgentTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentTaskRepository extends JpaRepository<AgentTask, Long> {
    Page<AgentTask> findByConversationIdAndUserIdOrderByIdDesc(Long conversationId, Long userId, Pageable page);
    Page<AgentTask> findByConversationIdAndUserIdAndStatusInOrderByIdDesc(Long conversationId, Long userId, List<String> statuses, Pageable page);
    @Transactional @Modifying(clearAutomatically=true,flushAutomatically=true)
    @Query("update AgentTask t set t.status=:to,t.confirmedAt=:ts,t.executionOwner=:owner,t.rowVersion=t.rowVersion+1 where t.id=:id and t.status=:from")
    int claimOwned(@Param("id")Long id,@Param("from")String from,@Param("to")String to,@Param("ts")LocalDateTime ts,@Param("owner")String owner);
    List<AgentTask> findByRunIdOrderByIdAsc(Long runId);
    List<AgentTask> findByStreamIdAndUserIdOrderByIdAsc(String streamId,Long userId);

    Optional<AgentTask> findByIdAndUserId(Long id, Long userId);

    /** Receipt reconciliation may settle only the exact state observed, never overwrite a newer result. */
    @Modifying(clearAutomatically=true,flushAutomatically=true)
    @Transactional
    @Query("update AgentTask t set t.status='SUCCESS', t.resultJson=:result, t.finishedAt=:ts, t.rowVersion=t.rowVersion+1 where t.id=:id and t.userId=:userId and t.status=:from and t.rowVersion=:version")
    int reconcileSuccess(@Param("id")Long id,@Param("userId")Long userId,@Param("from")String from,@Param("version")long version,
                         @Param("result")String result,@Param("ts")LocalDateTime ts);
    @Modifying(clearAutomatically=true,flushAutomatically=true)
    @Transactional
    @Query("update AgentTask t set t.status='RESULT_UNKNOWN',t.rowVersion=t.rowVersion+1 where t.id=:id and t.userId=:userId and t.status='RUNNING' and t.rowVersion=:version")
    int markInterruptedUnknown(@Param("id")Long id,@Param("userId")Long userId,@Param("version")long version);

    Page<AgentTask> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    List<AgentTask> findByUserIdAndStatusOrderByIdDesc(Long userId, String status);

    /**
     * 历史恢复（V26）：按会话 + 本页消息的 stream_id 集合反查任务，
     * 供 ConversationStore.messages 把确认卡/表单卡挂回对应消息。
     * 升序返回、调用方后写覆盖 → 同一 stream 多条任务时保留最新。
     */
    List<AgentTask> findByConversationIdAndStreamIdInOrderByIdAsc(Long conversationId, List<String> streamIds);

    /**
     * 同会话内处于指定状态的任务（多轮「接续」用）。
     *
     * <p>场景（用户反馈 item 3）：用户在纠正对话后让模型重出清单——
     * 会话里应当<b>始终只有一张可确认卡</b>，旧清单在创建新清单时一并作废，
     * 否则"首轮的卡片还在、上下文却已经走了"会让人以为按钮失效。
     *
     * <p>命名注意：<b>不能</b>写成 {@code ...AndStatusIn...}——Spring Data 会把 {@code In}
     * 解析成 IN 操作符并要求 Collection 参数，方法名带 In 却传单值会在启动期直接失败
     * （实测踩到：Application run failed / "Operator 'IN' on 'status' requires a Collection argument"）。
     */
    List<AgentTask> findByConversationIdAndStatusOrderByIdAsc(Long conversationId, String status);

    long countByUserIdAndStatus(Long userId, String status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AgentTask t set t.status = 'CANCELLED', t.finishedAt = :ts, t.rowVersion = t.rowVersion + 1 "
            + "where t.id = :id and t.userId = :userId and t.status in ('PENDING_CONFIRM', 'WAITING_INPUT', 'REVIEW_REQUIRED')")
    int cancelPending(@Param("id") Long id, @Param("userId") Long userId, @Param("ts") LocalDateTime ts);

    List<AgentTask> findByUserIdAndConversationIdInOrderByIdDesc(Long userId, List<Long> conversationIds);

    /**
     * 乐观锁抢占（D2）。影响行数 0 = 重复提交，直接返回幂等结果。
     * 这是确认门禁"后端强制"的关键：不依赖前端自觉。
     *
     * <p><b>⚠️ 事务声明在仓储方法上而非调用方</b>：JPA 规定 update/delete 必须在事务内执行，
     * 否则 Hibernate 抛 {@code TransactionRequiredException: No active transaction for
     * update or delete query}（首轮回归实测踩到：确认接口 500）。声明在此处后，
     * 无论调用方（Controller / 编排器）有没有事务，语义都一致；
     * 且抢占<b>立即独立提交</b>，不会被外层事务的回滚连带撤销。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AgentTask t set t.status = :to, t.confirmedAt = :ts, t.rowVersion = t.rowVersion + 1 where t.id = :id and t.status = :from")
    int claimForRun(@Param("id") Long id, @Param("from") String from, @Param("to") String to,
                    @Param("ts") LocalDateTime ts);

    /**
     * 落终态（SUCCESS/FAILED/PARTIAL/CANCELLED）。事务声明同 {@link #claimForRun}：
     * 独立提交，保证确认后的 {@code findById} 能读到终态，
     * 且不受调用方事务回滚/rollback-only 状态的影响。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AgentTask t set t.status = :status, t.resultJson = :result, t.finishedAt = :ts, t.rowVersion = t.rowVersion + 1 where t.id = :id")
    void finish(@Param("id") Long id, @Param("status") String status,
                @Param("result") String result, @Param("ts") LocalDateTime ts);
}
