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

/** 策划书知识源；检索时按版本校验作者、所选审核人及历史版本可见性。 */
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

    private final com.pams.module.activity.service.PlanAccessService access;
    private final ActivityPlanRepository planRepository;
    private final ActivityRepository activityRepository;

    public ActivityPlanSource(ActivityPlanRepository planRepository,
                              ActivityRepository activityRepository, com.pams.module.activity.service.PlanAccessService access) {
        this.access=access;
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
                + "当前版本仅作者与所选审核人可见，其他用户仅可检索历史版本。";
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
        return access.canRead(sourceId,ctx);
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
                .map(id -> "/activities/" + id + "?tab=plan&planId=" + sourceId)
                .orElse(null);   // 拿不到活动 ID 就返回不可点，不给必然 404 的链接
    }

    /* ------------------------------------------------------------------ helpers */

    private KnowledgeDoc toDoc(ActivityPlan p) {
        if (p == null) return null;

        Activity activity = p.getActivityId() == null ? null
                : activityRepository.findById(p.getActivityId()).orElse(null);
        String activityName = activity != null && activity.getName() != null
                ? activity.getName() : ("活动 " + p.getActivityId());
        if(p.getNameOverride()!=null) activityName=p.getNameOverride();

        String versionLabel = "策划书 v" + (p.getVersion() == null ? 1 : p.getVersion())
                + "（" + statusLabel(p.getStatus()) + "）";

        // 标题：活动名 + 版本状态 —— 这样引用列表里一眼能看出是"哪次活动、哪一版"
        String title = activityName + " · " + versionLabel;

        List<Section> sections = new ArrayList<>();
        if (p.getDocumentFileId()!=null && p.getDocumentText()!=null && !p.getDocumentText().isBlank()) {
            sections.add(Section.of(versionLabel + " · 文档正文",p.getDocumentText()));
        }
        for (Map.Entry<String, String> e : SECTION_LABELS.entrySet()) {
            if(p.getDocumentFileId()!=null) break;
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
            case WITHDRAWN -> "已撤回";
        };
    }
}
