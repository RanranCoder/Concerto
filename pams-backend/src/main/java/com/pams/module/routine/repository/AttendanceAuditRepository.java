package com.pams.module.routine.repository;

import com.pams.module.routine.entity.AttendanceAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttendanceAuditRepository extends JpaRepository<AttendanceAudit, Long> {
    List<AttendanceAudit> findByAttendanceIdOrderByOperateTimeDesc(Long attendanceId);
}