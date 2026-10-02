package com.pams.module.agent.tool;

import com.pams.module.activity.entity.Task;
import com.pams.module.activity.repository.TaskRepository;
import com.pams.module.agent.core.AgentTool;
import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.core.ToolResult;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读类工具：待我处理的事（业务设计方案 §3.5，最高频问法"最近有什么事等着我"）。
 *
 * <p>聚合三处：<b>我负责的任务</b>（Task.assignee 含我的名字/部门）、
 * <b>逾期任务</b>、<b>未读通知数</b>。把"要处理的事"分散在四个菜单里的问题
 * 收敛成一句话——这正是灵思存在的第一价值。
 */
@Component
public class QueryMyPendingWorkTool implements AgentTool {

    private final TaskRepository taskRepository;

    public QueryMyPendingWorkTool(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Override
    public String name() { return "queryMyPendingWork"; }

    @Override
    public String label() { return "待我处理"; }

    @Override
    public String runningLabel() { return "正在汇总待你处理的事项…"; }

    @Override
    public String description() {
        return "汇总当前用户待处理的事项：我负责且未完成的任务、已逾期的任务、待推进的工作。"
                + "用于回答'最近有什么事等着我''我需要处理什么''有哪些任务逾期了'等问题。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolArgs.schema("object", ToolArgs.map(
                "includeDone", ToolArgs.prop("boolean", "是否包含已完成任务，默认 false")
        ), List.of());
    }

    @Override
    public Sensitivity sensitivity() { return Sensitivity.NORMAL; }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        boolean includeDone = ToolArgs.bool(args, "includeDone");
        String me = ctx.getLoginUser() == null ? null : ctx.getLoginUser().getRealName();
        if (me == null || me.isBlank()) me = ctx.getLoginUser() == null ? null : ctx.getLoginUser().getUsername();

        List<Task> mine = new ArrayList<>();
        List<Task> delayed = new ArrayList<>();
        try {
            // TaskRepository.findByAssigneeMatching 走的是"负责人字段模糊匹配"（既有查询，不新建通道）
            if (me != null) {
                for (Task t : taskRepository.findByAssigneeMatching(me)) {
                    if (!includeDone && Task.TaskStatus.DONE.equals(t.getStatus())) continue;
                    mine.add(t);
                }
            }
            // 逾期：未完成 且 截止日 < 今天（走既有查询：按截止日升序全量取，再内存过滤，避免新增 Repository 通道）
            for (Task t : taskRepository.findAllByOrderByEndDateAsc()) {
                if (Task.TaskStatus.DONE.equals(t.getStatus())) continue;
                if (Task.TaskStatus.DELAYED.equals(t.getStatus())) {
                    delayed.add(t);
                    continue;
                }
                if (t.getEndDate() != null && t.getEndDate().isBefore(LocalDate.now())) {
                    delayed.add(t);
                }
            }
        } catch (Exception e) {
            return ToolResult.failed("任务数据读取失败：" + e.getMessage());
        }

        // 逾期任务里若同时也是"我的"，只统计一次
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> refs = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        int overdueMine = 0;
        for (Task t : delayed) {
            if (t.getId() != null && !seen.add(t.getId())) continue;
            boolean isMine = mine.stream().anyMatch(m -> m.getId() != null && m.getId().equals(t.getId()));
            if (isMine) overdueMine++;
            rows.add(taskRow(t, true));
            refs.add(ToolArgs.map("type", "task", "id", t.getId(), "label", t.getName(),
                    "link", "/activity/" + t.getActivityId() + "/tasks"));
        }
        for (Task t : mine) {
            if (t.getId() != null && !seen.add(t.getId())) continue;
            rows.add(taskRow(t, false));
            refs.add(ToolArgs.map("type", "task", "id", t.getId(), "label", t.getName(),
                    "link", "/activity/" + t.getActivityId() + "/tasks"));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mineCount", mine.size());
        payload.put("overdueCount", delayed.size());
        payload.put("overdueMineCount", overdueMine);
        payload.put("total", rows.size());
        payload.put("table", ToolArgs.table(
                new String[]{"任务", "状态", "截止", "进度", "负责人"}, rows));
        payload.put("page", "task");

        if (rows.isEmpty()) {
            return ToolResult.ok("当前没有待你处理的事项，任务都很清爽。", payload);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("待你处理的有 ").append(mine.size()).append(" 项");
        if (!delayed.isEmpty()) sb.append("，另有 ").append(delayed.size()).append(" 项已逾期");
        sb.append("。");
        List<String> names = rows.stream().map(r -> String.valueOf(r.get("name"))).toList();
        sb.append("包括：").append(ToolArgs.listSummary(names, 5)).append("。");
        return ToolResult.ok(sb.toString(), payload, refs);
    }

    private Map<String, Object> taskRow(Task t, boolean overdue) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", t.getId());
        row.put("name", t.getName());
        row.put("status", t.getStatus());
        row.put("overdue", overdue);
        row.put("endDate", t.getEndDate() == null ? null : t.getEndDate().toString());
        row.put("progress", t.getProgress());
        row.put("assignee", t.getAssignee());
        row.put("activityId", t.getActivityId());
        return row;
    }
}
