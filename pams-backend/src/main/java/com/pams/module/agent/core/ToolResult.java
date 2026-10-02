package com.pams.module.agent.core;

import java.util.List;
import java.util.Map;

/**
 * 工具执行结果。
 *
 * @param status      OK / FAILED / DENIED / SKIPPED / TIMEOUT / FORM
 * @param summary     给 LLM 与人看的一句话摘要（已按敏感级裁剪）
 * @param references  实体引用（类型 + id + 展示名），前端渲染为可跳转链接，避免"AI 说了但找不到"
 * @param plan        写类工具的拟执行清单；读类工具为 null
 * @param payload     结构化数据（供前端结果卡渲染：表格/统计/列表）
 * @param error       失败原因
 * @param form        表单补全请求（D2.5 Form Elicitation）；仅 FORM 状态非空
 */
public record ToolResult(
        String status,
        String summary,
        List<Map<String, Object>> references,
        AgentActionPlan plan,
        Map<String, Object> payload,
        String error,
        AgentInputForm form
) {
    public static ToolResult ok(String summary) {
        return new ToolResult("OK", summary, List.of(), null, Map.of(), null, null);
    }

    public static ToolResult ok(String summary, Map<String, Object> payload) {
        return new ToolResult("OK", summary, List.of(), null, payload, null, null);
    }

    public static ToolResult ok(String summary, Map<String, Object> payload, List<Map<String, Object>> refs) {
        return new ToolResult("OK", summary, refs, null, payload, null, null);
    }

    public static ToolResult plan(String summary, AgentActionPlan plan) {
        return new ToolResult("OK", summary, List.of(), plan, Map.of(), null, null);
    }

    /**
     * 表单补全（D2.5）：写类工具关键参数不全时返回，由编排器落 WAITING_INPUT 任务、
     * 推 INPUT_NEEDED 事件，前端弹窗收集后经 POST /tasks/{id}/input 重跑 dry-run。
     */
    public static ToolResult form(String summary, AgentInputForm form) {
        return new ToolResult("FORM", summary, List.of(), null, Map.of(), null, form);
    }

    public static ToolResult failed(String error) {
        return new ToolResult("FAILED", "执行失败：" + error, List.of(), null, Map.of(), error, null);
    }

    public static ToolResult denied(String reason) {
        return new ToolResult("DENIED", "无权访问：" + reason, List.of(), null, Map.of(), reason, null);
    }

    /** restricted 数据的固定话术结果（D4：内容不经 LLM） */
    public static ToolResult locked(String summary, List<Map<String, Object>> refs) {
        return new ToolResult("DENIED", summary, refs, null, Map.of("restricted", true), null, null);
    }

    public boolean isOk() { return "OK".equals(status); }

    /** 是否为表单补全请求（D2.5） */
    public boolean needsInput() { return form != null; }

    /** 送入 LLM 上下文的文本（PolicyGuard 之后调用） */
    public String llmText() {
        return summary == null ? "" : summary;
    }
}
