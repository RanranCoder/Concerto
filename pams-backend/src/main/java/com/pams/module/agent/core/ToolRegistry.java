package com.pams.module.agent.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表 + <b>D3 三层防护</b>（业务设计方案 D3，本方案最容易踩的坑）。
 *
 * <p><b>坑</b>：现有权限注解 99% 挂在 <b>Controller</b> 上，{@code Service} 方法本身<b>不带</b>
 * {@code @PreAuthorize}。Agent 直接调 Service ⇒ <b>绕过全部方法级鉴权</b>。
 *
 * <p><b>三层防护</b>：
 * <ol>
 *   <li><b>工具声明所需权限码</b>：{@link AgentTool#requiredPermissions()}。
 *       工具粒度与权限码一一对应，不允许"一个工具混用多个权限域"。</li>
 *   <li><b>派发前断言</b>：{@link #dispatch} 在调用前用 <b>{@code Authentication.getAuthorities()}</b> 校验
 *       （<b>不是</b> {@code ctx.loginUser.permissions}——{@code LoginUser} 不含该字段），
 *       不满足抛 {@link AccessDeniedException}（403），<b>且该工具不会出现在给 LLM 的 tools 列表中</b>
 *       （不可见 ⇒ 不可诱导）。</li>
 *   <li><b>测试兜底</b>：为每个写类工具写"低权限角色调用必失败"的集成测试。</li>
 * </ol>
 */
@Slf4j
@Component
public class ToolRegistry {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.pams.module.permission.service.TenantAuthorizationService authorization;

    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.pams.module.agent.service.AgentRequestService requests;
    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();

    public ToolRegistry(List<AgentTool> all) {
        all.stream()
          .sorted(Comparator.comparing(AgentTool::name))
          .forEach(t -> tools.put(t.name(), t));
        log.info("灵思工具注册完成，共 {} 个（读 {} / 写 {}）", tools.size(),
                tools.values().stream().filter(t -> !t.writeOperation()).count(),
                tools.values().stream().filter(AgentTool::writeOperation).count());
    }

    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 【第 2 层防护】工具可见性判定——决定该工具是否出现在给 LLM 的 tools 列表里。
     * 不可见 ⇒ 不可诱导，这是比"调用了再拒绝"更彻底的防护。
     *
     * <p>四个条件取"与"：运行模式 → 领导专属 → 权限码。
     *
     * <p><b>只读/对话模式（用户主动降档或切换任务轴）放在最前面</b>：写类工具整体不可见。
     * 之所以不单独加一道"调用时拒绝"，是因为本方法同时被
     * {@code visibleTools}（给 LLM 的 tools 数组 + /api/agent/tools 面板）、
     * {@link #dispatch}（执行断言）、{@code TaskGate.confirm}（确认时二次断言）复用 ——
     * 一处收敛，四处生效；漏掉任何一处都会出现"看不到却能执行"的裂缝。
     *
     * <p><b>2026-09-14 补对话模式（权限审计之外的 R1 双保险）</b>：
     * 对话模式本身走 {@code KbPipeline} 独立流水线、根本不进工具循环，
     * 所以理论上到不了这里。但仍然加一道 —— 因为"没有工具循环"是编排层的约定，
     * 而这里是<b>物理约束</b>。约定会被后来的人改，物理约束不会。
     */
    public boolean visibleTo(AgentTool tool, ToolContext ctx) {
        if (ctx == null) return false;
        if (tool.writeOperation() && (ctx.isReadonly() || ctx.isKb())) return false;
        if (tool.requiresLeader() && !ctx.hasAnyPermission("content:manage","party:view")) return false;
        if (authorization != null && ctx.getUserId()!=null) {
            var live = authorization.business(ctx.getUserId());
            if (tool.requiresLeader() && !live.contains(tool.name().equals("queryPartyMemberProgress") ? "party:view" : "content:manage")) return false;
            var required = tool.requiredPermissions();
            if (required != null && !required.isEmpty() && !(tool.anyPermission() ? required.stream().anyMatch(live::contains) : live.containsAll(required))) return false;
        }

        List<String> req = tool.requiredPermissions();
        if (req == null || req.isEmpty()) return true;

        return tool.anyPermission()
                ? ctx.hasAnyPermission(req.toArray(new String[0]))
                : req.stream().allMatch(ctx::hasPermission);
    }

    /** 当前用户可见的工具清单（供 GET /api/agent/tools 与提示卡过滤） */
    public List<AgentTool> visibleTools(ToolContext ctx) {
        List<AgentTool> out = new ArrayList<>();
        for (AgentTool t : tools.values()) {
            if (visibleTo(t, ctx)) out.add(t);
        }
        return out;
    }

    /**
     * 【第 2 层防护】派发：调用前显式断言，不满足直接抛 403。
     *
     * <p>注意这里与 {@link #visibleTo} 是<b>两道</b>独立检查，不是重复——
     * 可见性是"让 LLM 看不到"，断言是"即便被诱导到也不放行"。
     * LLM 可能凭空编造工具名或参数，只有后者能兜住。
     */
    public ToolResult dispatch(String toolName, ToolContext ctx, Map<String, Object> args) {
        if(requests!=null && ctx.getStreamId()!=null && requests.byStream(ctx.getStreamId()).map(com.pams.module.agent.entity.AgentRequest::isCancelled).orElse(false)) throw new java.util.concurrent.CancellationException("本次请求已停止");
        if (authorization != null) {
            var ceiling=ctx.getAuthorities();
            boolean durable=ctx.getWorkItemId()!=null;
            ctx = authorization.refresh(ctx);
            if(durable) ctx=ctx.restrictTo(ceiling);
        }
        AgentTool tool = tools.get(toolName);
        if (tool == null) {
            // LLM 幻觉工具名（D5 风险表）：不抛异常，返回可读结果让编排器继续
            return ToolResult.failed("未知工具：" + toolName);
        }

        if (!visibleTo(tool, ctx)) {
            log.warn("灵思工具越权被拒：tool={} user={} role={} mode={}",
                    toolName, ctx == null ? null : ctx.getUserId(),
                    ctx == null ? null : ctx.getRoleCode(),
                    ctx == null ? null : ctx.getMode());
            // 只读模式与"无权限"是两回事，文案必须分开——
            // 否则用户会以为自己的账号权限被收走了（实际是本次会话的主动降档）
            if (ctx != null && ctx.isReadonly() && tool.writeOperation()) {
                throw new AccessDeniedException("当前为只读模式，不会执行写操作：" + toolName);
            }
            // 对话模式同理：这是任务轴切换，不是权限问题。话术要指出"切回标准模式即可"
            if (ctx != null && ctx.isKb() && tool.writeOperation()) {
                throw new AccessDeniedException("当前为对话模式（知识库问答），不会执行写操作：" + toolName);
            }
            // 这里抛 AccessDeniedException，由 GlobalExceptionHandler 统一转 403
            throw new AccessDeniedException("无权限执行工具：" + toolName);
        }

        return tool.execute(ctx, args == null ? Map.of() : args);
    }

    /** 全部工具（含不可见的），管理/排障用 */
    public List<AgentTool> all() {
        return new ArrayList<>(tools.values());
    }

    /**
     * 给 LLM 的工具清单：只包含可见工具，且只暴露 name/description/parameters。
     * 转换在编排层完成，这里提供中间结构。
     */
    public List<Map<String, Object>> toolSchemas(ToolContext ctx) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentTool t : visibleTools(ctx)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("label", t.label());
            m.put("runningLabel", t.runningLabel());
            m.put("sensitivity", t.sensitivity().name().toLowerCase());
            m.put("write", t.writeOperation());
            out.add(m);
        }
        return out;
    }
}
