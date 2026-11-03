package com.pams.module.agent.tool;

import com.pams.module.activity.repository.TaskRepository;
import com.pams.module.activity.service.TaskService;
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
 * 读类工具：活动任务进度（业务设计方案 §3.5）。
 *
 * <p>回答"XX 活动筹备到哪了""哪些任务逾期了"。复用 {@code TaskService.listByActivity}。
 * 不传 activityId 时给出全量逾期任务（跨活动），这正是"甘特图有问题才看得见"的补位。
 */
@Component
public class QueryActivityTasksTool implements AgentTool {

    private final TaskService taskService;
    private final TaskRepository taskRepository;

    public QueryActivityTasksTool(TaskService taskService, TaskRepository taskRepository) {
        this.taskService = taskService;
        this.taskRepository = taskRepository;
    }

    @Override
    public String name() { return "queryActivityTasks"; }

    @Override
    public String label() { return "任务进度"; }

    @Override
    public String runningLabel() { return "正在查看任务进度…"; }

    @Override
    public String description() {
        return "查询活动筹备任务清单与进度。用于回答'XX 活动筹备到哪了''哪些任务逾期了'"
                + "'任务进度如何'等问题。不传 activityId 时汇总逾期任务。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolArgs.schema("object", ToolArgs.map(
                "activityId", ToolArgs.prop("integer", "活动 ID；省略时只看逾期任务"),
                "overdueOnly", ToolArgs.prop("boolean", "只看逾期任务，默认 false"),
                "page", ToolArgs.prop("integer", "页码，从0开始，每页5条，hasMore时继续查询")
        ), List.of());
    }

    @Override
    public List<String> requiredPermissions() { return List.of("task:view"); }

    @Override
    public Sensitivity sensitivity() { return Sensitivity.NORMAL; }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        if(ctx==null || ctx.getLoginUser()==null || !ctx.hasPermission("task:view") || !java.util.Objects.equals(ctx.getLoginUser().getOrgId(),com.pams.common.OrgContext.get())) return ToolResult.denied("无权读取当前组织任务");
        Long activityId = ToolArgs.lng(args, "activityId");
        boolean overdueOnly = ToolArgs.bool(args, "overdueOnly");
        int pageNumber=ToolArgs.intVal(args,"page",0);
        if(pageNumber<0 || pageNumber>10000)return ToolResult.failed("page超出范围");

        try {
            List<com.pams.module.activity.entity.Task> tasks;
            if (activityId != null) {
                tasks = taskService.listByActivity(activityId);
            } else {
                tasks = new ArrayList<>();
                for (com.pams.module.activity.entity.Task t : taskRepository.findAllByOrderByEndDateAsc()) {
                    if (isOverdue(t)) tasks.add(t);
                }
                overdueOnly = true;
            }
            if (tasks == null) tasks = List.of();
            tasks=tasks.stream().filter(t->java.util.Objects.equals(t.getOrgId(),ctx.getLoginUser().getOrgId())).sorted(java.util.Comparator.comparing(com.pams.module.activity.entity.Task::getId)).toList();
            String planBaseline=activityId==null?null:com.pams.module.agent.service.AgentTaskPlanService.baseline(tasks);

            List<Map<String, Object>> rows = new ArrayList<>();
            List<Map<String, Object>> refs = new ArrayList<>();
            int overdue = 0;
            int done = 0;
            for (com.pams.module.activity.entity.Task t : tasks) {
                boolean od = isOverdue(t);
                if (od) overdue++;
                if (com.pams.module.activity.entity.Task.TaskStatus.DONE.equals(t.getStatus())) done++;
                if (overdueOnly && !od) continue;
                rows.add(ToolArgs.row(
                        "id", t.getId(),
                        "revision", t.getRevision(),
                        "assigneeUserId", t.getAssigneeUserId(),
                        "name", t.getName(),
                        "status", t.getStatus() == null ? null : t.getStatus().name(),
                        "progress", t.getProgress(),
                        "endDate", t.getEndDate() == null ? null : t.getEndDate().toString(),
                        "assignee", t.getAssignee(),
                        "overdue", od
                ));
                refs.add(ToolArgs.map("type", "task", "id", t.getId(), "label", t.getName(),
                        "link", "/activity/" + t.getActivityId() + "/tasks"));
            }

            int totalMatches=rows.size();int start=Math.min(pageNumber*5,rows.size()),end=Math.min(start+5,rows.size());
            rows=new ArrayList<>(rows.subList(start,end));refs=new ArrayList<>(refs.subList(start,end));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("planBaseline",planBaseline);payload.put("pageNumber",pageNumber);payload.put("pageSize",5);payload.put("hasMore",end<totalMatches);payload.put("totalMatches",totalMatches);
            payload.put("activityId", activityId);
            payload.put("total", tasks.size());
            payload.put("doneCount", done);
            payload.put("overdueCount", overdue);
            payload.put("returned", rows.size());
            payload.put("table", ToolArgs.table(
                    new String[]{"任务", "状态", "进度", "截止", "负责人"}, rows));
            payload.put("page", "task");

            if (rows.isEmpty()) {
                if(totalMatches>0)return ToolResult.ok("当前页无更多任务，请查看前页；不代表匹配任务为空。",payload);
                return ToolResult.ok(overdueOnly ? "没有逾期任务，进度都正常。"
                        : "该活动还没有创建任务。", payload);
            }
            StringBuilder sb = new StringBuilder();
            if (activityId != null) {
                sb.append("该活动共 ").append(tasks.size()).append(" 项任务，已完成 ").append(done).append(" 项");
                if (overdue > 0) sb.append("，其中 ").append(overdue).append(" 项已逾期");
                sb.append("。");
            } else {
                sb.append("共 ").append(overdue).append(" 项任务已逾期。");
            }
            List<String> names = rows.stream().map(r -> String.valueOf(r.get("name"))).toList();
            sb.append("涉及：").append(ToolArgs.listSummary(names, 5)).append("。");
            return ToolResult.ok(sb.toString(), payload, refs);
        } catch (Exception e) {
            return ToolResult.failed("任务读取失败：" + e.getMessage());
        }
    }

    private boolean isOverdue(com.pams.module.activity.entity.Task t) {
        if (t.getStatus() == null) return false;
        if (com.pams.module.activity.entity.Task.TaskStatus.DONE.equals(t.getStatus())) return false;
        if (com.pams.module.activity.entity.Task.TaskStatus.DELAYED.equals(t.getStatus())) return true;
        return t.getEndDate() != null && t.getEndDate().isBefore(LocalDate.now());
    }
}
