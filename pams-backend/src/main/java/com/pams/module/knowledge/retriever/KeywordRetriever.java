package com.pams.module.knowledge.retriever;

import com.pams.module.agent.core.ToolContext;
import com.pams.module.knowledge.source.KnowledgeSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 关键词检索实现（一期）。
 *
 * <p><b>技术路线定性</b>：本类就是 RAG 里的那个 R。它是**关键词检索型**，
 * 不是向量检索 —— 方案 §3.3 已经说明 RAG ≠ 向量数据库。
 * 检索层设计了 SPI 接缝，二期接向量时**只换本接口的实现**，
 * 编排层、权限复核、引用回填、前端全都不动。
 *
 * <h3>打分公式</h3>
 * <pre>
 * T1（全文级）：score = BM25(ngram 全文命中) × 源权重
 * T2（字段级）：score = 0.4（标题命中固定分） × 源权重     ← 正文权重为 0
 * </pre>
 * T2 的 0.4 是**固定低分**而不是 BM25：T2 的内容是"模板名""材料描述"这类短元数据，
 * 它们的分值不该与制度条款同台竞争（方案 R13）。固定低分保证它们
 * "能被找到，但不会挤掉正文命中"。
 *
 * <p>源权重由各 {@link KnowledgeSource#weight()} 显式声明，
 * 表达的是**权威度**（制度/公告 1.0 &gt; 稿件/策划书 0.9），不是篇幅或热度。
 */
@Slf4j
@Service
public class KeywordRetriever implements KnowledgeRetriever {

    /** T2 标题命中的固定基础分（乘源权重之前） */
    public static final double T2_TITLE_SCORE = 0.4;

    private final ChunkSearchRepository search;
    private final KnowledgeSearchMode mode;
    private final Map<String, Double> weights = new LinkedHashMap<>();

    public KeywordRetriever(ChunkSearchRepository search,
                           KnowledgeSearchMode mode,
                           List<KnowledgeSource> sources) {
        this.search = search;
        this.mode = mode;
        // 启动期就把权重表建好：检索是热路径，不该每次去问源列表
        for (KnowledgeSource s : sources) {
            weights.put(s.sourceType(), s.weight());
        }
        log.info("知识库检索权重表：{}", weights);
    }

    @Override
    public String name() { return "keyword"; }

    @Override
    public List<ScoredChunk> search(String query, ToolContext ctx, int topK) {
        if (query == null || query.isBlank() || topK <= 0) return List.of();

        int t1Limit = Math.max(topK * 2, 20);
        List<ScoredChunk> t1 = mode.fulltextAvailable()
                ? search.fulltextT1(query, ctx, t1Limit)
                : search.likeT1(query, ctx, t1Limit);

        // T2 取更小的配额：它的定位是"补充线索"，不该占据 TopK 的主要位置
        List<ScoredChunk> t2 = search.titleMatchT2(query, ctx, Math.max(topK / 2, 3));

        List<ScoredChunk> merged = new ArrayList<>(t1.size() + t2.size());
        for (ScoredChunk c : t1) merged.add(c.withScore(c.score() * weightOf(c.sourceType())));
        for (ScoredChunk c : t2) {
            merged.add(c.withScore(T2_TITLE_SCORE * weightOf(c.sourceType())));
        }

        // 同源去重：同一个 (sourceKey, seq) 只保留最高分。
        // D12 之后源变多，同一份内容被两个源收录的概率上升（方案 R9）
        Map<String, ScoredChunk> best = new LinkedHashMap<>();
        for (ScoredChunk c : merged) {
            String key = c.sourceKey() + "#" + c.seq();
            ScoredChunk old = best.get(key);
            if (old == null || c.score() > old.score()) best.put(key, c);
        }

        return best.values().stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(topK)
                .toList();
    }

    private double weightOf(String sourceType) {
        // 未声明的源权重按 0 处理：宁可让它检索不出来，也不要悄悄给一个 1.0
        // （那会让新增源"看起来接好了"，实际权重是拍脑袋定的）
        Double w = weights.get(sourceType);
        if (w == null) {
            log.warn("知识源 {} 未声明权重，检索时将不参与打分", sourceType);
            return 0.0;
        }
        return w;
    }
}
