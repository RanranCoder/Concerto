package com.pams.module.agent.core;

import com.pams.security.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具执行上下文（业务设计方案 D1 / D3 / D11）。
 *
 * <p>三件事：
 * <ol>
 *   <li><b>权限断言的数据源</b>：权限码取自 {@code Authentication.getAuthorities()}，
 *       <b>不是</b> {@code LoginUser}——已核实 {@link LoginUser} 不含权限码字段，
 *       权限码由 {@code JwtAuthenticationFilter} 查 {@code role_permission} 后注入到 Authentication 上。</li>
 *   <li><b>脱敏标志的统一推导</b>：{@link #isStaff()}，禁止工具自行判断角色。</li>
 *   <li><b>SecurityContext 显式下传</b>：长任务在独立线程池执行时 ThreadLocal 会丢，
 *       因此把 LoginUser 作为入参显式携带（D1）。</li>
 * </ol>
 */
@Slf4j
public class ToolContext {

    /** 只读模式：本次会话不写入任何数据 */
    public static final String MODE_READONLY = "readonly";
    /** 标准模式：可写，但写操作仍须过 D2 确认门禁（默认） */
    public static final String MODE_STANDARD = "standard";
    /**
     * 对话模式：知识库问答（D12 全域知识源）。
     *
     * <p><b>语义边界</b>：这不是权限档位，是<b>任务轴</b>的切换 ——
     * 「只读 / 标准」回答"这次能不能动数据"，「对话」回答"这次是查资料还是办事"。
     * 因此它不等价于"readonly 的更严格版本"，而是走一条完全不同的流水线
     * （{@code KbPipeline}，不进工具循环）。
     *
     * <p>传输层取值用 {@code kb} 而不是 {@code chat}：后者会与端点
     * {@code POST /api/agent/chat} 及 {@code AgentMessage.ROLE_*} 语义撞车。
     */
    public static final String MODE_KB = "kb";

    private final LoginUser loginUser;
    private final Set<String> authorities;
    private final Long conversationId;
    private final String streamId;
    private final String mode;
    private Long runId;
    private String requestId;
    private Integer attempt;
    private Long workItemId;
    private Integer workItemRevision;

    public ToolContext(LoginUser loginUser, Collection<? extends GrantedAuthority> granted,
                       Long conversationId, String streamId) {
        this(loginUser, granted, conversationId, streamId, MODE_STANDARD);
    }

    public ToolContext(LoginUser loginUser, Collection<? extends GrantedAuthority> granted,
                       Long conversationId, String streamId, String mode) {
        this(loginUser,
             granted == null ? Set.of()
                     : granted.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toUnmodifiableSet()),
             conversationId, streamId, normalizeMode(mode));
    }

    private ToolContext(LoginUser loginUser, Set<String> authorities,
                        Long conversationId, String streamId) {
        this(loginUser, authorities, conversationId, streamId, MODE_STANDARD);
    }

    private ToolContext(LoginUser loginUser, Set<String> authorities,
                        Long conversationId, String streamId, String mode) {
        this.loginUser = loginUser;
        this.authorities = authorities == null ? Set.of() : authorities;
        this.conversationId = conversationId;
        this.streamId = streamId;
        this.mode = mode;
    }

    /**
     * 归一化运行模式。
     *
     * <p><b>⚠️ 这里曾是本方案最危险的静默失效点（权限审计之外的 R1）</b>：
     * 原实现只识别 {@code readonly}，其余一律回退 {@code standard}。
     * 于是前端传 {@code mode=kb} 时 —— 若这里没加分支 —— 会落进标准模式，
     * 模型手里握着 10 个写工具，而<b>全程不会有任何报错</b>。
     * 用户以为自己在"查资料"，实际拿到了写能力。这是 D11 第 ③ 类的教科书案例。
     *
     * <p>因此改为<b>显式三值枚举</b>，并对真正的未知值留 WARN 便于排查。
     * 未知值仍回退 {@code standard} 是刻意的：保持一致的历史语义
     * （老客户端不发 mode 时不能突然变成只读或问答）。
     *
     * <p>配套的单测见 {@code ToolContextKbModeTest}——重点断言
     * <b>{@code kb} 不被归一化成 {@code standard}</b>。
     */
    private static String normalizeMode(String mode) {
        if (mode == null) return MODE_STANDARD;
        String m = mode.trim();
        if (MODE_READONLY.equalsIgnoreCase(m)) return MODE_READONLY;
        if (MODE_KB.equalsIgnoreCase(m)) return MODE_KB;
        if (MODE_STANDARD.equalsIgnoreCase(m)) return MODE_STANDARD;
        // 真正的异常输入：不是三个已知值之一。留日志，否则"传错了档位"永远查不出来
        log.warn("灵思未知运行模式「{}」，按标准模式处理（已知值：readonly/standard/kb）", m);
        return MODE_STANDARD;
    }

    /**
     * 派生上下文：换 conversationId / streamId，其余字段（用户、权限码）原样保留。
     *
     * <p>为什么需要：Controller 构造 ctx 时会话可能还没创建（新会话首轮 conversationId=null，
     * streamId 在编排器内部才生成）。编排器在会话落定后用本方法回填两个字段——
     * 否则首轮任务的 conversation_id 落 NULL、任务与流无法绑定（历史恢复失效）。
     *
     * <p><b>mode 必须一并透传</b>：漏传会让只读模式在 {@code ctx.derive(...)} 之后静默失效
     * （编排器第 148 行必然走 derive），写类工具重新可见——这是典型的 D11 第 ③ 类语义变更。
     */
    public ToolContext derive(Long conversationId, String streamId) {
        return new ToolContext(loginUser, authorities, conversationId, streamId, mode).withRunId(runId).withWorkItem(workItemId, workItemRevision).withRequestId(requestId).withAttempt(attempt);
    }

    /** 换运行模式，其余字段原样保留 */
    public ToolContext withMode(String mode) {
        return new ToolContext(loginUser, authorities, conversationId, streamId, normalizeMode(mode)).withRunId(runId).withWorkItem(workItemId, workItemRevision).withRequestId(requestId).withAttempt(attempt);
    }

    public Long getRunId() { return runId; }
    public ToolContext withRunId(Long id) {
        ToolContext copy = new ToolContext(loginUser, authorities, conversationId, streamId, mode);
        copy.runId = id;
        copy.requestId = requestId;
        copy.attempt = attempt;
        copy.workItemId = workItemId;
        copy.workItemRevision = workItemRevision;
        return copy;
    }
    public Long getWorkItemId() { return workItemId; }
    public Integer getWorkItemRevision() { return workItemRevision; }
    public ToolContext withWorkItem(Long id, Integer revision) {
        ToolContext copy = withRunId(runId);
        copy.workItemId = id;
        copy.workItemRevision = revision;
        return copy;
    }
    public ToolContext restrictTo(Set<String> ceiling) {
        Set<String> permitted = authorities.stream().filter(ceiling::contains).collect(Collectors.toUnmodifiableSet());
        return new ToolContext(loginUser,permitted,conversationId,streamId,mode).withRunId(runId).withWorkItem(workItemId,workItemRevision).withRequestId(requestId).withAttempt(attempt);
    }
    public Integer getAttempt() { return attempt; }
    public ToolContext withAttempt(Integer value) { ToolContext copy=withRunId(runId); copy.attempt=value; return copy; }
    public String getRequestId() { return requestId; }
    public ToolContext withRequestId(String id) { ToolContext copy=withRunId(runId); copy.requestId=id; return copy; }

    /** 从当前 SecurityContext 构造（同步路径用；异步路径请显式传 loginUser 构造）。 */
    public static ToolContext current(Long conversationId, String streamId) {
        return current(conversationId, streamId, MODE_STANDARD);
    }

    public static ToolContext current(Long conversationId, String streamId, String mode) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        LoginUser user = (auth != null && auth.getPrincipal() instanceof LoginUser u) ? u : null;
        return new ToolContext(user, auth == null ? null : auth.getAuthorities(), conversationId, streamId, mode);
    }

    public LoginUser getLoginUser() { return loginUser; }

    public Long getUserId() { return loginUser == null ? null : loginUser.getId(); }

    public String getRoleCode() { return loginUser == null ? null : loginUser.getRoleCode(); }

    public Long getDeptId() { return loginUser == null ? null : loginUser.getDeptId(); }

    public Set<String> getAuthorities() { return authorities; }

    public Long getConversationId() { return conversationId; }

    public String getStreamId() { return streamId; }

    /** 运行模式：{@link #MODE_READONLY} / {@link #MODE_STANDARD} / {@link #MODE_KB} */
    public String getMode() { return mode; }

    /**
     * 只读模式（用户主动降档）。
     *
     * <p><b>语义边界（勿误用）</b>：这不是权限降级——用户本身的权限码一个没少，
     * 只是本次会话主动放弃写能力。实现上等价于「工具可见性过滤」的又一个条件，
     * 因此不引入新的越权风险；真正的安全边界仍是权限码 + 确认门禁。
     *
     * <p><b>不要用本方法表达「对话模式」</b>：两者都会让写工具不可见，
     * 但对话模式还要分流到 {@code KbPipeline}。请用 {@link #isKb()}。
     */
    public boolean isReadonly() { return MODE_READONLY.equals(mode); }

    /**
     * 对话模式（知识库问答，D12）。
     *
     * <p>使用者有两处，且两处都要判：
     * <ol>
     *   <li>{@code AgentOrchestrator.run()} —— 分流到独立流水线，<b>不进工具循环</b>。
     *       这比"靠可见性挡住工具"更彻底：没有循环就无从诱导。</li>
     *   <li>{@link ToolRegistry#visibleTo} —— 双保险。即便将来有代码路径漏到
     *       {@code dispatch}，对话模式也不会写库。</li>
     * </ol>
     */
    public boolean isKb() { return MODE_KB.equals(mode); }

    /**
     * 脱敏标志（D11 统一约定）。
     *
     * <p>{@code PartyMemberService.page(..., boolean staff)} 的 staff 由 Controller 传入，
     * 工具漏传即脱敏失效。故统一从 LoginUser 推导，禁止各工具自行判断。
     */
    public boolean isStaff() {
        return !hasPermission("party:sensitive:view");
    }

    /** 是否领导角色（content 模块的 LEADER 等价口径，G1） */
    public boolean isLeader() {
        return loginUser != null && hasPermission("kb:restricted:read");
    }

    /** 是否具备该权限码 */
    public boolean hasPermission(String code) {
        return code != null && authorities.contains(code);
    }

    /** 是否具备任一权限码 */
    public boolean hasAnyPermission(String... codes) {
        for (String c : codes) {
            if (hasPermission(c)) return true;
        }
        return false;
    }
}
