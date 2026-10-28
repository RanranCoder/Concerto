package com.pams.module.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.module.activity.dto.PlanRequest;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.service.PlanService;
import com.pams.module.agent.core.*;
import com.pams.module.agent.service.HtmlSanitizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写类工具：策划书章节草稿（业务设计方案 §3.9.1，对应工具清单 #15 {@code draftPlanSections}）。
 *
 * <p>回答"帮这个活动写一份策划书""把策划书补齐"。落 {@code activity_plan(DRAFT)}，
 * <b>不提交审核</b>——起草是"给出初稿"，提交是"我认可这稿"，两者不应合并（与推文草稿同一原则）。
 *
 * <p><b>内容由谁写</b>：章节正文由 <b>LLM 撰写后作为工具参数传入</b>（工具描述里明确要求），
 * 工具负责"校验 + 净化 + 出预览 + 确认后落库"。这样做有三个好处：
 * <ol>
 *   <li>不引入"工具内部再调一次 LLM"的反模式（工具依赖注入 LLM 会让权限/审计边界变模糊）；</li>
 *   <li>正文天然进入 D2 确认门禁——先看全文再落库（用户反馈 item 2 的诉求）；</li>
 *   <li>事实类字段（名称/时间/地点）由前端信息章节从活动自动带出，模型只写需要文采的章节。</li>
 * </ol>
 *
 * <p><b>只写"模型真的写了"的章节</b>：空章节不落库，避免把用户已编辑过的内容清空；
 * 未生成的章节以 skipped 呈现（必现，符合 §4.3），用户一眼看到还缺哪几章。
 *
 * <p>权限码 {@code plan:edit}；数据级 normal；可撤回（草稿可改可删）。
 */
@Component
public class DraftPlanSectionsTool implements WriteAgentTool {

    private static final String ARGS_MARK = "__args__:";

    /**
     * 章节顺序与字段映射——编号与前端 12 章模板一致（三/四/九/十/十一/十二），
     * 一/二/五/六/七/八 是"活动基本信息"只读章节，由前端从活动带出，模型不需要写。
     * flow（活动流程）不在 12 章骨架里，但数据模型有该字段，作为附节生成。
     */
    private static final Object[][] CHAPTERS = {
            {"background", "三、活动背景"},
            {"purpose", "四、活动目的"},
            {"content", "九、活动内容"},
            {"notice", "十、活动注意事项"},
            {"emergency", "十一、应急预案"},
            {"budget", "十二、经费预算"},
            {"flow", "附：活动流程"},
    };

    /** preview 里每章最多展示的字符数（防止抽屉被单片长文撑爆；落库内容不受此限制） */
    private static final int PREVIEW_PER_CHAPTER = 600;

    private final ActivityResolver activityResolver;
    private final PlanService planService;
    private final HtmlSanitizer htmlSanitizer;
    private final ObjectMapper mapper = new ObjectMapper();

    public DraftPlanSectionsTool(ActivityResolver activityResolver, PlanService planService,
                                 HtmlSanitizer htmlSanitizer) {
        this.activityResolver = activityResolver;
        this.planService = planService;
        this.htmlSanitizer = htmlSanitizer;
    }

    @Override
    public String name() { return "draftPlanSections"; }

    @Override
    public String label() { return "策划书草稿"; }

    @Override
    public String runningLabel() { return "正在整理策划书草稿…"; }

