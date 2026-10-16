package com.pams.module.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.module.agent.core.CitationMapper;
import com.pams.module.agent.core.KbPrompts;
import com.pams.module.agent.core.PolicyGuard;
import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.stream.AgentEventPublisher;
import com.pams.module.agent.stream.AgentStreamEvent;
import com.pams.module.agent.stream.ThinkingTrace;
import com.pams.module.knowledge.entity.KbRetrievalLog;
import com.pams.module.knowledge.repository.KbRetrievalLogRepository;
import com.pams.module.knowledge.retriever.ScoredChunk;
import com.pams.module.knowledge.service.KnowledgeQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库问答流水线（灵思「对话」档位）。
 *
 * <h3>为什么是独立流水线，而不是"给工具循环开一个只读工具"</h3>
 * <table>
 *   <tr><td>确定性</td><td>检索一次、生成一次，不依赖模型决定检索几轮</td></tr>
 *   <tr><td>延迟</td><td>一轮 LLM 调用 + 一次检索，TTFE 与总时长可预估</td></tr>
 *   <tr><td>安全</td><td><b>没有工具循环 = 不存在被诱导调工具的可能</b>。比"靠可见性过滤挡住"更彻底</td></tr>
 *   <tr><td>可审计</td><td>一次问答一条检索记录，行数固定</td></tr>
 * </table>
 * 代价是没有多跳能力（一期接受，见方案 §1.5）。
 *
 * <h3>五步，每一步都有对应的闸口</h3>
 * <pre>
 * ① 推 THINKING（TTFE 起算，必须在检索之前）
 * ② 检索 + 权限复核（闸口 A：粗筛在 SQL、复核回源）
 * ③ 无依据 → 直接拒答，**不调 LLM**（省钱、且避免模型硬编）
 * ④ 片段过 PolicyGuard（闸口 B：唯一出网口）→ 拼 prompt → 流式生成
 * ⑤ 服务端回填引用（模型只写编号）；零引用**不静默通过**（闸口 C）
 * </pre>
 */
@Slf4j
@Service
public class KbPipeline {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.pams.module.permission.service.TenantAuthorizationService authorization;

    /** 引用卡片段摘要的截断长度（不等于给 LLM 的片段长度） */
    private static final int SNIPPET_CHARS = 240;

    private final KnowledgeQuery knowledgeQuery;
    private final CitationMapper citationMapper;
    private final PolicyGuard policyGuard;
    private final AgentEventPublisher publisher;
    private final KbRetrievalLogRepository logRepository;
    /**
     * 一次 LLM 调用（重试/降级/字符流）。
     *
     * <p>依赖 {@link AgentLlmCaller} 而**不是**编排器：编排器已经依赖本类，
     * 反向依赖会构成循环。抽成独立组件比 {@code @Lazy} 硬解更好 ——
     * 它把这层关系表达成"两条流水线共用一个能力"，而不是"两个类互相需要"。
     */
    private final AgentLlmCaller llmCaller;
    /** 思维链暂存：对话档位的旁白（「已找到 N 条相关依据」）也要落库，否则刷新即丢（V36） */
    private final ThinkingTrace thinkingTrace;
    private final ObjectMapper mapper = new ObjectMapper();

    public KbPipeline(KnowledgeQuery knowledgeQuery,
                      CitationMapper citationMapper,
                      PolicyGuard policyGuard,
                      AgentEventPublisher publisher,
                      KbRetrievalLogRepository logRepository,
                      AgentLlmCaller llmCaller,
                      ThinkingTrace thinkingTrace) {
        this.knowledgeQuery = knowledgeQuery;
        this.citationMapper = citationMapper;
        this.policyGuard = policyGuard;
        this.publisher = publisher;
        this.logRepository = logRepository;
        this.llmCaller = llmCaller;
        this.thinkingTrace = thinkingTrace;
    }

