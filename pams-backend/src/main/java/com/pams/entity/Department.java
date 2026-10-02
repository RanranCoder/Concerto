package com.pams.entity;

import com.pams.common.OrgScoped;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 组织架构节点（部门/中心/小组/分队）。表名沿用 {@code sys_department}，
 * 原地演进为树形结构（V30 加列）——刻意不新建第二套部门表，避免双轨维护。
 *
 * <p>{@code User.dept}、{@code Task.deptId}、{@code Material.deptId} 等既有引用全部不变。
 */
@Data
@Entity
@Table(name = "sys_department")
public class Department implements OrgScoped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 说明：组织架构表不参与 @TenantId 自动过滤（注册流程需跨租户供给；见 A3 边界）
    private Long orgId;
    private Long parentId;

    private String name;

    /** 节点类型：部门 / 中心 / 小组 / 分队。Java 侧默认值（DB 默认不适用于 Hibernate 显式 insert） */
    private String unitType = "部门";
    private String code;

    /** 层级深度（根为 1） */
    private Integer level = 1;

    /** 物化路径，如 /1/3/7 */
    private String path;

    private Long leaderUserId;

    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
