package com.pams.module.agent.entity;
import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 灵思任务 / 拟执行清单（对应 V24 agent_task）—— D2 确认门禁的载体，本模块核心表。
 *
 * <p>状态机：{@code WAITING_INPUT}（表单补全，D2.5）→ {@code PENDING_CONFIRM} →
 * {@code RUNNING} → {@code SUCCESS/FAILED/PARTIAL}；另有 {@code CANCELLED} 分支。
 * WAITING_INPUT 与 PENDING_CONFIRM 共用 plan_json 列（存表单 schema 或清单，以 type 区分），
 * 不需要新增列。
 *
 * <p>确认的幂等性由<b>乐观锁</b>保证（不是靠前端自觉）：
 * <pre>UPDATE agent_task SET status='RUNNING' WHERE id=? AND status='PENDING_CONFIRM'</pre>
 * 影响行数为 0 即判定重复提交，直接返回已有结果。
 */
@Data
@Entity
@Table(name = "agent_task")
public class AgentTask implements OrgScoped {

    public static final String PENDING_CONFIRM = "PENDING_CONFIRM";
    /** 表单补全中（D2.5 Form Elicitation）：等用户在弹窗里补全参数 */
    public static final String WAITING_INPUT = "WAITING_INPUT";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String PARTIAL = "PARTIAL";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "conversation_id")
    private Long conversationId;
    private Long runId;
    private Long workItemId;
    private Integer workItemRevision;
    @Version
    private long rowVersion;
    @Column(nullable = false, length = 12)
    private String dataSensitivity = "NORMAL";
    private String executionOwner;

    /**
     * 产生本任务的过程流 ID（V26 历史恢复）。
     * agent_message.stream_id 同源，因此任务卡能精确挂回"产生它的那条助手消息"；
     * 旧数据（V26 之前）此列为 NULL，历史恢复时这类任务不出卡。
     */
    @Column(name = "stream_id", length = 64)
    private String streamId;

    @Column(name = "tool_name", nullable = false, length = 80)
    private String toolName;

    @Column(name = "task_type", nullable = false, length = 10)
    private String taskType = "SYNC";

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 255)
    private String summary;

    @Column(name = "plan_json", columnDefinition = "JSON")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String planJson;

    @Column(name = "result_json", columnDefinition = "JSON")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String resultJson;

    @Column(name = "confirm_token", length = 64)
    private String confirmToken;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
    @org.hibernate.annotations.TenantId
    private Long orgId;
}
