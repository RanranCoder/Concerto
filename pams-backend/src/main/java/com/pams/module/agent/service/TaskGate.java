package com.pams.module.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.BizException;
import com.pams.common.PageResult;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.AgentTask;
import com.pams.module.agent.repository.AgentTaskRepository;
import com.pams.module.agent.stream.AgentEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 确认门禁（业务设计方案 D2）—— 写操作的强制关卡。
 *
 * <p><b>为什么不用"LLM 复述一遍让用户说'确认'"</b>：LLM 无法可靠地"记住"影响范围，
 * 且用户在对话里说"好的"语义模糊，无法作为审计依据。
 *
 * <p>因此：dry-run 落 {@code agent_task(PENDING_CONFIRM)} → 用户携带一次性 confirmToken 确认
 * → <b>乐观锁</b>抢占（{@code UPDATE ... WHERE status='PENDING_CONFIRM'}）→ 影响行数 0 即判定重复提交，
 * 直接返回幂等结果。
 *
 * <p>关键性质：确认门禁是<b>后端强制的</b>，不依赖前端自觉。
 */
@Slf4j
@Service
public class TaskGate {

    private final AgentTaskRepository repository;
    private final ToolRegistry registry;
    private final AuditService auditService;
    private final AgentEventPublisher publisher;
    private final ObjectMapper mapper = new ObjectMapper();

    public TaskGate(AgentTaskRepository repository, ToolRegistry registry,
                    AuditService auditService, AgentEventPublisher publisher) {
        this.repository = repository;
        this.registry = registry;
        this.auditService = auditService;
        this.publisher = publisher;
    }

    /**
     * dry-run 阶段：落一条 PENDING_CONFIRM 任务，返回带一次性令牌的清单。
     * 由写类工具的 {@code execute}（读阶段）调用。
     *
     * <p>兼容入口（无内容预览）——非内容类工具走这里。
     */
    @Transactional
    public AgentActionPlan createPlan(ToolContext ctx, AgentTool tool, String summary,
                                      int total, List<AgentActionPlan.PlanItem> items,
                                      List<AgentActionPlan.SkippedItem> skipped,
                                      List<String> detailLines, boolean reversible) {
        return createPlan(ctx, tool, summary, total, items, skipped, detailLines, reversible, null);
    }

