package com.pams.module.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.module.agent.core.*;
import com.pams.module.archive.dto.AnnouncementRequest;
import com.pams.module.archive.service.AnnouncementService;
import com.pams.module.agent.service.HtmlSanitizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写类工具：发布公告（业务设计方案 §3.5）。
 *
 * <p>回答"发个通知告诉大家…"。落库走 {@code AnnouncementService.create}，
 * <b>会自动触发既有的 {@code AnnouncementPublishedEvent}</b>
 * ——通知中心广播与站内信都不需要灵思额外做任何事。这是"复用现有 Service"的直接红利（铁律①）。
 *
 * <p><b>不可撤回</b>：公告一旦发布，通知已广播给全员。因此 plan 的
 * {@code reversible=false}，前端会显示更硬的确认提示（灵思 §4.4）。
 *
 * <p>正文经 {@link HtmlSanitizer} 净化后落库（D10）。
 * 权限码 {@code notice:publish}；数据级 normal。
 */
@Component
public class PublishAnnouncementTool implements WriteAgentTool {

    private static final String ARGS_MARK = "__args__:";

    private final AnnouncementService announcementService;
    private final HtmlSanitizer htmlSanitizer;
    private final ObjectMapper mapper = new ObjectMapper();

    public PublishAnnouncementTool(AnnouncementService announcementService, HtmlSanitizer htmlSanitizer) {
        this.announcementService = announcementService;
        this.htmlSanitizer = htmlSanitizer;
    }

    @Override
    public String name() { return "publishAnnouncement"; }

    @Override
    public String label() { return "发布公告"; }

    @Override
    public String runningLabel() { return "正在准备公告…"; }

    @Override
    public String description() {
        return "发布一条面向全员的公告/通知，发布后会推送到通知中心。"
                + "适用于'发个通知''公告一下''告诉大家 XX'等指令。"
                + "注意：公告发布后无法撤回，会先生成待确认内容供核对。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolArgs.schema("object", ToolArgs.map(
                "title", ToolArgs.prop("string", "公告标题，必填"),
                "content", ToolArgs.prop("string", "公告正文，必填")
        ), List.of("title", "content"));
    }

    @Override
    public List<String> requiredPermissions() { return List.of("notice:publish"); }

    @Override
    public Sensitivity sensitivity() { return Sensitivity.NORMAL; }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String title = ToolArgs.str(args, "title");
        String content = ToolArgs.str(args, "content");
        if (title == null || title.isBlank()) return ToolResult.failed("公告标题不能为空。");
        if (content == null || content.isBlank()) return ToolResult.failed("公告正文不能为空。");
        if (title.length() > 100) title = title.substring(0, 100);

        String plain = htmlSanitizer.toPlainText(content);
        List<AgentActionPlan.PlanItem> items = List.of(
                new AgentActionPlan.PlanItem(null, "标题", title),
                new AgentActionPlan.PlanItem(null, "正文", ToolArgs.clip(plain, 80))
        );

        List<String> detailLines = new ArrayList<>();
        detailLines.add("标题：" + title);
        detailLines.add("可见范围：全员（发布后推送通知中心）");
        detailLines.add("⚠️ 公告发布后无法撤回，请确认内容无误。");

        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("title", title);
        exec.put("content", content);
        detailLines.add(ARGS_MARK + writeJson(exec));

        AgentActionPlan plan = new AgentActionPlan(null, "发布公告：" + title, 1, items, List.of(),
                detailLines, false, null);

        return ToolResult.plan(
                "已准备好公告《" + title + "》。\n"
                        + "确认后将立即发布并推送给全员，发布后无法撤回。",
                plan);
    }

    @Override
    public CommitResult commit(ToolContext ctx, AgentActionPlan plan) {
        Map<String, Object> exec = readArgs(plan);
        if (exec == null) return CommitResult.failed("无法还原公告内容，请重新发起。");

        try {
            AnnouncementRequest req = new AnnouncementRequest();
            req.setTitle(str(exec.get("title")));
            // D10：落库前净化
            req.setContent(htmlSanitizer.sanitize(str(exec.get("content"))));
            req.setPublishTime(java.time.LocalDateTime.now());

            var saved = announcementService.create(ctx.getUserId(), req);

            List<Map<String, Object>> refs = new ArrayList<>();
            refs.add(ToolArgs.map("type", "announcement", "id", saved.getId(),
                    "label", saved.getTitle(), "link", "/archive/announcement?id=" + saved.getId()));

            return new CommitResult("SUCCESS", 1, 0,
                    "公告《" + saved.getTitle() + "》已发布，并已推送到通知中心。",
                    ToolArgs.map("announcementId", saved.getId()), refs);
        } catch (Exception e) {
            return CommitResult.failed("公告发布失败：" + e.getMessage());
        }
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
