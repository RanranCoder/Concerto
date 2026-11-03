package com.pams.module.agent.core;

import java.util.List;
import java.util.Map;

/**
 * Agent 工具契约（业务设计方案 §3.5 工具清单）。
 *
 * <p>设计原则：<b>薄包装</b>。工具只负责「参数归一化 + 调用现有 Service + 结果裁剪 + 话术组织」，
 * 业务规则、查询逻辑、权限全部保留在现有 Service / Repository 里。这是 D11"透明变更自动跟随"的前提。
 *
 * <p>工具粒度与权限码<b>一一对应</b>，不允许"一个工具混用多个权限域"（D3 第 1 层防护）。
 */
public interface AgentTool {

    /** 工具标识（LLM function name，英文小驼峰） */
    String name();

    /** 中文业务名——前端过程区显示这个，不显示标识符（灵思 §7.1） */
    String label();

    /** 进行中文案（如"正在查询活动概况…"） */
    String runningLabel();

    /** 给 LLM 的能力描述 */
    String description();

    /** JSON Schema 形式的入参定义（简化为 Map，由 LlmProvider 组装） */
    Map<String, Object> parametersSchema();

    /** 所需权限码；空 = 登录即可（D3 第 1 层） */
    default List<String> requiredPermissions() { return List.of(); }

    /** 是否"满足任一权限码即可"（默认 false = 全部满足）。
     *  用于少数需要"管理权限 或 本人数据"的工具（如 querySchedule / queryCreditRecords）。 */
    default boolean anyPermission() { return false; }

    /** 是否领导角色专属（content 模块 LEADER 等价口径，G1）。与 requiredPermissions 取"与"。 */
    default boolean requiresLeader() { return false; }

    /** 数据敏感级（D4，决定能否出网） */
    Sensitivity sensitivity();

    /** 是否写类工具（写类必须走 D2 确认门禁） */
    default boolean writeOperation() { return false; }

    /** Explicit opt-in: missing arguments do not imply that a tool can create a form. */
    default boolean supportsForm() { return false; }

    /** Runtime diagnostic contract; does not grant permissions or change the function schema. */
    default ToolDescriptor descriptor() { return ToolDescriptor.from(this); }

    /**
     * 执行工具。
     *
     * <p>读类工具在此直接返回结果；写类工具的 {@link #execute} 实现的是 <b>dry-run 读阶段</b>
     * （只查询匹配、绝不写库），真正的写库在 {@link WriteAgentTool#commit} 里。
     *
     * @param ctx    执行上下文（含权限与脱敏标志）
     * @param args   LLM 给出的参数（已通过 Schema 校验）
     * @return 工具结果；写类工具返回的 {@code plan} 字段即拟执行清单
     */
    ToolResult execute(ToolContext ctx, Map<String, Object> args);

    /**
     * 结果裁剪与脱敏钩子（D4：PolicyGuard 是唯一出网闸口）。
     * 普通读结果保留有界结构化事实；写结果和敏感结果只给摘要，之后仍须过 PolicyGuard。
     */
    default String toLlmText(ToolContext ctx, ToolResult result) {
        return ToolResultText.render(this,result);
    }
}