    /**
     * 同上，额外携带<b>内容预览</b>（§3.9.1）：策划书章节草稿 / 推文草稿这类"写进去的是一篇长文"的工具，
     * 把正文一并落 plan_json，前端在确认卡上给「查看全文」抽屉——先通读再确认。
     */
    @Transactional
    public AgentActionPlan createPlan(ToolContext ctx, AgentTool tool, String summary,
                                      int total, List<AgentActionPlan.PlanItem> items,
                                      List<AgentActionPlan.SkippedItem> skipped,
                                      List<String> detailLines, boolean reversible, String preview) {
        // 多轮「接续」（用户反馈 item 3）：出新的确认卡之前，先把本会话里旧的待确认任务作废，
        // 保证"会话里始终只有一张可确认卡"。用户纠正后不会出现"首轮卡片还在、上下文已走"的错位。
        supersedePending(ctx);

        AgentTask t = new AgentTask();
        t.setUserId(ctx.getUserId());
        t.setConversationId(ctx.getConversationId());
        // V26 历史恢复：任务与产生它的流绑定，前端据此把确认卡挂回对应消息
        t.setStreamId(ctx.getStreamId());
        t.setToolName(tool.name());
        t.setTaskType("SYNC");
        t.setStatus(AgentTask.PENDING_CONFIRM);
        t.setSummary(summary);
        t.setConfirmToken("ct_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        t.setCreatedAt(LocalDateTime.now());

        AgentActionPlan plan = new AgentActionPlan(null, summary, total, items, skipped,
                detailLines, reversible, t.getConfirmToken(), preview);
        try {
            t.setPlanJson(mapper.writeValueAsString(plan.toJson()));
        } catch (Exception e) {
            log.warn("plan_json 序列化失败", e);
        }
        AgentTask saved = repository.save(t);

        return new AgentActionPlan(saved.getId(), summary, total, items, skipped,
                detailLines, reversible, saved.getConfirmToken(), preview);
    }

    /* ====================================================== 多轮「接续」支持 */

    /**
     * 作废本会话内仍待确认的旧清单（置 CANCELLED）。
     *
     * <p>范围刻意<b>只覆盖 PENDING_CONFIRM</b>：
     * 表单卡（WAITING_INPUT）是"模型在向用户要信息"，与"清单待确认"不是同一语义，
     * 误作废会让 D2.5 表单补全链路半途失效。会话内多张确认卡才是用户反馈的问题（上下文偏移）。
     */
    private void supersedePending(ToolContext ctx) {
        if (ctx == null || ctx.getConversationId() == null) return;
        try {
            List<AgentTask> olds = repository.findByConversationIdAndStatusOrderByIdAsc(
                    ctx.getConversationId(), AgentTask.PENDING_CONFIRM);
            for (AgentTask old : olds) {
                repository.cancelPending(old.getId(), ctx.getUserId(), LocalDateTime.now());
            }
            if (!olds.isEmpty()) {
                log.info("多轮接续：作废会话 {} 内 {} 条旧待确认清单", ctx.getConversationId(), olds.size());
            }
        } catch (Exception e) {
            // 作废失败不该挡住新一轮（用户仍可手动取消旧卡）
            log.warn("作废旧待确认清单失败：conv={} err={}", ctx.getConversationId(), e.getMessage());
        }
    }

    /**
     * 本会话当前待确认清单的一句话摘要，注入 LLM 上下文（修「上下文偏移」）。
     *
     * <p>为什么需要：不告诉模型"上一轮已经出了一张清单"，它在用户纠正时会当成全新任务，
     * 于是重复出卡或答非所问；告知后它可以给出"按你的说法，把清单改成……"的修订意见。
     */
    public String pendingPlanNote(Long conversationId, Long userId) {
        if (conversationId == null) return null;
        try {
            List<AgentTask> pendings = repository.findByConversationIdAndStatusOrderByIdAsc(
                    conversationId, AgentTask.PENDING_CONFIRM);
            if (pendings.isEmpty()) return null;
            AgentTask t = pendings.get(pendings.size() - 1);
            String preview = "";
            if (t.getPlanJson() != null) {
                try {
                    Map<String, Object> m = mapper.readValue(t.getPlanJson(), Map.class);
                    Object np = m.get("total");
                    preview = np == null ? "" : ("，共 " + np + " 项");
                } catch (Exception ignored) {
                    // 解析失败就只给工具名与摘要，不影响主链路
                }
            }
            return "【当前待确认清单】用户上一轮已生成一张待确认清单（工具 " + t.getToolName()
                    + "，摘要：" + (t.getSummary() == null ? "" : t.getSummary()) + preview
                    + "，任务 ID " + t.getId() + "）。若用户本轮是在纠正/修改它，"
                    + "请按新说法重新调用相应工具生成新清单（新清单会自动作废旧的），"
                    + "不要只做口头说明；若用户是另起一件不相干的事，正常处理即可。";
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 表单补全阶段（D2.5 Form Elicitation）：落一条 WAITING_INPUT 任务。
     *
     * <p>plan_json 复用为表单载体：{@code {type:'form', formKey, title, description,
     * fields, values, toolName, originalArgs}}——{@code originalArgs} 保留 LLM 首次调用
     * 时的原始参数，用户提交表单后合并重跑 dry-run。与 PENDING_CONFIRM 共用一列，
     * 无需迁移。
     *
     * @return 表单 VO（含 taskId，供前端弹窗提交）
     */
    @Transactional
    public Map<String, Object> createForm(ToolContext ctx, AgentTool tool, AgentInputForm form,
                                          Map<String, Object> originalArgs) {
        AgentTask t = new AgentTask();
        t.setUserId(ctx.getUserId());
        t.setConversationId(ctx.getConversationId());
        // V26 历史恢复：同 createPlan
        t.setStreamId(ctx.getStreamId());
        t.setToolName(tool.name());
        t.setTaskType("SYNC");
        t.setStatus(AgentTask.WAITING_INPUT);
        t.setSummary(form.title());
        t.setCreatedAt(LocalDateTime.now());

        Map<String, Object> payload = form.toJson();
        payload.put("type", "form");
        payload.put("toolName", tool.name());
        payload.put("originalArgs", originalArgs == null ? Map.of() : originalArgs);
        try {
            t.setPlanJson(mapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("表单 payload 序列化失败", e);
        }
        AgentTask saved = repository.save(t);

        payload.put("taskId", saved.getId());
        payload.put("status", saved.getStatus());
        return payload;
    }

    /**
     * 用户提交表单：合并参数 → 重跑工具 dry-run → 参数齐全则转入确认门禁。
     *
     * <p><b>⚠️ 本方法刻意不开整体事务</b>（与 {@link #confirm} 同款理由）：内部会经
     * {@code registry.dispatch} 重跑业务 Service 的读路径，失败要能优雅返回而不是 500；
     * 对本表的多处写都是 {@code save} 整行更新（无 @Modifying），且各分支独立提交可接受。
     *
     * <p>返回四种状态：
     * <ul>
     *   <li>{@code PLAN} —— 参数齐全，任务已转 PENDING_CONFIRM（附 plan + 一次性令牌）</li>
     *   <li>{@code FORM} —— 仍缺信息，返回更新后的表单（保持 WAITING_INPUT）</li>
     *   <li>{@code ERROR} —— dry-run 校验失败（如日期先后矛盾），保留 WAITING_INPUT 供修改重提</li>
     *   <li>{@code CANCELLED} —— 业务拦停（如无可处理对象），任务终态化</li>
     * </ul>
     */
    public Map<String, Object> submitInput(Long taskId, Map<String, Object> values, ToolContext ctx) {
        AgentTask task = repository.findByIdAndUserId(taskId, ctx.getUserId())
                .orElseThrow(() -> new BizException(4404, "任务不存在或无权访问"));
        if (!AgentTask.WAITING_INPUT.equals(task.getStatus())) {
            throw new BizException(4400, "该任务不在待填写状态");
        }
        if (values == null || values.isEmpty()) {
            throw new BizException(4400, "请填写表单内容");
        }

        Map<String, Object> payload = readFormPayload(task);
        if (payload == null || !"form".equals(payload.get("type"))) {
            throw new BizException(4400, "任务数据异常，请重新发起");
        }
        AgentTool tool = registry.find(task.getToolName())
                .orElseThrow(() -> new BizException(4404, "工具已下线：" + task.getToolName()));
        if (!registry.visibleTo(tool, ctx)) {
            throw new BizException(4403, "无权限执行该操作");
        }

        // 合并参数：originalArgs（LLM 已提取）在前，表单值（用户显式填写）覆盖同名键
        Map<String, Object> merged = new LinkedHashMap<>();
        if (payload.get("originalArgs") instanceof Map<?, ?> raw) {
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                merged.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (e.getValue() == null) continue;
            String v = String.valueOf(e.getValue()).trim();
            if (v.isEmpty()) continue;
            merged.put(e.getKey(), v);
        }

        ToolResult result;
        try {
            // 与编排器同一入口：权限断言 + 工具执行（D3 第 2 层防护在提交时依然生效）
            result = registry.dispatch(tool.name(), ctx, merged);
        } catch (AccessDeniedException e) {
            throw new BizException(4403, "无权限执行该操作");
        } catch (Exception e) {
            log.warn("灵思表单提交后重跑失败：task={} tool={}", taskId, task.getToolName(), e);
            result = ToolResult.failed(e.getMessage() == null ? "执行异常" : e.getMessage());
        }

        // 审计留痕（D9）：表单提交是写链路的一环
        try {
            auditService.log(ctx, tool.name(), merged, "WRITE_INPUT",
                    tool.sensitivity(), result.status());
        } catch (Exception e) {
            log.warn("灵思表单审计旁路异常已忽略：tool={} err={}", tool.name(), e.getMessage());
        }

        // 分支 A：仍缺信息 —— 更新表单（预填值带上用户刚填的内容），保持 WAITING_INPUT
        if (result.needsInput() && result.form() != null) {
            Map<String, Object> next = result.form().toJson();
            next.put("type", "form");
            next.put("toolName", tool.name());
            next.put("originalArgs", merged);
            try {
                task.setPlanJson(mapper.writeValueAsString(next));
            } catch (Exception ignored) {
                // 序列化失败保留旧 payload：用户可重试
            }
            repository.save(task);
            next.put("taskId", taskId);
            next.put("status", AgentTask.WAITING_INPUT);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "FORM");
            out.put("form", next);
            return out;
        }

        // 分支 B：参数齐全 —— 同一任务转入确认门禁（换发一次性令牌，plan_json 整体替换为清单）
        if (result.plan() != null) {
            AgentActionPlan raw = result.plan();
            task.setStatus(AgentTask.PENDING_CONFIRM);
            task.setSummary(raw.summary());
            task.setConfirmToken("ct_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            try {
                task.setPlanJson(mapper.writeValueAsString(raw.toJson()));
            } catch (Exception e) {
                log.warn("表单转清单序列化失败", e);
            }
            repository.save(task);
            AgentActionPlan persisted = new AgentActionPlan(task.getId(), raw.summary(), raw.total(),
                    raw.items(), raw.skipped(), raw.detailLines(), raw.reversible(), task.getConfirmToken(), raw.preview());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "PLAN");
            out.put("plan", persisted.toJson());
            return out;
        }

        // 分支 C：业务拦停（工具 OK 但无清单可出）—— 任务终态化，避免悬挂
        if (result.isOk()) {
            task.setStatus(AgentTask.CANCELLED);
            task.setFinishedAt(LocalDateTime.now());
            Map<String, Object> rj = new LinkedHashMap<>();
            rj.put("status", "CANCELLED");
            rj.put("message", result.summary());
            try {
                task.setResultJson(mapper.writeValueAsString(rj));
            } catch (Exception ignored) {
                // 结果 JSON 非关键数据
            }
            repository.save(task);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "CANCELLED");
            out.put("message", result.summary());
            return out;
        }

        // 分支 D：校验失败（如日期矛盾）—— 保留 WAITING_INPUT，用户改完可重提
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ERROR");
        out.put("error", result.error() == null ? result.summary() : result.error());
        return out;
    }

    private Map<String, Object> readFormPayload(AgentTask task) {
        if (task.getPlanJson() == null) return null;
        try {
            return AgentJson.readObject(mapper, task.getPlanJson());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 确认并执行。
     *
     * <p><b>⚠️ 本方法刻意不开整体事务</b>（与 {@link #createPlan} / {@link #cancel} 不同）：
     * <ol>
     *   <li>{@code @Modifying} 更新语句必须有事务——事务已下沉声明到
     *       {@link AgentTaskRepository#claimForRun} 与 {@link AgentTaskRepository#finish}
     *       各自的仓储方法上（首轮回归实测：缺事务直接抛
     *       {@code TransactionRequiredException}，确认接口 500）。</li>
     *   <li>若在本方法上整体挂 {@code @Transactional}，{@code writeTool.commit} 内部的业务
     *       Service（同为 REQUIRED，加入同一共享事务）一旦抛异常，会把共享事务标记为
     *       rollback-only；此处 catch 转为 FAILED 结果后，代理提交阶段仍会抛
     *       {@code UnexpectedRollbackException}——与审计旁路（AuditService）同款陷阱。
     *       事务下沉后：抢占即持久化、业务写入各事务自洽、终态独立落库，
     *       执行失败可优雅落 FAILED 而不是 500。</li>
     * </ol>
     *
     * @param taskId       任务 ID
     * @param confirmToken 一次性令牌（防重放）
     * @param ctx          执行上下文
     */
    public Map<String, Object> confirm(Long taskId, String confirmToken, ToolContext ctx) {
        AgentTask task = repository.findByIdAndUserId(taskId, ctx.getUserId())
                .orElseThrow(() -> new BizException(4404, "任务不存在或无权访问"));

        // 幂等：已执行过的任务直接返回已有结果（不报错——前端不做自动重试，避免"到底成没成"的歧义）
        if (!AgentTask.PENDING_CONFIRM.equals(task.getStatus())) {
            return toResultVo(task, true);
        }
        if (confirmToken == null || !confirmToken.equals(task.getConfirmToken())) {
            throw new BizException(4403, "确认令牌无效或已过期");
        }
        if (isExpired(task)) {
            throw new BizException(4403, "确认清单已过期，请重新生成");
        }
        AgentTool tool = registry.find(task.getToolName())
                .orElseThrow(() -> new BizException(4404, "工具已下线：" + task.getToolName()));
        // Changing modes must not consume a pending confirmation or leave RUNNING behind.
        if (tool.writeOperation() && (ctx.isReadonly() || ctx.isKb())) {
            throw new BizException(4403, "当前模式不允许写操作，请切换标准模式后确认");
        }

        // 【乐观锁抢占】—— 影响行数 0 = 重复提交
        int claimed = repository.claimForRun(taskId, AgentTask.PENDING_CONFIRM, AgentTask.RUNNING,
                LocalDateTime.now());
        if (claimed == 0) {
            AgentTask fresh = repository.findById(taskId).orElseThrow();
            return toResultVo(fresh, true);
        }

        // 二次断言：确认时再校验一次权限（任务可能是很久之前创建的，期间用户角色可能已变）
        if (!registry.visibleTo(tool, ctx)) {
            boolean readonly = ctx.isReadonly() && tool.writeOperation();
            String why = readonly ? "只读模式下不执行写操作" : "确认时权限校验未通过";
            repository.finish(taskId, AgentTask.FAILED, "{\"message\":\"" + why + "\"}", LocalDateTime.now());
            throw new BizException(4403, readonly ? "当前为只读模式，已阻止该写操作" : "无权限执行该操作");
        }

        if (!(tool instanceof WriteAgentTool writeTool)) {
            repository.finish(taskId, AgentTask.FAILED, "{\"message\":\"该工具不是写类工具\"}", LocalDateTime.now());
            throw new BizException(4400, "该任务不可执行");
        }

        AgentActionPlan plan = readPlan(task);
        WriteAgentTool.CommitResult result;
        String status;
        try {
            result = writeTool.commit(ctx, plan);
            status = result.resultStatus();
        } catch (Exception e) {
            log.error("灵思写操作执行失败：task={} tool={}", taskId, task.getToolName(), e);
            result = WriteAgentTool.CommitResult.failed(e.getMessage() == null ? "执行异常" : e.getMessage());
            status = AgentTask.FAILED;
        }

        String resultJson;
        Map<String, Object> resultMap = null;
        try {
            Map<String, Object> rj = new LinkedHashMap<>();
            rj.put("status", result.status());
            rj.put("affectedCount", result.affectedCount());
            rj.put("failedCount", result.failedCount());
            rj.put("message", result.message());
            rj.put("detail", result.detail());
            rj.put("references", result.references());
            rj.put("skipped", plan.skipped());
            resultMap = rj;
            resultJson = mapper.writeValueAsString(rj);
        } catch (Exception e) {
            resultJson = "{\"status\":\"" + status + "\"}";
        }
        repository.finish(taskId, status, resultJson, LocalDateTime.now());

        // 审计留痕（D9，写操作 100% 覆盖）
        auditService.logWrite(ctx, task, result.affectedCount(), status);

        // 推任务完成事件（D7 长任务；前端 useWebSocket 加一个订阅即可）
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("type", "AGENT_TASK_DONE");
        done.put("taskId", taskId);
        done.put("status", status);
        done.put("summary", task.getSummary());
        done.put("affectedCount", result.affectedCount());
        done.put("failedCount", result.failedCount());
        done.put("message", result.message());
        // 产物清单（UI v2 §4「产物卡」）：与 toResultVo 的 artifacts 同构 ——
        // 前端两条取数路径（REST 确认返回 / WS 异步完成）拿到的结构完全一致，避免分叉。
        done.put("artifacts", toArtifacts(resultMap));
        done.put("detail", result.detail());
        done.put("skipped", plan.skipped());
        done.put("toolName", task.getToolName());
        // 供前端判断"这次完成是不是当前正开着的那条会话"
        done.put("conversationId", task.getConversationId());
        try {
            if (ctx.getLoginUser() != null) {
                publisher.publishTaskDone(ctx.getLoginUser().getUsername(), done);
            }
        } catch (Exception e) {
            log.warn("任务完成事件推送失败", e);
        }

        AgentTask finished = repository.findById(taskId).orElse(task);
        return toResultVo(finished, false);
    }

    @Transactional
    public void cancel(Long taskId, Long userId) {
        AgentTask task = repository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new BizException(4404, "任务不存在或无权访问"));
        boolean cancellable = AgentTask.PENDING_CONFIRM.equals(task.getStatus())
                || AgentTask.WAITING_INPUT.equals(task.getStatus());
        if (!cancellable) {
            throw new BizException(4400, "仅待确认或待填写的任务可以取消");
        }
        if (repository.cancelPending(taskId, userId, LocalDateTime.now()) == 0) {
            throw new BizException(4400, "任务状态已变化，请刷新后查看执行结果");
        }
    }

    private AgentActionPlan readPlan(AgentTask task) {
        if (task.getPlanJson() == null) return AgentActionPlan.empty();
        try {
            Map<String, Object> m = AgentJson.readObject(mapper, task.getPlanJson());
            Object itemsRaw = m.get("items");
            Object skippedRaw = m.get("skipped");
            List<AgentActionPlan.PlanItem> items = new ArrayList<>();
            if (itemsRaw instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> mm) {
                        items.add(new AgentActionPlan.PlanItem(
                                mm.get("id") == null ? null : Long.valueOf(String.valueOf(mm.get("id"))),
                                str(mm.get("label")), str(mm.get("sub"))));
                    }
                }
            }
            List<AgentActionPlan.SkippedItem> skipped = new ArrayList<>();
            if (skippedRaw instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> mm) {
                        skipped.add(new AgentActionPlan.SkippedItem(
                                str(mm.get("label")), str(mm.get("sub")), str(mm.get("reason"))));
                    }
                }
            }
            List<String> detailLines = new ArrayList<>();
            if (m.get("detailLines") instanceof List<?> l) {
                for (Object o : l) detailLines.add(str(o));
            }
            return new AgentActionPlan(task.getId(), str(m.get("summary")),
                    m.get("total") == null ? 0 : Integer.parseInt(String.valueOf(m.get("total"))),
                    items, skipped, detailLines,
                    Boolean.TRUE.equals(m.get("reversible")), task.getConfirmToken(), str(m.get("preview")));
        } catch (Exception e) {
            log.warn("plan_json 反序列化失败，退回空清单", e);
            return AgentActionPlan.empty();
        }
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    public Map<String, Object> detail(Long taskId, Long userId) {
        AgentTask t = repository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new BizException(4404, "任务不存在或无权访问"));
        return toResultVo(t, true);
    }

