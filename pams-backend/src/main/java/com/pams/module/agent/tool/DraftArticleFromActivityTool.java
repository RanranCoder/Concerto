package com.pams.module.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.module.activity.entity.Activity;
import com.pams.module.agent.core.*;
import com.pams.module.agent.service.HtmlSanitizer;
import com.pams.module.content.dto.ArticleRequest;
import com.pams.module.content.service.ArticleService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写类工具：从活动生成推文草稿（业务设计方案 §3.5）。
 *
 * <p>回答"帮我把这次活动的推文起草了"。产出落为 {@code Article(DRAFT)}，
 * 不自动提交审核——起草是"给出初稿"，提交是"我认可这稿"，两者不应合并。
 *
 * <p><b>净化在此不可省</b>：草稿正文由 LLM 生成，会写入数据库。
 * 必须经 {@link HtmlSanitizer} 后再落库（D10）——否则脏 HTML 会在<b>所有</b>读取路径上生效。
 *
 * <p>权限：content 模块现状口径（G1）→ {@code requiresLeader()}。
 */
@Component
public class DraftArticleFromActivityTool implements WriteAgentTool {

    private static final String ARGS_MARK = "__args__:";

    private final ArticleService articleService;
    private final HtmlSanitizer htmlSanitizer;
    private final ActivityResolver activityResolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public DraftArticleFromActivityTool(ArticleService articleService,
                                        HtmlSanitizer htmlSanitizer,
                                        ActivityResolver activityResolver) {
        this.articleService = articleService;
        this.htmlSanitizer = htmlSanitizer;
        this.activityResolver = activityResolver;
    }

    @Override
    public String name() { return "draftArticleFromActivity"; }

    @Override
    public String label() { return "起草推文"; }

    @Override
    public String runningLabel() { return "正在起草推文…"; }

