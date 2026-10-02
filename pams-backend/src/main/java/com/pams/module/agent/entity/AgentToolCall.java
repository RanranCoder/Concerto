package com.pams.module.agent.entity;
import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具调用流水（对应 V24 agent_tool_call）。
 *
 * <p>两个用途：
 * <ol>
 *   <li>排障与统计</li>
 *   <li><b>前端过程区（P5）历史回看的数据源</b>——刷新页面后 {@code ✓ 查询我的待办 184ms}
 *       这些行从本表读回，因此过程区是真实历史而非前端临时状态（灵思 §7.1）。</li>
 * </ol>
 * 不含结果内容，仅 tool_name/args_digest/duration/status —— 与审计同性质，永久保留。
 */
@Data
@Entity
@Table(name = "agent_tool_call")
public class AgentToolCall implements OrgScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id")
    private Long conversationId;

    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "stream_id", length = 64)
    private String streamId;

    @Column(nullable = false)
    private Integer seq = 0;

    @Column(name = "tool_name", nullable = false, length = 80)
    private String toolName;

    @Column(length = 120)
    private String label;

    @Column(name = "args_json", columnDefinition = "JSON")
    private String argsJson;

    @Column(nullable = false, length = 20)
    private String sensitivity = "normal";

    @Column(name = "side_effect", nullable = false, length = 10)
    private String sideEffect = "READ";

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "result_status", length = 20)
    private String resultStatus;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
    private Long orgId;
}
