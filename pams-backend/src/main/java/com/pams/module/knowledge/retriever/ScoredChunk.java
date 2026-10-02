package com.pams.module.knowledge.retriever;

import java.time.LocalDateTime;

/**
 * 一个带相关度分数的检索命中。
 *
 * <p>刻意做成 record：它在"检索 → 权限复核 → 拼 prompt → 构造 citations"
 * 之间流转，任何一环都不该改它 —— 尤其是 {@link #content} 与 {@link #path}，
 * 因为 citations 里的 {@code snippet} 直接取自这里。
 * 可变对象会让"这条片段到底有没有过复核"变得不可追踪（方案 G2 的成因）。
 *
 * @param asOf  数据截止时间；{@code snapshot} 类必填。引用回填时若为 null 且
 *              {@code sourceType} 是快照类，**必须拒绝出网**（方案 R16 的硬断言）
 * @param score 已乘过源权重的最终分（T2 的正文权重为 0，只可能靠标题命中）
 */
public record ScoredChunk(long id,
                          String sourceType,
                          Long sourceId,
                          String sourceKey,
                          int seq,
                          String title,
                          String path,
                          String content,
                          String tier,
                          String sensitivity,
                          LocalDateTime asOf,
                          double score) {

    /** 片段摘要：引用卡展示用。截断以控制响应体大小（不是给 LLM 的，LLM 用的是完整 content） */
    public String snippet(int maxChars) {
        if (content == null) return "";
        String flat = content.replace('\n', ' ').strip();
        return flat.length() <= maxChars ? flat : flat.substring(0, maxChars) + "…";
    }

    /** 带上数据截止时间/分数的新实例（record 不可变，检索打分与快照回填都要造副本） */
    public ScoredChunk withScore(double newScore) {
        return new ScoredChunk(id, sourceType, sourceId, sourceKey, seq, title, path,
                content, tier, sensitivity, asOf, newScore);
    }
}
