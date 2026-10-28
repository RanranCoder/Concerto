package com.pams.module.agent.tool;

import com.pams.module.agent.core.AgentTool;
import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.core.ToolResult;
import com.pams.module.routine.service.AttendanceManageService;
import com.pams.module.routine.dto.AttendanceVO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读类工具：考勤查询（业务设计方案 §3.5）。
 *
 * <p>回答"最近考勤怎么样""我有没有缺勤"。复用 {@code AttendanceManageService.stats / my}
 * ——考勤的统计口径（应到/实到/请假/缺勤）完全沿用既有实现。
 *
 * <p>数据级 {@code SENSITIVE}：考勤明细关联到具体人 → 出网前脱敏。
 * 无管理权限时只返回本人考勤（归属过滤在工具侧显式完成，同 D11）。
 */
@Component
public class QueryAttendanceTool implements AgentTool {

    private final AttendanceManageService attendanceManageService;

    public QueryAttendanceTool(AttendanceManageService attendanceManageService) {
        this.attendanceManageService = attendanceManageService;
    }

    @Override
    public String name() { return "queryAttendance"; }

    @Override
    public String label() { return "考勤查询"; }

    @Override
    public String runningLabel() { return "正在查询考勤…"; }

    @Override
    public String description() {
        return "查询考勤统计与明细（应到、实到、请假、缺勤）。"
                + "用于回答'最近考勤怎么样''有没有人缺勤''我的考勤情况'等问题。"
                + "无管理权限时只返回本人的考勤记录。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolArgs.schema("object", ToolArgs.map(
                "activityId", ToolArgs.prop("integer", "限定活动/课程 ID，可省略"),
                "mineOnly", ToolArgs.prop("boolean", "只看本人考勤，默认 false")
        ), List.of());
    }

    @Override
    public List<String> requiredPermissions() { return List.of("attendance:view", "attendance:self:view"); }

    @Override
    public boolean anyPermission() { return true; }

    @Override
    public Sensitivity sensitivity() { return Sensitivity.SENSITIVE; }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        Long activityId = ToolArgs.lng(args, "activityId");
        boolean mineOnly = ToolArgs.bool(args, "mineOnly");
        boolean canViewAll = ctx.hasPermission("attendance:view");

        try {
            // 无管理权限 → 强制只看自己
            if (!canViewAll || mineOnly) {
                String realName = ctx.getLoginUser() == null ? null : ctx.getLoginUser().getRealName();
                List<AttendanceVO> rowsVos = attendanceManageService.my(ctx.getUserId(), realName);
                return myResult(rowsVos);
            }

            Map<String, Object> filters = new LinkedHashMap<>();
            if (activityId != null) filters.put("activityId", activityId);
            Map<String, Object> stats = attendanceManageService.stats(filters);
            List<AttendanceVO> list = attendanceManageService.list(filters);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("stats", stats);
            payload.put("count", list == null ? 0 : list.size());
            payload.put("page", "attendance");
            if (list != null && !list.isEmpty()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (AttendanceVO v : list) {
                    rows.add(ToolArgs.row(
                            "id", v.getId(),
                            "personName", v.getPersonName(),
                            "status", v.getStatus() == null ? null : String.valueOf(v.getStatus()),
                            "remark", v.getRemark()
                    ));
                    if (rows.size() >= 20) break;
                }
                payload.put("table", ToolArgs.table(
                        new String[]{"姓名", "状态", "备注"}, rows));
            }

            StringBuilder sb = new StringBuilder("考勤统计：");
            if (stats != null && !stats.isEmpty()) {
                for (Map.Entry<String, Object> e : stats.entrySet()) {
                    sb.append(e.getKey()).append(' ').append(e.getValue()).append("，");
                }
                sb.setLength(Math.max(sb.length() - 1, 0));
                sb.append('。');
            } else {
                sb.append("暂无数据。");
            }
            return ToolResult.ok(sb.toString(), payload);
        } catch (Exception e) {
            return ToolResult.failed("考勤读取失败：" + e.getMessage());
        }
    }

    private ToolResult myResult(List<AttendanceVO> list) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", "SELF");
        payload.put("count", list == null ? 0 : list.size());
        payload.put("page", "attendance");

        if (list == null || list.isEmpty()) {
            return ToolResult.ok("你还没有考勤记录。", payload);
        }

        int abnormal = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AttendanceVO v : list) {
            String st = v.getStatus() == null ? "" : v.getStatus();
            if (!"NORMAL".equalsIgnoreCase(st) && !"PENDING".equalsIgnoreCase(st)) abnormal++;
            rows.add(ToolArgs.row(
                    "id", v.getId(),
                    "status", st,
                    "activityName", v.getActivityName(),
                    "remark", v.getRemark()
            ));
            if (rows.size() >= 20) break;
        }
        payload.put("table", ToolArgs.table(new String[]{"状态", "活动", "备注"}, rows));
        payload.put("abnormalCount", abnormal);

        String summary = "你的考勤记录共 " + list.size() + " 条"
                + (abnormal > 0 ? "，其中 " + abnormal + " 条为异常（缺勤/迟到/请假等）" : "，均正常")
                + "。";
        return ToolResult.ok(summary, payload);
    }
}
