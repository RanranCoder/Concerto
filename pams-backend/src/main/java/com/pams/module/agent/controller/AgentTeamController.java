package com.pams.module.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.Result;
import com.pams.module.agent.entity.AgentDefinition;
import com.pams.module.agent.entity.AgentRun;
import com.pams.module.agent.entity.AgentStep;
import com.pams.module.agent.orchestration.Blackboard;
import com.pams.module.agent.orchestration.CoordinatorAgent;
import com.pams.module.agent.orchestration.AgentRunService;
import com.pams.module.agent.registry.AgentRegistry;
import com.pams.security.LoginUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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
@RestController
@RequestMapping("/api/agent/team")
public class AgentTeamController {

    private final CoordinatorAgent coordinator;
    private final AgentRunService runService;
    private final AgentRegistry registry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentTeamController(CoordinatorAgent coordinator, AgentRunService runService,
                               AgentRegistry registry) {
        this.coordinator = coordinator;
        this.runService = runService;
        this.registry = registry;
    }

    /** 只做任务分解，不落库（预览协作计划）。 */
    @PostMapping("/plan")
    public Result<Blackboard> plan(@RequestBody TeamRequest req) {
        return Result.ok(coordinator.plan(req.goal()));
    }

    /** 执行一次协作 Run（B 批次：登记规划步骤 + 落黑板）。 */
    @PostMapping("/run")
    public Result<Map<String, Object>> run(@RequestBody TeamRequest req,
                                           @AuthenticationPrincipal LoginUser lu) {
        AgentRun run = coordinator.run(req.goal(), lu.getId(), req.conversationId(),
                req.mode() == null ? "P1" : req.mode(), null, null);
        return Result.ok(toDetail(run));
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
