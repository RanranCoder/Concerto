package com.pams.module.agent.orchestration;

import com.pams.common.OrgContext;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.repository.ActivityRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多租户隔离测试（A3）：{@code @TenantId} 是否真正把业务数据按组织隔开。
 *
 * <p>刻意<b>不加 {@code @Transactional}</b>：每次仓储调用各自成事务/会话，
 * 这样切换 {@link OrgContext} 才能真正影响 Hibernate 的租户判别。
 * 用完即删，避免污染其他用例。
 */
@SpringBootTest
@ActiveProfiles("test")
class TenantIsolationTest {

    private static final Long ORG_A = 1L;
    private static final Long ORG_B = 2L;

    @Autowired
    ActivityRepository activityRepository;

    @AfterEach
    void clear() {
        OrgContext.clear();
    }

    @Test
    void businessData_isolatedByOrg() {
        // 在组织 B 下写入
        OrgContext.set(ORG_B);
        Activity a = new Activity();
        a.setName("租户B的活动");
        a.setType("OTHER");
        a.setStatus(ActivityStatus.ASSIGNED);
        a.setStartDate(LocalDate.now());
        a.setDeleted(0);
        a.setCreatedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        Long id = activityRepository.save(a).getId();

        try {
            // 组织 A 看不到 B 的数据
            OrgContext.set(ORG_A);
            assertThat(activityRepository.findById(id)).isEmpty();
            assertThat(activityRepository.findAll()).extracting(Activity::getId).doesNotContain(id);

            // 组织 B 能看到自己的数据
            OrgContext.set(ORG_B);
            assertThat(activityRepository.findById(id)).isPresent();
        } finally {
            // 清理（在 B 的租户上下文下删除）
            OrgContext.set(ORG_B);
            activityRepository.deleteById(id);
        }
    }
}
