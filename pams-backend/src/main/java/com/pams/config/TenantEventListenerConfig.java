package com.pams.config;

import com.pams.common.TenantFillListener;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.springframework.stereotype.Component;

/**
 * 把 {@link TenantFillListener} 注册为全局 Hibernate 事件监听（PRE_INSERT）。
 *
 * <p>项目实体无共同父类，无法用 {@code @EntityListeners} 逐个标注，
 * 故在 SessionFactory 构建后统一追加监听器。
 */
@Component
public class TenantEventListenerConfig {

    private final EntityManagerFactory entityManagerFactory;
    private final TenantFillListener tenantFillListener;

    public TenantEventListenerConfig(EntityManagerFactory entityManagerFactory,
                                     TenantFillListener tenantFillListener) {
        this.entityManagerFactory = entityManagerFactory;
        this.tenantFillListener = tenantFillListener;
    }

    @PostConstruct
    void registerListeners() {
        SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        EventListenerRegistry registry =
                sessionFactory.getServiceRegistry().requireService(EventListenerRegistry.class);
        registry.getEventListenerGroup(EventType.PRE_INSERT).appendListener(tenantFillListener);
    }
}
