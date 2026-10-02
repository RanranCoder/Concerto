package com.pams.module.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.PageResult;
import com.pams.module.agent.core.PolicyGuard;
import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.entity.AgentAuditLog;
import com.pams.module.agent.entity.AgentTask;
import com.pams.module.agent.repository.AgentAuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计（业务设计方案 D9）。
 *
 * <p>设计范式<b>沿用现有 {@code AttendanceAudit}</b>，不是在项目里另创一套。
 *
 * <p>附带收益：系统目前"读操作无审计"，灵思恰好是补齐这一缺口的契机
 * （谁、何时、通过什么问法读取了哪些数据）。
 *
 * <p>写操作用 {@code REQUIRES_NEW}：即便主流程事务回滚，审计记录也必须留下。
 */
@Slf4j
@Service
public class AuditService {

    private final AgentAuditLogRepository repository;
    private final PolicyGuard policyGuard;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuditService(AgentAuditLogRepository repository, PolicyGuard policyGuard) {
        this.repository = repository;
        this.policyGuard = policyGuard;
    }

    /**
     * 写操作审计（确认人 + 确认时间 + 影响条数）。
     *
     * <p><b>⚠️ 为什么要 manually-flush + noRollbackFor</b>（踩过坑）：
     * {@code REQUIRES_NEW} 的事务在<b>提交阶段</b>才可能抛 {@code UnexpectedRollbackException}，
     * 这个异常发生在方法体之外（代理的 commit 里），方法内的 {@code try/catch} <b>捕不到</b>，
     * 于是会冒泡打断调用方的主流程——审计本该是旁路，绝不能反过来搞挂主链路。
     *
     * <p>因此这里做两件事：
     * <ol>
     *   <li>显式 {@code saveAndFlush} —— 把写库异常提前到方法体内，能被 catch 住</li>
     *   <li>{@code noRollbackFor = Exception.class} —— 即便仍有异常，也不再把事务标记为回滚</li>
     * </ol>
     * 两重保险后，审计失败最多是"少一条审计"，不会影响用户看到的业务结果。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = Exception.class)
    public void logWrite(ToolContext ctx, AgentTask task, Integer affectedCount, String status) {
        try {
            AgentAuditLog log = new AgentAuditLog();
            log.setUserId(task.getUserId());
            log.setConversationId(task.getConversationId());
            log.setTaskId(task.getId());
            log.setToolName(task.getToolName());
            log.setArgsDigest(policyGuard.digest(Map.of("taskId", task.getId())));
            log.setSideEffect("WRITE");
            log.setConfirmedBy(ctx == null ? null : ctx.getUserId());
            log.setResultStatus(status);
            log.setAffectedCount(affectedCount);
            log.setCreatedAt(LocalDateTime.now());
            repository.saveAndFlush(log);
        } catch (Exception e) {
            log.warn("灵思写操作审计写入失败：task={}", task == null ? null : task.getId(), e);
        }
    }

    /**
     * 通用审计记录（读也可记，用于"谁通过什么问法读了哪些数据"）。
     *
     * <p>事务与异常口径同 {@link #logWrite}：审计是旁路，失败不影响主流程。
     * <b>另一个坑</b>：本方法被 {@code AgentOrchestrator} 在<b>只读事务上下文</b>里调用过
     * （工具内部 Service 带 {@code @Transactional(readOnly=true)}）。{@code REQUIRES_NEW}
     * 会挂起外层、另起可写事务，语义正确；但若这里不 flush，异常依然会推迟到 commit 阶段逃逸。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = Exception.class)
    public void log(ToolContext ctx, String toolName, Map<String, Object> args,
                    String sideEffect, Sensitivity sensitivity, String resultStatus) {
        if (ctx == null || ctx.getUserId() == null) return;
        // restricted 一律不落明文（D4）
        if (sensitivity == Sensitivity.RESTRICTED) return;
        try {
            AgentAuditLog log = new AgentAuditLog();
            log.setUserId(ctx.getUserId());
            log.setConversationId(ctx.getConversationId());
            log.setToolName(toolName);
            log.setArgsDigest(policyGuard.digest(args));
            log.setSideEffect(sideEffect);
            log.setResultStatus(resultStatus);
            log.setCreatedAt(LocalDateTime.now());
            repository.saveAndFlush(log);
        } catch (Exception e) {
            log.warn("灵思审计写入失败：tool={}", toolName, e);
        }
    }

    public PageResult<Map<String, Object>> list(Long userId, int page, int size) {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), 100);
        Page<AgentAuditLog> p = repository.findByUserIdOrderByIdDesc(userId, PageRequest.of(page - 1, size));
        List<Map<String, Object>> recs = new ArrayList<>();
        for (AgentAuditLog a : p.getContent()) {
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("id", a.getId());
            vo.put("toolName", a.getToolName());
            vo.put("sideEffect", a.getSideEffect());
            vo.put("resultStatus", a.getResultStatus());
            vo.put("affectedCount", a.getAffectedCount());
            vo.put("confirmedBy", a.getConfirmedBy());
            vo.put("createdAt", a.getCreatedAt());
            recs.add(vo);
        }
        PageResult<Map<String, Object>> r = new PageResult<>();
        r.setRecords(recs);
        r.setTotal(p.getTotalElements());
        r.setCurrent(page);
        r.setSize(size);
        return r;
    }

    /** 供 args_json 落库（脱敏后的入参） */
    public String toJson(Map<String, Object> args) {
        try {
            return mapper.writeValueAsString(policyGuard.sanitizeArgs(args));
        } catch (Exception e) {
            return "{}";
        }
    }
}
