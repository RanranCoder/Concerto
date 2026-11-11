package com.pams.module.agent.service;

import com.pams.common.BizException;
import com.pams.common.PageResult;
import com.pams.module.agent.core.PolicyGuard;
import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.entity.AgentConversation;
import com.pams.module.agent.entity.AgentMessage;
import com.pams.module.agent.entity.AgentTask;
import com.pams.module.agent.repository.AgentConversationRepository;
import com.pams.module.agent.repository.AgentMessageRepository;
import com.pams.module.agent.repository.AgentTaskRepository;
import com.pams.module.agent.stream.ThinkingTrace;
import com.pams.module.knowledge.service.PermissionResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话与上下文（业务设计方案 D8）。
 *
 * <p>两条关键约束：
 * <ul>
 *   <li>会话归属<b>严格绑定 userId</b>，他人会话不可见（越权读会话是常见漏洞，在服务层强校验）。</li>
 *   <li>敏感消息<b>不落明文</b>——由 {@link PolicyGuard#forStorage} 在<b>写入时</b>就替换为摘要，
 *       因此敏感内容从未被写入，连"90 天后清理任务"都省掉（决策 6）。</li>
 * </ul>
 *
 * <p>V26 历史恢复：assistant 消息额外落 {@code refs_json}（工具结果引用），
 * {@link #messages} 按 stream_id 反查 {@code agent_task}，把<b>仍可交互</b>的
 * 确认卡（PENDING_CONFIRM）/表单卡（WAITING_INPUT）挂回产生它的那条消息——
 * 重新进入会话不再丢卡。终态任务（已执行/已取消）不恢复交互卡。
 */
@Slf4j
@Service
public class ConversationStore {

    private final AgentConversationRepository conversationRepository;
    private final AgentMessageRepository messageRepository;
    private final AgentTaskRepository taskRepository;
    private final PolicyGuard policyGuard;
    /**
     * V27：历史引用回看时的权限复核。
     *
     * <p>依赖方向是 agent → knowledge（单向），符合方案里的模块边界约定：
     * 知识库有自己的业务边界与运维入口，不是灵思的从属物。
     */
    private final PermissionResolver permissionResolver;
    private final ObjectMapper mapper = new ObjectMapper();
    @org.springframework.beans.factory.annotation.Autowired
    private com.pams.module.agent.orchestration.DagScheduler scheduler;
    @org.springframework.beans.factory.annotation.Autowired
    private AgentRequestService requests;

    public ConversationStore(AgentConversationRepository conversationRepository,
                             AgentMessageRepository messageRepository,
                             AgentTaskRepository taskRepository,
                             PolicyGuard policyGuard,
                             PermissionResolver permissionResolver) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.taskRepository = taskRepository;
        this.policyGuard = policyGuard;
        this.permissionResolver = permissionResolver;
    }

    @Transactional
    public AgentConversation create(Long userId, String firstMessage) {
        AgentConversation c = new AgentConversation();
        c.setUserId(userId);
        c.setTitle(titleFrom(firstMessage));
        c.setDeleted(0);
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        return conversationRepository.save(c);
    }

    /** 会话标题取用户首条消息前 18 字——不弹窗让用户命名（打断成本高于收益，灵思 §3.3） */
    private String titleFrom(String msg) {
        if (msg == null || msg.isBlank()) return "新对话";
        String t = msg.replaceAll("\\s+", " ").trim();
        return t.length() > 18 ? t.substring(0, 18) : t;
    }

    /** 取会话并强校验归属 */
    public AgentConversation require(Long conversationId, Long userId) {
        AgentConversation c = conversationRepository
                .findByIdAndUserIdAndDeleted(conversationId, userId, 0)
                .orElseThrow(() -> new BizException(4404, "会话不存在或无权访问"));
        return c;
    }

    public PageResult<Map<String, Object>> list(Long userId, int page, int size) {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), 100);
        Page<AgentConversation> p = conversationRepository
                .findByUserIdAndDeletedOrderByUpdatedAtDesc(userId, 0, PageRequest.of(page - 1, size));

        List<Long> ids = p.getContent().stream().map(AgentConversation::getId).toList();
        Map<Long, List<AgentTask>> tasks = new HashMap<>();
        Map<Long, AgentMessage> latestMessages = new HashMap<>();
        if (!ids.isEmpty()) {
            for (AgentTask t : taskRepository.findByUserIdAndConversationIdInOrderByIdDesc(userId, ids)) {
                tasks.computeIfAbsent(t.getConversationId(), ignored -> new ArrayList<>()).add(t);
            }
            for (AgentMessage m : messageRepository.findLatestByConversationIds(ids)) {
                latestMessages.put(m.getConversationId(), m);
            }
        }

        List<Map<String, Object>> records = new ArrayList<>();
        for (AgentConversation c : p.getContent()) {
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("id", c.getId());
            vo.put("title", c.getTitle());
            vo.put("updatedAt", c.getUpdatedAt());
            vo.put("createdAt", c.getCreatedAt());
            List<AgentTask> conversationTasks = tasks.getOrDefault(c.getId(), List.of());
            vo.put("pendingTaskCount", conversationTasks.stream()
                    .filter(t -> AgentTask.PENDING_CONFIRM.equals(t.getStatus())).count());
            vo.put("status", conversationStatus(conversationTasks, latestMessages.get(c.getId())));
            records.add(vo);
        }
        PageResult<Map<String, Object>> r = new PageResult<>();
        r.setRecords(records);
        r.setTotal(p.getTotalElements());
        r.setCurrent(page);
        r.setSize(size);
        return r;
    }

    /** 工作状态来自持久化任务与最近一条消息，不把“没有待确认”误读成已完成。 */
    static String conversationStatus(List<AgentTask> tasks, AgentMessage latest) {
        if(latest!=null && java.util.Set.of("STOPPING","NEEDS_RECONCILE","REVIEW_REQUIRED").contains(java.util.Objects.toString(latest.getExecutionState(),""))) return latest.getExecutionState();
        for (String status : List.of(AgentTask.RUNNING, AgentTask.WAITING_INPUT, AgentTask.PENDING_CONFIRM)) {
            if (tasks.stream().anyMatch(t -> status.equals(t.getStatus()))) return status;
        }
        if (latest == null || !AgentMessage.ROLE_ASSISTANT.equals(latest.getRole())) return "READY";
        if ("RUNNING".equals(latest.getExecutionState())) return "RUNNING";
        if ("WAITING_INPUT".equals(latest.getExecutionState())) return "WAITING_INPUT";
        if ("WAITING_CONFIRM".equals(latest.getExecutionState())) return "PENDING_CONFIRM";
        if (latest.getExecutionState() != null && List.of("FAILED", "PARTIAL", "CANCELLED").contains(latest.getExecutionState())) return latest.getExecutionState();
        if (!tasks.isEmpty()) {
            AgentTask task = tasks.getFirst();
            if (task.getCreatedAt() != null && latest.getCreatedAt() != null
                    && !latest.getCreatedAt().isAfter(task.getFinishedAt() == null ? task.getCreatedAt() : task.getFinishedAt())) {
                return AgentTask.SUCCESS.equals(task.getStatus()) ? "COMPLETED" : task.getStatus();
            }
        }
        return "COMPLETED";
    }

    @Transactional
    public void rename(Long id, Long userId, String title) {
        if (title == null || title.isBlank()) throw new BizException(400, "标题不能为空");
        String t = title.trim();
        if (t.length() > 60) t = t.substring(0, 60);
        int n = conversationRepository.rename(id, userId, t);
        if (n == 0) throw new BizException(4404, "会话不存在或无权访问");
    }

    @Transactional
    public void delete(Long id, Long userId) {
        require(id, userId);
        conversationRepository.deleteWithMemory(id, userId, LocalDateTime.now());
    }

    @Transactional
    public void touch(Long id) {
        conversationRepository.touch(id, LocalDateTime.now());
    }

    /**
     * 落库一条消息。
     *
     * @param sensitivity 敏感级——<b>决定这条消息存什么</b>（决策 6 的写时脱敏）
     * @param summary     摘要（sensitive 级只存这个）
     * @param references  工具结果引用（V26）：随 assistant 消息持久化，历史恢复用；可为 null
     */
    @Transactional
    public AgentMessage save(Long conversationId, String role, String fullText,
                             Sensitivity sensitivity, String summary,
                             List<Map<String, Object>> toolCalls,
                             List<Map<String, Object>> references,
                             String streamId, String provider, Integer durationMs) {
        // 保留旧签名的重载：既有调用点（用户消息、办事型回答）零改动（D10 的相容做法）
        return save(conversationId, role, fullText, sensitivity, summary, toolCalls,
                references, streamId, provider, durationMs, null, null);
    }

    /**
     * 落库（V27 扩展：{@code mode} + {@code citations}）。
     *
     * @param mode       本轮运行模式（{@code readonly/standard/kb}）；老调用传 null。
     *                   用途：①历史回看渲染差异（知识问答轮显示依据卡）②按模式做效果统计
     * @param citations  本轮答案的依据（服务端回填）。与 {@code references} 语义不同：
     *                   references = "我查到了什么"，citations = "答案据此下了什么结论"。
     *                   <b>不能合并</b> —— 合并后历史回看无法区分"提到过"与"据以下结论"。
     */
    @Transactional
    public AgentMessage save(Long conversationId, String role, String fullText,
                             Sensitivity sensitivity, String summary,
                             List<Map<String, Object>> toolCalls,
                             List<Map<String, Object>> references,
                             String streamId, String provider, Integer durationMs,
                             String mode, List<Map<String, Object>> citations) {
        // V35/V36 起多两个 thinking*：老调用点零改动（前端历史回看时两者都是空数组）
        return save(conversationId, role, fullText, sensitivity, summary, toolCalls,
                references, streamId, provider, durationMs, mode, citations, null, null);
    }

    /** 敏感档下思维链每段保留的字符上限（与正文摘要 {@code summarize(reply, 100)} 同量级） */
    private static final int THINKING_SENSITIVE_MAX = 500;

    /**
     * 思维链两列（推理 / 旁白）的留存闸门 —— <b>与正文同一套策略</b>（D4 / 决策 6）：
     * NORMAL 全量、SENSITIVE 仅存摘要、RESTRICTED 一个字都不落。
     *
     * <p><b>为什么从"只放行 NORMAL"改成这样</b>：原先非 NORMAL 一律整列不落，
     * 与 D4 / 决策 6 以及 {@link Sensitivity} 自己的 javadoc（"sensitive 会话中仅存摘要"）
     * 都不一致。后果是同一轮里**正文留了摘要、过程区整块空白** —— 用户回看时"有结论、没过程"，
     * 正好打掉 §7.1 的验收点"过程区是真实历史"。
     *
     * <p><b>为什么这里是"截断"而不是像正文那样传入现成摘要</b>：思维链是模型的原始输出流，
     * 没有现成的摘要可存；为它单独跑一次 LLM 摘要既贵又慢，而敏感轮次占比很低，不值得。
     * 因此按字符截断，并在末尾显式标注"被截断"，避免被读成"推理就到这"。
     *
     * <p><b>必须在 JSON 内部截断，而不是截 JSON 文本</b>：这两列是 MySQL 的 JSON 列，
     * 直接截字符串会得到非法 JSON 被数据库拒绝（写不进去，且只在日志里报错）。
     */
    private String forThinkingStorage(Sensitivity level, String json) {
        if (json == null || json.isBlank()) return null;
        Sensitivity lv = level == null ? Sensitivity.NORMAL : level;
        if (!lv.canEgress()) return null;                 // restricted：不落内容
        if (lv != Sensitivity.SENSITIVE) return json;     // normal：全量
        try {
            JsonNode arr = mapper.readTree(json);
            if (arr == null || !arr.isArray()) return null;
            for (JsonNode node : arr) {
                if (!(node instanceof ObjectNode obj)) continue;
                // 推理分段用 text、旁白行用 note —— 两类都截
                for (String field : new String[]{"text", "note"}) {
                    String v = obj.path(field).asText("");
                    if (v.length() > THINKING_SENSITIVE_MAX) {
                        obj.put(field, v.substring(0, THINKING_SENSITIVE_MAX) + "…（敏感档仅存摘要）");
                    }
                }
            }
            return mapper.writeValueAsString(arr);
        } catch (Exception e) {
            // 解析不了就不落：宁可少留，也不要把非预期形态的内容写进去
            log.warn("思维链敏感档截断失败，按不落处理", e);
            return null;
        }
    }

    /**
     * 读侧：JSON 列 → 列表（**容忍 H2 的"字符串里再套一层 JSON"形态**，且失败不再静默）。
     *
     * <p><b>为什么需要它</b>：MySQL 的 JSON 列会把写入文本解析成 JSON 文档（读回即原文），
     * 而 H2（test profile）把 JSON 列当普通字符串，读回是 {@code "[{\"round\":0}]"} ——
     * 一个**字符串**。直接 {@code readValue(json, List.class)} 会抛异常，而此前这里
     * （五处 JSON 列全一样）都是 {@code catch (Exception ignored)} 吞掉并回落空数组：
     * 后果是**读侧在 H2 下静默失效**——"刷新后还能看到思维链 / 工具行 / 引用"这几条
     * 恰好都是验收点，却永远测不出来；线上万一出现同形态数据（或列被写成字符串）也是无声丢内容。
     *
     * <p>做法：先按原样解析；结果是字符串就再解一层；两次都失败**留 warn** 后回落空列表
     * （保留"一行坏数据不该炸掉整个历史列表"的既有容错，但不再无声无息）。
     */
    private List<Object> readJsonList(String json, String what) {
        if (json == null || json.isBlank()) return List.of();
        try {
            Object v = mapper.readValue(json, Object.class);
            if (v instanceof String s) v = mapper.readValue(s, Object.class);
            return v instanceof List<?> list ? new ArrayList<>(list) : List.of();
        } catch (Exception e) {
            log.warn("{} 解析失败，历史回放将缺这部分内容：{}", what, e.getMessage());
            return List.of();
        }
    }

    /**
     * 落库（V35 扩展：{@code thinkingJson}；V36 扩展：{@code thinkingNotesJson}）。
     *
     * @param thinkingJson      本轮**模型推理**分段（{@link ThinkingTrace#toJson(String)} 的产物）。
     * @param thinkingNotesJson 本轮**编排旁白**（{@link ThinkingTrace#notesJson(String)} 的产物）。
     *                          两者都按与正文**同一套策略**留存（见 {@link #forThinkingStorage}）。
     */
    @Transactional
    public AgentMessage save(Long conversationId, String role, String fullText,
                             Sensitivity sensitivity, String summary,
                             List<Map<String, Object>> toolCalls,
                             List<Map<String, Object>> references,
                             String streamId, String provider, Integer durationMs,
                             String mode, List<Map<String, Object>> citations,
                             String thinkingJson, String thinkingNotesJson) {
        AgentMessage m = new AgentMessage();
        m.setConversationId(conversationId);
        m.setRole(role);
        m.setSensitivity(sensitivity.name().toLowerCase());
        m.setContent(policyGuard.forStorage(sensitivity, fullText, summary));
        m.setThinkingJson(forThinkingStorage(sensitivity, thinkingJson));
        m.setThinkingNotesJson(forThinkingStorage(sensitivity, thinkingNotesJson));
        if (toolCalls != null && !toolCalls.isEmpty()) {
            try {
                m.setToolCalls(mapper.writeValueAsString(toolCalls));
            } catch (Exception e) {
                log.warn("tool_calls 序列化失败", e);
            }
        }
        if (references != null && !references.isEmpty()) {
            try {
                m.setRefsJson(mapper.writeValueAsString(references));
            } catch (Exception e) {
                log.warn("refs_json 序列化失败", e);
            }
        }
        if (citations != null && !citations.isEmpty()) {
            try {
                m.setCitationsJson(mapper.writeValueAsString(citations));
            } catch (Exception e) {
                log.warn("citations_json 序列化失败", e);
            }
        }
        m.setMode(mode);
        m.setStreamId(streamId);
        m.setProvider(provider);
        m.setDurationMs(durationMs);
        m.setCreatedAt(LocalDateTime.now());
        return messageRepository.save(m);
    }

    @Transactional
    public void deleteMessage(Long id) {
        messageRepository.deleteById(id);
    }

    /**
     * 历史消息（分页，倒序取出后反转为正序）。assistant 消息附带可交互的任务卡（plan/form）。
     *
     * <p><b>⚠️ 必须传 {@code ctx}（V27 起）</b>：参数不是可选的装饰 ——
     * 它用于对历史消息里的 citations 做**实时回源复核**。
     *
     * <p>为什么必须这样做（方案 G8 / R21）：用户在 3 月问过"某党员的发展情况"，
     * 回答里的片段落库；6 月他被调离组织部门。若不做复核，他重开旧对话
     * **仍能读到姓名与阶段** —— 权限收紧将永不生效。
     *
     * <p>刻意把 {@code ctx} 设为必填（不提供省略它的重载）：漏传的后果是
     * "看不出任何异常，只是越权内容继续可见"，属 D11 第 ③ 类静默失效。
     * 强制传参能让"忘记复核"在编译期暴露。
     */
    public PageResult<Map<String, Object>> messages(Long conversationId, Long userId, int page, int size,
                                                    ToolContext ctx) {
        require(conversationId, userId);
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), 100);
        Page<AgentMessage> p = messageRepository
                .findByConversationIdOrderByIdDesc(conversationId, PageRequest.of(page - 1, size));

        List<AgentMessage> content = new ArrayList<>(p.getContent());
        // 倒序取出后反转为正序，保证前端按时间线渲染
        java.util.Collections.reverse(content);

        // V26 历史恢复：按 stream_id 一次查出本页消息关联的任务（避免 N+1），
        // 把仍可交互的确认卡/表单卡挂回产生它的那条 assistant 消息
        Map<String, AgentTask> taskByStream = loadInteractiveTasks(conversationId, content, userId);

        List<Map<String, Object>> records = new ArrayList<>();
        for (AgentMessage m : content) {
            Map<String, Object> vo = toVo(m);
            if (requests != null && AgentMessage.ROLE_USER.equals(m.getRole())) {
                requests.forMessage(m.getStreamId(), m.getConversationId(), ctx).ifPresent(request -> {
                    if (request.isSteeringOpen() && !request.isCancelled() && "RUNNING".equals(request.getStatus())
                            && !request.getUpdatedAt().isBefore(com.pams.module.agent.orchestration.RuntimeIdentity.STARTED)
                            && requests.live(request)) {
                        vo.put("activeRequestId", request.getId());
                    }
                    var instructions = requests.instructions(request);
                    if (!instructions.isEmpty()) {
                        vo.put("instructions", instructions);
                        vo.put("instructionsConsumed", request.getSteeringConsumed());
                    }
                });
            }
            if (m.getRunId()!=null && AgentMessage.ROLE_ASSISTANT.equals(m.getRole())) {
                try {
                    Map<String,Object> run=scheduler.view(m.getRunId(),ctx);
                    vo.put("run",run); vo.put("executionState",run.get("status"));
                } catch(BizException denied) {
                    vo.put("runUnavailable",true); vo.put("content","该协作任务所需权限已变化，请基于当前可访问资料重新发起。");
                    for(String key:List.of("references","toolCalls","thinking","thinkingNotes","artifacts")) vo.put(key,List.of());
                }
            }
            if (AgentMessage.ROLE_ASSISTANT.equals(m.getRole()) && m.getStreamId() != null) {
                AgentTask t = m.getRunId()==null ? taskByStream.get(m.getStreamId()) : null;
                if (t != null) {
                    attachTaskCard(vo, t);
                    if (List.of(AgentTask.SUCCESS, AgentTask.FAILED, AgentTask.PARTIAL, AgentTask.CANCELLED).contains(t.getStatus())) {
                        Map<String, Object> result = new LinkedHashMap<>();
                        if ("normal".equalsIgnoreCase(m.getSensitivity())) {
                            try {
                                if (t.getResultJson() != null) result.putAll(AgentJson.readObject(mapper,t.getResultJson()));
                                if (t.getPlanJson() != null) {
                                    Map<?, ?> plan = AgentJson.readObject(mapper,t.getPlanJson());
                                    result.put("skipped", plan.getOrDefault("skipped", null));
                                }
                                result.put("artifacts", TaskGate.toArtifacts(result));
                                vo.put("artifacts", result.get("artifacts"));
                            } catch (Exception e) { log.warn("任务结果回放失败：task={}", t.getId()); }
                        }
                        result.put("status", t.getStatus());
                        result.put("taskId", t.getId());
                        vo.put("taskResult", result);
                    }
                }
            }
            records.add(vo);
        }

        // 权限收紧必须能撤回历史（方案 G8 / R21）
        revokeUnauthorizedCitations(records, ctx);

        PageResult<Map<String, Object>> r = new PageResult<>();
        r.setRecords(records);
        r.setTotal(p.getTotalElements());
        r.setCurrent(page);
        r.setSize(size);
        return r;
    }

    /**
     * 对历史消息的 citations 做实时回源复核，不通过的**就地降级**。
     *
     * <p>降级方式：清空 {@code snippet} 并标记 {@code revoked=true}，而不是整条删除。
     * 理由是"当时确实引用了这份文件"本身是事实，不该被抹掉 ——
     * 前端据此渲染成「该引用已不可见」，用户能理解"不是系统坏了，是我没权限了"。
     *
     * <p><b>必须先清 snippet 再标记</b>：只标记不清内容，越权正文仍会随响应下发，
     * 等于复核白做。
     *
     * <p>批量策略：先把本页所有引用的 {@code (sourceType, sourceId)} 去重，
     * 再逐条判定并缓存结果 —— 一页 50 条消息可能引用同一份文档几十次，
     * 不去重就是几十次重复查询。
     */
    private void revokeUnauthorizedCitations(List<Map<String, Object>> records, ToolContext ctx) {
        if (records.isEmpty()) return;

        Map<String, Boolean> cache = new HashMap<>();
        int revoked = 0;

        for (Map<String, Object> vo : records) {
            Object raw = vo.get("citations");
            if (!(raw instanceof List<?> list) || list.isEmpty()) continue;

            List<Map<String, Object>> kept = new ArrayList<>(list.size());
            boolean unavailable = false;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> cit)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> c = (Map<String, Object>) cit;
                String sourceType = c.get("sourceType") == null ? null : String.valueOf(c.get("sourceType"));
                Long sourceId = toLong(c.get("sourceId"));

                String key = sourceType + ":" + sourceId;
                Boolean ok = cache.get(key);
                if (ok == null) {
                    // ctx 为 null 时 canAccess 一律返回 false → 保守拒绝（历史引用不可见，
                    // 而不是"看不到就全放行"）
                    ok = permissionResolver.canAccess(sourceType, sourceId, ctx);
                    cache.put(key, ok);
                }

                if (ok) {
                    kept.add(c);
                } else {
                    // 清内容 + 标记，两步都要做
                    c.put("snippet", "");
                    c.put("path", null);
                    c.put("link", null);
                    c.put("revoked", true);
                    c.put("sourceTitle", "已不可见的依据");
                    kept.add(c);
                    unavailable = true;
                    revoked++;
                }
            }
            vo.put("citations", kept);
            // The answer and reasoning may quote the revoked source verbatim.
            // Without sentence-level provenance, hide the complete derived answer.
            if (unavailable) {
                vo.put("content", "该回答引用的资料已不可见，请基于当前可访问资料重新提问。");
                vo.put("thinking", List.of());
                vo.put("thinkingNotes", List.of());
                vo.put("toolCalls", List.of());
                vo.put("references", List.of());
            }
        }

        if (revoked > 0) {
            log.info("历史引用回源复核：{} 条引用因权限变化被降级为「已不可见」", revoked);
        }
    }

    private Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 本页消息涉及 stream_id → 最新一条任务（一次查询，防 N+1；无任务返回空 Map） */
    private Map<String, AgentTask> loadInteractiveTasks(Long conversationId, List<AgentMessage> content, Long userId) {
        List<String> streamIds = content.stream()
                .filter(m -> AgentMessage.ROLE_ASSISTANT.equals(m.getRole()))
                .map(AgentMessage::getStreamId)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
        if (streamIds.isEmpty()) return Map.of();
        Map<String, AgentTask> out = new HashMap<>();
        // 按 id 升序遍历、后写覆盖 → 同一 stream 多条任务时保留最新
        for (AgentTask t : taskRepository.findByConversationIdAndStreamIdInOrderByIdAsc(conversationId, streamIds)) {
            if (userId.equals(t.getUserId()) && t.getStreamId() != null) out.put(t.getStreamId(), t);
        }
        return out;
    }

    /**
     * 把任务卡挂到消息 VO：只挂<b>仍可交互</b>的两态——
     * PENDING_CONFIRM → {@code plan}（含一次性令牌）；WAITING_INPUT + type=form → {@code form}。
     * 终态任务（SUCCESS/FAILED/PARTIAL/CANCELLED）不挂卡：确认/取消按钮对已完成的操作是误导。
     */
    private void attachTaskCard(Map<String, Object> vo, AgentTask t) {
        if (t.getPlanJson() == null) return;
        try {
            Map<String, Object> parsed = AgentJson.readObject(mapper,t.getPlanJson());
            if (AgentTask.PENDING_CONFIRM.equals(t.getStatus())) {
                parsed.put("planId", t.getId());
                parsed.put("confirmToken", t.getConfirmToken() == null ? "" : t.getConfirmToken());
                vo.put("plan", parsed);
            } else if (AgentTask.WAITING_INPUT.equals(t.getStatus()) && "form".equals(parsed.get("type"))) {
                parsed.put("taskId", t.getId());
                parsed.put("status", t.getStatus());
                vo.put("form", parsed);
            }
        } catch (Exception e) {
            log.warn("历史任务卡解析失败：task={} stream={}", t.getId(), t.getStreamId());
        }
    }

    @Transactional
    public void saveExecutionState(AgentMessage message, String state) {
        if (state == null) return;
        message.setExecutionState(state);
        messageRepository.save(message);
    }

    private Map<String, Object> toVo(AgentMessage m) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", m.getId());
        vo.put("conversationId", m.getConversationId());
        vo.put("role", m.getRole());
        vo.put("content", m.getContent());
        vo.put("sensitivity", m.getSensitivity());
        vo.put("streamId", m.getStreamId());
        vo.put("provider", m.getProvider());
        vo.put("executionState", m.getExecutionState());
        vo.put("durationMs", m.getDurationMs());
        vo.put("createdAt", m.getCreatedAt());
        // 读侧统一走 readJsonList：容忍 H2 的字符串包装，且解析失败留 warn（不再静默变空数组）
        vo.put("toolCalls", readJsonList(m.getToolCalls(), "tool_calls"));
        // V26：引用随消息恢复（此前只随 /chat 同步返回，刷新即丢）
        vo.put("references", readJsonList(m.getRefsJson(), "refs_json"));
        // V27：运行模式（历史回看渲染差异 + 效果统计）。老数据为 null，按 standard 兜底
        vo.put("mode", m.getMode() == null ? "standard" : m.getMode());
        // V35：思维链（历史回放）。老数据为 null → 空数组，前端据此只渲染工具行
        vo.put("thinking", readJsonList(m.getThinkingJson(), "thinking_json"));
        // V36：编排旁白。前端按 ts 与推理段合并排序，刷新后与实时看到的是同一条时间轴
        vo.put("thinkingNotes", readJsonList(m.getThinkingNotesJson(), "thinking_notes_json"));
        // V27：答案依据。⚠️ 这里放的是**落库时的原样**，
        // 调用方（messages）会再跑一次回源复核 —— 权限收紧必须能撤回历史（方案 G8 / R21）
        vo.put("citations", readJsonList(m.getCitationsJson(), "citations_json"));
        vo.put("plan", null);
        vo.put("form", null);
        return vo;
    }

    /**
     * 组装 LLM 上下文（D5 成本控制）：system + 最近 K 轮 + 工具结果摘要。
     * 注意：这里读出来的 content 已是"写时脱敏"后的内容，因此上下文天然不含敏感明文。
     */
    public List<AgentMessage> context(Long conversationId, int rounds) {
        int window = Math.min(50, Math.max(1, rounds));
        List<AgentMessage> recent = messageRepository.findByConversationIdAndRoleInOrderByIdDesc(conversationId,
                List.of(AgentMessage.ROLE_USER, AgentMessage.ROLE_ASSISTANT),
                PageRequest.of(0, window * 4 + 1)).getContent();
        List<AgentMessage> ordered = new ArrayList<>(recent);
        java.util.Collections.reverse(ordered);
        int remaining = window + 1; // current user plus completed turns
        int start = ordered.size();
        for (int i = ordered.size() - 1; i >= 0; i--) {
            if (AgentMessage.ROLE_USER.equals(ordered.get(i).getRole())) {
                if (--remaining < 0) break;
                start = i;
            }
        }
        return new ArrayList<>(ordered.subList(start, ordered.size()));
    }

    /** Extractive memory: keep original instructions traceable and recheck visibility on every read.
     * No derived plaintext cache can outlive a source permission change. */
    public List<AgentMessage> memorySources(Long conversationId, Long before) {
        Map<Long, AgentMessage> sources = new java.util.TreeMap<>();
        var first = messageRepository.findByConversationIdAndIdLessThanAndRoleOrderByIdAsc(
                conversationId, before, AgentMessage.ROLE_USER, PageRequest.of(0, 1));
        first.forEach(m -> sources.put(m.getId(), m));
        var latest = messageRepository.findByConversationIdAndIdLessThanAndRoleOrderByIdDesc(
                conversationId, before, AgentMessage.ROLE_USER, PageRequest.of(0, 8));
        latest.forEach(m -> sources.put(m.getId(), m));
        return new ArrayList<>(sources.values());
    }

    public String recall(Long conversationId, Long userId, ToolContext ctx, String keyword) {
        return recall(conversationId, userId, ctx, keyword, Long.MAX_VALUE);
    }
    public String recall(Long conversationId, Long userId, ToolContext ctx, String keyword, Long before) {
        require(conversationId, userId);
        if (keyword == null || keyword.length() > 100 || before == null || before <= 0)
            throw new BizException(400, "历史检索参数无效");
        var page = messageRepository.findByConversationIdAndIdLessThanAndContentContainingIgnoreCaseAndRoleInOrderByIdDesc(
                conversationId, before, keyword.trim(), List.of(AgentMessage.ROLE_USER, AgentMessage.ROLE_ASSISTANT), PageRequest.of(0, 3));
        StringBuilder text = new StringBuilder("历史检索节选（不代表当前业务执行状态）：\n");
        for (var message : page) text.append("[messageId=").append(message.getId()).append(" role=")
                .append(message.getRole()).append("] ").append(ContextBudget.excerpt(contextText(message, ctx), 450)).append('\n');
        if (page.hasNext()) text.append("继续回查：beforeMessageId=").append(page.getContent().getLast().getId()).append('\n');
        return text.toString();
    }

    public String recallMessage(Long conversationId, Long userId, ToolContext ctx, Long messageId, int offset) {
        require(conversationId, userId);
        var message = messageRepository.findByIdAndConversationId(messageId, conversationId)
                .filter(m -> List.of(AgentMessage.ROLE_USER, AgentMessage.ROLE_ASSISTANT).contains(m.getRole()))
                .orElseThrow(() -> new BizException(4404, "历史消息不存在或无权访问"));
        String text = contextText(message, ctx);
        if (text == null) text = "";
        int count = text.codePointCount(0, text.length());
        if (offset < 0 || offset > count) throw new BizException(400, "历史消息偏移无效");
        int end = Math.min(count, offset + 300);
        return "历史原文（仅作证据） messageId=" + messageId + " role=" + message.getRole()
                + (end < count ? " nextOffset=" + end : " END") + "\n"
                + text.substring(text.offsetByCodePoints(0, offset), text.offsetByCodePoints(0, end));
    }

    public String taskState(Long conversationId, Long userId) {
        require(conversationId, userId);
        StringBuilder text = new StringBuilder("当前任务状态（数据库事实优先于历史助手描述）：\n");
        Map<Long, AgentTask> selected = new LinkedHashMap<>();
        var active = taskRepository.findByConversationIdAndUserIdAndStatusInOrderByIdDesc(conversationId, userId,
                List.of("RUNNING", "WAITING_INPUT", "PENDING_CONFIRM", "REVIEW_REQUIRED", "NEEDS_RECONCILE"), PageRequest.of(0, 12));
        active.forEach(t -> selected.put(t.getId(), t));
        taskRepository.findByConversationIdAndUserIdOrderByIdDesc(conversationId, userId, PageRequest.of(0, 12))
                .forEach(t -> selected.putIfAbsent(t.getId(), t));
        if (active.getTotalElements() > 12) text.append("仍有更多活跃任务，请到任务列表核对。\n");
        selected.values().forEach(t -> text.append("taskId=").append(t.getId()).append(" tool=").append(t.getToolName())
                    .append(" status=").append(t.getStatus()).append(" workItemId=").append(t.getWorkItemId())
                    .append(" revision=").append(t.getWorkItemRevision()).append(" artifactId=").append(t.getArtifactId())
                    .append(" artifactVersion=").append(t.getArtifactVersion()).append('\n'));
        return text.toString();
    }

    /** Recheck source visibility before historical answers go back to the model. */
    public String contextText(AgentMessage message, ToolContext ctx) {
        Map<String, Object> view = toVo(message);
        revokeUnauthorizedCitations(List.of(view), ctx);
        String content = (String) view.get("content");
        if (requests == null || !AgentMessage.ROLE_USER.equals(message.getRole())) return content;
        return requests.forMessage(message.getStreamId(), message.getConversationId(), ctx).map(request -> {
            var inputs = requests.instructions(request);
            var used = inputs.stream().limit(request.getSteeringConsumed()).map(AgentRequestService.Instruction::text).toList();
            return used.isEmpty() ? content : content + "\n\n运行中用户补充：\n" + String.join("\n", used);
        }).orElse(content);
    }

    /** 摘要：截断到 N 字（sensitive 消息只存这个） */
    public String summarize(String text, int max) {
        if (text == null) return "";
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
