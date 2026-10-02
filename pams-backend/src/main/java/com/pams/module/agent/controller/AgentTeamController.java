package com.pams.module.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.Result;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.entity.AgentDefinition;
import com.pams.module.agent.entity.AgentRun;
import com.pams.module.agent.entity.AgentStep;
import com.pams.module.agent.orchestration.Blackboard;
import com.pams.module.agent.orchestration.CoordinatorAgent;
import com.pams.module.agent.orchestration.AgentRunService;
import com.pams.module.agent.registry.AgentRegistry;
import com.pams.module.agent.stream.AgentEventPublisher;
import com.pams.module.agent.stream.AgentStreamEvent;
import com.pams.security.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.Executor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多 Agent 协作 API（B 批次）。
 *
 * <p>与既有 {@code /api/agent}（单 Agent 灵思）并存：
 * {@code /api/agent/team/*} 提供协作规划与 Run 查询，不改动既有单 Agent 链路。
 * 登录即可访问；Run 按 {@code user_id} 强隔离。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/team")
public class AgentTeamController {

    private final CoordinatorAgent coordinator;
    private final AgentRunService runService;
    private final AgentRegistry registry;
    private final AgentEventPublisher eventPublisher;
    private final java.util.concurrent.Executor executor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentTeamController(CoordinatorAgent coordinator, AgentRunService runService,
                               AgentRegistry registry, AgentEventPublisher eventPublisher,
                               @org.springframework.beans.factory.annotation.Qualifier("agentExecutor")
                               java.util.concurrent.Executor executor) {
        this.coordinator = coordinator;
        this.runService = runService;
        this.registry = registry;
        this.eventPublisher = eventPublisher;
        this.executor = executor;
    }

    /** 只做任务分解，不落库（预览协作计划）。 */
    @PostMapping("/plan")
    public Result<Blackboard> plan(@RequestBody TeamRequest req) {
        return Result.ok(coordinator.plan(req.goal()));
    }

    /**
     * 发起一次协作 Run（D 批次：<b>异步</b>）。
     * 立即返回 {@code {runId, streamId}}；执行在 agentExecutor 中后台进行，
     * 过程经 {@code /user/queue/agent/stream} 实时推送 {@code AGENT_*}/{@code RUN_DONE} 事件，
     * 前端收到 {@code RUN_DONE} 后再拉 {@code GET /runs/{id}} 取最终详情。
     */
    @PostMapping("/run")
    public Result<Map<String, Object>> run(@RequestBody TeamRequest req,
                                           @AuthenticationPrincipal LoginUser lu) {
        String streamId = eventPublisher.newStream("run", lu.getId());
        ToolContext ctx = ToolContext.current(req.conversationId(), streamId);
        String mode = req.mode() == null ? "P1" : req.mode();
        AgentRun run = coordinator.start(req.goal(), ctx, req.conversationId(), mode);

        final String goal = req.goal();
        final String username = lu.getUsername();
        executor.execute(() -> {
            try {
                coordinator.executeRun(run, goal, ctx, streamId, mode);
                publishRunDone(username, streamId, run.getId(), AgentRun.STATUS_SUCCESS);
            } catch (Exception e) {
                log.warn("协作 Run 执行失败：runId={}", run.getId(), e);
                runService.finishRun(run.getId(), AgentRun.STATUS_FAILED, new Blackboard());
                publishRunDone(username, streamId, run.getId(), AgentRun.STATUS_FAILED);
            }
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.getId());
        out.put("streamId", streamId);
        return Result.ok(out);
    }

    private void publishRunDone(String username, String streamId, Long runId, String status) {
        try {
            eventPublisher.publish(username, AgentStreamEvent.runDone(
                    streamId, eventPublisher.nextSeq(streamId), runId, status));
        } catch (Exception e) {
            log.warn("推送 RUN_DONE 失败：runId={}", runId, e);
        }
    }

    /** Run 详情（含步骤与黑板），按归属校验。 */
    @GetMapping("/runs/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id,
                                              @AuthenticationPrincipal LoginUser lu) {
        return Result.ok(toDetail(runService.getOwned(id, lu.getId())));
    }

    /** 已注册的 Agent 定义（供前端展示专家团队）。 */
    @GetMapping("/agents")
    public Result<List<AgentDefinition>> agents() {
        return Result.ok(registry.enabled());
    }

    private Map<String, Object> toDetail(AgentRun run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", run.getId());
        m.put("conversationId", run.getConversationId());
        m.put("goal", run.getGoal());
        m.put("mode", run.getMode());
        m.put("status", run.getStatus());
        m.put("startedAt", run.getStartedAt());
        m.put("finishedAt", run.getFinishedAt());
        m.put("blackboard", parseBlackboard(run.getBlackboardJson()));
        List<Map<String, Object>> steps = runService.steps(run.getId()).stream().map(this::toStep).toList();
        m.put("steps", steps);
        return m;
    }

    private Map<String, Object> toStep(AgentStep s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("agentName", s.getAgentName());
        m.put("seq", s.getSeq());
        m.put("parentStepId", s.getParentStepId());
        m.put("status", s.getStatus());
        m.put("durationMs", s.getDurationMs());
        m.put("output", s.getOutputJson());
        return m;
    }

    private Blackboard parseBlackboard(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Blackboard.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 协作请求体。 */
    public record TeamRequest(String goal, Long conversationId, String mode) {
    }
}
