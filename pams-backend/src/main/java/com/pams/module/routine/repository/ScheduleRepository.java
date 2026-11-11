package com.pams.module.routine.repository;

import com.pams.module.routine.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from Schedule s where s.id = :id")
    java.util.Optional<Schedule> lockById(@org.springframework.data.repository.query.Param("id") Long id);
    List<Schedule> findByScheduleType(String scheduleType);
    List<Schedule> findByScheduleTypeAndStatus(String scheduleType, String status);
    List<Schedule> findByWeekNo(Integer weekNo);
    List<Schedule> findByActivityId(Long activityId);
    List<Schedule> findByScheduleTypeAndActivityId(String scheduleType, Long activityId);
    List<Schedule> findByScheduleTypeAndActivityIdAndScheduleDateBetween(
            String scheduleType, Long activityId, LocalDate startDate, LocalDate endDate);
    List<Schedule> findByScheduleTypeAndActivityIdAndStatus(String scheduleType, Long activityId, String status);

    /**
     * 统计 schedule_date 落入 [start, end]（含端点）的排班条数。
     * 替代 findAll 全表遍历后内存过滤。Between 为闭区间，与原逻辑一致。
     */
    long countByScheduleDateBetween(LocalDate start, LocalDate end);
}
