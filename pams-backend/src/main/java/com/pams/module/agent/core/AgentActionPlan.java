package com.pams.module.agent.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 拟执行清单（业务设计方案 D2 / 灵思 §4.5）。
 *
 * <p>前端 {@code AgentActionCard} <b>只依赖本结构，不感知具体业务</b>——新增写类工具前端零改动。
 * 这是 D11 解耦契约在前端的对应物。
 *
 * @param planId       任务ID（agent_task.id）
 * @param summary      摘要，如"第四十期培训班 · 素拓批量加分"
 * @param total        将执行条数
 * @param items        明细（可能为空，如"发布公告"）
 * @param skipped      跳过项及原因（<b>必现且不进折叠</b>，灵思 §4.3）
 * @param detailLines  补充说明，如"来源：签到名单第一组/第二组"
 * @param reversible   是否可撤回（决定前端提示文案的硬度）
 * @param confirmToken 一次性确认令牌
 * @param preview      <b>内容预览</b>（§3.9.1）：内容类工具（策划书章节草稿 / 推文草稿）生成的长文正文。
 *                     空串表示该工具没有长文内容。前端在确认卡上给「查看全文」入口，用抽屉阅读。
 *                     与 detailLines 的分工：detailLines 是"清单的补充说明"（逐行、短），
 *                     preview 是"即将写入数据库的正文本身"（长文、需要通读后才能确认）。
 */
public record AgentActionPlan(
        Long planId,
        String summary,
        int total,
        List<PlanItem> items,
        List<SkippedItem> skipped,
        List<String> detailLines,
        boolean reversible,
        String confirmToken,
        String preview
) {
    /** 明细项 */
    public record PlanItem(Long id, String label, String sub) {}

    /** 跳过项 */
    public record SkippedItem(String label, String sub, String reason) {}

    /**
     * 兼容构造：无内容预览。
     *
     * <p>刻意保留 8 参签名——写类工具与门禁的既有调用点都是 8 参，
     * 只有内容类工具（策划书/推文）需要 preview。这样新增字段不会波及十余处调用点（D11 思路）。
     */
    public AgentActionPlan(Long planId, String summary, int total, List<PlanItem> items,
                           List<SkippedItem> skipped, List<String> detailLines,
                           boolean reversible, String confirmToken) {
        this(planId, summary, total, items, skipped, detailLines, reversible, confirmToken, null);
    }

    public static AgentActionPlan empty() {
        return new AgentActionPlan(null, "", 0, List.of(), List.of(), List.of(), false, null);
    }

    /**
     * 序列化为 plan_json。
     *
     * <p>用 LinkedHashMap 而非 {@code Map.of}：后者最多 10 对键值、且遇 null value 抛 NPE
     * （preview 在非内容类工具上恒为 null）。
     */
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planId", planId == null ? 0L : planId);
        m.put("summary", summary == null ? "" : summary);
        m.put("total", total);
        m.put("items", items == null ? List.of() : items);
        m.put("skipped", skipped == null ? List.of() : skipped);
        m.put("detailLines", detailLines == null ? List.of() : detailLines);
        m.put("reversible", reversible);
        m.put("confirmToken", confirmToken == null ? "" : confirmToken);
        m.put("preview", preview == null ? "" : preview);
        m.put("needsConfirm", true);
        return m;
    }
}
