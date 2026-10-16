package com.pams.module.agent.core;

/**
 * 数据敏感分级（业务设计方案 D4）。
 *
 * <p>PolicyGuard 依据本分级裁决"工具结果能否进入 LLM 上下文"：
 * <ul>
 *   <li>{@link #NORMAL} —— 允许直接进入外部模型上下文</li>
 *   <li>{@link #SENSITIVE} —— 必须先经服务端字段级遮罩/摘要，再进入；会话中仅存摘要</li>
 *   <li>{@link #RESTRICTED} —— 禁止出网；结果不经 LLM，由模板渲染固定话术 + 深链</li>
 * </ul>
 */
public enum Sensitivity {
    NORMAL,
    SENSITIVE,
    RESTRICTED;

    public static Sensitivity of(String v) {
        if (v == null) return NORMAL;
        for (Sensitivity s : values()) {
            if (s.name().equalsIgnoreCase(v.trim())) return s;
        }
        return NORMAL;
    }

    /** restricted 一律不出网 */
    public boolean canEgress() {
        return this != RESTRICTED;
    }

    /**
     * 取更严的一档（{@code NORMAL < SENSITIVE < RESTRICTED}，与声明顺序一致）。
     *
     * <p><b>为什么需要它</b>：一轮问答可能调用多个工具，**该轮的留存档位必须取其中最严的那个** ——
     * 只要有一个工具碰过敏感数据，这一轮的答复与思维链就不该按 NORMAL 全量留存（D4 / 决策 6）。
     * 此前 {@code AgentOrchestrator} 里写的是 {@code pendingPlan != null ? NORMAL : NORMAL}，
     * 两个分支同值 —— 等于这条策略从未生效，敏感轮次的答复一直按全量落库。
     *
     * <p>用声明顺序而不是另加 {@code rank} 字段：本枚举的声明顺序本身就是"由松到严"，
     * 再放一个平行定义只会多一处可能漂移的来源。
     */
    public static Sensitivity max(Sensitivity a, Sensitivity b) {
        if (a == null) return b == null ? NORMAL : b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
