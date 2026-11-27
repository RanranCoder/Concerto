package com.pams.module.routine.service;

import com.pams.module.routine.entity.Attendance;
import com.pams.module.routine.entity.Schedule;
import com.pams.module.routine.repository.AttendanceRepository;
import com.pams.module.routine.repository.ScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import com.pams.common.OrgContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 自动考勤引擎：有完整日期与时段的已发布排班在结束时间过后，将仍未签到
 * （status_source=AUTO 且 status=PENDING）的排班人员置为「缺勤」。
 *
 * 硬性约束：绝不覆盖 status_source=MANUAL 的考勤行；排班置 ENDED 后不再重复处理（幂等）。
 */
@Component
@Profile("!postgresql-candidate")
public class AttendanceAutoJob {

    private static final Logger log = LoggerFactory.getLogger(AttendanceAutoJob.class);

    private final ActivityScheduleService publication;
    private final AttendanceRepository attendanceRepository;
    private final ScheduleRepository scheduleRepository;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public AttendanceAutoJob(ActivityScheduleService publication,
                             AttendanceRepository attendanceRepository,
                             ScheduleRepository scheduleRepository,
                             JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.publication = publication;
        this.attendanceRepository = attendanceRepository;
        this.scheduleRepository = scheduleRepository;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelay = 300_000)
    public void run() {
        process(false);
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void reconcileUpcoming() {
        process(true);
    }

    private void process(boolean upcoming) {
        LocalDateTime now = AttendanceWindow.now();
        Long previous = OrgContext.get();
        try {
            for (Long orgId : jdbc.queryForList(
                    "SELECT id FROM organization WHERE status=1 AND deleted=0 ORDER BY id", Long.class)) {
                OrgContext.set(orgId);
                List<Long> eligible = transactions.execute(status -> scheduleRepository.findByStatus(Schedule.STATUS_PUBLISHED)
                        .stream().filter(s -> eligible(s, now, upcoming)).map(Schedule::getId).toList());
                if (eligible == null) continue;
                for (Long id : eligible) {
                    try {
                        transactions.executeWithoutResult(status -> apply(id, now, upcoming));
                    } catch (RuntimeException e) {
                        log.warn("自动考勤失败 org={} schedule={}", orgId, id, e);
                    }
                }
            }
        } finally {
            if (previous == null) OrgContext.clear();
            else OrgContext.set(previous);
        }
    }

    private void apply(Long id, LocalDateTime now, boolean upcoming) {
        scheduleRepository.lockById(id)
                .filter(s -> Schedule.STATUS_PUBLISHED.equals(s.getStatus()) && eligible(s, AttendanceWindow.now(), upcoming))
                .ifPresent(s -> {
                    if (upcoming) {
                        publication.ensureUpcomingAttendanceRows(s);
                        return;
                    }
                    markAbsent(id, now);
                    s.setStatus(Schedule.STATUS_ENDED);
                    s.setUpdatedAt(now);
                    scheduleRepository.save(s);
                });
    }

    private boolean eligible(Schedule schedule, LocalDateTime now, boolean upcoming) {
        if (schedule.getScheduleDate() == null || schedule.getStartTime() == null || schedule.getEndTime() == null
                || !schedule.getEndTime().isAfter(schedule.getStartTime())) return false;
        return upcoming ? now.isBefore(schedule.getScheduleDate().atTime(schedule.getStartTime()))
                : !now.isBefore(schedule.getScheduleDate().atTime(schedule.getEndTime()));
    }

    private void markAbsent(Long scheduleId, LocalDateTime now) {
        for (Attendance row : attendanceRepository.findByScheduleId(scheduleId)) {
            if (Attendance.SOURCE_MANUAL.equals(row.getStatusSource())
                    || !Attendance.STATUS_PENDING.equals(Attendance.normalizedStatus(row.getStatus()))) continue;
            row.setStatus(Attendance.STATUS_ABSENT);
            row.setRecordTime(now);
            attendanceRepository.save(row);
        }
    }
}
