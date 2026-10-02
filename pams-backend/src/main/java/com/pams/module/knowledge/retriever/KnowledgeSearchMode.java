package com.pams.module.knowledge.retriever;

import org.springframework.stereotype.Component;

/**
 * 全文检索能力开关（启动期探测结果）。
 *
 * <p>方案 §4.2 的要求：中文需要 {@code FULLTEXT ... WITH PARSER ngram}，
 * 但 ngram 解析器在个别环境不可用（版本/构建差异）。把它写进 Flyway 迁移是不安全的 ——
 * 迁移失败会让**整个应用启动不了**，用"检索可能变慢"换"服务起不来"不划算。
 *
 * <p>所以改由 {@code KnowledgeIndexInitializer} 在启动时探测并创建，
 * 失败则把本开关置为降级，检索自动走 {@code LIKE}：<b>功能可用，性能下降</b>。
 *
 * <p>这个开关同时是**可观测的降级信号**：{{@code /api/kb/health} 会把它透出来，
 * 配合"检索耗时 P95 < 300ms"这条指标即可发现"悄悄退化成 LIKE"（方案 R14）。
 */
@Component
public class KnowledgeSearchMode {

    /** 全文索引是否可用；不可用时检索降级为 LIKE */
    private volatile boolean fulltextAvailable = false;

    /** 说明：为什么降级、当时发生了什么 —— 排查时这句话比布尔值有用得多 */
    private volatile String note = "未初始化";

    public boolean fulltextAvailable() { return fulltextAvailable; }

    public String note() { return note; }

    public void markFulltext(String note) {
        this.fulltextAvailable = true;
        this.note = note;
    }

    public void markDegraded(String note) {
        this.fulltextAvailable = false;
        this.note = note;
    }

    /** 供健康检查/统计展示 */
    public String mode() { return fulltextAvailable ? "fulltext-ngram" : "like-fallback"; }
}