    @Override
    public String description() {
        return "根据活动的名称、主题、时间、地点等信息，生成一篇新闻稿/推文的草稿。"
                + "生成结果为草稿状态，需要用户确认后才写入系统并可在稿件管理中继续编辑。"
                + "适用于'帮我把这次活动的推文起草了''写一篇活动总结'等指令。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolArgs.schema("object", ToolArgs.map(
                "activityId", ToolArgs.prop("integer", "活动 ID，可省略；与 activityName 二选一"),
                "activityName", ToolArgs.prop("string", ActivityResolver.NAME_DESC),
                "articleType", ToolArgs.prop("string", "稿件类型：REPORT（报道）/ NOTICE（通知），默认 REPORT"),
                "authorId", ToolArgs.prop("integer", "稿件负责人用户 ID，可省略（默认当前用户）")
        ), List.of());
    }

    @Override
    public boolean requiresLeader() { return true; }

    @Override
    public Sensitivity sensitivity() { return Sensitivity.NORMAL; }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        ActivityResolver.Resolution res =
                activityResolver.resolve(ToolArgs.lng(args, "activityId"), ToolArgs.str(args, "activityName"));
        if (res.failed()) return ToolResult.failed(res.message());
        Long activityId = res.activity().getId();
        String articleType = ToolArgs.str(args, "articleType", "REPORT").toUpperCase();
        Long authorId = ToolArgs.lng(args, "authorId");
        if (authorId == null) authorId = ctx.getUserId();

        try {
            Activity a = res.activity();

            // 生成草稿正文（纯规则模板 + 活动事实；LLM 的价值在于措辞，事实必须来自库）
            String title = buildTitle(a);
            String contentHtml = buildContent(a);
            String pureText = htmlSanitizer.toPlainText(contentHtml);

            List<AgentActionPlan.PlanItem> items = List.of(
                    new AgentActionPlan.PlanItem(activityId, "标题", title),
                    new AgentActionPlan.PlanItem(activityId, "正文", pureText.length() > 60
                            ? pureText.substring(0, 60) + "…" : pureText)
            );

            List<AgentActionPlan.SkippedItem> skipped = new ArrayList<>();
            if (a.getStartDate() == null) {
                skipped.add(new AgentActionPlan.SkippedItem("活动时间", "缺失", "活动中未填写时间，草稿中留空"));
            }

            List<String> detailLines = new ArrayList<>();
            detailLines.add("关联活动：" + a.getName());
            detailLines.add("稿件类型：" + articleType);
            detailLines.add("落库状态：草稿（需在稿件管理中提交审核）");
            Map<String, Object> exec = new LinkedHashMap<>();
            exec.put("activityId", activityId);
            exec.put("articleType", articleType);
            exec.put("authorId", authorId);
            exec.put("title", title);
            exec.put("summary", buildSummary(a));
            exec.put("content", contentHtml);
            detailLines.add(ARGS_MARK + writeJson(exec));

            String summary = "活动「" + a.getName() + "」· 推文草稿";

            // 内容预览（§3.9.1）：推文属于"写进去的是一篇长文"，确认前应能通读全文
            String preview = "# " + title + "\n\n" + pureText
                    + "\n\n> 本稿由灵思根据活动信息起草，确认后进入稿件管理（草稿状态），可继续补充现场细节与图片。";

            AgentActionPlan plan = new AgentActionPlan(null, summary, 1, items, skipped,
                    detailLines, true, null, preview);

            return ToolResult.plan(
                    "已根据活动信息生成推文草稿：标题《" + title + "》。\n"
                            + "确认后将写入稿件管理（草稿状态），你可以继续在编辑器里修改。",
                    plan);
        } catch (Exception e) {
            return ToolResult.failed("起草失败：" + e.getMessage());
        }
    }

    @Override
    public CommitResult commit(ToolContext ctx, AgentActionPlan plan) {
        Map<String, Object> exec = readArgs(plan);
        if (exec == null) return CommitResult.failed("无法还原草稿参数，请重新发起。");

        try {
            ArticleRequest req = new ArticleRequest();
            req.setTitle(str(exec.get("title")));
            req.setSummary(str(exec.get("summary")));
            // D10：落库前必须净化（LLM 产出 → 数据库 → 所有读取路径）
            req.setContent(htmlSanitizer.sanitize(str(exec.get("content")), true));
            req.setActivityId(lng(exec.get("activityId")));
            req.setAuthorId(lng(exec.get("authorId")));
            req.setArticleType(str(exec.get("articleType")));
            req.setDeadline(LocalDateTime.now().plusDays(7));

            var saved = articleService.create(ctx.getUserId(), req);

            List<Map<String, Object>> refs = new ArrayList<>();
            refs.add(ToolArgs.map("type", "article", "id", saved.getId(),
                    "label", saved.getTitle(), "link", "/content/article?id=" + saved.getId()));

            return new CommitResult("SUCCESS", 1, 0,
                    "已生成推文草稿《" + saved.getTitle() + "》，可在稿件管理中继续编辑。",
                    ToolArgs.map("articleId", saved.getId()), refs);
        } catch (Exception e) {
            return CommitResult.failed("草稿写入失败：" + e.getMessage());
        }
    }

    /* ============================================================ 草稿生成（事实来自库，措辞用模板） */

    private String buildTitle(Activity a) {
        String theme = a.getTheme() == null || a.getTheme().isBlank() ? a.getName() : a.getTheme();
        return theme + "顺利开展";
    }

    private String buildContent(Activity a) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p>").append(esc(a.getName())).append("于");
        if (a.getStartDate() != null) {
            sb.append(a.getStartDate());
            if (a.getEndDate() != null && !a.getEndDate().equals(a.getStartDate())) {
                sb.append("至").append(a.getEndDate());
            }
        } else {
            sb.append("近期");
        }
        if (a.getLocation() != null && !a.getLocation().isBlank()) {
            sb.append("在").append(esc(a.getLocation()));
        }
        sb.append("开展。</p>");

        if (a.getHost() != null && !a.getHost().isBlank()) {
            sb.append("<p>本次活动由").append(esc(a.getHost())).append("主办");
            if (a.getOrganizer() != null && !a.getOrganizer().isBlank()) {
                sb.append("，").append(esc(a.getOrganizer())).append("承办");
            }
            sb.append("。</p>");
        }
        if (a.getTargetAudience() != null && !a.getTargetAudience().isBlank()) {
            sb.append("<p>参与对象：").append(esc(a.getTargetAudience())).append("。</p>");
        }
        if (a.getDescription() != null && !a.getDescription().isBlank()) {
            sb.append("<p>").append(esc(htmlSanitizer.toPlainText(a.getDescription()))).append("</p>");
        }
        sb.append("<p>（本稿由灵思根据活动信息自动生成初稿，请补充现场情况与图片后再提交审核。）</p>");
        return sb.toString();
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 摘要：用于列表页展示，取活动事实拼一句，不重复正文全文 */
    private String buildSummary(Activity a) {
        StringBuilder sb = new StringBuilder();
        sb.append(a.getName());
        if (a.getStartDate() != null) sb.append("，").append(a.getStartDate());
        if (a.getLocation() != null && !a.getLocation().isBlank()) sb.append("，" ).append(a.getLocation());
        sb.append("。");
        return sb.toString();
    }

    /* ============================================================ 参数载体 */

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

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    private Long lng(Object o) {
        if (o == null) return null;
        try {
            return Long.valueOf(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }
}
