package com.pams.module.agent.orchestration;

import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.entity.AgentRun;
import com.pams.module.agent.entity.AgentStep;
import com.pams.module.agent.registry.AgentCatalog;
import com.pams.module.agent.registry.AgentRegistry;
import com.pams.module.agent.stream.AgentEventPublisher;
import com.pams.module.agent.stream.AgentStreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 协调者（Coordinator）—— 多 Agent 协作的调度入口（B 批次）。
 *
 * <p><b>B 批次范围</b>：确定性任务分解（规则路由）+ 协作 Run/Step 落库 + 协作事件。
 * <b>专家 Agent 的真实执行在 C 批次接入</b>——本批次 Run 只登记「协调者的规划步骤」，
 * 计划本身完整写入黑板（{@code agent_run.blackboard_json}），可回放、可展示拓扑。
 *
 * <p>分工：{@code CoordinatorAgent} 只做「分解 + 调度记录」，不直调业务工具；
 * 真实工具调用由专家 Agent 在 C 批次承接（沿用现有 {@code ToolRegistry} 的三层权限防护）。
 */
@Slf4j
@Service
public class CoordinatorAgent {

    private final AgentRegistry registry;
    private final AgentRunService runService;
    private final AgentEventPublisher eventPublisher;
    private final SpecialistExecutor specialistExecutor;

    public CoordinatorAgent(AgentRegistry registry,
                            AgentRunService runService,
                            AgentEventPublisher eventPublisher,
                            SpecialistExecutor specialistExecutor) {
        this.registry = registry;
        this.runService = runService;
        this.eventPublisher = eventPublisher;
        this.specialistExecutor = specialistExecutor;
    }

    /** 规则式任务分解：按关键词把目标路由到专家 Agent，产出协作计划。 */
    public Blackboard plan(String goal) {
        String g = goal == null ? "" : goal;
        List<String> agents = new ArrayList<>();

        boolean asksQuery = containsAny(g, "查", "找", "多少", "哪些", "情况", "进度", "?", "？");
        boolean asksPlan = containsAny(g, "策划", "方案", "议程", "策划书");
        boolean asksWriting = containsAny(g, "推文", "新闻", "报道", "公告", "稿件");
        boolean asksSchedule = containsAny(g, "排班", "值班", "任务分解", "座位");
        boolean asksData = containsAny(g, "加分", "素拓", "通知", "建成员", "导入");

        if (asksQuery && !asksWriting) {
            agents.add(AgentCatalog.RETRIEVAL);
        }
        if (asksPlan) {
            agents.add(AgentCatalog.PLANNING);
        }
        if (asksWriting) {
            agents.add(AgentCatalog.WRITING);
        }
        if (asksSchedule) {
            agents.add(AgentCatalog.SCHEDULING);
        }
        if (asksData) {
            agents.add(AgentCatalog.DATA);
        }
        if (agents.isEmpty()) {
            agents.add(AgentCatalog.RETRIEVAL);
        }
        // 产出型任务追加审查（反思回路）
        if (agents.stream().anyMatch(a -> a.equals(AgentCatalog.WRITING)
                || a.equals(AgentCatalog.PLANNING) || a.equals(AgentCatalog.DATA))) {
            agents.add(AgentCatalog.REVIEWER);
        }
        // 只调度「已注册且启用」的 Agent（注册表为准，避免调度到未启用的能力）
        java.util.Set<String> registered = registry.enabled().stream()
                .map(com.pams.module.agent.entity.AgentDefinition::getName)
                .collect(java.util.stream.Collectors.toSet());
        agents.removeIf(a -> !registered.contains(a));

        Blackboard bb = new Blackboard();
        bb.setGoal(goal);
        int id = 1;
        for (String a : agents) {
            Blackboard.PlanItem item = new Blackboard.PlanItem();
            item.setId(id++);
            item.setAgent(a);
            item.setTask(taskFor(a, goal));
            bb.getPlan().add(item);
        }
        return bb;
    }

    /**
     * 执行一次协作 Run（C 批次：规划 → 逐步委派专家 → 落库）。
     *
     * @param ctx      工具执行上下文（含发起用户与权限）；为专家执行时以「发起用户身份」鉴权
     * @param streamId 可空；非空时推送协作事件（前端协作视图）
     */
    public AgentRun run(String goal, ToolContext ctx, Long conversationId, String mode, String streamId) {
        Blackboard bb = plan(goal);
        bb.setGoal(goal);
        bb.setMode(mode);

        Long userId = ctx == null ? null : ctx.getUserId();
        String username = ctx != null && ctx.getLoginUser() != null
                ? ctx.getLoginUser().getUsername() : null;

        AgentRun run = runService.startRun(conversationId, userId, goal, mode);

        // 协调者：规划步骤
        emit(username, streamId, AgentStreamEvent.agentStart(
                streamId, eventPublisher.nextSeq(streamId), AgentCatalog.COORDINATOR, null, null));
        long t0 = System.currentTimeMillis();
        AgentStep step = runService.recordStep(run.getId(), AgentCatalog.COORDINATOR,
                runService.nextSeq(run.getId()), null,
                runService.toJson(java.util.Map.of("goal", goal == null ? "" : goal)),
                runService.toJson(bb.getPlan()), AgentStep.STATUS_SUCCESS, System.currentTimeMillis() - t0);
        long planDuration = System.currentTimeMillis() - t0;
        emit(username, streamId, AgentStreamEvent.agentEnd(
                streamId, eventPublisher.nextSeq(streamId), AgentCatalog.COORDINATOR,
                step.getId(), AgentStep.STATUS_SUCCESS, planDuration));
        bb.addFinding(AgentCatalog.COORDINATOR, "已生成 " + bb.getPlan().size() + " 步协作计划", 1.0);

        // 逐步委派专家执行（P1 串行）
        specialistExecutor.executePlan(run, bb, ctx, streamId);

        return runService.finishRun(run.getId(), AgentRun.STATUS_SUCCESS, bb);
    }

    private void emit(String username, String streamId, AgentStreamEvent event) {
        if (streamId != null && username != null) {
            eventPublisher.publish(username, event);
        }
    }

    private String taskFor(String agent, String goal) {
        return switch (agent) {
            case AgentCatalog.RETRIEVAL -> "检索与「" + safe(goal) + "」相关的数据与知识";
            case AgentCatalog.PLANNING -> "产出活动方案 / 策划书章节草稿";
            case AgentCatalog.WRITING -> "撰写推文 / 新闻稿 / 公告草稿";
            case AgentCatalog.SCHEDULING -> "生成排班 / 筹备任务";
            case AgentCatalog.DATA -> "批量事务（先出拟执行清单）";
            case AgentCatalog.REVIEWER -> "对产物做完整性 / 事实 / 合规审查";
            default -> "执行子任务";
        };
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
