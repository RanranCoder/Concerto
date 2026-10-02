package com.pams.module.knowledge.service;

import com.pams.module.knowledge.retriever.KnowledgeSearchMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动期探测/创建中文全文索引（方案 §4.2）。
 *
 * <h3>为什么不在 Flyway 迁移里建索引</h3>
 * 中文需要 {@code FULLTEXT ... WITH PARSER ngram}，而 ngram 解析器在个别环境不可用
 * （版本 / 构建差异 / 云厂商裁剪）。若把它写进迁移，一旦失败就是
 * <b>Flyway 报错 → 应用启不来</b> —— 用"问答检索可能变慢"换"服务不可用"，不划算。
 *
 * <p>所以放到应用层：探测 → 尝试创建 → 失败则降级为 {@code LIKE} 并打 WARN。
 * <b>功能可用，性能下降，且这件事是可见的</b>（{@link KnowledgeSearchMode} 会把模式
 * 透给 /api/kb/health，配合"检索耗时 P95" 指标即可发现悄悄退化 —— 方案 R14）。
 */
@Slf4j
@Component
public class KnowledgeIndexInitializer implements ApplicationRunner {

    private static final String INDEX_NAME = "ft_kb_chunk_content";
    private static final String TABLE = "kb_chunk";

    private final JdbcTemplate jdbc;
    private final KnowledgeSearchMode mode;

    /** 关闭后只探测不创建（用于 DBA 手工管理索引的环境） */
    @Value("${pams.agent.kb.auto-create-fulltext:true}")
    private boolean autoCreate;

    public KnowledgeIndexInitializer(JdbcTemplate jdbc, KnowledgeSearchMode mode) {
        this.jdbc = jdbc;
        this.mode = mode;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (indexExists()) {
                mode.markFulltext("全文索引 " + INDEX_NAME + " 已存在");
                log.info("知识库检索模式：全文索引已就绪（ngram）");
                return;
            }
            if (!autoCreate) {
                mode.markDegraded("全文索引不存在，且已禁用自动创建（pams.agent.kb.auto-create-fulltext=false）");
                log.warn("知识库检索降级为 LIKE：{}", mode.note());
                return;
            }
            createIndex();
            mode.markFulltext("本次启动创建了全文索引 " + INDEX_NAME);
            log.info("知识库检索模式：已创建 ngram 全文索引");
        } catch (Exception e) {
            // 任何异常都降级而不是抛出 —— 启动期不该因为检索优化失败而中断
            mode.markDegraded("创建全文索引失败：" + e.getMessage() + "（已降级为 LIKE 检索）");
            log.warn("知识库检索降级为 LIKE：{}", mode.note(), e);
        }
    }

    private boolean indexExists() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class, TABLE, INDEX_NAME);
        return n != null && n > 0;
    }

    private void createIndex() {
        jdbc.execute("CREATE FULLTEXT INDEX " + INDEX_NAME + " ON " + TABLE
                + " (title, content) WITH PARSER ngram");
    }
}
