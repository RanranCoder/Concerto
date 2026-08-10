package com.pams.module.schedule.repository;

import com.pams.module.schedule.entity.NoClassScheduleRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NoClassScheduleRecordRepository extends JpaRepository<NoClassScheduleRecord, Long> {
    Optional<NoClassScheduleRecord> findByDeptIdAndSemester(Long deptId, String semester);
}
