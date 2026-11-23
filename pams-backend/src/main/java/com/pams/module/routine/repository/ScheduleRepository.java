package com.pams.module.routine.repository;

import com.pams.module.routine.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from Schedule s where s.id = :id")
    java.util.Optional<Schedule> lockById(@org.springframework.data.repository.query.Param("id") Long id);
    @org.springframework.data.jpa.repository.Query("select s.id from Schedule s where s.scheduleType = 'ACTIVITY' and s.status = 'DRAFT' and (:activityId is null or s.activityId = :activityId) order by s.id")
    List<Long> findDraftActivityIds(@org.springframework.data.repository.query.Param("activityId") Long activityId);

    List<Schedule> findByScheduleType(String scheduleType);
    List<Schedule> findByStatus(String status);
    List<Schedule> findByScheduleDateBetweenOrderByIdAsc(LocalDate from, LocalDate to);

    @org.springframework.data.jpa.repository.Query("""
            select count(s.id)
            from Schedule s, SchedulePerson p
            where p.scheduleId = s.id and p.orgId = s.orgId and p.userId in :personIds
              and s.id <> :id and s.status = 'PUBLISHED' and s.scheduleDate = :date
              and s.startTime < :endTime and s.endTime > :startTime
            """)
    long countRosterConflicts(
            @org.springframework.data.repository.query.Param("id") Long id,
            @org.springframework.data.repository.query.Param("date") LocalDate date,
            @org.springframework.data.repository.query.Param("startTime") java.time.LocalTime startTime,
            @org.springframework.data.repository.query.Param("endTime") java.time.LocalTime endTime,
            @org.springframework.data.repository.query.Param("personIds") List<Long> personIds);
    List<Schedule> findByScheduleTypeAndStatus(String scheduleType, String status);
    List<Schedule> findByWeekNo(Integer weekNo);
    List<Schedule> findByActivityId(Long activityId);
    List<Schedule> findByScheduleTypeAndActivityId(String scheduleType, Long activityId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
    List<Schedule> findByScheduleTypeAndActivityIdAndScheduleDateBetween(
            String scheduleType, Long activityId, LocalDate startDate, LocalDate endDate);
    List<Schedule> findByScheduleTypeAndActivityIdAndStatus(String scheduleType, Long activityId, String status);

    /**
     * 统计 schedule_date 落入 [start, end]（含端点）的排班条数。
     * 替代 findAll 全表遍历后内存过滤。Between 为闭区间，与原逻辑一致。
     */
    long countByScheduleDateBetween(LocalDate start, LocalDate end);
}
