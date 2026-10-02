package com.pams.module.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.BizException;
import com.pams.common.OrgContext;
import com.pams.module.agent.entity.AgentRun;
import com.pams.module.agent.entity.AgentStep;
import com.pams.module.agent.repository.AgentRunRepository;
import com.pams.module.agent.repository.AgentStepRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 协作 Run / Step 的落库与黑板序列化（B 批次）。
 *
 * <p>只负责「记录」，不含编排逻辑（那是 {@code CoordinatorAgent} 的职责）。
 * 单条 step 独立事务写入，保证协作过程中途失败也能留下已完成的步骤（可回放）。
 */
@Slf4j
@Service
public class AgentRunService {

    private final AgentRunRepository runRepository;
    private final AgentStepRepository stepRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentRunService(AgentRunRepository runRepository,
                           AgentStepRepository stepRepository) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
    }

    /** 开启一次协作 Run。 */
    @Transactional
    public AgentRun startRun(Long conversationId, Long userId, String goal, String mode) {
        AgentRun run = new AgentRun();
        run.setConversationId(conversationId);
        run.setUserId(userId);
        run.setOrgId(OrgContext.getOrDefault());
        run.setGoal(goal);
        run.setMode(mode);
        run.setStatus(AgentRun.STATUS_RUNNING);
        run.setStartedAt(LocalDateTime.now());
        return runRepository.save(run);
    }

    /** 记录一个协作步骤（独立事务，失败不牵连已完成的步骤）。 */
    @Transactional
    public AgentStep recordStep(Long runId, String agentName, int seq, Long parentStepId,
                                String inputJson, String outputJson, String status, long durationMs) {
        AgentStep step = new AgentStep();
        step.setRunId(runId);
        step.setAgentName(agentName);
        step.setSeq(seq);
        step.setParentStepId(parentStepId);
        step.setInputJson(inputJson);
        step.setOutputJson(outputJson);
        step.setStatus(status);
        step.setDurationMs((int) durationMs);
        step.setCreatedAt(LocalDateTime.now());
        return stepRepository.save(step);
    }

    public int nextSeq(Long runId) {
        return (int) stepRepository.countByRunId(runId) + 1;
    }

    /** 结束 Run：落终态 + 黑板整体落库（供回放）。 */
    @Transactional
    public AgentRun finishRun(Long runId, String status, Blackboard blackboard) {
        AgentRun run = runRepository.findById(runId)
                .orElseThrow(() -> new BizException(4004, "协作 Run 不存在"));
        run.setStatus(status);
        run.setFinishedAt(LocalDateTime.now());
        run.setBlackboardJson(toJson(blackboard));
        return runRepository.save(run);
    }

    /** 归属校验：只能看自己的 Run。 */
    public AgentRun getOwned(Long runId, Long userId) {
        return runRepository.findByIdAndUserId(runId, userId)
                .orElseThrow(() -> new BizException(4004, "协作 Run 不存在或无权访问"));
    }

    public List<AgentStep> steps(Long runId) {
        return stepRepository.findByRunIdOrderBySeqAsc(runId);
    }

    public String toJson(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("序列化协作数据失败", e);
            return null;
        }
    }
}
