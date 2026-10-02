package com.pams.module.knowledge.service;

import com.pams.module.agent.core.ToolContext;
import com.pams.module.knowledge.retriever.ScoredChunk;
import com.pams.module.knowledge.source.KnowledgeSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索期权限复核（方案 §6.4 的第二个阶段，闸口 A 的后半段）。
 *
 * <pre>
 * 索引期  写保守可见性字段  → 只用于 SQL 粗筛（性能）
 * 检索期  本类回源复核      → 正确性的唯一来源     ← 这里
 * 出网前  不通过的先丢弃    → 越权内容不进 prompt 与 citations
 * </pre>
 *
 * <h3>为什么必须是"回源"而不是"信索引里的字段"</h3>
 * D12 之后知识源从 2 个变成 10 个异构源，每个源的权限逻辑不同
 * （权限码 / 角色谓词 / 部门归属 / uploads 归属）。在索引侧复制这 10 套逻辑
 * 等于**把它们重写一遍**，且任何一处源侧变更都要人肉同步 ——
 * 这正是 D11 第 ③ 类静默失效的温床。回源复核让每个源复用它**自己已有的**判断。
 *
 * <p>成本可控：只对 TopK（≤12 条）复核，不是全表。
 *
 * <h3>为什么用包装类型返回</h3>
 * {@link FilteredChunks} 不是装饰 —— 它让"忘记复核"在**编译期就不可能**：
 * 下游（{@code KbPipeline}）只接受这个类型，拿不到裸 `List&lt;ScoredChunk&gt;`。
 * 方案 G2 描述的缺陷（答案正文干净、出处却带毒）就是这么被结构性排除的。
 */
@Slf4j
@Service
public class PermissionResolver {

    private final Map<String, KnowledgeSource> sources = new LinkedHashMap<>();

    public PermissionResolver(List<KnowledgeSource> all) {
        for (KnowledgeSource s : all) sources.put(s.sourceType(), s);
    }

    /**
     * 单条复核（供**历史引用回看**等非检索场景复用）。
     *
     * <p>为什么要有这个方法：历史消息里存着当时回填的 citations，
     * 而"当时有权限"不等于"现在有权限"。若不复核，用户被降权后
     * 仍能从旧对话里读到片段 —— 权限收紧将永不生效（方案 G8 / R21）。
     *
     * <p>把它放在本类而不是让调用方自己 new 一个判定逻辑：
     * <b>"怎么问一个源"只应该有一个答案</b>，否则两处判定迟早会不一致。
     */
    public boolean canAccess(String sourceType, Long sourceId, ToolContext ctx) {
        if (ctx == null || sourceType == null || sourceId == null) return false;
        KnowledgeSource src = sources.get(sourceType);
        return src != null && src.canAccess(ctx, sourceId);
    }

    /**
     * 逐条回源复核。
     *
     * <p>同一个源记录可能命中多个分块 → 用 {@code (sourceType, sourceId)} 缓存判定结果，
     * 避免对同一篇文档重复查询（TopK 里同源多块是常态）。
     */
    public FilteredChunks filter(List<ScoredChunk> hits, ToolContext ctx) {
        if (hits == null || hits.isEmpty()) return new FilteredChunks(List.of(), 0);

        Map<String, Boolean> cache = new HashMap<>();
        List<ScoredChunk> allowed = new ArrayList<>(hits.size());
        int filtered = 0;

        for (ScoredChunk c : hits) {
            String key = c.sourceType() + ":" + c.sourceId();
            Boolean ok = cache.get(key);
            if (ok == null) {
                KnowledgeSource src = sources.get(c.sourceType());
                // 未知源一律拒绝：宁可少给，也不要让"适配器被删了但分块还在"变成越权口
                ok = src != null && src.canAccess(ctx, c.sourceId());
                cache.put(key, ok);
            }
            if (ok) {
                allowed.add(c);
            } else {
                filtered++;
            }
        }

        if (filtered > 0) {
            // 这条日志是索引期可见性字段偏松的体检信号（对应 kb_retrieval_log.filtered_count）
            log.info("知识库权限复核：命中 {} 条，放行 {} 条，拦下 {} 条", hits.size(), allowed.size(), filtered);
        }
        return new FilteredChunks(List.copyOf(allowed), filtered);
    }

    /**
     * 复核结果。
     *
     * @param allowed       复核通过、可进 prompt 与 citations 的命中
     * @param filteredCount 粗筛放过但被拦下的条数 —— 持续大于 0 说明索引期字段偏松
     */
    public record FilteredChunks(List<ScoredChunk> allowed, int filteredCount) {

        public int hitCount() { return allowed.size() + filteredCount; }

        public boolean isEmpty() { return allowed.isEmpty(); }
    }
}
