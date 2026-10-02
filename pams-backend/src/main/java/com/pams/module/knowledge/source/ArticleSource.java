package com.pams.module.knowledge.source;

import com.pams.module.agent.core.ToolContext;
import com.pams.module.content.entity.Article;
import com.pams.module.content.repository.ArticleRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识源：推文 / 稿件（{@code article}）。
 *
 * <h3>只索引已发布（内容决策，不是权限决策）</h3>
 * 未发布稿件会被<b>整体排除在索引之外</b>，理由与权限无关：
 * <pre>
 * 草稿是"还没定稿的东西"。把它当依据回答"推文标题有什么要求"，
 * 等于用一份可能被推翻的文档回答一个规范性问题 —— 这不安静地降低可信度。
 * </pre>
 * 这是一个**显式的产品取舍**，所以必须写在文档里：
 * 「页面能看到草稿，但知识库不会引用草稿。这不是越权，是内容质量策略。」
 * 如果将来需要先例型问答覆盖草稿，改这一处即可（并且要在方案里登记为语义变更）。
 *
 * <h3>权限口径（canAccess）</h3>
 * 页面 {@code /content/articles} <b>无路由守卫</b>（代码注释："推文干事可看"），
 * {@code GET /api/articles} 也无 {@code @PreAuthorize}。
 * 所以可见性口径是"登录即可"。
 *
 * <p>顺带记录一处不一致（不是缺陷，但要知情）：灵思工具 {@code searchArticles}
 * 用的是 {@code requiresLeader()}，**比页面更严**。工具更严是安全的方向，可以接受；
 * 但排查"为什么我看得到页面却问不出来"时要能立刻想到这条。
 *
 * <h3>分级与权重</h3>
 * T1 全文级，权重 {@code 0.9} —— 低于制度与公告：历史做法有参考价值，
 * 但问"该怎么规定"时不该被过去的实践带偏。
 */
@Component
public class ArticleSource implements KnowledgeSource {

    public static final String SOURCE_TYPE = "article";

    private final ArticleRepository repository;

    public ArticleSource(ArticleRepository repository) {
        this.repository = repository;
    }

    @Override
    public String sourceType() { return SOURCE_TYPE; }

    @Override
    public String label() { return "推文与稿件"; }

    @Override
    public String description() {
        return "已发布（PUBLISHED）的推文/稿件正文。"
                + "未发布草稿刻意不进索引 —— 未定稿不作为依据，这是内容策略而非权限限制。"
                + "页面入口无路由守卫，可见性口径为「登录即可」。";
    }

    @Override
    public Tier tier() { return Tier.T1; }

    @Override
    public double weight() { return 0.9; }

    @Override
    public List<KnowledgeDoc> extractAll() {
        List<KnowledgeDoc> out = new ArrayList<>();
        for (Article a : repository.findAll()) {
            KnowledgeDoc d = toDoc(a);
            if (d != null) out.add(d);
        }
        return out;
    }

    @Override
    public List<KnowledgeDoc> extractOne(Long sourceId) {
        return repository.findById(sourceId)
                .map(this::toDoc)
                .filter(java.util.Objects::nonNull)
                .map(List::of)
                .orElse(List.of());
    }

    @Override
    public boolean canAccess(ToolContext ctx, Long sourceId) {
        if (ctx == null) return false;
        Article a = repository.findById(sourceId).orElse(null);
        if (a == null) return false;
        // 已发布：与页面一致（登录即可）。未发布：不进索引，因此这里也不放行，
        // 避免"某条历史上被索引过、后来退回草稿"时仍能读到
        return a.getStatus() == Article.ArticleStatus.PUBLISHED;
    }

    /** 推文列表页 */
    @Override
    public String link(Long sourceId) {
        return "/content/articles";
    }

    /* ------------------------------------------------------------------ helpers */

    private KnowledgeDoc toDoc(Article a) {
        if (a == null || a.getStatus() != Article.ArticleStatus.PUBLISHED) return null;
        if (a.getContent() == null || a.getContent().isBlank()) return null;

        List<Section> sections = new ArrayList<>();
        if (a.getSummary() != null && !a.getSummary().isBlank()) {
            sections.add(Section.of("摘要", a.getSummary()));
        }
        sections.addAll(PlainTextSectioning.split(a.getContent()));
        if (sections.isEmpty()) return null;

        return KnowledgeDoc.of(SOURCE_TYPE, a.getId(), a.getTitle(), Tier.T1, sections,
                a.getPublishTime() == null ? null : a.getPublishTime().toString());
    }
}
