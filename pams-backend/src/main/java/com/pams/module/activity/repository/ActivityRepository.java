package com.pams.module.activity.repository;

import com.pams.module.activity.entity.Activity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ActivityRepository extends JpaRepository<Activity, Long>,
        JpaSpecificationExecutor<Activity> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Activity a WHERE a.id=:id")
    Optional<Activity> lockById(@Param("id") Long id);

    /** 按名称查活动（@SQLRestriction deleted=0 自动过滤） */
    List<Activity> findByName(String name);

    /**
     * 按 status 分组计数（status 为 null 的归入 ASSIGNED 由服务层处理）。
     * 替代 findAll 全表遍历，数据库端聚合。@SQLRestriction(deleted=0) 自动生效。
     * 返回每行 [ActivityStatus(可能为 null), Long 计数]。
     */
    @Query("SELECT a.status, COUNT(a) FROM Activity a GROUP BY a.status")
    List<Object[]> countGroupByStatus();

    @Query("SELECT t.activityId, COUNT(t), SUM(CASE WHEN t.progress >= 100 THEN 1 ELSE 0 END) FROM Task t WHERE t.activityId IN :ids GROUP BY t.activityId")
    List<Object[]> taskCounts(@Param("ids") List<Long> ids);

    @Query("SELECT m.activityId, COUNT(m) FROM Material m WHERE m.activityId IN :ids GROUP BY m.activityId")
    List<Object[]> materialCounts(@Param("ids") List<Long> ids);

    @Query("SELECT DISTINCT a.organizer FROM Activity a WHERE a.organizer IS NOT NULL AND TRIM(a.organizer) <> '' ORDER BY a.organizer")
    List<String> organizers();
}
