package com.pams.module.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.module.activity.entity.Activity;
import com.pams.module.agent.core.*;
import com.pams.module.seat.service.SeatLayoutService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写类工具：从模板创建座位表（业务设计方案 §3.5）。
 *
 * <p>回答"给这个活动建个座位表"。复用 {@code SeatLayoutService.createFromTemplate}
 * ——既有的"模板 → 活动座位表"复制能力，灵思不做布局算法。
 *
 * <p>权限码 {@code seat:edit}；数据级 normal。
 * 可撤回：座位表是按活动隔离的，删掉重建即可（reversible=true）。
 */
@Component
public class CreateSeatLayoutTool implements WriteAgentTool {

    private static final String ARGS_MARK = "__args__:";

    private final SeatLayoutService seatLayoutService;
    private final ActivityResolver activityResolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public CreateSeatLayoutTool(SeatLayoutService seatLayoutService, ActivityResolver activityResolver) {
        this.seatLayoutService = seatLayoutService;
        this.activityResolver = activityResolver;
    }

    @Override
    public String name() { return "createSeatLayout"; }

    @Override
    public String label() { return "创建座位表"; }

    @Override
    public String runningLabel() { return "正在准备座位表…"; }

    @Override
    public String description() {
        return "为活动创建座位表，可以基于已有模板快速生成。"
                + "适用于'给这个活动建个座位表''按上次的模板做个座位安排'等指令。"
                + "不传 templateId 时会列出可用模板。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolArgs.schema("object", ToolArgs.map(
                "activityId", ToolArgs.prop("integer", "活动 ID，可省略；与 activityName 二选一"),
                "activityName", ToolArgs.prop("string", ActivityResolver.NAME_DESC),
                "templateId", ToolArgs.prop("integer", "座位模板 ID，可省略"),
                "name", ToolArgs.prop("string", "座位表名称，可省略（默认取活动名）")
        ), List.of());
    }

    @Override
    public List<String> requiredPermissions() { return List.of("seat:edit"); }

    @Override
    public Sensitivity sensitivity() { return Sensitivity.NORMAL; }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        ActivityResolver.Resolution res =
                activityResolver.resolve(ToolArgs.lng(args, "activityId"), ToolArgs.str(args, "activityName"));
        if (res.failed()) return ToolResult.failed(res.message());
        Long activityId = res.activity().getId();
        Long templateId = ToolArgs.lng(args, "templateId");
        String name = ToolArgs.str(args, "name");

        Activity activity = res.activity();
        if (name == null || name.isBlank()) name = activity.getName() + " 座位表";

        // 未给模板 → 列出可用模板让用户挑（比"报错要求传参"友好）
        if (templateId == null) {
            List<Map<String, Object>> templates;
            try {
                templates = seatLayoutService.listTemplates();
            } catch (Exception e) {
                return ToolResult.failed("模板列表读取失败：" + e.getMessage());
            }
            if (templates == null || templates.isEmpty()) {
                return ToolResult.ok("系统里还没有座位表模板。请先在座位表管理页保存一个模板，"
                        + "或到该页面手动创建座位表。");
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> t : templates) {
                rows.add(ToolArgs.row(
                        "id", t.get("id"),
                        "name", t.get("name"),
                        "category", t.get("templateCategory"),
                        "rows", t.get("rows"),
                        "cols", t.get("cols")
                ));
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("templates", rows);
            payload.put("table", ToolArgs.table(new String[]{"模板名", "分类", "行", "列"}, rows));
            payload.put("page", "seat");
            return ToolResult.ok("有 " + templates.size() + " 个可用座位模板，请告诉我用哪一个。", payload);
        }

        // 有模板 → 出清单
        String templateName = "模板 #" + templateId;
        try {
            for (Map<String, Object> t : seatLayoutService.listTemplates()) {
                if (templateId.equals(lngOf(t.get("id")))) {
                    Object tn = t.get("name");
                    if (tn != null) templateName = String.valueOf(tn);
                    break;
                }
            }
        } catch (Exception ignored) {
            // 取不到名字不影响创建，用 ID 兜底
        }

        List<AgentActionPlan.PlanItem> items = List.of(
                new AgentActionPlan.PlanItem(null, "活动", activity.getName()),
                new AgentActionPlan.PlanItem(null, "座位表名称", name),
                new AgentActionPlan.PlanItem(null, "基于模板", templateName)
        );

        List<String> detailLines = new ArrayList<>();
        detailLines.add("活动：" + activity.getName());
        detailLines.add("座位表：" + name);
        detailLines.add("模板：" + templateName + "（将复制其布局，后续可在编辑器调整）");
        detailLines.add("该操作可撤回：删除该座位表即可重新创建。");

        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("activityId", activityId);
        exec.put("templateId", templateId);
        exec.put("name", name);
        detailLines.add(ARGS_MARK + writeJson(exec));

        AgentActionPlan plan = new AgentActionPlan(null,
                activity.getName() + " · 座位表（基于" + templateName + "）", items.size(), items,
                List.of(), detailLines, true, null);

        return ToolResult.plan("已准备好座位表创建信息，确认后将生成可在编辑器中拖拽调整的座位表。", plan);
    }

    @Override
    public CommitResult commit(ToolContext ctx, AgentActionPlan plan) {
        Map<String, Object> exec = readArgs(plan);
        if (exec == null) return CommitResult.failed("无法还原座位表参数，请重新发起。");

        Long activityId = lng(exec.get("activityId"));
        Long templateId = lng(exec.get("templateId"));
        String name = str(exec.get("name"));
        if (activityId == null || templateId == null) {
            return CommitResult.failed("活动或模板缺失，请重新发起。");
        }

        try {
            Long newId = seatLayoutService.createFromTemplate(ctx.getUserId(), templateId,
                    activityId, name);
            if (newId == null) {
                return CommitResult.failed("座位表创建失败：模板内容为空。");
            }
            List<Map<String, Object>> refs = new ArrayList<>();
            refs.add(ToolArgs.map("type", "seatLayout", "id", newId, "label", name,
                    "link", "/activity/" + activityId + "/seat"));

            return new CommitResult("SUCCESS", 1, 0,
                    "座位表《" + name + "》已创建，可在座位表编辑器中调整。",
                    ToolArgs.map("seatLayoutId", newId), refs);
        } catch (Exception e) {
            return CommitResult.failed("座位表创建失败：" + e.getMessage());
        }
    }

    private Long lngOf(Object o) {
        if (o == null) return null;
        try {
            return Long.valueOf(String.valueOf(o));
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

    private Long lng(Object o) {
        if (o == null) return null;
        try {
            return Long.valueOf(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }
}
