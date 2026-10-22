package com.pams.module.activity.repository;

import com.pams.module.activity.entity.ActivityPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ActivityPlanRepository extends JpaRepository<ActivityPlan, Long> {
    List<ActivityPlan> findByDocumentFileId(Long documentFileId);
    @org.springframework.data.jpa.repository.Query("SELECT p FROM ActivityPlan p WHERE p.background LIKE :reference OR p.purpose LIKE :reference OR p.content LIKE :reference OR p.flow LIKE :reference OR p.notice LIKE :reference OR p.emergency LIKE :reference OR p.budget LIKE :reference OR p.sectionOrder LIKE :reference")
    List<ActivityPlan> referencingFile(@org.springframework.data.repository.query.Param("reference") String reference);
    /** B5 fix: 替代 findAll() 全表扫描 */
    Optional<ActivityPlan> findTopByActivityIdOrderByVersionDesc(Long activityId);

    /** B5 fix: 按版本倒序查询 */
    List<ActivityPlan> findByActivityIdOrderByVersionDesc(Long activityId);
}
