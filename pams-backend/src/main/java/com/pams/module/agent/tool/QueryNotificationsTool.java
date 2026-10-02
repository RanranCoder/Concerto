package com.pams.module.agent.tool;

import com.pams.module.agent.core.AgentTool;
import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.core.ToolResult;
import com.pams.module.notification.dto.NotificationVO;
import com.pams.module.notification.service.NotificationService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读类工具：通知查询（业务设计方案 §3.5）。
 *
 * <p>回答"有什么新通知""未读消息有哪些"。复用 {@code NotificationService.findForUser}
 * 与 {@code countUnreadForUser}——通知的可见范围口径（userId / roleCode / deptId 三选）
 * 完全沿用现有实现，灵思不重新定义谁能看什么通知。
 */
@Component
public class QueryNotificationsTool implements AgentTool {

    private final NotificationService notificationService;

    public QueryNotificationsTool(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public String name() { return "queryNotifications"; }

    @Override
    public String label() { return "通知查询"; }

    @Override
    public String runningLabel() { return "正在查看通知…"; }

    @Override
    public String description() {
        return "查询当前用户的站内通知（含未读数）。用于回答'有什么新通知''有哪些未读消息'"
                + "'最近有什么公告'等问题。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolArgs.schema("object", ToolArgs.map(
                "unreadOnly", ToolArgs.prop("boolean", "只看未读，默认 false"),
                "limit", ToolArgs.prop("integer", "返回条数上限，默认 10")
        ), List.of());
    }

    @Override
    public Sensitivity sensitivity() { return Sensitivity.NORMAL; }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        if (ctx.getUserId() == null) {
            return ToolResult.denied("无法确定当前用户");
        }
        boolean unreadOnly = ToolArgs.bool(args, "unreadOnly");
        int limit = Math.min(Math.max(ToolArgs.intVal(args, "limit", 10), 1), 30);

        try {
            long unread = notificationService.countUnreadForUser(
                    ctx.getUserId(), ctx.getRoleCode(), ctx.getDeptId());

            List<NotificationVO> all = notificationService.findForUser(
                    ctx.getUserId(), ctx.getRoleCode(), ctx.getDeptId());

            List<Map<String, Object>> rows = new ArrayList<>();
            List<Map<String, Object>> refs = new ArrayList<>();
            List<String> titles = new ArrayList<>();
            for (NotificationVO n : all) {
                boolean isUnread = !n.isRead();
                if (unreadOnly && !isUnread) continue;
                rows.add(ToolArgs.row(
                        "id", n.getId(),
                        "title", n.getTitle(),
                        "type", n.getType(),
                        "read", n.isRead(),
                        "createdAt", n.getCreatedAt()
                ));
                if (n.getTitle() != null) titles.add(n.getTitle());
                refs.add(ToolArgs.map("type", "notification", "id", n.getId(),
                        "label", n.getTitle(), "link", "/notification"));
                if (rows.size() >= limit) break;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("unreadCount", unread);
            payload.put("returned", rows.size());
            payload.put("table", ToolArgs.table(
                    new String[]{"标题", "类型", "状态", "时间"}, rows));
            payload.put("page", "notification");

            if (rows.isEmpty()) {
                return ToolResult.ok(unreadOnly
                        ? "没有未读通知，消息都清完了。"
                        : "暂时没有通知。", payload);
            }
            String summary = (unread > 0 ? "有 " + unread + " 条未读通知。" : "通知均已读。")
                    + "最近的是：" + ToolArgs.listSummary(titles, 4) + "。";
            return ToolResult.ok(summary, payload, refs);
        } catch (Exception e) {
            return ToolResult.failed("通知读取失败：" + e.getMessage());
        }
    }
}
