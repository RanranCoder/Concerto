package com.pams.module.agent.entity;
import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 灵思消息（对应 V24 agent_message）。
 *
 * <p>留存策略见业务设计方案 D4 / 决策 6：
 * <ul>
 *   <li>{@code normal} —— 完整文本，随会话永久</li>
 *   <li>{@code sensitive} —— <b>仅摘要</b>（写时就脱敏，而非事后清理）</li>
 *   <li>{@code restricted} —— 不落内容，仅"已拒绝 + 深链"占位</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "agent_message")
public class AgentMessage implements OrgScoped {

    public static final String ROLE_SYSTEM = "SYSTEM";
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";
    public static final String ROLE_TOOL = "TOOL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(nullable = false, length = 20)
    private String role;

    @Lob
    @Column(columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(name = "tool_calls", columnDefinition = "JSON")
    private String toolCalls;

    /**
     * 本轮工具结果引用（V26 历史恢复）：实体跳转链接数组，
     * 之前只随 /chat 同步返回、不落库，刷新即丢——现在随 assistant 消息持久化。
     */
    @Column(name = "refs_json", columnDefinition = "JSON")
    private String refsJson;

    /**
     * 本轮的「依据」（V27 知识库问答）。与 {@link #refsJson} 语义不同，不能合并：
     * <ul>
     *   <li>{@code refs_json} = <b>我查到了什么</b>（工具返回的实体引用，dry-run 阶段即可有）</li>
     *   <li>{@code citations_json} = <b>答案引用了什么</b>（服务端从检索结果回填的出处）</li>
     * </ul>
     * 前者是"过程"，后者是"证据"。合并会让历史回看时无法区分"我提到过"与"我据此下的结论"。
     *
     * <p>结构见 {@code AgentCitation}（前端同名类型）。<b>历史回看时需对每条引用做实时回源复核</b>
     * —— 权限收紧必须能撤回历史（审计 A9 / 知识库 G8）。
     */
    @Column(name = "citations_json", columnDefinition = "JSON")
    private String citationsJson;

    /**
     * 本轮使用的运行模式（V27）：{@code readonly} / {@code standard} / {@code kb}。
     *
     * <p>为什么要落库：①历史回看时渲染差异（知识问答轮显示依据卡、办事轮显示产物卡）
     * ②按模式做效果统计。老数据为 {@code NULL}，读取时按 {@code standard} 兜底渲染。
     */
    @Column(length = 20)
    private String mode;

    @Column(nullable = false, length = 20)
    private String sensitivity = "normal";

    @Column(name = "stream_id", length = 64)
    private String streamId;

    /** 实际答复的 provider（deepseek/ollama/stub）——前端据此标注"规则模式"（灵思 §3.5.1） */
    @Column(length = 20)
    private String provider;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @org.hibernate.annotations.TenantId
    private Long orgId;
}
