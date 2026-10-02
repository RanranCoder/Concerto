package com.pams.module.agent.tool;

import com.pams.module.agent.core.AgentTool;
import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.core.ToolResult;
import com.pams.module.schedule.service.CourseScheduleService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读类工具：共同空闲时间（业务设计方案 §3.5）。
 *
 * <p>回答"下周三下午部门开会有空吗"。直接复用 {@code CourseScheduleService.analyzeFreeTime}
 * ——这是项目里已实现的<b>规则算法</b>（无课表聚合 + 热力图 + 最优时段），
 * 灵思只是把它接到自然语言入口上。
 *
 * <p>数据级 normal：空闲时段不含个人信息（只体现"有无课"）。
 * 权限：登录即可（本人课表；若传 userIds 则需 schedule 相关权限）。
 */
@Component
public class QueryCommonFreeTimeTool implements AgentTool {

    private final CourseScheduleService courseScheduleService;

    public QueryCommonFreeTimeTool(CourseScheduleService courseScheduleService) {
        this.courseScheduleService = courseScheduleService;
    }

    @Override
    public String name() { return "queryCommonFreeTime"; }

    @Override
    public String label() { return "共同空闲"; }

    @Override
    public String runningLabel() { return "正在分析共同空闲时段…"; }

    @Override
    public String description() {
        return "分析指定人员在指定学期的共同空闲时段（基于无课表）。"
                + "用于回答'下周三下午部门开会有空吗''找个大家都有空的时间'等问题。"
                + "不传 userIds 时分析当前用户本人。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolArgs.schema("object", ToolArgs.map(
                "semester", ToolArgs.prop("string", "学期，如 2025-2026-1，可省略"),
                "userIds", ToolArgs.prop("array", "用户 ID 数组；省略时为自己")
        ), List.of());
    }

    @Override
    public Sensitivity sensitivity() { return Sensitivity.NORMAL; }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String semester = ToolArgs.str(args, "semester");
        List<Long> userIds = ToolArgs.longList(args, "userIds");
        if (userIds.isEmpty() && ctx.getUserId() != null) {
            userIds = List.of(ctx.getUserId());
        }

        try {
            Map<String, Object> analysis = courseScheduleService.analyzeFreeTime(semester, userIds);
            if (analysis == null) {
                return ToolResult.ok("暂时无法分析空闲时段：该学期无课表数据。");
            }

            Object optimal = analysis.get("optimal");
            Object periods = analysis.get("periods");
            int peopleCount = userIds.size();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("semester", analysis.get("semester"));
            payload.put("peopleCount", peopleCount);
            payload.put("periods", periods);
            payload.put("heatmap", analysis.get("heatmap"));
            payload.put("optimal", optimal);
            payload.put("page", "schedule");

            StringBuilder sb = new StringBuilder();
            if (peopleCount == 1) {
                sb.append("你的空闲时段已分析完成。");
            } else {
                sb.append(peopleCount).append(" 人的共同空闲时段已分析完成。");
            }
            if (optimal != null) {
                sb.append("推荐时段：").append(describeOptimal(optimal)).append("。");
            } else if (periods instanceof List<?> l && !l.isEmpty()) {
                sb.append("共找到 ").append(l.size()).append(" 个可选时段。");
            } else {
                sb.append("按现有课表看，没有整块的空闲时段，建议缩短会议或调整人员。");
            }
            return ToolResult.ok(sb.toString(), payload);
        } catch (Exception e) {
            return ToolResult.failed("空闲分析失败：" + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String describeOptimal(Object optimal) {
        if (optimal instanceof Map<?, ?> m) {
            List<String> parts = new ArrayList<>();
            Object day = m.get("dayOfWeek") != null ? m.get("dayOfWeek") : m.get("day");
            Object period = m.get("periodName") != null ? m.get("periodName") : m.get("period");
            Object free = m.get("freeCount");
            Object total = m.get("totalCount");
            if (day != null) parts.add(String.valueOf(day));
            if (period != null) parts.add(String.valueOf(period));
            String head = String.join(" ", parts);
            if (free != null && total != null) {
                return head + "（" + free + "/" + total + " 人有空）";
            }
            return head.isEmpty() ? String.valueOf(optimal) : head;
        }
        return String.valueOf(optimal);
    }
}
