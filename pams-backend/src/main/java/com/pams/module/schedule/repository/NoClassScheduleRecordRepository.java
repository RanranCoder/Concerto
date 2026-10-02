package com.pams.module.schedule.repository;

import com.pams.module.schedule.entity.NoClassScheduleRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NoClassScheduleRecordRepository extends JpaRepository<NoClassScheduleRecord, Long> {
    Optional<NoClassScheduleRecord> findByDeptIdAndSemester(Long deptId, String semester);

    /** 最近一次导入的无课表记录（供前端默认选中） */
    Optional<NoClassScheduleRecord> findFirstByOrderByIdDesc();

    /** 已上传无课表的学期去重列表（按学期倒序，供前端下发选择） */
    @Query("select distinct r.semester from NoClassScheduleRecord r order by r.semester desc")
    List<String> findDistinctSemesters();

    /** 批量 JPQL 删除，DELETE 在调用时立即执行（而非排队到 flush），用于覆盖式重导。 */
    @Modifying
    @Query("delete from NoClassScheduleRecord r where r.deptId = :deptId and r.semester = :semester")
    void deleteByDeptIdAndSemester(@Param("deptId") Long deptId, @Param("semester") String semester);
}
