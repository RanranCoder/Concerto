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
    private Long runId;

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

    /**
     * 按 LLM 调用轮次分段的公开推理摘要；保留已有历史记录的读取结构。
     *
     * <p>结构见 {@code ThinkingTrace.Step}：
     * {@code [{round, text, startedAt, endedAt, durationMs}]}，前端同名类型 {@code AgentThinkingStep}。
     *
     * <p>与 {@link #toolCalls} 是**互补**的两层，不能互相推导：
     * <ul>
     *   <li>{@code tool_calls} = <b>做了什么</b>（工具、耗时、状态；有事实、无理由）</li>
     *   <li>{@code thinking_json} = <b>为什么这么做</b>（模型原文推理；有理由、无动作）</li>
     * </ul>
     * 过程区历史回看需要两者合起来才是一条完整的时间轴；只派生 toolCalls
     * 会让刷新后的时间轴缺掉"思考"这一半（V26 就是那个状态）。
     *
     * <p><b>脱敏</b>：仅 {@code sensitivity=normal} 时写入 —— 思考原文可能包含
     * 业务数据（真实姓名、金额），与正文同级对待，写时脱敏而不是事后清理。
     */
    @Column(name = "thinking_json", columnDefinition = "JSON")
    private String thinkingJson;

    /**
     * 本轮编排旁白（V36）：{@code THINKING} 事件的自述行，结构
     * {@code [{note, ts, durationMs?}]}，前端同名类型 {@code AgentThinkingNote}。
     *
     * <p>为什么不与 {@link #thinkingJson} 合成一列：两者是**不同主体的两种行** ——
     * 推理是模型说的（按 LLM 轮次分段、可展开读原文），旁白是后端编排说的
     * （一句话 + 持续到下一句的时长，没有正文可展开）。合成一列就得在同一个数组里
     * 塞两种结构，历史解析与前端类型都要做多态判断；分列则各自是干净的同类数组。
     *
     * <p>脱敏：与 {@link #thinkingJson} 同规则 —— 仅 {@code sensitivity=normal} 时写入。
     */
    @Column(name = "thinking_notes_json", columnDefinition = "JSON")
    private String thinkingNotesJson;

    @Column(nullable = false, length = 20)
    private String sensitivity = "normal";

    @Column(name = "stream_id", length = 64)
    private String streamId;

    /** 实际答复的原生协议或 stub；前端据此标注规则模式。 */
    @Column(length = 20)
    private String provider;

    @Column(name = "execution_state", length = 20)
    private String executionState;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @org.hibernate.annotations.TenantId
    private Long orgId;
}
