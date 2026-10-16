package com.pams.module.agent.orchestration;

import com.pams.module.agent.core.AgentTool;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.core.ToolRegistry;
import com.pams.module.agent.core.ToolResult;
import com.pams.module.agent.entity.AgentDefinition;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.registry.AgentRegistry;
import com.pams.module.agent.service.AgentLlmCaller;
import com.pams.module.agent.stream.AgentEventPublisher;
import com.pams.module.agent.stream.AgentStreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 专家 Agent（C 批次）：按「工具作用域 ∩ 当前用户可见工具」装配能力，执行一个子任务。
 *
 * <p><b>权限不放大</b>（策划 §3.5）：专家能看到的工具 = 其声明作用域 ∩ {@code ToolRegistry.visibleTo(ctx)}，
 * 即「Agent 职责 ∩ 发起用户权限」。执行一律走 {@link ToolRegistry#dispatch}（D3 第 2 层断言），
 * 因此多 Agent 协作不改变系统的授权边界。
 *
 * <p><b>两种执行方式</b>：
 * <ul>
 *   <li>默认：把子任务与作用域内工具的 schema 交给 LLM，取回工具调用；</li>
 *   <li>显式：调用方直接给出工具调用（{@code predecided}），跳过 LLM —— 用于测试与 P4 委派。</li>
 * </ul>
 */
@Slf4j
@Service
public class  SpecialistAgent {

    /** 单个工具调用的执行记录。 */
    public record ToolInvocation(String name, String label, String status, String summary, long durationMs) {
    }

    /** 专家执行结果。 */
    public record Result(String agent, String text, List<ToolInvocation> invocations) {
    }

    private final AgentRegistry registry;
    private final ToolRegistry toolRegistry;
    private final AgentLlmCaller llmCaller;
    private final AgentEventPublisher publisher;

    public SpecialistAgent(AgentRegistry registry, ToolRegistry toolRegistry,
                           AgentLlmCaller llmCaller, AgentEventPublisher publisher) {
        this.registry = registry;
        this.toolRegistry = toolRegistry;
        this.llmCaller = llmCaller;
        this.publisher = publisher;
    }

    /**
     * 执行一个专家子任务。
     *
     * @param predecided 显式工具调用（非空则跳过 LLM）
     */
    public Result execute(String agentName, String task, ToolContext ctx,
                          List<LlmProvider.ToolCallRequest> predecided,
                          String streamId, String username) {
        List<AgentTool> scoped = scopedTools(agentName, ctx);
        List<LlmProvider.ToolCallRequest> calls = predecided;
        String text = null;

        if (calls == null) {
            List<LlmProvider.ToolSpec> specs = scoped.stream()
                    .map(t -> new LlmProvider.ToolSpec(t.name(), t.description(), t.parametersSchema()))
                    .toList();
            List<LlmProvider.ChatMessage> messages = List.of(
                    LlmProvider.ChatMessage.text("system", rolePrompt(agentName)),
                    LlmProvider.ChatMessage.text("user", task == null ? "" : task));
            LlmProvider.ChatResponse resp = llmCaller.callLlm(messages, specs, ctx, streamId, 0);
            if (resp.toolCalls() != null && !resp.toolCalls().isEmpty()) {
                calls = resp.toolCalls();
            } else {
                text = resp.content();
            }
        }

        List<ToolInvocation> invocations = new ArrayList<>();
        if (calls != null) {
            Set<String> allowed = new LinkedHashSet<>(scoped.stream().map(AgentTool::name).toList());
            for (LlmProvider.ToolCallRequest c : calls) {
                invocations.add(invoke(agentName, c, allowed, ctx, streamId, username));
            }
        }
        return new Result(agentName, text, invocations);
    }

    private ToolInvocation invoke(String agentName, LlmProvider.ToolCallRequest call, Set<String> allowed,
                                  ToolContext ctx, String streamId, String username) {
        String name = call.name();
        String label = toolRegistry.find(name).map(AgentTool::label).orElse(name);
        long t0 = System.currentTimeMillis();
        boolean emit = streamId != null && username != null;

        // 作用域约束：LLM 可能幻觉出作用域外的工具名（正确性由 dispatch 的权限断言兜底）
        if (!allowed.contains(name)) {
            log.warn("专家 Agent「{}」尝试越出工具作用域：{}", agentName, name);
            if (emit) {
                emitStart(username, streamId, name, label);
            }
            long d = System.currentTimeMillis() - t0;
            if (emit) {
                emitEnd(username, streamId, name, label, "DENIED", d, "超出工具作用域");
            }
            return new ToolInvocation(name, label, "DENIED", "超出工具作用域", d);
        }

        if (emit) {
            emitStart(username, streamId, name, label);
        }
        try {
            ToolResult r = toolRegistry.dispatch(name, ctx, call.arguments());
            long d = System.currentTimeMillis() - t0;
            if (emit) {
                emitEnd(username, streamId, name, label, r.status(), d, r.error());
            }
            return new ToolInvocation(name, label, r.status(), r.summary(), d);
        } catch (AccessDeniedException e) {
            long d = System.currentTimeMillis() - t0;
            if (emit) {
                emitEnd(username, streamId, name, label, "DENIED", d, e.getMessage());
            }
            return new ToolInvocation(name, label, "DENIED", e.getMessage(), d);
        } catch (Exception e) {
            long d = System.currentTimeMillis() - t0;
            log.warn("专家 Agent 工具执行失败：agent={} tool={}", agentName, name, e);
            if (emit) {
                emitEnd(username, streamId, name, label, "FAILED", d, e.getMessage());
            }
            return new ToolInvocation(name, label, "FAILED", e.getMessage(), d);
        }
    }

    private void emitStart(String username, String streamId, String name, String label) {
        publisher.publish(username, AgentStreamEvent.toolStart(
                streamId, publisher.nextSeq(streamId), name, label));
    }

    /**
     * 专家的工具池 = 声明作用域 ∩ 当前用户可见工具。
     * 作用域为 null 表示「不限」（如协调者无工具、审查者只读黑板 → 空作用域返回空）。
     */
    public List<AgentTool> scopedTools(String agentName, ToolContext ctx) {
        List<AgentTool> visible = toolRegistry.visibleTools(ctx);
        List<String> scope = registry.byName(agentName).map(registry::toolScopeOf).orElse(List.of());
        if (scope == null) {
            return visible;
        }
        Set<String> allow = new LinkedHashSet<>(scope);
        return visible.stream().filter(t -> allow.contains(t.name())).toList();
    }

    private String rolePrompt(String agentName) {
        return registry.byName(agentName).map(AgentDefinition::getRolePrompt)
                .filter(p -> p != null && !p.isBlank())
                .orElse("你是学生组织工作台的专家 Agent（" + agentName + "），只依据检索到的证据作答，绝不编造。");
    }

    private void emit(String username, String streamId, AgentStreamEvent e) {
        if (streamId != null && username != null) {
            publisher.publish(username, e);
        }
    }

    private void emitEnd(String username, String streamId, String tool, String label,
                         String status, long durationMs, String error) {
        emit(username, streamId, AgentStreamEvent.toolEnd(
                streamId, publisher.nextSeq(streamId), tool, label, status, durationMs, error));
    }
}
