package com.pams.module.knowledge.entity;
import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 知识库-事实快照（对应 V28 {@code kb_snapshot}，D12 的 **B 形态聚合快照**）。
 *
 * <h3>它解决什么问题</h3>
 * 知识库此前只能答"文档类"问题。问"我们支部有多少人""最近活动办了几场"一律答不上，
 * 因为这类数据属于 **A 形态原始明细**，而 A 形态是**刻意不进索引**的
 * （含不可脱敏的个人信息，方案 §2.2）。
 *
 * <p>但"答不上"并不是正确答案 —— 它的正确出路是 **B 形态**：
 * 把聚合事实写成人读得懂的叙述、带上数据截止时间，再进索引。
 * 本实体就是那个落点。
 *
 * <h3>两条不可破的约束</h3>
 * <ol>
 *   <li><b>只存聚合，不存明细</b>：{@link #content} 里不得出现人名、学号、手机号、政治面貌。
 *       保证方式不是"写的时候注意"，而是
 *       {@code SnapshotSpecRegistry} 的取值口只接受计数与组织名 ——
 *       结构上就拿不到个人字段（与 {@code FilteredChunks} 同一思路）。</li>
 *   <li><b>{@link #asOf} 强制非空</b>：它是"这是某时刻的数字"的唯一标识。
 *       缺了它用户会把快照当"当前值"读 —— 拿着过期数字做决策比不答更糟（R16）。
 *       引用回填时 {@code CitationMapper} 也会对 snapshot 类引用做这条校验。</li>
 * </ol>
 */
@Data
@Entity
@Table(name = "kb_snapshot")
@SQLRestriction("deleted = 0")
public class KbSnapshot implements OrgScoped {

    /** 全局作用域（如"全校活动开展情况"）：scope_id 用 0 而不是 NULL */
    public static final long SCOPE_GLOBAL = 0L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "spec_code", nullable = false, length = 60)
    private String specCode;

    @Column(nullable = false, length = 200)
    private String title;

    /** 叙述化聚合事实（**只允许计数与组织名**） */
    @Lob
    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String content;

    /** 数据截止时间，**强制非空** */
    @Column(name = "as_of", nullable = false)
    private LocalDateTime asOf;

    @Column(name = "scope_type", nullable = false, length = 30)
    private String scopeType;

    /**
     * 作用域主键；{@link #SCOPE_GLOBAL} = 全局。
     *
     * <p>注意是 {@code 0} 而不是 {@code null}：MySQL 唯一索引把 NULL 视为互不相同，
     * 用 NULL 会让 {@code UNIQUE (spec_code, scope_type, scope_id, as_of)} 的幂等约束失效
     * —— 同一份快照会被反复插入。
     */
    @Column(name = "scope_id", nullable = false)
    private Long scopeId = SCOPE_GLOBAL;

    @Column(name = "owner_dept_id")
    private Long ownerDeptId;

    @Column(nullable = false, length = 20)
    private String visibility = "ALL";

    @Column(name = "required_permission", length = 60)
    private String requiredPermission;

    @Column(nullable = false, length = 20)
    private String sensitivity = "normal";

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @Column(nullable = false)
    private Integer deleted = 0;

    /** 引用/展示用：{@code snapshot:{id}} */
    public String sourceKey() { return "snapshot:" + id; }
    private Long orgId;
}
