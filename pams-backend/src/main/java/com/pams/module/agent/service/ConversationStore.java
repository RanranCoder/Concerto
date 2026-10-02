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
import com.pams.module.knowledge.service.PermissionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
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

        List<Map<String, Object>> records = new ArrayList<>();
        for (AgentConversation c : p.getContent()) {
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("id", c.getId());
            vo.put("title", c.getTitle());
            vo.put("updatedAt", c.getUpdatedAt());
            vo.put("createdAt", c.getCreatedAt());
            vo.put("pendingTaskCount", 0); // 由 Service 层填充，避免 N+1
            records.add(vo);
        }
        PageResult<Map<String, Object>> r = new PageResult<>();
        r.setRecords(records);
        r.setTotal(p.getTotalElements());
        r.setCurrent(page);
        r.setSize(size);
        return r;
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
        AgentConversation c = require(id, userId);
        c.setDeleted(1);
        c.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(c);
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
        AgentMessage m = new AgentMessage();
        m.setConversationId(conversationId);
        m.setRole(role);
        m.setSensitivity(sensitivity.name().toLowerCase());
        m.setContent(policyGuard.forStorage(sensitivity, fullText, summary));
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
        Map<String, AgentTask> taskByStream = loadInteractiveTasks(conversationId, content);

        List<Map<String, Object>> records = new ArrayList<>();
        for (AgentMessage m : content) {
            Map<String, Object> vo = toVo(m);
            if (AgentMessage.ROLE_ASSISTANT.equals(m.getRole()) && m.getStreamId() != null) {
                AgentTask t = taskByStream.get(m.getStreamId());
                if (t != null) attachTaskCard(vo, t);
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
                    kept.add(c);
                    revoked++;
                }
            }
            vo.put("citations", kept);
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
    private Map<String, AgentTask> loadInteractiveTasks(Long conversationId, List<AgentMessage> content) {
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
            if (t.getStreamId() != null) out.put(t.getStreamId(), t);
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
            Map<String, Object> parsed = mapper.readValue(t.getPlanJson(), Map.class);
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

    private Map<String, Object> toVo(AgentMessage m) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", m.getId());
        vo.put("conversationId", m.getConversationId());
        vo.put("role", m.getRole());
        vo.put("content", m.getContent());
        vo.put("sensitivity", m.getSensitivity());
        vo.put("streamId", m.getStreamId());
        vo.put("provider", m.getProvider());
        vo.put("durationMs", m.getDurationMs());
        vo.put("createdAt", m.getCreatedAt());
        if (m.getToolCalls() != null && !m.getToolCalls().isBlank()) {
            try {
                vo.put("toolCalls", mapper.readValue(m.getToolCalls(), List.class));
            } catch (Exception ignored) {
                vo.put("toolCalls", List.of());
            }
        } else {
            vo.put("toolCalls", List.of());
        }
        // V26：引用随消息恢复（此前只随 /chat 同步返回，刷新即丢）
        if (m.getRefsJson() != null && !m.getRefsJson().isBlank()) {
            try {
                vo.put("references", mapper.readValue(m.getRefsJson(), List.class));
            } catch (Exception ignored) {
                vo.put("references", List.of());
            }
        } else {
            vo.put("references", List.of());
        }
        // V27：运行模式（历史回看渲染差异 + 效果统计）。老数据为 null，按 standard 兜底
        vo.put("mode", m.getMode() == null ? "standard" : m.getMode());
        // V27：答案依据。⚠️ 这里放的是**落库时的原样**，
        // 调用方（messages）会再跑一次回源复核 —— 权限收紧必须能撤回历史（方案 G8 / R21）
        if (m.getCitationsJson() != null && !m.getCitationsJson().isBlank()) {
            try {
                vo.put("citations", mapper.readValue(m.getCitationsJson(), List.class));
            } catch (Exception ignored) {
                vo.put("citations", List.of());
            }
        } else {
            vo.put("citations", List.of());
        }
        vo.put("plan", null);
        vo.put("form", null);
        return vo;
    }

    /**
     * 组装 LLM 上下文（D5 成本控制）：system + 最近 K 轮 + 工具结果摘要。
     * 注意：这里读出来的 content 已是"写时脱敏"后的内容，因此上下文天然不含敏感明文。
     */
    public List<AgentMessage> context(Long conversationId, int rounds) {
        List<AgentMessage> recent = messageRepository.findTop20ByConversationIdOrderByIdDesc(conversationId);
        List<AgentMessage> ordered = new ArrayList<>(recent);
        java.util.Collections.reverse(ordered);
        int keep = Math.max(rounds * 2, 2);
        if (ordered.size() > keep) {
            return ordered.subList(ordered.size() - keep, ordered.size());
        }
        return ordered;
    }

    /** 摘要：截断到 N 字（sensitive 消息只存这个） */
    public String summarize(String text, int max) {
        if (text == null) return "";
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
