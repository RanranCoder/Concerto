package com.pams.module.activity.repository;

import com.pams.module.activity.entity.ActivityPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ActivityPlanRepository extends JpaRepository<ActivityPlan, Long> {
    /** B5 fix: 替代 findAll() 全表扫描 */
    Optional<ActivityPlan> findTopByActivityIdOrderByVersionDesc(Long activityId);

    /** B5 fix: 按版本倒序查询 */
    List<ActivityPlan> findByActivityIdOrderByVersionDesc(Long activityId);
}
