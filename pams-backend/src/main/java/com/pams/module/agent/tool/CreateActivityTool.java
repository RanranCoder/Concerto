package com.pams.module.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.module.activity.dto.ActivityRequest;
import com.pams.module.activity.service.ActivityService;
import com.pams.module.agent.core.*;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写类工具：创建活动（业务设计方案 §3.5）。
 *
 * <p>回答"策划一个 XX 活动""新建一个活动"。落库走 {@code ActivityService.create}，
 * 状态固定为初始状态（不直接进入进行中）。
 *
 * <p><b>为什么创建活动也要走确认门禁</b>：看起来"创建"比"删除"温和，
 * 但它会在列表里留下真实数据、触发通知、并且<b>无法通过灵思撤回</b>
 * （ActivityService 没有"撤回创建"的语义）。所以 reversible=false。
 *
 * <p>权限码 {@code activity:create}；数据级 normal。
 */
@Component
public class CreateActivityTool implements WriteAgentTool {

    private static final String ARGS_MARK = "__args__:";

    private final ActivityService activityService;
    private final ObjectMapper mapper = new ObjectMapper();

    public CreateActivityTool(ActivityService activityService) {
        this.activityService = activityService;
    }

    @Override
    public String name() { return "createActivity"; }

    @Override
    public String label() { return "创建活动"; }

    @Override
    public String runningLabel() { return "正在准备活动信息…"; }

