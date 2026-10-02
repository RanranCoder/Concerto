package com.pams.module.agent.tool;

import com.pams.common.PageResult;
import com.pams.module.agent.core.AgentTool;
import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.core.ToolResult;
import com.pams.module.content.service.ArticleService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读类工具：稿件检索（业务设计方案 §3.5）。
 *
 * <p>回答"有哪些稿子等着审""推文发了没"。
 *
 * <p><b>权限口径（G1）</b>：content 模块当前仍用「领导角色宏」而非权限码
 * ——Controller 上是 {@code hasRole('MEDIA_LEADER') or hasAnyRole('TEACHER','DIRECTOR')}。
 * 一期<b>不收敛</b>（PRD-v5 S2 长尾），因此这里用 {@link AgentTool#requiresLeader()}
 * 表达同样的口径：<b>普通干事看不到这个工具</b>（不可见 ⇒ 不可诱导，D3）。
 *
 * <p>这个"忠实映射现状而非顺手改权限"的做法，正是 D11 第二类「契约破坏性变更」的回避策略。
 */
@Component
public class SearchArticlesTool implements AgentTool {

    private final ArticleService articleService;

    public SearchArticlesTool(ArticleService articleService) {
        this.articleService = articleService;
    }

    @Override
    public String name() { return "searchArticles"; }

    @Override
    public String label() { return "稿件检索"; }

    @Override
    public String runningLabel() { return "正在检索稿件…"; }

    @Override
    public String description() {
        return "检索新闻稿/推文的生产状态（草稿、待审、已发布）与阅读数据。"
                + "用于回答'有哪些稿子待审''推文发布情况''阅读量怎么样'等问题。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolArgs.schema("object", ToolArgs.map(
                "keyword", ToolArgs.prop("string", "标题关键词，可省略"),
                "status", ToolArgs.prop("string", "状态：DRAFT / PENDING / APPROVED / PUBLISHED / REJECTED，可省略"),
                "limit", ToolArgs.prop("integer", "返回条数上限，默认 10")
        ), List.of());
    }

    /**
     * 与 content 模块现有 Controller 口径一致（G1）：
     * {@code MediaLeader} 或 {@code Teacher/Director} 之一的<b>领导角色</b>专属。
     */
    @Override
    public boolean requiresLeader() { return true; }

    @Override
    public Sensitivity sensitivity() { return Sensitivity.NORMAL; }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String keyword = ToolArgs.str(args, "keyword");
        String status = ToolArgs.str(args, "status");
        int limit = Math.min(Math.max(ToolArgs.intVal(args, "limit", 10), 1), 30);

        try {
            PageResult<Map<String, Object>> page =
                    articleService.page(status, null, keyword, null, 1, limit);
            List<Map<String, Object>> records = page.getRecords() == null ? List.of() : page.getRecords();

            if (records.isEmpty()) {
                return ToolResult.ok("没有找到符合条件的稿件。");
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            List<Map<String, Object>> refs = new ArrayList<>();
            List<String> titles = new ArrayList<>();
            for (Map<String, Object> a : records) {
                Object title = a.get("title");
                if (title != null) titles.add(String.valueOf(title));
                rows.add(ToolArgs.row(
                        "id", a.get("id"),
                        "title", title,
                        "status", a.get("status"),
                        "articleType", a.get("articleType"),
                        "readCount", a.get("readCount"),
                        "publishTime", a.get("publishTime")
                ));
                refs.add(ToolArgs.map("type", "article", "id", a.get("id"), "label", title,
                        "link", "/content/article?id=" + a.get("id")));
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("count", page.getTotal());
            payload.put("returned", records.size());
            payload.put("table", ToolArgs.table(
                    new String[]{"标题", "状态", "类型", "阅读量", "发布时间"}, rows));
            payload.put("page", "article");

            String summary = "共匹配 " + page.getTotal() + " 篇稿件，包括："
                    + ToolArgs.listSummary(titles, 5) + "。";
            return ToolResult.ok(summary, payload, refs);
        } catch (Exception e) {
            return ToolResult.failed("稿件检索失败：" + e.getMessage());
        }
    }
}
