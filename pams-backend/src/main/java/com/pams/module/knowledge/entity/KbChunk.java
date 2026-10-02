package com.pams.module.knowledge.entity;
import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 知识库-分块（对应 V27 {@code kb_chunk}）—— 检索单元，所有知识源共用。
 *
 * <p><b>关键结构决策：它不隶属于 {@code kb_document}，而隶属于任意业务源。</b>
 * 用 {@code (sourceType, sourceId)} 定位，公告/稿件/策划书/人工文档都落到这张表。
 * D12 的架构形状要求是"新增一个源 = 一个适配器类 + 一条准入配置"，
 * 如果 chunk 挂在 kb_document 下，每加一个源就得改表结构。
 *
 * <p><b>权限字段的定位（勿误用）</b>：{@link #ownerDeptId} / {@link #visibility} /
 * {@link #requiredPermission} 是**索引期的保守缓存**，只用于 SQL 粗筛保住性能。
 * 正确性由 {@code PermissionResolver} 在检索期**回源复核**提供（方案 §6.4）。
 * 因此这些字段过期只会"少给"、不会"多给"——方向是安全的。
 *
 * <p><b>不要在任何地方为了判断权限而回查本表的字段并当作结论</b>：
 * 结论只能来自 {@code KnowledgeSource#canAccess}。
 */
@Data
@Entity
@Table(name = "kb_chunk")
@SQLRestriction("deleted = 0")
public class KbChunk implements OrgScoped {

    /** T1 全文级：参与 BM25 正文打分 */
    public static final String TIER_T1 = "T1";
    /** T2 字段级：只做标题匹配，正文权重为 0（短元数据进正文索引会稀释相关性） */
    public static final String TIER_T2 = "T2";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    /** {@code source_type:source_id}，唯一键的一部分，用于去重与级联清理 */
    @Column(name = "source_key", nullable = false, length = 80)
    private String sourceKey;

    @Column(nullable = false)
    private Integer seq;

    @Column(length = 200)
    private String title;

    /** 标题路径，如「第三章 第二十六条」—— 引用的可读性靠它（只给文档标题无从核验） */
    @Column(length = 300)
    private String path;

    @Lob
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "start_offset")
    private Integer startOffset;

    @Column(name = "end_offset")
    private Integer endOffset;

    @Column(name = "token_len")
    private Integer tokenLen;

    @Column(nullable = false, length = 10)
    private String tier = TIER_T1;

    /**
     * 数据截止时间。
     *
     * <p>{@code snapshot} 类分块必填（"截至 2026-09-14 在册 87 人"），文档类为 {@code null}。
     * 方案 R16 要求 snapshot 类引用强制携带 {@code asOf} —— 放在 chunk 上是为了
     * 引用回填时不必回查来源表（回查会让"引用构造"依赖更多源，正是 G2 想避免的）。
     */
    @Column(name = "as_of")
    private LocalDateTime asOf;

    @Column(name = "owner_dept_id")
    private Long ownerDeptId;

    @Column(nullable = false, length = 20)
    private String visibility = "ALL";

    @Column(name = "required_permission", length = 60)
    private String requiredPermission;

    @Column(nullable = false, length = 20)
    private String sensitivity = "normal";

    @Column(nullable = false)
    private Integer deleted = 0;

    @Column(name = "indexed_at")
    private LocalDateTime indexedAt;

    public static String key(String sourceType, Long sourceId) {
        return sourceType + ":" + sourceId;
    }
    @org.hibernate.annotations.TenantId
    private Long orgId;
}
