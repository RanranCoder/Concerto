package com.pams.module.knowledge.source;

import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.entity.ActivityPlan;
import com.pams.module.activity.repository.ActivityPlanRepository;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.agent.core.ToolContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识源：活动策划书（{@code activity_plan}）。
 *
 * <p><b>这是"先例型问答"的主力语料</b> —— 用户问"上次南湖纪念馆那场活动的策划书
 * 写了哪些环节""办这类活动一般怎么安排流程"，答案就在这张表里。
 * 而且它已经在库、已有版本，接入成本几乎为零（方案 §2.6 把它列为 P0 的理由）。
 *
 * <h3>为什么草稿也进索引（与 {@link ArticleSource} 相反）</h3>
 * 两个源的取舍刻意不同，理由要说清：
 * <pre>
 * 稿件是"要对外发布的作品"  → 未定稿会误导"格式/要求"类问题 → 排除
 * 策划书是"内部执行记录"    → 草稿也真实反映了"当时是怎么办的" → 收录，但把状态写进 path
 * </pre>
 * 所以本源的 {@code path} 里会带上版本与状态（如「策划书 v3（草稿）· 活动流程」），
 * <b>让出处自己说明它的可信度</b>，而不是替用户做过滤决定。这比"静默排除"诚实。
 *
 * <h3>权限口径（canAccess）</h3>
 * 策划书在活动详情页里展示，而 {@code /activities/:id} <b>无路由守卫</b>
 * （代码注释："活动管理（干事可看…）"）。所以口径是"登录即可"。
 *
 * <p>⚠️ 注意与 {@code ActivityController} 的关系：本方案同时给
 * {@code GET /api/activities} 补了 {@code activity:view} 注解。由于 7 个角色的
 * 权限种子全都包含该码，**实际门槛未变**，所以这里用"登录即可"仍与页面一致。
 * 若将来真的收紧了 {@code activity:view} 的授予范围，**必须同步改这里** ——
 * 这是方案 R20（权限缺口向索引层传导）的登记点之一。
 *
 * <h3>分级与权重</h3>
 * T1 全文级，权重 {@code 0.9} —— 与稿件同档：实践记录，低于权威规范。
 */
@Component
public class ActivityPlanSource implements KnowledgeSource {

    public static final String SOURCE_TYPE = "activity_plan";

    /** 策划书章节字段 → 中文小标题。顺序即阅读顺序，用于拼出稳定的章节顺序 */
    private static final Map<String, String> SECTION_LABELS = new LinkedHashMap<>();

    static {
        SECTION_LABELS.put("background", "活动背景");
        SECTION_LABELS.put("purpose", "活动目的");
        SECTION_LABELS.put("content", "活动内容");
        SECTION_LABELS.put("flow", "活动流程");
        SECTION_LABELS.put("notice", "注意事项");
        SECTION_LABELS.put("emergency", "应急预案");
        SECTION_LABELS.put("budget", "经费预算");
    }

    private final ActivityPlanRepository planRepository;
    private final ActivityRepository activityRepository;

    public ActivityPlanSource(ActivityPlanRepository planRepository,
                              ActivityRepository activityRepository) {
        this.planRepository = planRepository;
        this.activityRepository = activityRepository;
    }

    @Override
    public String sourceType() { return SOURCE_TYPE; }

    @Override
    public String label() { return "活动策划书"; }

    @Override
    public String description() {
        return "活动策划书各章节正文（背景/目的/内容/流程/注意事项/预案/预算）。"
                + "先例型问答的主力语料；草稿也收录，但版本与状态会写进出处的标题路径。"
                + "页面入口 /activities/:id 无路由守卫，可见性口径为「登录即可」。";
    }

    @Override
    public Tier tier() { return Tier.T1; }

    @Override
    public double weight() { return 0.9; }

    @Override
    public List<KnowledgeDoc> extractAll() {
        List<KnowledgeDoc> out = new ArrayList<>();
        for (ActivityPlan p : planRepository.findAll()) {
            KnowledgeDoc d = toDoc(p);
            if (d != null) out.add(d);
        }
        return out;
    }

    @Override
    public List<KnowledgeDoc> extractOne(Long sourceId) {
        return planRepository.findById(sourceId)
                .map(this::toDoc)
                .filter(java.util.Objects::nonNull)
                .map(List::of)
                .orElse(List.of());
    }

    @Override
    public boolean canAccess(ToolContext ctx, Long sourceId) {
        if (ctx == null) return false;
        return planRepository.findById(sourceId).isPresent();
    }

    /**
     * 跳到活动详情页（策划书在那一页里）。
     *
     * <p>必须自己解析 {@code activityId} —— chunk 里只有 {@code planId}，
     * 而"策划书挂在哪个活动下"只有本源知道。这正是 {@link KnowledgeSource#link} 存在的理由。
     */
    @Override
    public String link(Long sourceId) {
        return planRepository.findById(sourceId)
                .map(ActivityPlan::getActivityId)
                .filter(id -> id != null)
                .map(id -> "/activities/" + id)
                .orElse(null);   // 拿不到活动 ID 就返回不可点，不给必然 404 的链接
    }

    /* ------------------------------------------------------------------ helpers */

    private KnowledgeDoc toDoc(ActivityPlan p) {
        if (p == null) return null;

        Activity activity = p.getActivityId() == null ? null
                : activityRepository.findById(p.getActivityId()).orElse(null);
        String activityName = activity != null && activity.getName() != null
                ? activity.getName() : ("活动 " + p.getActivityId());

        String versionLabel = "策划书 v" + (p.getVersion() == null ? 1 : p.getVersion())
                + "（" + statusLabel(p.getStatus()) + "）";

        // 标题：活动名 + 版本状态 —— 这样引用列表里一眼能看出是"哪次活动、哪一版"
        String title = activityName + " · " + versionLabel;

        List<Section> sections = new ArrayList<>();
        for (Map.Entry<String, String> e : SECTION_LABELS.entrySet()) {
            String text = field(p, e.getKey());
            if (text == null || text.isBlank()) continue;
            // path 带上版本与状态：让出处自己说明可信度（见类注释）
            sections.add(Section.of(versionLabel + " · " + e.getValue(), text));
        }
        if (sections.isEmpty()) return null;

        return KnowledgeDoc.of(SOURCE_TYPE, p.getId(), title, Tier.T1, sections,
                p.getUpdatedAt() == null ? null : p.getUpdatedAt().toString());
    }

    private String field(ActivityPlan p, String name) {
        return switch (name) {
            case "background" -> p.getBackground();
            case "purpose" -> p.getPurpose();
            case "content" -> p.getContent();
            case "flow" -> p.getFlow();
            case "notice" -> p.getNotice();
            case "emergency" -> p.getEmergency();
            case "budget" -> p.getBudget();
            default -> null;
        };
    }

    private String statusLabel(ActivityPlan.PlanStatus status) {
        if (status == null) return "未知状态";
        return switch (status) {
            case DRAFT -> "草稿";
            case PENDING -> "待审";
            case APPROVED -> "已审";
            case REJECTED -> "被驳回";
        };
    }
}
