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
}
