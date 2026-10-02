package com.pams.module.routine.repository;

import com.pams.module.routine.entity.Attendance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AttendanceRepository extends JpaRepository<Attendance, Long> {
    List<Attendance> findByScheduleId(Long scheduleId);
    Optional<Attendance> findByScheduleIdAndPersonId(Long scheduleId, Long personId);
    Optional<Attendance> findByScheduleIdAndPersonName(Long scheduleId, String personName);
    List<Attendance> findBySessionId(Long sessionId);
    Optional<Attendance> findBySessionIdAndPersonId(Long sessionId, Long personId);
    Optional<Attendance> findBySessionIdAndPersonName(Long sessionId, String personName);
    List<Attendance> findByPersonNameContaining(String personName);
    long countByPersonName(String personName);
    void deleteByScheduleId(Long scheduleId);
}
