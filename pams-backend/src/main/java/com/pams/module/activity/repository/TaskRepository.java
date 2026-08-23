package com.pams.module.activity.repository;

import com.pams.module.activity.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {
    List<Task> findByActivityIdOrderByStartDateAsc(Long activityId);

    /**
     * 按负责人匹配当前用户的任务（忽略大小写与首尾空格）。
     * 替代 findAll 全表遍历后内存匹配。@SQLRestriction(deleted=0) 自动生效。
     */
    @Query("SELECT t FROM Task t WHERE LOWER(TRIM(t.assignee)) = LOWER(TRIM(:assignee))")
    List<Task> findByAssigneeMatching(@Param("assignee") String assignee);
}
