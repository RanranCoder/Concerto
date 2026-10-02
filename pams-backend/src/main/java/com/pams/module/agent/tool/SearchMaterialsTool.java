package com.pams.module.agent.tool;

import com.pams.common.PageResult;
import com.pams.module.agent.core.AgentTool;
import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.core.ToolResult;
import com.pams.module.archive.service.MaterialService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读类工具：材料检索（业务设计方案 §3.5）。
 *
 * <p>回答"找一下 XX 的策划书""上次的总结在哪"。材料库当前仅支持<b>元数据检索</b>
 * （名称/类型/所属活动），不支持全文检索——一期如实按元数据检索，
 * 不假装能做全文（二期上 ngram FULLTEXT 后再扩展）。
 *
 * <p>权限码 {@code material:view}；数据级 normal（材料元数据不含个人信息）。
 */
@Component
public class SearchMaterialsTool implements AgentTool {

    private final MaterialService materialService;

    public SearchMaterialsTool(MaterialService materialService) {
        this.materialService = materialService;
    }

    @Override
    public String name() { return "searchMaterials"; }

    @Override
    public String label() { return "材料检索"; }

    @Override
    public String runningLabel() { return "正在检索材料库…"; }

    @Override
    public String description() {
        return "在材料库中按名称关键词检索材料文件（策划书、总结、照片、表格等）。"
                + "用于回答'找一下 XX 活动的材料''XX 策划书在哪'等问题。"
                + "注意：当前仅支持按名称/类型检索，不支持正文内容检索。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolArgs.schema("object", ToolArgs.map(
                "keyword", ToolArgs.prop("string", "材料名称关键词，可省略"),
                "bizType", ToolArgs.prop("string", "材料类型，可省略"),
                "activityId", ToolArgs.prop("integer", "限定某个活动 ID，可省略"),
                "limit", ToolArgs.prop("integer", "返回条数上限，默认 10")
        ), List.of());
    }

    @Override
    public List<String> requiredPermissions() { return List.of("material:view"); }

    @Override
    public Sensitivity sensitivity() { return Sensitivity.NORMAL; }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String keyword = ToolArgs.str(args, "keyword");
        String bizType = ToolArgs.str(args, "bizType");
        Long activityId = ToolArgs.lng(args, "activityId");
        int limit = Math.min(Math.max(ToolArgs.intVal(args, "limit", 10), 1), 30);

        try {
            PageResult<Map<String, Object>> page =
                    materialService.page(keyword, bizType, activityId, ctx.getDeptId(), 1, limit);
            List<Map<String, Object>> records = page.getRecords() == null ? List.of() : page.getRecords();

            if (records.isEmpty()) {
                String cond = keyword == null || keyword.isBlank() ? "" : "名称含「" + keyword + "」的";
                return ToolResult.ok("材料库里没有找到" + (cond.isEmpty() ? "符合条件的" : cond) + "材料。");
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            List<Map<String, Object>> refs = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (Map<String, Object> m : records) {
                Object name = m.get("name") != null ? m.get("name") : m.get("title");
                Object id = m.get("id");
                rows.add(ToolArgs.row(
                        "id", id,
                        "name", name,
                        "bizType", m.get("bizType"),
                        "fileName", m.get("fileName"),
                        "createdAt", m.get("createdAt")
                ));
                if (name != null) names.add(String.valueOf(name));
                refs.add(ToolArgs.map("type", "material", "id", id, "label", name,
                        "link", "/archive/material?id=" + id));
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("count", page.getTotal());
            payload.put("returned", records.size());
            payload.put("table", ToolArgs.table(
                    new String[]{"材料名称", "类型", "文件名", "上传时间"}, rows));
            payload.put("page", "material");

            String summary = "材料库共匹配 " + page.getTotal() + " 项，包括："
                    + ToolArgs.listSummary(names, 5) + "。";
            return ToolResult.ok(summary, payload, refs);
        } catch (Exception e) {
            return ToolResult.failed("材料检索失败：" + e.getMessage());
        }
    }
}
