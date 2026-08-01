package com.pams.module.routine.repository;

import com.pams.module.routine.entity.FreeSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FreeScheduleRepository extends JpaRepository<FreeSchedule, Long> {
    List<FreeSchedule> findByDeptId(Long deptId);
}
