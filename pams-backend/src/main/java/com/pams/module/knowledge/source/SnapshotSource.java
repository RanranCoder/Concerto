package com.pams.module.knowledge.source;

import com.pams.module.agent.core.ToolContext;
import com.pams.module.knowledge.entity.KbSnapshot;
import com.pams.module.knowledge.repository.KbSnapshotRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识源：事实快照（{@code kb_snapshot}，D12 的 **B 形态聚合快照**）。
 *
 * <h3>它补的是知识库最大的一块空白</h3>
 * 在此之前知识库只能答"文档类"问题（制度/公告/稿件/策划书/成员条目）。
 * 问"我们支部有多少人""最近活动办了几场"一律答"没有找到依据" ——
 * 因为这类数据属于 **A 形态原始明细**，而 A 形态是**刻意不进索引**的
 * （含不可脱敏的个人信息，方案 §2.2）。但"答不上"不是正确答案，
 * 它的出路是把聚合事实写成人读得懂的叙述再进索引 —— 就是本源。
 *
 * <h3>三个必须做对的地方</h3>
 * <ol>
 *   <li><b>{@code asOf} 必须一路传到分块</b>（{@link KnowledgeDoc#withAsOf}）。
 *       缺了它，{@code CitationMapper} 会**直接拒绝**该引用出网（R16 硬断言）——
 *       这是刻意的：用户把"某时刻的数字"当"当前值"读，比不答更糟。</li>
 *   <li><b>权限口径由快照自己的 visibility 决定</b>，而不是统一一个值。
 *       同一个源里，成员构成是全员可见、党员结构是部长及以上 ——
 *       因为它们的来源页面守卫不同。硬套一个口径必然有一半是错的。</li>
 *   <li><b>分块标题要带作用域</b>（"第 40 届成员构成"），否则多届快照在引用列表里
 *       看起来一模一样，用户无从分辨是哪一份。</li>
 * </ol>
 *
 * <h3>分级与权重</h3>
 * T1 全文级（叙述文本是完整句子），权重 {@code 0.85} ——
 * **低于制度/公告（1.0）**：聚合事实回答"有多少/什么情况"，
 * 不该在"该怎么规定"这类问题上盖过条款（方案 R13 的同一条思路）。
 */
@Component
public class SnapshotSource implements KnowledgeSource {

    public static final String SOURCE_TYPE = "snapshot";

    /** 与 {@code KbSnapshot.visibility} 的取值约定一致 */
    private static final String VISIBILITY_LEADER = "LEADER";

    private final KbSnapshotRepository repository;

    public SnapshotSource(KbSnapshotRepository repository) {
        this.repository = repository;
    }

    @Override
    public String sourceType() { return SOURCE_TYPE; }

    @Override
    public String label() { return "事实快照"; }

    @Override
    public String description() {
        return "按日生成的聚合事实（成员构成、活动开展情况、党员发展结构等）。"
                + "**只有计数与组织名，不含任何个人明细** —— 逐人数据（学号/电话/政治面貌）"
                + "属于原始明细，刻意不进知识库。可见范围逐条声明（成员构成全员、"
                + "党员结构部长及以上），与各自来源页面一致。"
                + "每条快照都带数据截止时间，回答时会明示「截至 …」。";
    }

    @Override
    public Tier tier() { return Tier.T1; }

    @Override
    public double weight() { return 0.85; }

    /** 聚合数据按敏感级处理：出网前仍走 PolicyGuard 闸口 */
    @Override
    public String sensitivity() { return "sensitive"; }

    /* ---------------------------------------------------------------- 抽取 */

    @Override
    public List<KnowledgeDoc> extractAll() {
        List<KnowledgeDoc> out = new ArrayList<>();
        for (KbSnapshot s : repository.findAll()) {
            KnowledgeDoc doc = toDoc(s);
            if (doc != null) out.add(doc);
        }
        return out;
    }

    @Override
    public List<KnowledgeDoc> extractOne(Long sourceId) {
        return repository.findById(sourceId)
                .map(this::toDoc)
                .filter(java.util.Objects::nonNull)
                .map(List::of)
                .orElse(List.of());   // 已软删/不存在 → 清空其分块
    }

    /**
     * 可见性来自快照自身的字段（逐条声明），不是源的统一口径。
     *
     * <p>另外校验 {@code asOf} 非空：快照没有数据截止时间就不该被检索到 ——
     * 让它进上下文等于放任"过期数字冒充当前值"。
     */
    @Override
    public boolean canAccess(ToolContext ctx, Long sourceId) {
        if (ctx == null) return false;
        KbSnapshot s = repository.findById(sourceId).orElse(null);
        if (s == null || s.getAsOf() == null) return false;

        if (VISIBILITY_LEADER.equals(s.getVisibility()) && !ctx.isLeader()) return false;
        if (s.getRequiredPermission() != null && !s.getRequiredPermission().isBlank()
                && !ctx.hasPermission(s.getRequiredPermission())) {
            return false;
        }
        return true;
    }

    /**
     * 跳转：按 spec 区分页面。
     *
     * <p>成员构成进成员管理页、党员结构进党务台账、活动情况进活动列表 ——
     * 这个映射只有本源知道，引用层不该猜（{@link KnowledgeSource#link} 的存在理由）。
     */
    @Override
    public String link(Long sourceId) {
        KbSnapshot s = repository.findById(sourceId).orElse(null);
        if (s == null || s.getSpecCode() == null) return null;
        return switch (s.getSpecCode()) {
            case "member_composition" -> "/members";
            case "party_composition" -> "/party/members";
            case "activity_overview" -> "/activities";
            default -> null;   // 未知 spec：不给必然 404 的链接
        };
    }

    /* ---------------------------------------------------------------- 组装 */

    private KnowledgeDoc toDoc(KbSnapshot s) {
        if (s == null || s.getContent() == null || s.getContent().isBlank()) return null;
        // 没有数据截止时间的快照不进索引 —— 见 canAccess 的说明
        if (s.getAsOf() == null) return null;

        Section section = Section.of(null, s.getContent());

        return KnowledgeDoc.of(SOURCE_TYPE, s.getId(), s.getTitle(), Tier.T1,
                        List.of(section),
                        s.getAsOf().toString())
                // 关键：把数据截止时间一路带到分块（方案 R16）
                .withAsOf(s.getAsOf())
                .withVisibility(s.getVisibility(), s.getRequiredPermission(), s.getSensitivity());
    }
}