    @Override
    public String description() {
        return "创建一个新的活动记录，填写名称、时间、地点、主办方等信息。"
                + "适用于'策划一个 XX 活动''新建活动''安排一次 XX'等指令。"
                + "<b>参数可以不全、甚至为空直接调用</b>——名称、开始日期等缺失项由系统弹出"
                + "结构化表单请使用者补全，补全后生成待确认清单，确认后写入。"
                + "不要在对话里向用户追问这些字段。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolArgs.schema("object", ToolArgs.map(
                "name", ToolArgs.prop("string", "活动名称，可空——缺失时系统弹表单向用户收集"),
                "theme", ToolArgs.prop("string", "活动主题，可省略"),
                "type", ToolArgs.prop("string", "活动类型，可省略"),
                "startDate", ToolArgs.prop("string", "开始日期 yyyy-MM-dd，可空——缺失时系统弹表单向用户收集"),
                "endDate", ToolArgs.prop("string", "结束日期 yyyy-MM-dd，可省略"),
                "location", ToolArgs.prop("string", "活动地点，可省略"),
                "organizer", ToolArgs.prop("string", "承办单位，可省略"),
                "host", ToolArgs.prop("string", "主办单位，可省略"),
                "targetAudience", ToolArgs.prop("string", "参与对象，可省略"),
                "description", ToolArgs.prop("string", "活动简介，可省略")
        ), List.of());
    }

    @Override
    public List<String> requiredPermissions() { return List.of("activity:create"); }

    @Override
    public Sensitivity sensitivity() { return Sensitivity.NORMAL; }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String name = ToolArgs.str(args, "name");
        LocalDate start = parseDate(ToolArgs.str(args, "startDate"));
        LocalDate end = parseDate(ToolArgs.str(args, "endDate"));
        if (start != null && end != null && end.isBefore(start)) {
            return ToolResult.failed("结束日期不能早于开始日期。");
        }

        // ===== D2.5 Form Elicitation：关键信息不全 → 返回结构化表单，前端弹窗让用户补全 =====
        // "关键"的口径：名称（没有它连清单摘要都写不出）与开始日期（排班/签到/加分都挂在这个轴上）。
        // 其余字段非必填但表单一并展示——用户顺手补全，避免二次追问。
        // 此前缺参直接 FAILED，LLM 只能在对话里连环追问（要问四五个回合），这里一步收集。
        if (name == null || name.isBlank() || start == null) {
            return ToolResult.form("活动关键信息缺失，需要用户补充。", buildForm(name, start, args));
        }

        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(row("活动名称", name));
        addIfNotEmpty(fields, "活动主题", ToolArgs.str(args, "theme"));
        addIfNotEmpty(fields, "活动类型", ToolArgs.str(args, "type"));
        if (start != null) fields.add(row("开始日期", start.toString()));
        if (end != null) fields.add(row("结束日期", end.toString()));
        addIfNotEmpty(fields, "活动地点", ToolArgs.str(args, "location"));
        addIfNotEmpty(fields, "主办单位", ToolArgs.str(args, "host"));
        addIfNotEmpty(fields, "承办单位", ToolArgs.str(args, "organizer"));
        addIfNotEmpty(fields, "参与对象", ToolArgs.str(args, "targetAudience"));

        List<AgentActionPlan.PlanItem> items = new ArrayList<>();
        for (Map<String, Object> f : fields) {
            items.add(new AgentActionPlan.PlanItem(null, str(f.get("label")), str(f.get("value"))));
        }

        List<AgentActionPlan.SkippedItem> skipped = new ArrayList<>();
        if (start == null) {
            skipped.add(new AgentActionPlan.SkippedItem("活动时间", "未提供",
                    "未给出日期，创建后需在活动详情补充"));
        }

        List<String> detailLines = new ArrayList<>();
        detailLines.add("创建后状态：筹备中（初始状态）");
        detailLines.add("⚠️ 活动创建后无法通过对话撤回，如需删除请到活动管理页操作。");

        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("name", name);
        for (String k : List.of("theme", "type", "location", "organizer", "host", "targetAudience", "description")) {
            String v = ToolArgs.str(args, k);
            if (v != null && !v.isBlank()) exec.put(k, v);
        }
        if (start != null) exec.put("startDate", start.toString());
        if (end != null) exec.put("endDate", end.toString());
        detailLines.add(ARGS_MARK + writeJson(exec));

        AgentActionPlan plan = new AgentActionPlan(null, "创建活动：" + name, items.size(), items,
                skipped, detailLines, false, null);

        return ToolResult.plan("已整理好活动信息，请核对后确认创建。", plan);
    }

    @Override
    public CommitResult commit(ToolContext ctx, AgentActionPlan plan) {
        Map<String, Object> exec = readArgs(plan);
        if (exec == null) return CommitResult.failed("无法还原活动信息，请重新发起。");

        try {
            ActivityRequest req = new ActivityRequest();
            req.setName(str(exec.get("name")));
            req.setTheme(str(exec.get("theme")));
            req.setType(str(exec.get("type")));
            req.setLocation(str(exec.get("location")));
            req.setOrganizer(str(exec.get("organizer")));
            req.setHost(str(exec.get("host")));
            req.setTargetAudience(str(exec.get("targetAudience")));
            req.setDescription(str(exec.get("description")));
            if (exec.get("startDate") != null) req.setStartDate(LocalDate.parse(String.valueOf(exec.get("startDate"))));
            if (exec.get("endDate") != null) req.setEndDate(LocalDate.parse(String.valueOf(exec.get("endDate"))));

            Long id = activityService.create(req);

            List<Map<String, Object>> refs = new ArrayList<>();
            refs.add(ToolArgs.map("type", "activity", "id", id,
                    "label", str(exec.get("name")), "link", "/activity/" + id));

            return new CommitResult("SUCCESS", 1, 0,
                    "活动《" + str(exec.get("name")) + "》已创建，可在活动管理中继续完善。",
                    ToolArgs.map("activityId", id), refs);
        } catch (Exception e) {
            return CommitResult.failed("活动创建失败：" + e.getMessage());
        }
    }

    /**
     * 组装活动信息表单（D2.5）。
     *
     * <p>schema 自描述（D11 同款思路）：前端按 fields 渲染弹窗，本工具后续若再增删字段
     * 前端零改动。values 取 LLM 已提取的非空参数做预填——用户只补缺，不重填。
     */
    private AgentInputForm buildForm(String name, LocalDate start, Map<String, Object> args) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("name", "活动名称", "text", true, null, "如：党团知识竞赛"));
        fields.add(field("type", "活动类型", "select", false,
                List.of("主题党日", "志愿服务", "文体活动", "学习培训", "会议", "其他"), "可输入自定义类型"));
        fields.add(field("startDate", "开始日期", "date", true, null, null));
        fields.add(field("endDate", "结束日期", "date", false, null, "单日活动可留空"));
        fields.add(field("location", "活动地点", "text", false, null, "如：报告厅"));
        fields.add(field("host", "主办单位", "text", false, null, null));
        fields.add(field("organizer", "承办单位", "text", false, null, null));
        fields.add(field("targetAudience", "参与对象", "text", false, null, "如：全体党员"));
        fields.add(field("description", "活动简介", "textarea", false, null, null));

        Map<String, Object> values = new LinkedHashMap<>();
        putIfNotBlank(values, "name", name);
        putIfNotBlank(values, "startDate", start == null ? null : start.toString());
        for (String k : List.of("type", "endDate", "location", "host", "organizer", "targetAudience", "description")) {
            putIfNotBlank(values, k, ToolArgs.str(args, k));
        }

        return new AgentInputForm(
                "createActivity",
                "完善活动信息",
                "以下信息将用于创建活动，带 * 为必填。已根据对话内容预填，可直接修改或补全。",
                fields,
                values
        );
    }

    private Map<String, Object> field(String name, String label, String type, boolean required,
                                      List<String> options, String placeholder) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("label", label);
        m.put("type", type);
        m.put("required", required);
        if (options != null) m.put("options", options);
        if (placeholder != null) m.put("placeholder", placeholder);
        return m;
    }

    private void putIfNotBlank(Map<String, Object> m, String key, String v) {
        if (v != null && !v.isBlank()) m.put(key, v.trim());
    }

    private void addIfNotEmpty(List<Map<String, Object>> list, String label, String value) {
        if (value != null && !value.isBlank()) list.add(row(label, value));
    }

    private Map<String, Object> row(String label, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("value", value);
        return m;
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
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
}
