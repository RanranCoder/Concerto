package com.pams.module.knowledge.snapshot;

import com.pams.module.knowledge.entity.KbSnapshot;
import com.pams.module.knowledge.repository.KbSnapshotRepository;
import com.pams.module.knowledge.service.KnowledgeIndexer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事实快照生成器（D12 的 B 形态）。
 *
 * <pre>
 * 遍历注册表里的每个 spec → 对每个作用域取聚合 → 写成叙述文本 + as_of
 *   → 落 kb_snapshot → 调用 KnowledgeIndexer 增量重建该快照的分块
 * </pre>
 *
 * <h3>as_of 取生成时刻，且截断到分钟</h3>
 * 聚合查询读到的是当前状态，所以"截至现在"是准确表述。
 * <b>截断到分钟</b>是因为 {@code UNIQUE (spec_code, scope_type, scope_id, as_of)} ——
 * 不截断的话同一分钟内连续刷新会插入多行几乎相同的快照，让检索配额被同一份数据挤占。
 * 截断后同一分钟内的重复生成会命中唯一键，走"更新而非新增"，天然幂等。
 *
 * <h3>生成完必须重建索引</h3>
 * 快照写进 {@code kb_snapshot} 只是"数据落库"，要能被检索到还得进 {@code kb_chunk}。
 * 这一步不能漏 —— 漏了的表现是"快照生成成功、知识库页面能看到、但问答就是查不到"，
 * 而且不报错。
 */
@Slf4j
@Service
public class SnapshotGenerator {

    private final SnapshotSpecRegistry registry;
    private final KbSnapshotRepository snapshotRepository;
    private final KnowledgeIndexer indexer;

    public SnapshotGenerator(SnapshotSpecRegistry registry,
                             KbSnapshotRepository snapshotRepository,
                             KnowledgeIndexer indexer) {
        this.registry = registry;
        this.snapshotRepository = snapshotRepository;
        this.indexer = indexer;
    }

    /**
     * 每日生成全部快照（凌晨 3:10，早于 3:40 的全量重建，这样重建时能一并索引）。
     *
     * <p>为什么不要求更高频率：聚合本身的刷新频率由业务节奏决定（人员/活动一天变化几次），
     * 每日足够；提高频率只会让 {@code kb_snapshot} 堆积更多历史版本、
     * 让"在册 87 人"出现几十个互相竞争召回的版本（方案 R9）。
     */
    @Scheduled(cron = "${pams.agent.kb.snapshot-cron:0 10 3 * * ?}")
    public void dailyGenerate() {
        try {
            Map<String, Object> r = generateAll();
            log.info("知识库事实快照每日生成完成：{}", r);
        } catch (Exception e) {
            // 定时任务必须自己吞异常，否则一次失败会让后续调度静默停止
            log.error("知识库事实快照每日生成失败", e);
        }
    }

    /**
     * 生成全部快照（定时任务与手工触发共用）。
     *
     * @return 统计报告：每个 spec 生成了几份、跳过几份（无数时不生成）
     */
    @Transactional
    public Map<String, Object> generateAll() {
        Map<String, Object> report = new LinkedHashMap<>();
        int totalWritten = 0;
        int totalSkipped = 0;

        for (SnapshotSpec spec : registry.all()) {
            int written = 0;
            int skipped = 0;
            for (SnapshotSpec.ScopeRef scope : safeScopes(spec)) {
                if (generateOne(spec, scope)) written++;
                else skipped++;
            }
            totalWritten += written;
            totalSkipped += skipped;
            report.put(spec.code(), Map.of("written", written, "skipped", skipped));
        }
        report.put("totalWritten", totalWritten);
        report.put("totalSkipped", totalSkipped);
        report.put("lastAsOf", snapshotRepository.latestAsOf());
        return report;
    }