    @Override
    public String description() {
        return "为活动起草策划书章节内容，生成一份策划书草稿（DRAFT 状态，不提交审核）。"
                + "用法：你（模型）先按章节撰写正文，再作为参数传进来——"
                + "activityName 传活动名称；background/purpose/content/notice/emergency/budget/flow "
                + "分别对应「活动背景/活动目的/活动内容/活动注意事项/应急预案/经费预算/活动流程」，"
                + "只写你确实写了内容的章节，没写的章节不用传。"
                + "正文可含简单排版（段落、列表），也支持纯文本。"
                + "调用后会先给用户看全文预览，确认后才写入策划书（草稿）。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolArgs.schema("object", ToolArgs.map(
                "activityId", ToolArgs.prop("integer", "活动 ID，可省略；与 activityName 二选一"),
                "activityName", ToolArgs.prop("string", ActivityResolver.NAME_DESC),
                "background", ToolArgs.prop("string", "「活动背景」正文（你撰写的）"),
                "purpose", ToolArgs.prop("string", "「活动目的」正文（你撰写的）"),
                "content", ToolArgs.prop("string", "「活动内容」正文（你撰写的，可分条）"),
                "notice", ToolArgs.prop("string", "「活动注意事项」正文（你撰写的）"),
                "emergency", ToolArgs.prop("string", "「应急预案」正文（你撰写的）"),
                "budget", ToolArgs.prop("string", "「经费预算」正文（物品/数量/单价/总价，可用纯文本表格）"),
                "flow", ToolArgs.prop("string", "「活动流程」正文（你撰写的，可按时间顺序排列）")
        ), List.of());
    }

    @Override
    public List<String> requiredPermissions() { return List.of("plan:edit"); }

    @Override
    public Sensitivity sensitivity() { return Sensitivity.NORMAL; }

    /* ============================================================ 读阶段：dry-run */

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        ActivityResolver.Resolution res =
                activityResolver.resolve(ToolArgs.lng(args, "activityId"), ToolArgs.str(args, "activityName"));
        if (res.failed()) return ToolResult.failed(res.message());
        Activity activity = res.activity();
        Long activityId = activity.getId();

        // 只收非空章节；空串不落库（否则会把用户已编辑的章节清空）
        Map<String, String> provided = new LinkedHashMap<>();
        for (Object[] row : CHAPTERS) {
            String key = (String) row[0];
            String v = ToolArgs.str(args, key);
            if (v != null && !v.isBlank()) provided.put(key, v.trim());
        }
        if (provided.isEmpty()) {
            return ToolResult.failed("还没有任何章节内容。请先按章节撰写正文（至少给出「活动背景」与「活动内容」），"
                    + "再把它们作为参数传给我；也可以告诉我活动主题与要点，我来帮你写。");
        }

        // D10：落库前必须净化（LLM 产出 → 数据库 → 所有读取路径）
        Map<String, String> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : provided.entrySet()) {
            cleaned.put(e.getKey(), htmlSanitizer.sanitize(e.getValue(), true));
        }

        List<AgentActionPlan.PlanItem> items = new ArrayList<>();
        List<AgentActionPlan.SkippedItem> skipped = new ArrayList<>();
        for (Object[] row : CHAPTERS) {
            String key = (String) row[0];
            String chapterLabel = (String) row[1];
            String body = cleaned.get(key);
            if (body == null) {
                skipped.add(new AgentActionPlan.SkippedItem(chapterLabel, "本次未生成", "留待你补充或下一轮再写"));
                continue;
            }
            String plain = htmlSanitizer.toPlainText(body).trim();
            items.add(new AgentActionPlan.PlanItem(null, chapterLabel, plain.length() + " 字"));
        }

        List<String> detailLines = new ArrayList<>();
        detailLines.add("关联活动：" + activity.getName());
        detailLines.add("落库状态：策划书草稿（DRAFT，需在活动详情 → 策划书中提交审核）");
        detailLines.add("生成章节：" + items.size() + " 章"
                + (skipped.isEmpty() ? "（12 章骨架中其余章节由活动基本信息带出）"
                                     : "，未生成 " + skipped.size() + " 章（见上方跳过项）"));
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("activityId", activityId);
        exec.putAll(cleaned);
        detailLines.add(ARGS_MARK + writeJson(exec));

        String preview = buildPreview(activity, cleaned);
        String summary = "活动「" + activity.getName() + "」· 策划书草稿（" + items.size() + " 章）";

        AgentActionPlan plan = new AgentActionPlan(null, summary, items.size(), items, skipped,
                detailLines, true, null, preview);

        return ToolResult.plan(
                "已按 12 章骨架整理出 " + items.size() + " 章内容"
                        + (skipped.isEmpty() ? "。" : "，另有 " + skipped.size() + " 章未生成（已列在清单中）。")
                        + "请点「查看全文」通读后再确认；确认后写入策划书草稿（不会自动提交审核）。",
                plan);
    }

    /* ============================================================ 写阶段：用户确认后 */

    @Override
    public CommitResult commit(ToolContext ctx, AgentActionPlan plan) {
        Map<String, Object> exec = readArgs(plan);
        if (exec == null) return CommitResult.failed("无法还原草稿内容，请重新发起。");

        try {
            Long activityId = lng(exec.get("activityId"));
            if (activityId == null) return CommitResult.failed("草稿缺少关联活动，请重新发起。");

            PlanRequest req = new PlanRequest();
            req.setActivityId(activityId);
            // 二次净化：plan_json 落库往返后仍按不可信内容对待（D10）
            req.setBackground(sanitize(exec.get("background")));
            req.setPurpose(sanitize(exec.get("purpose")));
            req.setContent(sanitize(exec.get("content")));
            req.setNotice(sanitize(exec.get("notice")));
            req.setEmergency(sanitize(exec.get("emergency")));
            req.setBudget(sanitize(exec.get("budget")));
            req.setFlow(sanitize(exec.get("flow")));
            req.setSyncActivity(false); // 只写策划书，不回写活动基本信息

            var saved = planService.create(req,ctx);

            List<Map<String, Object>> refs = new ArrayList<>();
            refs.add(ToolArgs.map("type", "activity", "id", activityId,
                    "label", "活动 " + activityId + " 策划书", "link", "/activities/" + activityId));

            return new CommitResult("SUCCESS", 1, 0,
                    "已生成策划书草稿（第 " + saved.getVersion() + " 版，草稿状态），"
                            + "可在活动详情 →「策划书」中继续编辑并提交审核。",
                    ToolArgs.map("planId", saved.getId(), "version", saved.getVersion()), refs);
        } catch (Exception e) {
            return CommitResult.failed("策划书写入失败：" + e.getMessage());
        }
    }

    /* ============================================================ helpers */

    /**
     * 全文预览（Markdown 形态，供前端抽屉阅读）。
     * 每章截断到 {@link #PREVIEW_PER_CHAPTER} 字——预览的目的是"能通读并判断要不要落库"，
     * 而不是把整篇长文塞进对话流；落库内容本身不受影响。
     */
    private String buildPreview(Activity activity, Map<String, String> cleaned) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(activity.getName()).append(" · 策划书草稿\n\n");
        sb.append("> 一、活动名称 / 二、活动主题 / 五、活动时间 / 六、活动地点 / 七、活动组织单位 / 八、活动对象 ");
        sb.append("由活动基本信息自动带出，以下为本次生成的章节正文。\n\n");
        for (Object[] row : CHAPTERS) {
            String body = cleaned.get((String) row[0]);
            if (body == null) continue;
            String plain = htmlSanitizer.toPlainText(body).trim();
            sb.append("## ").append(row[1]).append("\n\n");
            if (plain.length() > PREVIEW_PER_CHAPTER) {
                sb.append(plain, 0, PREVIEW_PER_CHAPTER).append("…（预览已截断，完整内容将在确认后写入）\n\n");
            } else {
                sb.append(plain).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    private String sanitize(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        if (s.isBlank()) return null;
        return htmlSanitizer.sanitize(s, true);
    }

    private String writeJson(Map<String, Object> m) {
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readArgs(AgentActionPlan plan) {
        if (plan == null || plan.detailLines() == null) return null;
        for (String line : plan.detailLines()) {
            if (line != null && line.startsWith(ARGS_MARK)) {
                try {
                    return mapper.readValue(line.substring(ARGS_MARK.length()), Map.class);
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    private Long lng(Object o) {
        if (o == null) return null;
        try {
            return Long.valueOf(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }
}
