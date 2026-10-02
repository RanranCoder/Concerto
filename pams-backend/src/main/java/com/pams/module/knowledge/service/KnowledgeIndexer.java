package com.pams.module.knowledge.service;

import com.pams.module.knowledge.entity.KbChunk;
import com.pams.module.knowledge.repository.KbChunkRepository;
import com.pams.module.knowledge.source.KnowledgeSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 索引器：把各知识源的内容写成 {@code kb_chunk}（可检索视图）。
 *
 * <h3>它是"视图"的维护者，不是"真相"的持有者</h3>
 * D12 的必要条件 ①：唯一真相永远在业务表（announcement / article / activity_plan / kb_document）。
 * 本类只负责把它们的当前状态抄成"可检索的切片"。所以：
 * <ul>
 *   <li><b>源变了必须重建</b>，否则答案依据的是旧内容（方案 R6）</li>
 *   <li><b>分块可以随时物理删除重建</b> —— 它是派生数据，没有留存价值
 *       （这也是为什么 {@code deleteBySource} 用物理删而不是软删）</li>
 * </ul>
 *
 * <h3>为什么重建设计成"整源先删后插"</h3>
 * 增量 diff（比对哪些块变了）能省写入，但会引入"diff 逻辑本身有 bug"
 * 这一类新的静默失效 —— 而知识库最不能出的问题就是"答出源里已经没有的内容"。
 * 单篇文档的块数是几十量级，整源重写成本极低，换来"索引状态 == 源状态"的强保证。
 * 这个取舍在源数量继续增长后再评估。
 */
@Slf4j
@Service
public class KnowledgeIndexer {

    private final Map<String, KnowledgeSource> sources = new LinkedHashMap<>();
    private final KbChunkRepository chunkRepository;
    private final KnowledgeChunker chunker;

    public KnowledgeIndexer(List<KnowledgeSource> all,
                            KbChunkRepository chunkRepository,
                            KnowledgeChunker chunker) {
        for (KnowledgeSource s : all) sources.put(s.sourceType(), s);
        this.chunkRepository = chunkRepository;
        this.chunker = chunker;
    }

    /** 已注册的源类型（供源清单接口与统计） */
    public List<KnowledgeSource> sources() { return List.copyOf(sources.values()); }

    /**
     * 全量重建：遍历所有源，清空后重写。
     *
     * <p>用途：① 首次上线 ② 每日兜底（防领域事件丢失）③ 权限字段重算的修复入口。
     *
     * @return 每个源写入了多少块（0 表示该源当前无内容，不是失败）
     */
    @Transactional
    public Map<String, Integer> reindexAll() {
        Map<String, Integer> report = new LinkedHashMap<>();
        for (KnowledgeSource src : sources.values()) {
            report.put(src.sourceType(), reindexSourceType(src.sourceType()));
        }
        log.info("知识库全量重建完成：{}", report);
        return report;
    }

    /** 重建单个源类型 */
    @Transactional
    public int reindexSourceType(String sourceType) {
        KnowledgeSource src = sources.get(sourceType);
        if (src == null) {
            log.warn("知识库重建跳过：未注册的源类型 {}", sourceType);
            return 0;
        }
        chunkRepository.deleteBySourceType(sourceType);

        int written = 0;
        for (KnowledgeSource.KnowledgeDoc doc : src.extractAll()) {
            written += writeDoc(doc);
        }
        return written;
    }

    /**
     * 重建单篇来源（增量路径，由 {@code AFTER_COMMIT} 领域事件触发）。
     *
     * <p><b>源已删除时 extractOne 返回空列表 → 清空该源的分块</b>。
     * 这一点必须是显式的：如果实现成"返回空就什么都不做"，
     * 源删除后知识库会继续答出已经不存在的内容（方案 §2.3 的删除语义）。
     *
     * @return 写入的块数；0 表示该源已无内容（通常是已删除）
     */
    @Transactional
    public int reindexSource(String sourceType, Long sourceId) {
        KnowledgeSource src = sources.get(sourceType);
        if (src == null) {
            log.warn("知识库增量重建跳过：未注册的源类型 {}", sourceType);
            return 0;
        }
        chunkRepository.deleteBySource(sourceType, sourceId);

        int written = 0;
        for (KnowledgeSource.KnowledgeDoc doc : src.extractOne(sourceId)) {
            written += writeDoc(doc);
        }
        if (written == 0) {
            log.info("知识库增量重建：源 {}({}) 已无内容，已清理其分块", sourceType, sourceId);
        }
        return written;
    }

    /* ------------------------------------------------------------------ 内部 */

    private int writeDoc(KnowledgeSource.KnowledgeDoc doc) {
        if (doc == null || doc.sections() == null || doc.sections().isEmpty()) return 0;

        List<KnowledgeChunker.ChunkDraft> drafts = chunker.chunk(doc);
        if (drafts.isEmpty()) return 0;

        KnowledgeSource src = sources.get(doc.sourceType());
        String visibility = doc.visibility() == null
                ? (src == null ? "ALL" : src.visibility()) : doc.visibility();
        String requiredPermission = doc.requiredPermission() != null
                ? doc.requiredPermission() : (src == null ? null : src.requiredPermission());
        String sensitivity = doc.sensitivity() != null
                ? doc.sensitivity() : (src == null ? "normal" : src.sensitivity());

        LocalDateTime now = LocalDateTime.now();
        List<KbChunk> entities = drafts.stream().map(d -> {
            KbChunk c = new KbChunk();
            c.setSourceType(doc.sourceType());
            c.setSourceId(doc.sourceId());
            c.setSourceKey(KbChunk.key(doc.sourceType(), doc.sourceId()));
            c.setSeq(d.seq());
            c.setTitle(d.title());
            c.setPath(d.path());
            c.setContent(d.content());
            c.setTokenLen(d.content() == null ? 0 : d.content().length());
            c.setTier(doc.tier() == null ? KbChunk.TIER_T1 : doc.tier().name());
            c.setAsOf(doc.asOf());
            c.setVisibility(visibility);
            c.setRequiredPermission(requiredPermission);
            c.setSensitivity(sensitivity);
            c.setDeleted(0);
            c.setIndexedAt(now);
            return c;
        }).toList();

        chunkRepository.saveAll(entities);
        return entities.size();
    }
}
