package com.pams.module.schedule.repository;

import com.pams.module.schedule.entity.NoClassScheduleRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface NoClassScheduleRecordRepository extends JpaRepository<NoClassScheduleRecord, Long> {
    Optional<NoClassScheduleRecord> findByDeptIdAndSemester(Long deptId, String semester);

    /** 批量 JPQL 删除，DELETE 在调用时立即执行（而非排队到 flush），用于覆盖式重导。 */
    @Modifying
    @Query("delete from NoClassScheduleRecord r where r.deptId = :deptId and r.semester = :semester")
    void deleteByDeptIdAndSemester(@Param("deptId") Long deptId, @Param("semester") String semester);
}
