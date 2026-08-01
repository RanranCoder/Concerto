package com.pams.module.routine.repository;

import com.pams.module.routine.entity.Attendance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttendanceRepository extends JpaRepository<Attendance, Long> {
    List<Attendance> findByScheduleId(Long scheduleId);
    List<Attendance> findByPersonNameContaining(String personName);
    void deleteByScheduleId(Long scheduleId);
}