    public PageResult<Map<String, Object>> list(Long userId, String status, int page, int size) {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), 100);
        Page<AgentTask> p;
        if (status == null || status.isBlank()) {
            p = repository.findByUserIdOrderByIdDesc(userId, PageRequest.of(page - 1, size));
        } else {
            List<AgentTask> all = repository.findByUserIdAndStatusOrderByIdDesc(userId, status);
            int from = Math.min((page - 1) * size, all.size());
            int to = Math.min(from + size, all.size());
            List<AgentTask> slice = all.subList(from, to);
            PageResult<Map<String, Object>> r = new PageResult<>();
            List<Map<String, Object>> recs = new ArrayList<>();
            for (AgentTask t : slice) recs.add(toResultVo(t, true));
            r.setRecords(recs);
            r.setTotal(all.size());
            r.setCurrent(page);
            r.setSize(size);
            return r;
        }
        PageResult<Map<String, Object>> r = new PageResult<>();
        List<Map<String, Object>> recs = new ArrayList<>();
        for (AgentTask t : p.getContent()) recs.add(toResultVo(t, true));
        r.setRecords(recs);
        r.setTotal(p.getTotalElements());
        r.setCurrent(page);
        r.setSize(size);
        return r;
    }

    public long countPending(Long userId) {
        return repository.countByUserIdAndStatus(userId, AgentTask.PENDING_CONFIRM);
    }

    private Map<String, Object> toResultVo(AgentTask t, boolean withPlan) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", t.getId());
        vo.put("toolName", t.getToolName());
        vo.put("status", t.getStatus());
        vo.put("summary", t.getSummary());
        vo.put("conversationId", t.getConversationId());
        vo.put("createdAt", t.getCreatedAt());
        vo.put("confirmedAt", t.getConfirmedAt());
        vo.put("finishedAt", t.getFinishedAt());
        vo.put("expired", isExpired(t));
        if (withPlan && t.getPlanJson() != null) {
            try {
                Map<String, Object> parsed = AgentJson.readObject(mapper, t.getPlanJson());
                if (AgentTask.WAITING_INPUT.equals(t.getStatus()) && "form".equals(parsed.get("type"))) {
                    // 待填写任务：plan_json 里是表单 payload，放 form 键，避免前端把表单误当清单
                    parsed.put("taskId", t.getId());
                    vo.put("form", parsed);
                } else {
                    parsed.put("planId", t.getId());
                    parsed.put("confirmToken", AgentTask.PENDING_CONFIRM.equals(t.getStatus()) ? t.getConfirmToken() : "");
                    vo.put("plan", parsed);
                }
            } catch (Exception ignored) {
                vo.put("plan", null);
            }
        }
        Map<String, Object> resultMap = null;
        if (t.getResultJson() != null) {
            try {
                resultMap = AgentJson.readObject(mapper, t.getResultJson());
                vo.put("result", resultMap);
            } catch (Exception ignored) {
                vo.put("result", null);
            }
        }
        // 产物清单（UI v2 §4）：结构化到顶层，前端不必钻进 result.references 再换键名
        vo.put("artifacts", toArtifacts(resultMap));
        // reversible 从 plan_json 结构化读取，不做字符串 contains——
        // JSON 列经 Hibernate 回读时序列化格式可能带空格，字符串匹配会误判（回归实测踩到）
        vo.put("reversible", readReversible(t));
        return vo;
    }

    /**
     * 把 {@link WriteAgentTool.CommitResult#references()} 映射为前端「产物卡」结构。
     *
     * <p>为什么不直接改 {@code CommitResult.references} 的键名：那份结构被
     * {@code agent_task.result_json} 持久化，改键名会让历史任务的结果块在新前端下渲染不出来
     * （D11 第 ③ 类语义变更）。因此保持落库格式不动，只在出网处做一次映射。
     *
     * <p>输出的键与 UI 改版方案 v2 §4 一一对应：
     * {@code entityType / entityId / name / path}。
     * 变更量（+N / -M）不在这里——它是<b>卡片级</b>属性，来自 {@code affectedCount} /
     * {@code failedCount} / {@code detail}，避免把"这次共写了多少"错误地摊到每个产物头上。
     */
    static List<Map<String, Object>> toArtifacts(Map<String, Object> result) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (result == null) return out;
        Object refs = result.get("references");
        if (!(refs instanceof List<?> list)) return out;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> r)) continue;
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("entityType", r.get("type"));
            a.put("entityId", r.get("id"));
            a.put("name", r.get("label"));
            a.put("path", r.get("link"));
            out.add(a);
        }
        return out;
    }

    private boolean readReversible(AgentTask t) {
        if (t.getPlanJson() == null) return false;
        try {
            Map<String, Object> m = AgentJson.readObject(mapper, t.getPlanJson());
            return Boolean.TRUE.equals(m.get("reversible"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * PENDING_CONFIRM 超过 24h 视为已过期。
     * 前端只做视觉提示（禁用确认按钮），<b>真正的令牌失效由后端保证</b>。
     */
    public boolean isExpired(AgentTask t) {
        return AgentTask.PENDING_CONFIRM.equals(t.getStatus())
                && t.getCreatedAt() != null
                && t.getCreatedAt().isBefore(LocalDateTime.now().minusHours(24));
    }
}
