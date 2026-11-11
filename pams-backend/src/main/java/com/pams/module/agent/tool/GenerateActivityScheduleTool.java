package com.pams.module.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.module.agent.core.*;
import com.pams.module.routine.dto.ActivityScheduleGenerateRequest;
import com.pams.module.routine.service.ActivityScheduleService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写类工具：一键生成活动排班（业务设计方案 §3.5）。
 *
 * <p>回答"给这个活动排个班"。复用 {@code ActivityScheduleService.generate}
 * ——这是项目里既有的<b>规则算法</b>（读无课表、按节次匹配空闲人员、全局均衡分配）。
 * 灵思不做排班算法，只做"把自然语言参数翻译成 generate 的入参"。
 *
 * <p>V6：dry-run 复用业务服务的只读预计算，展示真实候选人与空缺。
 * 确认时使用相同计算器复核指纹；名单变化则拒绝写入，要求重新核对。
 *
 * <p>权限码 {@code schedule:generate}；数据级 normal。
 */
@Component
public class GenerateActivityScheduleTool implements WriteAgentTool {

    private static final String ARGS_MARK = "__args__:";

    private final ActivityScheduleService scheduleService;
    private final ActivityResolver activityResolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public GenerateActivityScheduleTool(ActivityScheduleService scheduleService,
                                        ActivityResolver activityResolver) {
        this.scheduleService = scheduleService;
        this.activityResolver = activityResolver;
    }

    @Override
    public String name() { return "generateActivitySchedule"; }

    @Override
    public String label() { return "生成排班"; }

    @Override
    public String runningLabel() { return "正在生成活动排班…"; }