    /**
     * 跑一次知识库问答。
     *
     * <p>调用前提（由编排器保证）：会话已建立、{@code ctx} 已 derive、
     * 用户消息已落库、THINKING 已推。
     *
     * @return 答复与依据；落库与 MESSAGE_DONE 由编排器统一做
     *         （避免两条链路各写一份"收官逻辑"而漂移）
     */
    public KbOutcome run(String question, Long convId, ToolContext ctx, String streamId) {
        if (authorization != null) ctx = authorization.refresh(ctx);
        long t0 = System.currentTimeMillis();

        // ===== ② 检索 + 权限复核 =====
        KnowledgeQuery.RetrievalOutcome outcome = knowledgeQuery.retrieve(question, ctx);

        if (!outcome.usable()) {
            // ===== ③ 无依据：直接拒答，不调 LLM =====
            // "无命中"与"全部被权限拦下"给**逐字相同**的话术（防存在性枚举，方案 G7）
            String reply = outcome.filteredCount() > 0
                    ? KbPrompts.filteredReply(question)
                    : KbPrompts.noEvidenceReply(question);
            emitNote(ctx, streamId, "知识库里没有找到相关依据");
            writeLog(ctx, convId, question, outcome, 0, null, null, false,
                    System.currentTimeMillis() - t0);
            return new KbOutcome(reply, null, false, List.of());
        }

        List<ScoredChunk> allowed = outcome.allowed();
        emitNote(ctx, streamId, "已找到 " + allowed.size() + " 条相关依据，正在整理…");

        // ===== ④ 片段过闸口 → 拼 prompt → 生成 =====
        List<ScoredChunk> safe = egressFragments(allowed);
        if (safe.isEmpty()) {
            // 全部片段被出网闸口拦下（restricted）——理论上不该发生（索引期已排除），
            // 但真发生了就必须拒答，不能拿空片段去问模型
            String reply = KbPrompts.noEvidenceReply(question);
            writeLog(ctx, convId, question, outcome, 0, null, null, false,
                    System.currentTimeMillis() - t0);
            return new KbOutcome(reply, null, false, List.of());
        }

        List<LlmProvider.ChatMessage> messages = new ArrayList<>();
        messages.add(LlmProvider.ChatMessage.text("system", KbPrompts.KB_SYSTEM));
        messages.add(LlmProvider.ChatMessage.text("user", KbPrompts.buildUserMessage(question, safe)));

        // 无工具：对话档位"没有工具循环"的物理体现（方案 §7.3）。round 固定 0 ——
        // 本轮只有一次生成，不存在"重试/降级之外"的换 round 场景（那两种由 callLlm 内部处理）
        LlmProvider.ChatResponse response = llmCaller.callLlm(messages, List.of(), ctx, streamId, 0);
        String reply = response.content() == null ? "" : response.content();

        // ===== ⑤ 引用回填（闸口 C） =====
        // ⚠️ 只从 safe（= 复核通过 + 过闸口）构造，绝不从粗筛结果构造（方案 G2）
        CitationMapper.MapResult mapped = citationMapper.map(reply, safe, SNIPPET_CHARS);
        String skipReason = null;
        if (mapped.empty() && !reply.isBlank()) {
            // 零引用不静默通过：附提示而**不是**判死（模型有时把依据融进叙述忘了标编号，
            // 答案本身可能是对的，直接判死会误伤）
            reply = reply + KbPrompts.NO_CITATION_NOTICE;
            skipReason = KbRetrievalLog.SKIP_NO_CITATION;
        }

        List<Map<String, Object>> citationMaps = toMaps(mapped.citations());
        writeLog(ctx, convId, question, outcome, safe.size(), skipReason,
                response.provider(), response.degraded(), System.currentTimeMillis() - t0);

        return new KbOutcome(reply, response.provider(), response.degraded(), citationMaps);
    }

    /* ------------------------------------------------------------------ 内部 */

