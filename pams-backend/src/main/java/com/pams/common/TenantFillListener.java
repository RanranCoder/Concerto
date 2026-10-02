package com.pams.common;

import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.springframework.stereotype.Component;

/**
 * 租户写入回填：Hibernate insert 前，若实体是 {@link OrgScoped} 且 {@code orgId} 为空，
 * 从 {@link OrgContext} 取当前组织写入（并同步到 Hibernate 的 state 数组）。
 *
 * <p>注册见 {@code com.pams.config.TenantEventListenerConfig}（EventListenerRegistry）。
 * 之所以不用 {@code @EntityListeners}：本项目实体没有共同父类（BaseEntity 是死代码），
 * 逐个标注不可维护；Hibernate 事件监听是全局生效的。
 *
 * <p>⚠️ 已知边界：本回填只保证「当前登录用户的组织」。免登录路径（如 {@code /api/signins/scan}）
 * 无登录用户，会落到 {@link OrgContext#getOrDefault()} 的默认组织；若该数据父实体属于别的组织，
 * 需由父实体推导（阶段 2 处理）。
 */
@Component
public class TenantFillListener implements PreInsertEventListener {

    @Override
    public boolean onPreInsert(PreInsertEvent event) {
        Object entity = event.getEntity();
        if (entity instanceof OrgScoped scoped && scoped.getOrgId() == null) {
            Long orgId = OrgContext.getOrDefault();
            scoped.setOrgId(orgId);
            String[] propertyNames = event.getPersister().getPropertyNames();
            Object[] state = event.getState();
            for (int i = 0; i < propertyNames.length; i++) {
                if ("orgId".equals(propertyNames[i])) {
                    state[i] = orgId;
                    break;
                }
            }
        }
        return false;
    }
}
