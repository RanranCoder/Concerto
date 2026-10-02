package com.pams.config;

import com.pams.common.OrgContext;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

/**
 * 多租户判别器（A3 批次）：把 {@link OrgContext} 接入 Hibernate。
 *
 * <p>由 Hibernate 按类名直接实例化（见 application.yml
 * {@code spring.jpa.properties.hibernate.tenant_identifier_resolver}），无需 Spring 注入。
 *
 * <p>标了 {@code @TenantId} 的实体：查询自动追加租户谓词、插入自动回填租户值。
 * 租户标识取 {@link OrgContext}（登录用户的 {@code sys_user.org_id}）；
 * 匿名/系统上下文回退默认组织。
 *
 * <p>⚠️ 原生 SQL 与 JdbcTemplate 不受本机制覆盖（见《阶段1实施清单》§9.4）。
 */
public class OrgTenantIdentifierResolver implements CurrentTenantIdentifierResolver<Long> {

    @Override
    public Long resolveCurrentTenantIdentifier() {
        return OrgContext.getOrDefault();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
