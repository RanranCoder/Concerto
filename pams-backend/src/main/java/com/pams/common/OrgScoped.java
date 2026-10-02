package com.pams.common;

/**
 * 租户实体标记：实现该接口的实体在 insert 时若 {@code orgId} 为空，
 * 由 {@link TenantFillListener} 从 {@link OrgContext} 回填当前组织。
 *
 * <p>阶段 1「先建列」：所有带 org_id 列的实体都实现本接口。
 * Lombok {@code @Data} 已生成 getter/setter，满足接口约定。
 */
public interface OrgScoped {
    Long getOrgId();

    void setOrgId(Long orgId);
}
