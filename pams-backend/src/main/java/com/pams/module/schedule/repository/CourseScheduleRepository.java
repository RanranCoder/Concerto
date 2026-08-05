package com.pams.module.schedule.repository;

import com.pams.module.schedule.entity.CourseSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CourseScheduleRepository extends JpaRepository<CourseSchedule, Long> {
    List<CourseSchedule> findByUserIdAndSemester(Long userId, String semester);
    List<CourseSchedule> findByUserId(Long userId);
    Optional<CourseSchedule> findByUserIdAndSemesterAndDayOfWeekAndPeriod(Long userId, String semester, Integer dayOfWeek, Integer period);
    void deleteByUserIdAndSemester(Long userId, String semester);
    void deleteByUserId(Long userId);
}
