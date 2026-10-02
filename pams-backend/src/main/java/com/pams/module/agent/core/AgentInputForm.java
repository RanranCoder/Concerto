package com.pams.module.agent.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化输入请求（Form Elicitation，业务设计方案 D2.5）。
 *
 * <p><b>为什么需要它</b>：此前写类工具缺参只有两条路——工具返回 FAILED 让 LLM 在对话里
 * 连环追问，或 LLM 自行编造默认值。前者交互啰嗦（"活动叫什么？时间呢？地点呢？"要问四五回合），
 * 后者危险（编出来的日期直接进确认清单）。Claude/MCP 的 elicitation 与 Codex 的结构化确认
 * 给出第三条路：<b>工具在 dry-run 阶段声明"我还缺什么"，由前端弹出结构化表单让用户补全</b>。
 *
 * <p>契约（与 {@link AgentActionPlan} 同款解耦思路，D11）：
 * <ul>
 *   <li>{@code fields} 是自描述的 schema：{name, label, type(text|textarea|date|select|number),
 *       required, options, placeholder}——前端按 schema 渲染弹窗，<b>新增表单型工具前端零改动</b>。</li>
 *   <li>{@code values} 是 LLM 已从对话中提取的预填值——用户只需补缺、不必重填。</li>
 *   <li>本对象<b>只做参数收集</b>，不替代确认门禁（D2）：表单提交后仍要出拟执行清单、
 *       仍要一次性令牌确认，两条铁律不动。</li>
 * </ul>
 *
 * @param formKey     表单标识（一般等于工具名，前端留作扩展点）
 * @param title       弹窗标题，如"完善活动信息"
 * @param description 说明文案（为什么需要填、带 * 为必填）
 * @param fields      字段 schema 列表
 * @param values      预填值（键 = 字段 name）
 */
public record AgentInputForm(
        String formKey,
        String title,
        String description,
        List<Map<String, Object>> fields,
        Map<String, Object> values
) {

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("formKey", formKey);
        m.put("title", title == null ? "" : title);
        m.put("description", description == null ? "" : description);
        m.put("fields", fields == null ? List.of() : fields);
        m.put("values", values == null ? Map.of() : values);
        return m;
    }
}