    /**
     * 片段出网（闸口 B）。
     *
     * <p>逐片段按自己的 sensitivity 过闸口：{@code restricted} 的片段**整条丢弃**
     * （而不是替换成占位——占位会让模型以为"这里有个片段但看不到"，进而编内容）。
     */
    private List<ScoredChunk> egressFragments(List<ScoredChunk> allowed) {
        List<ScoredChunk> out = new ArrayList<>(allowed.size());
        for (ScoredChunk c : allowed) {
            String text = policyGuard.egressText(Sensitivity.of(c.sensitivity()), c.content());
            if (text == null) {
                log.warn("知识库片段被出网闸口拦下（restricted）：{}:{} seq={}",
                        c.sourceType(), c.sourceId(), c.seq());
                continue;
            }
            // 用净化后的文本替换原文 —— 与 ToolResult 的处理保持一致
            out.add(new ScoredChunk(c.id(), c.sourceType(), c.sourceId(), c.sourceKey(), c.seq(),
                    c.title(), c.path(), text, c.tier(), c.sensitivity(), c.asOf(), c.score()));
        }
        return out;
    }

    private void emitNote(ToolContext ctx, String streamId, String note) {
        if (ctx == null || ctx.getLoginUser() == null) return;
        // 先收集再发送：否则事件已到前端而落库快照里没有，刷新后少一行（与编排器同一约定）
        thinkingTrace.note(streamId, note);
        publisher.publish(ctx.getLoginUser().getUsername(),
                AgentStreamEvent.thinking(streamId, publisher.nextSeq(streamId), note));
    }

    private List<Map<String, Object>> toMaps(List<CitationMapper.KnowledgeCitation> citations) {
        List<Map<String, Object>> out = new ArrayList<>(citations.size());
        for (CitationMapper.KnowledgeCitation c : citations) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", c.index());
            m.put("sourceType", c.sourceType());
            m.put("sourceId", c.sourceId());
            m.put("sourceTitle", c.sourceTitle());
            m.put("path", c.path());
            m.put("snippet", c.snippet());
            m.put("link", c.link());
            m.put("asOf", c.asOfDate());          // 前端展示"数据截至 YYYY-MM-DD"
            m.put("revoked", false);
            out.add(m);
        }
        return out;
    }

    /**
     * 落检索记录。
     *
     * <p><b>失败绝不能影响问答</b>：记录是为了审计与运营，不该成为主链路的故障点，
     * 所以整体 try/catch（与 {@code safeAudit} 同一条思路）。
     */
    private void writeLog(ToolContext ctx, Long convId, String question,
                          KnowledgeQuery.RetrievalOutcome outcome, int allowedCount,
                          String skipReason, String provider, boolean degraded, long totalMs) {
        try {
            KbRetrievalLog row = new KbRetrievalLog();
            row.setUserId(ctx.getUserId());
            row.setConversationId(convId);
            row.setStreamId(ctx.getStreamId());
            row.setQueryText(policyGuard.mask(question));      // 用户原话可能含个人信息
            row.setRawQuestion(policyGuard.mask(question));
            row.setMode(ctx.getMode());
            row.setHitCount(outcome.hitCount());
            row.setAllowedCount(allowedCount);
            row.setFilteredCount(outcome.filteredCount());
            row.setTopScore(BigDecimal.valueOf(outcome.topScore()));
            row.setScanMs((int) outcome.scanMs());
            row.setTotalMs((int) totalMs);
            row.setSkipReason(skipReason);
            row.setProvider(provider);
            row.setDegraded(degraded ? 1 : 0);
            row.setHitsJson(buildHitsJson(outcome.allowed()));
            row.setCreatedAt(LocalDateTime.now());
            logRepository.save(row);
        } catch (Exception e) {
            log.warn("知识库检索记录落库失败（不影响问答）：{}", e.getMessage());
        }
    }

    private String buildHitsJson(List<ScoredChunk> allowed) {
        try {
            List<Map<String, Object>> hits = new ArrayList<>(allowed.size());
            for (ScoredChunk c : allowed) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sourceType", c.sourceType());
                m.put("sourceId", c.sourceId());
                m.put("sourceKey", c.sourceKey());
                m.put("seq", c.seq());
                m.put("path", c.path());
                m.put("score", c.score());
                hits.add(m);
            }
            return mapper.writeValueAsString(hits);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @param reply      最终答复（可能已追加"未标注出处"提示）
     * @param citations  已完成回填的依据（**只允许从这里取**）
     */
    public record KbOutcome(String reply, String provider, boolean degraded,
                            List<Map<String, Object>> citations) {}
}
