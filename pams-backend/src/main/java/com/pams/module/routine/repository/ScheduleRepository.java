package com.pams.module.routine.repository;

import com.pams.module.routine.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
    List<Schedule> findByScheduleType(String scheduleType);
    List<Schedule> findByWeekNo(Integer weekNo);
    List<Schedule> findByActivityId(Long activityId);

    /**
     * 统计 schedule_date 落入 [start, end]（含端点）的排班条数。
     * 替代 findAll 全表遍历后内存过滤。Between 为闭区间，与原逻辑一致。
     */
    long countByScheduleDateBetween(LocalDate start, LocalDate end);
}
