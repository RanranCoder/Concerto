package com.pams.module.knowledge.source;

import com.pams.module.agent.core.ToolContext;
import com.pams.module.knowledge.entity.KbDocument;
import com.pams.module.knowledge.repository.KbDocumentRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识源：人工录入文档（{@code kb_document}）。
 *
 * <p><b>这是唯一"知识库自己拥有数据"的源</b>，其余三个都是业务表的只读派生。
 * 它的存在理由是：项目里没有"制度/规范性文件"这类载体，而制度型问题
 * （"发展党员要走哪些流程"）恰恰是知识库最高频的场景。
 *
 * <h3>权限口径（canAccess）</h3>
 * 这条数据没有独立的页面入口 —— 它能被看到，是因为用户进了知识库页面或问了灵思。
 * 所以口径不来自"某个页面的守卫"，而由记录自身的两个字段表达：
 * <ul>
 *   <li>{@code visibility}：{@code ALL} 全员 / {@code LEADER} 部长及以上</li>
 *   <li>{@code requiredPermission}：附加权限码（可选）</li>
 * </ul>
 * 两者取"与"。这是**唯一一个口径由数据自己声明**的源 —— 其余三个源必须去问
 * "那个页面的入口被谁挡住了"（见 {@link KnowledgeSource} 的约定 ①）。
 *
 * <h3>分级与权重</h3>
 * T1 全文级，权重 {@code 1.0} —— 制度是最高权威的语料。
 */
@Component
public class ManualDocSource implements KnowledgeSource {

    public static final String SOURCE_TYPE = "manual";

    private final KbDocumentRepository repository;

    public ManualDocSource(KbDocumentRepository repository) {
        this.repository = repository;
    }

    @Override
    public String sourceType() { return SOURCE_TYPE; }

    @Override
    public String label() { return "制度与录入文档"; }

    @Override
    public String description() {
        return "人工录入的制度、上级文件、操作指引与常见问答。"
                + "这是知识库里唯一的一手来源（其余源都是业务表的只读派生）。";
    }

    @Override
    public Tier tier() { return Tier.T1; }

    @Override
    public double weight() { return 1.0; }

    @Override
    public List<KnowledgeDoc> extractAll() {
        List<KnowledgeDoc> out = new ArrayList<>();
        for (KbDocument d : repository.findByStatusOrderByIdAsc(KbDocument.STATUS_PUBLISHED)) {
            KnowledgeDoc doc = toDoc(d);
            if (doc != null) out.add(doc);
        }
        return out;
    }

    @Override
    public List<KnowledgeDoc> extractOne(Long sourceId) {
        return repository.findById(sourceId)
                .filter(KbDocument::indexable)
                .map(this::toDoc)
                .filter(java.util.Objects::nonNull)
                .map(List::of)
                .orElse(List.of());   // 空 = 清理它的分块（草稿/归档/已删都走这里）
    }

    @Override
    public boolean canAccess(ToolContext ctx, Long sourceId) {
        if (ctx == null) return false;   // 保守拒绝：无上下文说明调用点不对
        KbDocument d = repository.findById(sourceId).orElse(null);
        if (d == null || !d.indexable()) return false;

        // visibility 与 requiredPermission 取"与"——两个约束都满足才放行
        if (LEADER.equals(d.getVisibility()) && !ctx.isLeader()) return false;
        if (d.getRequiredPermission() != null && !d.getRequiredPermission().isBlank()
                && !ctx.hasPermission(d.getRequiredPermission())) {
            return false;
        }
        return true;
    }

    /* ------------------------------------------------------------------ helpers */

    /** 与 {@code KbDocument.visibility} 的取值约定保持一致 */
    public static final String LEADER = "LEADER";

    private KnowledgeDoc toDoc(KbDocument d) {
        String body = d.getBody() == null ? "" : d.getBody();
        // 摘要作为首段：问"这个制度是干什么的"时，摘要往往比正文更直接
        List<Section> sections = new ArrayList<>();
        if (d.getSummary() != null && !d.getSummary().isBlank()) {
            sections.add(Section.of("摘要", d.getSummary()));
        }
        sections.addAll(PlainTextSectioning.split(body));
        if (sections.isEmpty()) return null;

        return KnowledgeDoc.of(SOURCE_TYPE, d.getId(), d.getTitle(), Tier.T1, sections,
                        d.getUpdatedAt() == null ? null : d.getUpdatedAt().toString())
                .withVisibility(d.getVisibility(), d.getRequiredPermission(), d.getSensitivity());
    }
}
