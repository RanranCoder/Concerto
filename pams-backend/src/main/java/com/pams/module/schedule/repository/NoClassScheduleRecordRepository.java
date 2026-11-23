package com.pams.module.schedule.repository;

import com.pams.module.schedule.entity.NoClassScheduleRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NoClassScheduleRecordRepository extends JpaRepository<NoClassScheduleRecord, Long> {
    Optional<NoClassScheduleRecord> findByDeptIdAndSemester(Long deptId, String semester);

    /** 最近一次导入的无课表记录（供前端默认选中） */
    Optional<NoClassScheduleRecord> findFirstByOrderByCreatedAtDescIdDesc();

    Optional<NoClassScheduleRecord> findFirstByDeptIdOrderByCreatedAtDescIdDesc(Long deptId);

    /** 已上传无课表的学期去重列表（按学期倒序，供前端下发选择） */
    @Query("select distinct r.semester from NoClassScheduleRecord r order by r.semester desc")
    List<String> findDistinctSemesters();

    @Modifying
    @Query(value = """
            INSERT INTO no_class_schedule (org_id, dept_id, dept_name, semester, grid_json, created_at)
            VALUES (:orgId, :deptId, :deptName, :semester, :gridJson, :createdAt)
            ON DUPLICATE KEY UPDATE dept_name = :deptName, grid_json = :gridJson, created_at = :createdAt
            """, nativeQuery = true)
    void saveLatest(@Param("orgId") Long orgId, @Param("deptId") Long deptId,
                    @Param("deptName") String deptName, @Param("semester") String semester,
                    @Param("gridJson") String gridJson, @Param("createdAt") LocalDateTime createdAt);
}
