package com.pams.module.routine.repository;

import com.pams.module.routine.entity.SchedulePerson;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SchedulePersonRepository extends JpaRepository<SchedulePerson, Long> {
    List<SchedulePerson> findByScheduleId(Long scheduleId);
    /** C3: 批量按排班 id 集合加载人员，替代逐条 findByScheduleId，避免 N+1 */
    List<SchedulePerson> findByScheduleIdIn(List<Long> scheduleIds);
    long countByPersonName(String personName);
    void deleteByScheduleId(Long scheduleId);
}
