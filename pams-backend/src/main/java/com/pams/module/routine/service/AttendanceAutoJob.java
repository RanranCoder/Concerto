package com.pams.module.routine.service;

import com.pams.module.routine.entity.Attendance;
import com.pams.module.routine.entity.Schedule;
import com.pams.module.routine.entity.SchedulePerson;
import com.pams.module.routine.repository.AttendanceRepository;
import com.pams.module.routine.repository.SchedulePersonRepository;
import com.pams.module.routine.repository.ScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * 自动考勤引擎（活动级单码）：已发布的活动排班在活动结束时间过后，将仍未签到
 * （status_source=AUTO 且 status=PENDING）的排班人员置为「缺勤」。
 *
 * 硬性约束：绝不覆盖 status_source=MANUAL 的考勤行；排班置 ENDED 后不再重复处理（幂等）。
 */
@Component
public class AttendanceAutoJob {

    private static final Logger log = LoggerFactory.getLogger(AttendanceAutoJob.class);

    private final SchedulePersonRepository schedulePersonRepository;
    private final AttendanceRepository attendanceRepository;
    private final ScheduleRepository scheduleRepository;

    public AttendanceAutoJob(SchedulePersonRepository schedulePersonRepository,
                             AttendanceRepository attendanceRepository,
                             ScheduleRepository scheduleRepository) {
        this.schedulePersonRepository = schedulePersonRepository;
        this.attendanceRepository = attendanceRepository;
        this.scheduleRepository = scheduleRepository;
    }

    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void run() {
        LocalDateTime now = LocalDateTime.now();
        List<Schedule> published = scheduleRepository.findByScheduleTypeAndStatus(Schedule.TYPE_ACTIVITY, Schedule.STATUS_PUBLISHED);
        for (Schedule sch : published) {
            if (!isScheduleOver(sch, now)) continue;
            try {
                markAbsent(sch.getId());
            } catch (Exception e) {
                log.warn("自动考勤失败 schedule={}: {}", sch.getId(), e.getMessage());
            }
            sch.setStatus(Schedule.STATUS_ENDED);
            scheduleRepository.save(sch);
        }
    }

    /** 已发布活动排班是否已过活动结束时间（无日期则无法判定，跳过；无结束时间仅当日按天判定）。 */
    private boolean isScheduleOver(Schedule sch, LocalDateTime now) {
        if (sch.getScheduleDate() == null) return false;
        LocalDate today = now.toLocalDate();
        int cmp = sch.getScheduleDate().compareTo(today);
        if (cmp < 0) return true;
        if (cmp > 0) return false;
        if (sch.getEndTime() == null) return false; // 同日且无结束时间，无法判定
        return !now.toLocalTime().isBefore(sch.getEndTime());
    }

    private void markAbsent(Long scheduleId) {
        LocalDateTime now = LocalDateTime.now();
        List<SchedulePerson> persons = schedulePersonRepository.findByScheduleId(scheduleId);
        for (SchedulePerson p : persons) {
            Optional<Attendance> existing = p.getUserId() != null
                    ? attendanceRepository.findByScheduleIdAndPersonId(scheduleId, p.getUserId())
                    : attendanceRepository.findByScheduleIdAndPersonName(scheduleId, p.getPersonName());
            if (existing.isPresent()) {
                Attendance a = existing.get();
                if (Attendance.SOURCE_MANUAL.equals(a.getStatusSource())) continue; // 人工已改不覆盖
                if (!Attendance.STATUS_PENDING.equals(a.getStatus())) continue; // 已签到/非待签不覆盖
                a.setStatus(Attendance.STATUS_ABSENT);
                a.setRecordTime(now);
                attendanceRepository.save(a);
            } else {
                // 防御：有排班人员但缺考勤行时补建
                Attendance a = new Attendance();
                a.setScheduleId(scheduleId);
                a.setPersonId(p.getUserId());
                a.setPersonName(p.getPersonName());
                a.setStatus(Attendance.STATUS_ABSENT);
                a.setStatusSource(Attendance.SOURCE_AUTO);
                a.setRecordTime(now);
                a.setCreatedAt(now);
                attendanceRepository.save(a);
            }
        }
    }
}