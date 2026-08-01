package com.pams.module.activity.repository;

import com.pams.module.activity.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {
    List<Task> findByActivityIdOrderByStartDateAsc(Long activityId);
}