    /** 生成单个快照；返回是否写入（{@code false} = 该作用域没有数据，跳过） */
    @Transactional
    public boolean generateOne(SnapshotSpec spec, SnapshotSpec.ScopeRef scope) {
        String content = spec.narrator().apply(scope.id());
        if (content == null || content.isBlank()) {
            // 空数据不生成：否则会产生"本届成员共 0 人"这种既无用又会污染召回的条目
            return false;
        }

        LocalDateTime asOf = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);

        // 幂等：同一分钟内的重复生成更新既有行而不是插入新行
        KbSnapshot row = snapshotRepository
                .findBySpecCodeAndScopeTypeAndScopeIdAndAsOf(
                        spec.code(), spec.scopeType(), scope.id(), asOf)
                .orElseGet(KbSnapshot::new);

        row.setSpecCode(spec.code());
        row.setScopeType(spec.scopeType());
        row.setScopeId(scope.id());
        row.setAsOf(asOf);
        row.setTitle(spec.titleOf(scope.label()));
        row.setContent(content);
        row.setVisibility(spec.visibility());
        row.setRequiredPermission(spec.requiredPermission());
        row.setSensitivity("sensitive");   // 聚合数据按敏感级处理：出网前仍走闸口
        row.setGeneratedAt(LocalDateTime.now());
        row.setDeleted(0);

        KbSnapshot saved = snapshotRepository.save(row);

        // 落库只是"数据在"，能检索还得进 kb_chunk —— 漏了这一步的表现是
        // "页面能看到快照、问答却查不到"，且不报错
        try {
            indexer.reindexSource(com.pams.module.knowledge.source.SnapshotSource.SOURCE_TYPE,
                    saved.getId());
        } catch (Exception e) {
            log.warn("快照 {} 已生成但建立索引失败（下次全量重建会补上）：{}", saved.getId(), e.getMessage());
        }
        return true;
    }

    /**
     * 收起每个 spec 保留多少份历史。
     *
     * <p>保留策略的意义：快照按日生成，一年就是 365 份"在册 87 人"的变体。
     * 它们会互相竞争检索配额，让答案在"87"和"85"之间随机跳 —— 比只留最新一份更糟。
     * 所以每个 spec + 作用域只保留最近 {@link #KEEP_PER_SCOPE} 份（用于"这个数字变了多少"的对比）。
     */
    public static final int KEEP_PER_SCOPE = 3;

    /** 触发一次保留策略清理（在全量生成之后调用） */
    @Transactional
    public int pruneOldSnapshots() {
        int removed = 0;
        for (SnapshotSpec spec : registry.all()) {
            for (SnapshotSpec.ScopeRef scope : safeScopes(spec)) {
                List<Long> ids = snapshotRepository.findIdsNewestFirst(
                        spec.code(), spec.scopeType(), scope.id(),
                        org.springframework.data.domain.PageRequest.of(0, 1000));
                for (int i = KEEP_PER_SCOPE; i < ids.size(); i++) {
                    Long id = ids.get(i);
                    snapshotRepository.findById(id).ifPresent(s -> {
                        s.setDeleted(1);
                        snapshotRepository.save(s);
                    });
                    // 分块同步清理（软删行不再被 @SQLRestriction 查到，但 chunk 是派生的，直接重建）
                    indexer.reindexSource(
                            com.pams.module.knowledge.source.SnapshotSource.SOURCE_TYPE, id);
                    removed++;
                }
            }
        }
        if (removed > 0) log.info("知识库快照保留策略：清理了 {} 份历史版本", removed);
        return removed;
    }

    private List<SnapshotSpec.ScopeRef> safeScopes(SnapshotSpec spec) {
        try {
            List<SnapshotSpec.ScopeRef> refs = spec.scopes().get();
            return refs == null ? List.of() : refs;
        } catch (Exception e) {
            log.warn("快照 {} 的作用域枚举失败，本次跳过", spec.code(), e);
            return new ArrayList<>();
        }
    }
}
