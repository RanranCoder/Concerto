package com.pams.module.schedule.repository;

import com.pams.module.schedule.entity.NoClassScheduleRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NoClassScheduleRecordRepository extends JpaRepository<NoClassScheduleRecord, Long> {
    Optional<NoClassScheduleRecord> findByDeptIdAndSemester(Long deptId, String semester);

    /** 批量派生删除，DELETE 在调用时立即执行（而非排队到 flush），用于覆盖式重导。 */
    void deleteByDeptIdAndSemester(Long deptId, String semester);
}
