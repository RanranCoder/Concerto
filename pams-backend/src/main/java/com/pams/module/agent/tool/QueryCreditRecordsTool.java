package com.pams.module.agent.tool;

import com.pams.common.PageResult;
import com.pams.module.agent.core.AgentTool;
import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.core.ToolResult;
import com.pams.module.archive.service.CreditService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读类工具：素拓台账查询（业务设计方案 §3.5）。
 *
 * <p>回答"我这个月加了多少素拓分""XX 活动的加分情况"。
 *
 * <p>权限用 {@code anyPermission}：<b>有 quality:view 的可以查全部，普通干事也能查自己的</b>。
 * 这正是 D3 里"anyPermission"的用途——避免把"查自己的分"也锁在管理权限后面。
 * 服务侧的归属过滤由工具自身保证：无 quality:view 时强制按 userId = 自己过滤。
 */
@Component
public class QueryCreditRecordsTool implements AgentTool {

    private final CreditService creditService;

    public QueryCreditRecordsTool(CreditService creditService) {
        this.creditService = creditService;
    }

    @Override
    public String name() { return "queryCreditRecords"; }

    @Override
    public String label() { return "素拓台账"; }

    @Override
    public String runningLabel() { return "正在查询素拓记录…"; }

    @Override
    public String description() {
        return "查询素拓（第二课堂）加分记录。用于回答'我加了多少素拓分''XX 活动的加分情况'"
                + "'素拓台账有哪些记录'等问题。无管理权限时只返回本人的记录。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolArgs.schema("object", ToolArgs.map(
                "keyword", ToolArgs.prop("string", "项目名称关键词，可省略"),
                "activityId", ToolArgs.prop("integer", "限定活动 ID，可省略"),
                "limit", ToolArgs.prop("integer", "返回条数上限，默认 10")
        ), List.of());
    }

    @Override
    public List<String> requiredPermissions() { return List.of("quality:view"); }

    @Override
    public boolean anyPermission() { return true; }

    @Override
    public Sensitivity sensitivity() { return Sensitivity.NORMAL; }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String keyword = ToolArgs.str(args, "keyword");
        Long activityId = ToolArgs.lng(args, "activityId");
        int limit = Math.min(Math.max(ToolArgs.intVal(args, "limit", 10), 1), 30);

        // 无 quality:view 时强制只看自己（D11 第三类风险：服务侧归属过滤必须由工具显式传参）
        boolean canViewAll = ctx.hasPermission("quality:view");
        Long userId = canViewAll ? null : ctx.getUserId();

        try {
            PageResult<Map<String, Object>> page =
                    creditService.page(keyword, userId, activityId, 1, limit);
            List<Map<String, Object>> records = page.getRecords() == null ? List.of() : page.getRecords();

            if (records.isEmpty()) {
                return ToolResult.ok(canViewAll
                        ? "素拓台账里没有匹配的记录。"
                        : "你还没有素拓加分记录。");
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            List<Map<String, Object>> refs = new ArrayList<>();
            double total = 0;
            for (Map<String, Object> c : records) {
                Object credit = c.get("credit");
                if (credit instanceof Number n) total += n.doubleValue();
                rows.add(ToolArgs.row(
                        "id", c.get("id"),
                        "project", c.get("project"),
                        "credit", credit,
                        "userName", c.get("userName"),
                        "activityName", c.get("activityName"),
                        "createdAt", c.get("createdAt")
                ));
                refs.add(ToolArgs.map("type", "credit", "id", c.get("id"),
                        "label", c.get("project"), "link", "/archive/credit"));
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("count", page.getTotal());
            payload.put("returned", records.size());
            payload.put("sumCredit", Math.round(total * 10) / 10.0);
            payload.put("scope", canViewAll ? "ALL" : "SELF");
            payload.put("table", ToolArgs.table(
                    new String[]{"项目", "分值", "人员", "所属活动", "时间"}, rows));
            payload.put("page", "credit");

            String summary = (canViewAll ? "素拓台账共匹配 " + page.getTotal() + " 条"
                    : "你有 " + page.getTotal() + " 条素拓记录")
                    + "，本次列出 " + records.size() + " 条，合计 " + (Math.round(total * 10) / 10.0) + " 分。";
            return ToolResult.ok(summary, payload, refs);
        } catch (Exception e) {
            return ToolResult.failed("素拓记录读取失败：" + e.getMessage());
        }
    }
}
