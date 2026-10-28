package com.pams.module.activity.repository;

import com.pams.module.activity.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {
    List<Task> findByActivityIdOrderByStartDateAsc(Long activityId);
    @Query("SELECT t FROM Task t JOIN Activity a ON a.id=t.activityId WHERE t.assigneeUserId=:userId AND a.status<>'ARCHIVED' AND t.status<>'DONE' AND COALESCE(t.progress,0)<100 ORDER BY t.endDate ASC")
    List<Task> findByAssigneeUserIdOrderByEndDateAsc(@Param("userId") Long userId);

    @Query("SELECT t.activityId FROM Task t WHERE t.id = :id")
    Optional<Long> activityIdOf(@Param("id") Long id);

    @Query("SELECT COUNT(d) > 0 FROM Department d WHERE d.id = :deptId AND d.orgId = :orgId")
    boolean departmentInOrg(@Param("deptId") Long deptId, @Param("orgId") Long orgId);

    // Undo may restore a soft-deleted task. Native SQL must explicitly constrain tenant and activity.
    @Query(value = "SELECT * FROM task WHERE id = :id AND activity_id = :activityId AND org_id = :orgId", nativeQuery = true)
    Optional<Task> findForRestore(@Param("id") Long id, @Param("activityId") Long activityId, @Param("orgId") Long orgId);

    /**
     * 按负责人匹配当前用户的任务（忽略大小写与首尾空格）。
     * 替代 findAll 全表遍历后内存匹配。@SQLRestriction(deleted=0) 自动生效。
     */
    @Query("SELECT t FROM Task t WHERE LOWER(TRIM(t.assignee)) = LOWER(TRIM(:assignee))")
    List<Task> findByAssigneeMatching(@Param("assignee") String assignee);

    /** 按截止日升序（灵思"逾期任务"用；不新增 Service 方法，保持业务侧零侵入） */
    List<Task> findAllByOrderByEndDateAsc();
}
