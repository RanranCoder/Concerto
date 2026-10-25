package com.pams.module.knowledge.entity;
import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 知识库-检索记录（对应 V27 {@code kb_retrieval_log}）。
 *
 * <p><b>它在产品上承担两个职责，这也是"知识库页面要有检索记录 Tab"的理由</b>：
 * <ol>
 *   <li><b>审计</b>：谁在什么时候问了什么、命中了哪些源、有没有被权限复核拦下。
 *       知识库把散落在各业务表的正文做成了可检索副本，就必须有能力回答
 *       "谁看到过什么" —— 否则出了越权连追溯都做不到。</li>
 *   <li><b>运营</b>：{@link #skipReason} 的分布直接区分两类问题 ——
 *       "索引坏了"（突然全 NO_HIT）与"语料不足"（长期某类问题 NO_HIT）；
 *       未命中的高频问题就是补录语料与补别名表的最直接输入。</li>
 * </ol>
 *
 * <p><b>{@link #filteredCount} 是最有价值的一个字段</b>：它统计"粗筛放过、但回源复核拦下"
 * 的条数。持续大于 0 说明索引期写入的可见性字段偏松（本该宁严不宽），
 * 是这套两段式权限设计的**自体检指标**。
 *
 * <p>刻意不加 {@code @SQLRestriction}：检索记录是审计数据，
 * 不做软删（清理策略走归档/定期任务，而不是"查不到"）。
 */
@Data
@Entity
@Table(name = "kb_retrieval_log")
public class KbRetrievalLog implements OrgScoped {

    /** 零命中 —— 知识库里没有相关内容（语料问题） */
    public static final String SKIP_NO_HIT = "NO_HIT";
    /** 命中但全部低于阈值 —— 沾边但不足以支撑回答 */
    public static final String SKIP_BELOW_THRESHOLD = "BELOW_THRESHOLD";
    /** 模型答了但没标引用 —— 闸口 C 的计数 */
    public static final String SKIP_NO_CITATION = "NO_CITATION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "conversation_id")
    private Long conversationId;

    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "stream_id", length = 64)
    private String streamId;

    /** 实际用于检索的 query（一期 = 用户原话；P1 起为改写结果） */
    @Lob
    @Column(name = "query_text", columnDefinition = "TEXT", nullable = false)
    private String queryText;

    /** 用户原话（已脱敏）。留它是为了比对"改写有没有改错" */
    @Lob
    @Column(name = "raw_question", columnDefinition = "TEXT")
    private String rawQuestion;

    @Column(nullable = false, length = 20)
    private String mode = "kb";

    @Column(name = "hit_count", nullable = false)
    private Integer hitCount = 0;

    @Column(name = "allowed_count", nullable = false)
    private Integer allowedCount = 0;

    /** 粗筛放过、回源复核拦下 —— 索引期可见性字段偏松的体检指标 */
    @Column(name = "filtered_count", nullable = false)
    private Integer filteredCount = 0;

    @Column(name = "top_score", precision = 10, scale = 4)
    private BigDecimal topScore;

    @Column(name = "scan_ms")
    private Integer scanMs;

    @Column(name = "total_ms")
    private Integer totalMs;

    /** {@code NO_HIT} / {@code BELOW_THRESHOLD} / {@code NO_CITATION}；正常作答为 {@code null} */
    @Column(name = "skip_reason", length = 40)
    private String skipReason;

    @Column(length = 20)
    private String provider;

    @Column(nullable = false)
    private Integer degraded = 0;

    @Column(columnDefinition="JSON")
    private String inputFragmentsJson;

    /** 命中明细 JSON：[{sourceType,sourceId,sourceKey,seq,path,score,allowed}] */
    @Column(name = "hits_json", columnDefinition = "JSON")
    private String hitsJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @org.hibernate.annotations.TenantId
    private Long orgId;
}
