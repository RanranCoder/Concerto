package com.pams.module.knowledge.entity;
import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 知识库-人工录入文档（对应 V27 {@code kb_document}）。
 *
 * <p><b>它在 D12 里的定位是「源适配器体系中的一员」</b>，不是"权威源"。
 * 之所以仍然必须存在：项目里原本没有"制度/规范性文件"这一类载体 ——
 * 最接近的 {@code announcement} 是"通知"语义（时间性、一次性），
 * 不是"制度"（常驻、有生效期、会被修订）。而制度型问题恰恰是知识库最高频的场景。
 *
 * <p>与业务表（公告/稿件/策划书）的关系：那些表是**唯一真相**，本表是**独立的一手录入源**。
 * 两者的分块都落到 {@link KbChunk}，用 {@code sourceType} 区分。
 */
@Data
@Entity
@Table(name = "kb_document")
@SQLRestriction("deleted = 0")
public class KbDocument implements OrgScoped {

    /** 制度性文件 */
    public static final String TYPE_REGULATION = "REGULATION";
    /** 上级下发文件 */
    public static final String TYPE_POLICY = "POLICY";
    /** 操作指引 / 流程说明 */
    public static final String TYPE_MANUAL = "MANUAL";
    /** 常见问答 */
    public static final String TYPE_FAQ = "FAQ";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "doc_type", nullable = false, length = 30)
    private String docType = TYPE_REGULATION;

    @Column(length = 50)
    private String category;

    @Lob
    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String body;

    @Column(length = 500)
    private String summary;

    /**
     * 派生来源。人工录入为 {@code null}。
     *
     * <p>为什么要留这两个字段：D12 一期用"人工录入 + 派生只读"两条腿走路。
     * 派生只读的文档（例如把某条重要公告固化为知识条目）需要留指向，
     * 否则源更新后无法知道该重建哪一条。
     */
    @Column(name = "source_type", length = 30)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(length = 30)
    private String version;

    /** 生效日；问"现行规定"时按生效期过滤（方案 §4.3） */
    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    /** 废止日；{@code null} = 长期有效 */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "owner_dept_id")
    private Long ownerDeptId;

    /** 可见性：{@code ALL} 全员 / {@code LEADER} 部长及以上 */
    @Column(nullable = false, length = 20)
    private String visibility = "ALL";

    @Column(name = "required_permission", length = 60)
    private String requiredPermission;

    /** {@code normal} / {@code sensitive}；{@code restricted} 不落库（索引期排除） */
    @Column(nullable = false, length = 20)
    private String sensitivity = "normal";

    @Column(nullable = false, length = 20)
    private String status = STATUS_PUBLISHED;

    @Column(nullable = false)
    private Integer deleted = 0;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 是否对检索可见（草稿与归档不进索引） */
    public boolean indexable() {
        return STATUS_PUBLISHED.equals(status) && (deleted == null || deleted == 0);
    }
    private Long orgId;
}
