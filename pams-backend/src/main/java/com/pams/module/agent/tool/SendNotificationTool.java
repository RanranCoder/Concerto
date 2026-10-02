package com.pams.module.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.module.agent.core.*;
import com.pams.module.notification.entity.NotificationType;
import com.pams.module.notification.service.NotificationService;
import com.pams.module.agent.service.HtmlSanitizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写类工具：定向发送通知（业务设计方案 §3.5）。
 *
 * <p>回答"提醒一下组织部的同学…"。与"发布公告"的区别在于<b>受众定向</b>：
 * 公告是全员广播（走 {@code AnnouncementPublishedEvent}），
 * 通知是定向送达（走 {@code NotificationService.createAndSave} 并实时推送）。
 *
 * <p>权限码 {@code notification:send}；数据级 normal。
 * <b>不可撤回</b>：通知送达后即推送。
 */
@Component
public class SendNotificationTool implements WriteAgentTool {

    private static final String ARGS_MARK = "__args__:";

    private final NotificationService notificationService;
    private final HtmlSanitizer htmlSanitizer;
    private final ObjectMapper mapper = new ObjectMapper();

    public SendNotificationTool(NotificationService notificationService, HtmlSanitizer htmlSanitizer) {
        this.notificationService = notificationService;
        this.htmlSanitizer = htmlSanitizer;
    }

    @Override
    public String name() { return "sendNotification"; }

    @Override
    public String label() { return "发送通知"; }

    @Override
    public String runningLabel() { return "正在准备通知…"; }

    @Override
    public String description() {
        return "向指定的用户、角色或部门定向发送一条站内通知。"
                + "适用于'提醒一下 XX 部门的同学''通知负责人尽快处理'等指令。"
                + "注意：通知发送后即时推送，无法撤回。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolArgs.schema("object", ToolArgs.map(
                "title", ToolArgs.prop("string", "通知标题，必填"),
                "content", ToolArgs.prop("string", "通知正文，必填"),
                "recipientUserId", ToolArgs.prop("integer", "接收人用户 ID，可省略"),
                "recipientRole", ToolArgs.prop("string", "接收角色码，如 STAFF / DIRECTOR，可省略"),
                "recipientDeptId", ToolArgs.prop("integer", "接收部门 ID，可省略"),
                "entityType", ToolArgs.prop("string", "关联实体类型，如 activity / task，可省略"),
                "entityId", ToolArgs.prop("integer", "关联实体 ID，可省略")
        ), List.of("title", "content"));
    }

    @Override
    public List<String> requiredPermissions() { return List.of("notification:send"); }

    @Override
    public Sensitivity sensitivity() { return Sensitivity.NORMAL; }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String title = ToolArgs.str(args, "title");
        String content = ToolArgs.str(args, "content");
        if (title == null || title.isBlank()) return ToolResult.failed("通知标题不能为空。");
        if (content == null || content.isBlank()) return ToolResult.failed("通知正文不能为空。");

        Long recipientUserId = ToolArgs.lng(args, "recipientUserId");
        String recipientRole = ToolArgs.str(args, "recipientRole");
        Long recipientDeptId = ToolArgs.lng(args, "recipientDeptId");
        String entityType = ToolArgs.str(args, "entityType");
        Long entityId = ToolArgs.lng(args, "entityId");

        // 受众必须有明确指向——不能"发给所有人"，那应该用发布公告
        if (recipientUserId == null && (recipientRole == null || recipientRole.isBlank())
                && recipientDeptId == null) {
            return ToolResult.failed("需要明确通知的接收范围（指定用户 / 角色 / 部门之一）。"
                    + "如果要通知全员，请改用发布公告。");
        }

        List<AgentActionPlan.PlanItem> items = List.of(
                new AgentActionPlan.PlanItem(null, "标题", title),
                new AgentActionPlan.PlanItem(null, "接收范围", describeAudience(recipientUserId, recipientRole, recipientDeptId))
        );

        List<String> detailLines = new ArrayList<>();
        detailLines.add("接收范围：" + describeAudience(recipientUserId, recipientRole, recipientDeptId));
        detailLines.add("发送后即时推送，无法撤回。");

        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("title", title);
        exec.put("content", content);
        if (recipientUserId != null) exec.put("recipientUserId", recipientUserId);
        if (recipientRole != null && !recipientRole.isBlank()) exec.put("recipientRole", recipientRole);
        if (recipientDeptId != null) exec.put("recipientDeptId", recipientDeptId);
        if (entityType != null && !entityType.isBlank()) exec.put("entityType", entityType);
        if (entityId != null) exec.put("entityId", entityId);
        detailLines.add(ARGS_MARK + writeJson(exec));

        AgentActionPlan plan = new AgentActionPlan(null, "发送通知：" + title, 1, items, List.of(),
                detailLines, false, null);

        return ToolResult.plan("已准备好通知《" + title + "》，接收范围："
                + describeAudience(recipientUserId, recipientRole, recipientDeptId) + "。", plan);
    }

    @Override
    public CommitResult commit(ToolContext ctx, AgentActionPlan plan) {
        Map<String, Object> exec = readArgs(plan);
        if (exec == null) return CommitResult.failed("无法还原通知内容，请重新发起。");

        try {
            var saved = notificationService.createAndSave(
                    NotificationType.SYSTEM,
                    str(exec.get("title")),
                    htmlSanitizer.sanitize(str(exec.get("content"))),
                    str(exec.get("entityType")),
                    lng(exec.get("entityId")),
                    ctx.getUserId(),
                    lng(exec.get("recipientUserId")),
                    str(exec.get("recipientRole")),
                    lng(exec.get("recipientDeptId")));

            List<Map<String, Object>> refs = new ArrayList<>();
            refs.add(ToolArgs.map("type", "notification", "id", saved.getId(),
                    "label", saved.getTitle(), "link", "/notification"));

            return new CommitResult("SUCCESS", 1, 0,
                    "通知《" + saved.getTitle() + "》已发送。",
                    ToolArgs.map("notificationId", saved.getId()), refs);
        } catch (Exception e) {
            return CommitResult.failed("通知发送失败：" + e.getMessage());
        }
    }

    private String describeAudience(Long userId, String role, Long deptId) {
        List<String> parts = new ArrayList<>();
        if (userId != null) parts.add("指定用户 #" + userId);
        if (role != null && !role.isBlank()) parts.add("角色 " + role);
        if (deptId != null) parts.add("部门 #" + deptId);
        return parts.isEmpty() ? "未指定" : String.join(" + ", parts);
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
