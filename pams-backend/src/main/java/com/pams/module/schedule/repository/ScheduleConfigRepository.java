package com.pams.module.schedule.repository;

import com.pams.module.schedule.entity.ScheduleConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScheduleConfigRepository extends JpaRepository<ScheduleConfig, Long> {
    List<ScheduleConfig> findAllByOrderByPeriodAsc();
}
