package com.pams.module.knowledge.source;

import com.pams.module.agent.core.ToolContext;
import com.pams.module.archive.entity.Announcement;
import com.pams.module.archive.repository.AnnouncementRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识源：公告（{@code announcement}）。
 *
 * <h3>权限口径（canAccess）—— 为什么是"登录即可"</h3>
 * <b>这是照着页面的真实门槛来的，不是照着权限码来的。</b>
 * <pre>
 * 页面   GET /api/announcements  → 无 @PreAuthorize（实测）
 * 菜单   MainLayout: hasPerm('notice:view') 决定是否显示入口
 * </pre>
 * 也就是说 {@code notice:view} **只控制菜单显隐**，不控制能否读到数据。
 * 若这里按"有没有 notice:view"判定，就会凭空造出一个比页面更严限制；
 * 若反过来按"页面有注解"判定，会得出"这个源没有门"的错误结论。
 * 正确做法是照**实际能读到数据的最宽口径**（登录即可）——
 * 这也与方案 Q16 的默认选择一致：知识库不比页面更严，差异必须显式声明而不是默默产生。
 *
 * <h3>分级与权重</h3>
 * T1 全文级（{@code content} 是完整正文），权重 {@code 1.0} —— 公告是权威规范类来源。
 */
@Component
public class AnnouncementSource implements KnowledgeSource {

    public static final String SOURCE_TYPE = "announcement";

    private final AnnouncementRepository repository;

    public AnnouncementSource(AnnouncementRepository repository) {
        this.repository = repository;
    }

    @Override
    public String sourceType() { return SOURCE_TYPE; }

    @Override
    public String label() { return "公告通知"; }

    @Override
    public String description() {
        return "全部公告正文。页面入口（/archive/announcements）无路由守卫，"
                + "notice:view 仅控制菜单显隐，故可见性口径为「登录即可」。";
    }

    @Override
    public Tier tier() { return Tier.T1; }

    @Override
    public double weight() { return 1.0; }

    @Override
    public List<KnowledgeDoc> extractAll() {
        List<KnowledgeDoc> out = new ArrayList<>();
        for (Announcement a : repository.findAll()) {
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

    /**
     * 口径 = 页面的真实门槛（登录即可）。
     *
     * <p>实现上仍要求 {@code ctx != null} —— 这是防"被不持有上下文的旁路调用"
     * （审计 A1 就是这类旁路造成的），不是权限要求本身。
     */
    @Override
    public boolean canAccess(ToolContext ctx, Long sourceId) {
        if (ctx == null) return false;
        // 记录本身不存在/已软删时，返回 false 让它走"已不可见"分支（@SQLRestriction 已排除软删）
        return repository.findById(sourceId).isPresent();
    }

    /** 公告页面（列表即可定位；该页当前不支持按 id 直达详情） */
    @Override
    public String link(Long sourceId) {
        return "/archive/announcements";
    }

    /* ------------------------------------------------------------------ helpers */

    private KnowledgeDoc toDoc(Announcement a) {
        if (a.getContent() == null || a.getContent().isBlank()) return null;

        List<Section> sections = new ArrayList<>(PlainTextSectioning.split(a.getContent()));
        if (sections.isEmpty()) return null;

        return KnowledgeDoc.of(SOURCE_TYPE, a.getId(), a.getTitle(), Tier.T1, sections,
                a.getPublishTime() == null ? null : a.getPublishTime().toString());
    }
}
