package com.pams.module.routine.repository;

import com.pams.module.routine.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
    List<Schedule> findByScheduleType(String scheduleType);
    List<Schedule> findByWeekNo(Integer weekNo);
    List<Schedule> findByActivityId(Long activityId);
}
