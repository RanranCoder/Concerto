package com.pams.module.agent.controller;

import com.pams.common.PageResult;
import com.pams.common.Result;
import com.pams.module.agent.core.AgentTool;
import com.pams.module.agent.core.ChatRefsResolver;
import com.pams.module.agent.core.PolicyGuard;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.core.ToolRegistry;
import com.pams.module.agent.service.AgentOrchestrator;
import com.pams.module.agent.service.ConversationStore;
import com.pams.module.agent.service.TaskGate;
import com.pams.module.agent.stream.AgentEventPublisher;
import com.pams.module.agent.stream.AgentStreamEvent;
import com.pams.security.LoginUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 灵思接口（业务设计方案 §3.4 —— 端到端契约）。
 *
 * <p><b>⚠️ 本 Controller 是灵思唯一对外入口</b>。它做三件事：
 * <ol>
 *   <li>从 {@code @AuthenticationPrincipal} 构造 {@link ToolContext}（把 SecurityContext 显式下传）</li>
 *   <li>把请求交给 {@link AgentOrchestrator} / {@link TaskGate} / {@link ConversationStore}</li>
 *   <li>按 {@code Result<T>} 统一包壳</li>
 * </ol>
 *
 * <p><b>权限口径</b>：灵思本身要求登录（{@code authenticated}）即可访问；
 * <b>真正的权限控制在工具层</b>（D3 三层防护）——因为"能不能用灵思"和
 * "通过灵思能做什么"是两件事。普通干事可以用灵思查自己的待办，
 * 但诱导不出"批量加分"这种工具。
 * <b>不在此处按角色宏拦截，否则会把灵思做成"只有领导能用"</b>，违背产品定位。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentOrchestrator orchestrator;
    private final ConversationStore conversations;
    private final TaskGate taskGate;
    private final ToolRegistry registry;
    private final AgentEventPublisher publisher;
    private final PolicyGuard policyGuard;
    @org.springframework.beans.factory.annotation.Autowired private com.pams.module.agent.service.AgentRequestService requests;
    @org.springframework.beans.factory.annotation.Autowired private com.pams.module.agent.orchestration.WorkItemState workItems;

    public AgentController(AgentOrchestrator orchestrator, ConversationStore conversations,
                           TaskGate taskGate, ToolRegistry registry,
                           AgentEventPublisher publisher, PolicyGuard policyGuard) {
        this.orchestrator = orchestrator;
        this.conversations = conversations;
        this.taskGate = taskGate;
        this.registry = registry;
        this.publisher = publisher;
        this.policyGuard = policyGuard;
    }

    /* ================================================================= 对话 */

    /**
     * 提问（同步返回最终答复；过程事件经 WebSocket 实时推送）。
     *
     * <p>同步是刻意的：过程可见性由 {@code /user/queue/agent/stream} 保证，
     * 不必为了"看起来是异步"而牺牲"返回值就是答案"的简单性。
     */
    @PostMapping("/chat")
    @PreAuthorize("isAuthenticated()")
    public Result<Map<String, Object>> chat(@RequestBody Map<String, Object> body,
                                            @AuthenticationPrincipal LoginUser user) {
        String message = str(body.get("message"));
        if (message == null || message.isBlank()) {
            return Result.fail(400, "请输入你的问题");
        }
        if (message.length() > 2000) {
            message = message.substring(0, 2000);
        }
        Long conversationId = lng(body.get("conversationId"));

        // 自动协作开关（前端输入框，默认开）。缺失时按 true —— 老客户端不发这个字段，
        // 不能让它突然失去协作能力（与 mode 缺省即 standard 同一约定）。
        Object collabRaw = body.get("collab");
        boolean collab = !(collabRaw instanceof Boolean) || (Boolean) collabRaw;

        return Result.ok(orchestrator.chatSync(message, conversationId,
                ctx(user, conversationId, str(body.get("mode"))).withRequestId(str(body.get("requestId"))), extractRefs(body.get("refs")), collab));
    }

    /** 中止一次进行中的回答（编排器在下一个阶段边界退出） */
    @PostMapping("/chat/{streamId}/abort")
    @PreAuthorize("isAuthenticated()")
    public Result<Map<String, Object>> abort(@PathVariable String streamId,
                                            @AuthenticationPrincipal LoginUser user) {
        boolean aborted = publisher.abortOwned(streamId, user == null ? null : user.getId());
        if(aborted) taskGate.cancelUnsubmitted(streamId,user.getId());
        if(aborted) requests.byStream(streamId).filter(r->r.getRunId()!=null).ifPresent(r->workItems.cancel(r.getRunId(),user.getId()));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("streamId", streamId);
        r.put("aborted", aborted);
        return Result.ok(r);
    }

    /**
     * 补拉过程事件（断连补齐 / 历史回看）。
     *
     * <p>D7 的降级通道：WebSocket 断线期间的事件不会丢，
     * 前端重连后带 {@code since=lastSeq} 拉一次即可补齐。
     * 服务重启后缓冲丢失，此时从 {@code agent_tool_call} 表重建工具行
     * ——<b>过程是真实历史，不是前端临时状态</b>。
     *
     * <p><b>归属校验（权限审计 A5）</b>：{@code userId} 必须传 ——
     * 该接口此前只要求"已登录"，而 streamId 是 {@code hex(nanoTime())} 而非 UUID，
     * 泄露后任何人都能读到别人的过程事件。校验放服务层（内存映射 + 数据库回查两级），
     * 不匹配时返回空列表而不报错（避免存在性泄露）。
     */
    @GetMapping("/streams/{streamId}")
    @PreAuthorize("isAuthenticated()")
    public Result<Map<String, Object>> stream(@PathVariable String streamId,
                                             @RequestParam(defaultValue = "0") int since,
                                             @AuthenticationPrincipal LoginUser user) {
        return Result.ok(publisher.replay(streamId,since,user.getId()));
    }

    /* ================================================================= 会话 */

    /**
     * 会话列表。
     *
     * <p>返回体包一层（而非直接 PageResult），因为需要带出 {@code pendingTaskCount}
     * ——侧边栏要在会话列表上挂"有 N 项待确认"的角标。
     * 待确认数是<b>用户级</b>而非会话级，因此只查一次、放在外层（避免 N+1）。
     */
    @GetMapping("/conversations")
    @PreAuthorize("isAuthenticated()")
    public Result<Map<String, Object>> conversations(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal LoginUser user) {
        PageResult<Map<String, Object>> pageResult = conversations.list(user.getId(), page, size);
        long pending = taskGate.countPending(user.getId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", pageResult.getRecords());
        out.put("total", pageResult.getTotal());
        out.put("current", pageResult.getCurrent());
        out.put("size", pageResult.getSize());
        out.put("pendingTaskCount", pending);
        return Result.ok(out);
    }

    /**
     * 历史消息。
     *
     * <p><b>{@code ctx} 必须传</b>（V27 起 {@code ConversationStore.messages} 的必填参数）：
     * 它用于对历史消息里的 citations 做**实时回源复核** —— 用户被降权后，
     * 旧对话里的引用片段必须降级为"已不可见"，否则权限收紧永不生效
     * （方案 G8 / R21）。漏传不会报错、只会继续泄露，所以刻意做成必填。
     */
    @GetMapping("/conversations/{id}/messages")
    @PreAuthorize("isAuthenticated()")
    public Result<PageResult<Map<String, Object>>> messages(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal LoginUser user) {
        return Result.ok(conversations.messages(id, user.getId(), page, size, ctx(user, id, null)));
    }

    @DeleteMapping("/conversations/{id}")
    @PreAuthorize("isAuthenticated()")
    public Result<Void> deleteConversation(@PathVariable Long id,
                                          @AuthenticationPrincipal LoginUser user) {
        conversations.delete(id, user.getId());
        return Result.ok();
    }

    /* ================================================================= 任务（确认门禁） */

    @GetMapping("/tasks/{id}")
    @PreAuthorize("isAuthenticated()")
    public Result<Map<String, Object>> taskDetail(@PathVariable Long id,
                                                 @AuthenticationPrincipal LoginUser user) {
        return Result.ok(taskGate.detail(id, user.getId()));
    }

    /**
     * 确认并执行写操作（D2 的核心关卡）。
     *
     * <p>{@code confirmToken} 是<b>一次性</b>的；服务侧用乐观锁抢占，
     * 重复提交返回幂等结果而非报错（前端不做自动重试，"到底成没成"不能有歧义）。
     */
    @PostMapping("/tasks/{id}/confirm")
    @PreAuthorize("isAuthenticated()")
    public Result<Map<String, Object>> confirmTask(@PathVariable Long id,
                                                   @RequestBody(required = false) Map<String, Object> body,
                                                   @AuthenticationPrincipal LoginUser user) {
        String token = body == null ? null : str(body.get("confirmToken"));
        // mode 随请求带来：只读模式下 TaskGate 的二次可见性断言会拦下写工具
        return Result.ok(taskGate.confirm(id, token,
                ctx(user, null, body == null ? null : str(body.get("mode")))));
    }

    @PostMapping("/tasks/{id}/cancel")
    @PreAuthorize("isAuthenticated()")
    public Result<Void> cancelTask(@PathVariable Long id,
                                  @AuthenticationPrincipal LoginUser user) {
        taskGate.cancel(id, user.getId());
        return Result.ok();
    }

    /**
     * 提交表单补全（D2.5 Form Elicitation）。
     *
     * <p>用户在弹窗中补全参数后提交：后端把表单值与 LLM 首次调用的原始参数合并，
     * <b>经 ToolRegistry.dispatch 重跑 dry-run</b>（权限断言在提交时依然生效）。
     * 返回 {@code status=PLAN}（转入既有确认门禁，附一次性令牌）/ {@code FORM}（仍缺信息）
     * / {@code ERROR}（校验失败，可修改重提）/ {@code CANCELLED}（业务拦停）。
     */
    @PostMapping("/tasks/{id}/input")
    @PreAuthorize("isAuthenticated()")
    public Result<Map<String, Object>> submitTaskInput(@PathVariable Long id,
                                                       @RequestBody Map<String, Object> body,
                                                       @AuthenticationPrincipal LoginUser user) {
        if (!(body.get("values") instanceof Map)) {
            return Result.fail(400, "请填写表单内容");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) body.get("values");
        return Result.ok(taskGate.submitInput(id, values, ctx(user, null, str(body.get("mode")))));
    }

    /* ================================================================= 能力清单 */

    /**
     * 当前用户可见的工具清单（供前端提示卡过滤与"我能做什么"面板）。
     *
     * <p>只返回<b>可见</b>工具——不可见即不可诱导（D3 第 2 层）。
     *
     * @param mode 运行模式：{@code readonly} 时清单里不会出现任何写类工具。
     *             前端切换只读档位后要重新拉一次，否则面板与实际可调用集不一致。
     */
    @GetMapping("/tools")
    @PreAuthorize("isAuthenticated()")
    public Result<List<Map<String, Object>>> tools(@AuthenticationPrincipal LoginUser user,
                                                   @RequestParam(defaultValue = "standard") String mode) {
        ToolContext ctx = ctx(user, null, mode);

        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentTool t : registry.visibleTools(ctx)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("label", t.label());
            m.put("description", t.description());
            m.put("write", t.writeOperation());
            m.put("sensitivity", t.sensitivity().name().toLowerCase());
            out.add(m);
        }
        return Result.ok(out);
    }

    /* ================================================================= 内部工具方法 */

    /**
     * 构造工具上下文。四个端点（chat / confirm / input / tools）共用一处，
     * 避免 `mode` 在某个入口漏传——漏传会静默退回标准模式（写能力重新开放），
     * 属 D11 第 ③ 类"不报错但语义失效"的变更。
     */
    private ToolContext ctx(LoginUser user, Long conversationId, String mode) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return new ToolContext(user, auth == null ? null : auth.getAuthorities(),
                conversationId, null, mode);
    }

    /**
     * 提取「附加上下文」引用（{@code [{type, id, label}]}）。
     *
     * <p>前端只传"选了什么"，校验与解析在 {@code ChatRefsResolver} —— 这里只做
     * 形状收窄与数量上限（脏数据跳过而非报错：附加是锦上添花，不该让整轮问答失败）。
     * 上限取 {@link ChatRefsResolver#MAX_REFS}，再多说明用户要做的是批量操作，
     * 该走批量工具而不是堆上下文。
     */
    private List<Map<String, Object>> extractRefs(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Object type = m.get("type");
            Object id = m.get("id");
            if (type == null || id == null) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("type", String.valueOf(type));
            r.put("id", id);
            if (m.get("label") != null) r.put("label", String.valueOf(m.get("label")));
            out.add(r);
            if (out.size() >= ChatRefsResolver.MAX_REFS) break;
        }
        return out;
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    private Long lng(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try {
            String s = String.valueOf(o).trim();
            return s.isEmpty() ? null : Long.parseLong(s);
        } catch (Exception e) {
            return null;
        }
    }
}