    @Override
    public String description() {
        return "为活动一键生成值班/排班表，基于无课表自动匹配空闲人员。"
                + "需要提供活动、日期范围、时间段与参与部门。"
                + "适用于'给这个活动排个班''安排下周的值班'等指令。"
                + "通常先生成待确认清单。用户当轮明确说「为活动 #123 生成并保存 2026-11-16 至 2026-11-17 每天 09:00 至 10:00 部门 #77 每班 2 人的排班草稿」时，"
                + "严格传原文的活动、部门、日期、时段和人数，省略学期。服务器核对后仅保存未发布草稿。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolArgs.schema("object", ToolArgs.map(
                "activityId", ToolArgs.prop("integer", "活动 ID，可省略；与 activityName 二选一"),
                "activityName", ToolArgs.prop("string", ActivityResolver.NAME_DESC),
                "startDate", ToolArgs.prop("string", "开始日期 yyyy-MM-dd，必填"),
                "endDate", ToolArgs.prop("string", "结束日期 yyyy-MM-dd，必填"),
                "startTime", ToolArgs.prop("string", "每天开始时间 HH:mm，必填"),
                "endTime", ToolArgs.prop("string", "每天结束时间 HH:mm，必填"),
                "deptIds", ToolArgs.prop("array", "参与部门 ID 数组，必填"),
                "peoplePerSlot", ToolArgs.prop("integer", "每个时段需要人数，可省略（不限）"),
                "semester", ToolArgs.prop("string", "学期，如 2025-2026-1，可省略")
        ), List.of("startDate", "endDate", "startTime", "endTime", "deptIds"));
    }

    @Override
    public List<String> requiredPermissions() { return List.of("schedule:generate"); }

    @Override
    public Sensitivity sensitivity() { return Sensitivity.NORMAL; }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        ActivityResolver.Resolution res =
                activityResolver.resolve(ToolArgs.lng(args, "activityId"), ToolArgs.str(args, "activityName"));
        LocalDate startDate = parseDate(ToolArgs.str(args, "startDate"));
        LocalDate endDate = parseDate(ToolArgs.str(args, "endDate"));
        LocalTime startTime = parseTime(ToolArgs.str(args, "startTime"));
        LocalTime endTime = parseTime(ToolArgs.str(args, "endTime"));
        List<Long> deptIds = ToolArgs.longList(args, "deptIds");
        Integer peoplePerSlot = ToolArgs.lng(args, "peoplePerSlot") == null ? null
                : ToolArgs.intVal(args, "peoplePerSlot", 0);
        String semester = ToolArgs.str(args, "semester");

        List<AgentActionPlan.SkippedItem> skipped = new ArrayList<>();
        if (res.failed()) return ToolResult.failed(res.message());
        Long activityId = res.activity().getId();
        if (startDate == null) return ToolResult.failed("需要指定开始日期（格式 yyyy-MM-dd）。");
        if (endDate == null) return ToolResult.failed("需要指定结束日期（格式 yyyy-MM-dd）。");
        if (startTime == null) return ToolResult.failed("需要指定每天的开始时间（格式 HH:mm）。");
        if (endTime == null) return ToolResult.failed("需要指定每天的结束时间（格式 HH:mm）。");
        if (deptIds.isEmpty()) return ToolResult.failed("需要指定参与部门。");
        if (endDate.isBefore(startDate)) return ToolResult.failed("结束日期不能早于开始日期。");

        String activityName = res.activity().getName() == null ? ("活动 " + activityId) : res.activity().getName();

        long days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (days > 60) {
            return ToolResult.failed("日期范围过长（" + days + " 天），请缩小到 60 天以内。");
        }

        List<AgentActionPlan.PlanItem> items = new ArrayList<>();
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            items.add(new AgentActionPlan.PlanItem(null, d.toString(),
                    startTime + "-" + endTime + " · " + deptIds.size() + " 个部门"));
        }

        List<String> detailLines = new ArrayList<>();
        detailLines.add("活动：" + activityName);
        detailLines.add("日期：共 " + days + " 天（" + startDate + " 至 " + endDate + "）");
        detailLines.add("时段：每日 " + startTime + " - " + endTime);
        detailLines.add("部门：共 " + deptIds.size() + " 个"
                + (peoplePerSlot != null && peoplePerSlot > 0 ? "，每时段 " + peoplePerSlot + " 人" : "，人数不限"));
        detailLines.add("保存为排班草稿，复核后在活动排班中发布。候选发生变化时需重新生成清单。");

        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("activityId", activityId);
        exec.put("startDate", startDate.toString());
        exec.put("endDate", endDate.toString());
        exec.put("startTime", startTime.toString());
        exec.put("endTime", endTime.toString());
        exec.put("deptIds", deptIds);
        if (peoplePerSlot != null) exec.put("peoplePerSlot", peoplePerSlot);
        if (semester != null) exec.put("semester", semester);

        ActivityScheduleGenerateRequest req = requestFrom(exec);
        List<Map<String, Object>> candidates = scheduleService.preview(req);
        exec.put("previewFingerprint", ActivityScheduleService.previewFingerprint(candidates));
        items.clear();
        StringBuilder preview = new StringBuilder("## 活动排班候选清单\n\n| 日期 | 时段 | 人员 | 核对情况 |\n| --- | --- | --- | --- |\n");
        for (Map<String, Object> day : candidates) {
            Object rawSlots = day.get("slots");
            if (!(rawSlots instanceof List<?> slots)) continue;
            if (slots.isEmpty()) skipped.add(new AgentActionPlan.SkippedItem(str(day.get("date")), "", "没有可匹配的无课表时段"));
            for (Object rawSlot : slots) {
                if (!(rawSlot instanceof Map<?, ?> slot)) continue;
                String date = str(day.get("date"));
                String time = str(slot.get("startTime")) + "–" + str(slot.get("endTime"));
                int count = ((Number) slot.get("count")).intValue();
                int missing = ((Number) slot.get("missingCount")).intValue();
                List<?> persons = (List<?>) slot.get("persons");
                String names = persons.stream().filter(p -> p instanceof Map<?, ?>)
                        .map(p -> str(((Map<?, ?>) p).get("name"))).collect(java.util.stream.Collectors.joining("、"));
                if (count > 0) items.add(new AgentActionPlan.PlanItem(null, date + " " + time, names));
                if (count == 0 || missing > 0) skipped.add(new AgentActionPlan.SkippedItem(date + " " + time,
                        count > 0 ? "已匹配 " + count + " 人" : "未分配", missing > 0 ? "尚缺 " + missing + " 人，请补齐或调整时段" : "无空闲候选人员"));
                preview.append("| ").append(date).append(" | ").append(time).append(" | ")
                        .append(names.isBlank() ? "未分配" : names.replace("|", "\\|"))
                        .append(" | ").append(count == 0 ? "无候选" : missing > 0 ? "尚缺 " + missing + " 人" : "已匹配")
                        .append(" |\n");
            }
        }
        detailLines.add(ARGS_MARK + writeJson(exec));

        String summary = activityName + " · 保存排班草稿（" + items.size() + " 个班次）";
        AgentActionPlan plan = new AgentActionPlan(null, summary, items.size(), items, skipped,
                detailLines, true, null, preview.toString());

        return ToolResult.plan(
                "已匹配「" + activityName + "」的 " + items.size() + " 个候选班次。请核对人员与空缺，确认后保存草稿。",
                plan);
    }

    @Override
    public CommitResult commit(ToolContext ctx, AgentActionPlan plan) {
        return commit(plan, false);
    }

    @Override
    public CommitResult commitDelegated(ToolContext ctx, AgentActionPlan plan) {
        return commit(plan, true);
    }

    private CommitResult commit(AgentActionPlan plan, boolean delegated) {
        Map<String, Object> exec = readArgs(plan);
        if (exec == null) return CommitResult.failed("无法还原排班参数，请重新发起。");

        try {
            ActivityScheduleGenerateRequest req = requestFrom(exec);
            String fingerprint = str(exec.get("previewFingerprint"));
            List<Map<String, Object>> details = delegated ? scheduleService.generateDelegatedDraft(req, fingerprint)
                    : scheduleService.generateReviewed(req, fingerprint);
            int created = 0;
            int persons = 0;
            for (Map<String, Object> d : details) {
                Object c = d.get("count");
                if (c instanceof Number n) persons += n.intValue();
                if (d.get("slots") instanceof List<?> slots) {
                    for (Object slot : slots) if (slot instanceof Map<?, ?> m
                            && m.get("count") instanceof Number n && n.intValue() > 0) created++;
                }
            }

            List<Map<String, Object>> refs = new ArrayList<>();
            Long activityId = lng(exec.get("activityId"));
            refs.add(ToolArgs.map("type", "activity", "id", activityId,
                    "label", "活动排班", "link", "/routine/activity-schedules?activityId=" + activityId));

            String msg = "已生成 " + created + " 个班次，共安排 " + persons + " 人次。"
                    + "排班为草稿状态，可在排班管理中复核后发布。";
            if (persons == 0) {
                return new CommitResult("FAILED", 0, plan.skipped().size(),
                        "没有匹配到空闲人员，未生成班次。请检查参与部门的无课表后重新生成清单。",
                        ToolArgs.map("slots", 0, "persons", 0), List.of());
            }
            return new CommitResult(plan.skipped().isEmpty() ? "SUCCESS" : "PARTIAL", persons, plan.skipped().size(),
                    msg + (plan.skipped().isEmpty() ? "" : "仍有空缺，请在活动排班中补齐。"),
                    ToolArgs.map("slots", created, "persons", persons, "skipped", plan.skipped()), refs);
        } catch (com.pams.common.BizException e) {
            return CommitResult.failed("排班生成失败：" + e.getMessage());
        } catch (Exception e) {
            if (delegated) return new CommitResult("RESULT_UNKNOWN", 0, 0,
                    "排班草稿保存结果待核对，请先查看活动排班。", Map.of(), List.of());
            return CommitResult.failed("排班生成失败：" + e.getMessage());
        }
    }

    private ActivityScheduleGenerateRequest requestFrom(Map<String, Object> exec) {
        ActivityScheduleGenerateRequest req = new ActivityScheduleGenerateRequest();
        req.setActivityId(lng(exec.get("activityId")));
        req.setStartDate(LocalDate.parse(str(exec.get("startDate"))));
        req.setEndDate(LocalDate.parse(str(exec.get("endDate"))));
        req.setStartTime(LocalTime.parse(str(exec.get("startTime"))));
        req.setEndTime(LocalTime.parse(str(exec.get("endTime"))));
        req.setDeptIds(toLongList(exec.get("deptIds")));
        if (exec.get("peoplePerSlot") != null) req.setPeoplePerSlot(Integer.parseInt(str(exec.get("peoplePerSlot"))));
        if (exec.get("semester") != null) req.setSemester(str(exec.get("semester")));
        return req;
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private LocalTime parseTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            String t = s.trim();
            if (t.length() == 5) t = t + ":00";
            return LocalTime.parse(t);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Long> toLongList(Object o) {
        List<Long> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                if (x instanceof Number n) out.add(n.longValue());
                else if (x != null) {
                    try {
                        out.add(Long.parseLong(String.valueOf(x).trim()));
                    } catch (Exception ignored) {
                        // 跳过不可解析项
                    }
                }
            }
        }
        return out;
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
