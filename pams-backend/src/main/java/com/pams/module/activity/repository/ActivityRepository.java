package com.pams.module.activity.repository;

import com.pams.module.activity.entity.Activity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ActivityRepository extends JpaRepository<Activity, Long>,
        JpaSpecificationExecutor<Activity> {

    /** 按名称查活动（@SQLRestriction deleted=0 自动过滤） */
    List<Activity> findByName(String name);

    /**
     * 按 status 分组计数（status 为 null 的归入 ASSIGNED 由服务层处理）。
     * 替代 findAll 全表遍历，数据库端聚合。@SQLRestriction(deleted=0) 自动生效。
     * 返回每行 [ActivityStatus(可能为 null), Long 计数]。
     */
    @Query("SELECT a.status, COUNT(a) FROM Activity a GROUP BY a.status")
    List<Object[]> countGroupByStatus();
}
