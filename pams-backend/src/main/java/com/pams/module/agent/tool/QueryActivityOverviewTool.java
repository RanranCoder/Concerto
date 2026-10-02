package com.pams.module.agent.tool;

import com.pams.common.PageResult;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.agent.core.AgentTool;
import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.core.ToolResult;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读类工具：活动概览（业务设计方案 §3.5 工具清单第 1 项，最高频兜底）。
 *
 * <p>回答"这个月有哪些活动""最近有什么"。因为几乎所有问法都会沾到"活动"二字，
 * 它是兜底命中率最高的工具，也因此被 StubProvider 放在最后一位匹配。
 *
 * <p>权限：登录即可（活动一览本身是全员可见信息）。数据级 normal。
 */
@Component
public class QueryActivityOverviewTool implements AgentTool {

    private final ActivityRepository activityRepository;

    public QueryActivityOverviewTool(ActivityRepository activityRepository) {
        this.activityRepository = activityRepository;
    }

    @Override
    public String name() { return "queryActivityOverview"; }

    @Override
    public String label() { return "活动概览"; }

    @Override
    public String runningLabel() { return "正在查询活动概况…"; }

    @Override
    public String description() {
        return "查询活动列表与概览。支持按名称关键词、状态、时间范围过滤。"
                + "用于回答'这个月有哪些活动''最近有什么活动''XX 活动什么状态'等问题。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolArgs.schema("object", ToolArgs.map(
                "keyword", ToolArgs.prop("string", "活动名称关键词，可省略"),
                "status", ToolArgs.prop("string", "活动状态：PLANNING / ONGOING / FINISHED / CANCELLED，可省略"),
                "month", ToolArgs.prop("string", "查询月份，格式 yyyy-MM，可省略；省略时默认近 3 个月"),
                "limit", ToolArgs.prop("integer", "返回条数上限，默认 10")
        ), List.of());
    }

    @Override
    public Sensitivity sensitivity() { return Sensitivity.NORMAL; }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String keyword = ToolArgs.str(args, "keyword");
        String status = ToolArgs.str(args, "status");
        String month = ToolArgs.str(args, "month");
        int limit = Math.min(Math.max(ToolArgs.intVal(args, "limit", 10), 1), 50);

        LocalDate from = null, to = null;
        if (month != null && month.matches("\\d{4}-\\d{2}")) {
            LocalDate start = LocalDate.parse(month + "-01");
            from = start;
            to = start.withDayOfMonth(start.lengthOfMonth());
        } else {
            // 默认近 3 个月，避免把历史全部倒出来
            from = LocalDate.now().minusMonths(3);
            to = LocalDate.now().plusMonths(1);
        }

        // 走现有 Service 的分页查询（铁律①：复用现有查询通道，不新建 Repository 查询）
        PageResult<Map<String, Object>> page = null;
        try {
            // ActivityService 在这里只做分页+关键词；时间/状态过滤在内存里补
            org.springframework.data.domain.Page<Activity> raw =
                    activityRepository.findAll(PageRequest.of(0, 200,
                            Sort.by(Sort.Direction.DESC, "startDate")));
            List<Activity> filtered = new ArrayList<>();
            for (Activity a : raw.getContent()) {
                if (keyword != null && !keyword.isBlank()
                        && (a.getName() == null || !a.getName().contains(keyword))) continue;
                if (status != null && !status.isBlank()
                        && (a.getStatus() == null || !status.equalsIgnoreCase(a.getStatus().name()))) continue;
                if (a.getStartDate() != null) {
                    if (from != null && a.getStartDate().isBefore(from)) continue;
                    if (to != null && a.getStartDate().isAfter(to)) continue;
                }
                filtered.add(a);
                if (filtered.size() >= limit) break;
            }
            return buildResult(filtered, month, keyword, status);
        } catch (Exception e) {
            return ToolResult.failed("活动数据读取失败：" + e.getMessage());
        }
    }

    private ToolResult buildResult(List<Activity> list, String month, String keyword, String status) {
        if (list.isEmpty()) {
            String cond = (keyword == null || keyword.isBlank() ? "" : "名称含「" + keyword + "」的")
                    + (month == null ? "" : month + " 的");
            return ToolResult.ok("没有查到" + (cond.isEmpty() ? "符合条件的" : cond) + "活动。");
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> refs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Activity a : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.getId());
            row.put("name", a.getName());
            row.put("status", a.getStatus() == null ? null : a.getStatus().name());
            row.put("startDate", a.getStartDate() == null ? null : a.getStartDate().toString());
            row.put("endDate", a.getEndDate() == null ? null : a.getEndDate().toString());
            row.put("location", a.getLocation());
            row.put("leader", a.getLeader());
            rows.add(row);
            names.add(a.getName());
            refs.add(ToolArgs.map("type", "activity", "id", a.getId(),
                    "label", a.getName(), "link", "/activity/" + a.getId()));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", list.size());
        payload.put("table", ToolArgs.table(
                new String[]{"活动名称", "状态", "开始", "地点"}, rows));
        payload.put("page", "activity");

        String summary = "查到 " + list.size() + " 个活动：" + ToolArgs.listSummary(names, 6) + "。";
        return ToolResult.ok(summary, payload, refs);
    }
}
