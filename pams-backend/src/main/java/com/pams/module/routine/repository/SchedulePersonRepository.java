package com.pams.module.routine.repository;

import com.pams.module.routine.entity.SchedulePerson;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SchedulePersonRepository extends JpaRepository<SchedulePerson, Long> {
    List<SchedulePerson> findByScheduleId(Long scheduleId);
    long countByPersonName(String personName);
    void deleteByScheduleId(Long scheduleId);
}
