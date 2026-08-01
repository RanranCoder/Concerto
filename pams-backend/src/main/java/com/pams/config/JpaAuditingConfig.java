package com.pams.config;

import org.springframework.context.annotation.Configuration;

/**
 * JPA 审计配置的扩展点。
 * 说明：{@code @EnableJpaAuditing} 已在主类 PartyAffairsManagementSystemApplication 上声明
 * （见 Task 3 简报 Step 3），此处不再重复声明，避免 'jpaAuditingHandler' 重复注册。
 * 后续若需自定义 AuditorAware（如 createdBy/updatedBy 填充），在此类补充即可。
 */
@Configuration
public class JpaAuditingConfig {
}
