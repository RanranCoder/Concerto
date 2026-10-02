package com.pams.module.knowledge.service;

import com.pams.module.agent.core.ToolContext;
import com.pams.module.knowledge.entity.KbRetrievalLog;
import com.pams.module.knowledge.retriever.KnowledgeRetriever;
import com.pams.module.knowledge.retriever.ScoredChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 检索门面：编排层唯一需要接触的知识库入口。
 *
 * <p>它把"检索 → 阈值裁剪 → 权限复核"串成一步，并给出**可解释的拒答原因**。
 * 编排层（{@code KbPipeline}）不直接碰 {@code KnowledgeRetriever} 或
 * {@code PermissionResolver} —— 少一个接触点，就少一处"忘了复核"的可能。
 *
 * <h3>两种"没有依据"必须在日志里分开、在对用户的话术上合并</h3>
 * <pre>
 * 日志里   NO_HIT            → 知识库里确实没有（语料问题，指导补录）
 *          BELOW_THRESHOLD   → 沾边但不足（阈值/改写问题）
 *          filteredCount &gt; 0 → 其实有内容，但你无权看
 * 对用户   一律给"没有找到相关依据"
 * </pre>
 * <b>为什么对外必须合并</b>：如果"无权查看"与"没有内容"给出不同提示，
 * 攻击者就能拿"无权查看"当存在性证明去枚举（方案 G7）。安全与可读性冲突时选安全。
 * 而运维需要真相，所以真相留在 {@code kb_retrieval_log.filtered_count} 里。
 */
@Slf4j
@Service
public class KnowledgeQuery {

    /** 召回条数：12 条足够选出 6–8 条进 prompt（方案 §4.3） */
    public static final int TOP_K = 12;

    /**
     * 相对阈值：只保留分数不低于最高分 25% 的命中。
     *
     * <p>为什么用**相对**而不是绝对阈值：MySQL 的 {@code MATCH ... AGAINST}
     * 在 NATURAL LANGUAGE 模式下分值与文档长度、词频相关，是**无界**的。
     * 拍一个绝对阈值（比如 1.0）在不同语料规模下会时而全放、时而全砍。
     * 相对阈值只关心"这一批里谁明显更差"，行为稳定得多。
     */
    public static final double RELATIVE_CUT = 0.25;

    private final KnowledgeRetriever retriever;
    private final PermissionResolver permissionResolver;

    public KnowledgeQuery(KnowledgeRetriever retriever, PermissionResolver permissionResolver) {
        this.retriever = retriever;
        this.permissionResolver = permissionResolver;
    }

    /**
     * 检索并复核。
     *
     * @param question 用户原话（一期直接作为 query；P1 起会先做查询改写）
     */
    public RetrievalOutcome retrieve(String question, ToolContext ctx) {
        long t0 = System.currentTimeMillis();

        List<ScoredChunk> hits = retriever.search(question, ctx, TOP_K);
        if (hits.isEmpty()) {
            return new RetrievalOutcome(List.of(), 0, 0, 0, elapsed(t0), KbRetrievalLog.SKIP_NO_HIT);
        }

        double top = hits.stream().mapToDouble(ScoredChunk::score).max().orElse(0);
        double cut = top * RELATIVE_CUT;
        List<ScoredChunk> kept = hits.stream().filter(c -> c.score() >= cut && c.score() > 0).toList();
        if (kept.isEmpty()) {
            return new RetrievalOutcome(List.of(), hits.size(), 0, top, elapsed(t0),
                    KbRetrievalLog.SKIP_BELOW_THRESHOLD);
        }

        // ⚠️ 权限复核：这是"正确性"的唯一来源。下游只能拿到 FilteredChunks，拿不到裸列表
        PermissionResolver.FilteredChunks filtered = permissionResolver.filter(kept, ctx);
        if (filtered.isEmpty()) {
            // 对外与"没有内容"同因；真相（filteredCount）留给日志
            log.info("知识库检索：{} 条命中全部被权限复核拦下（对用户按『无依据』处理）",
                    filtered.filteredCount());
            return new RetrievalOutcome(List.of(), hits.size(), filtered.filteredCount(), top,
                    elapsed(t0), KbRetrievalLog.SKIP_NO_HIT);
        }

        return new RetrievalOutcome(filtered.allowed(), hits.size(), filtered.filteredCount(),
                top, elapsed(t0), null);
    }

    private long elapsed(long t0) { return System.currentTimeMillis() - t0; }

    /**
     * 检索结果。
     *
     * @param allowed       复核通过、可进 prompt 与 citations 的命中（**只允许用它**）
     * @param hitCount      粗筛命中数
     * @param filteredCount 被权限复核拦下数（索引期字段偏松的体检指标）
     * @param topScore      最高相关度（阈值调参用）
     * @param skipReason    拒答原因；{@code null} = 正常作答
     */
    public record RetrievalOutcome(List<ScoredChunk> allowed,
                                   int hitCount,
                                   int filteredCount,
                                   double topScore,
                                   long scanMs,
                                   String skipReason) {

        /** 是否有可用依据 */
        public boolean usable() { return skipReason == null && !allowed.isEmpty(); }
    }
}
