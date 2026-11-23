package com.pams.module.routine.service;

import com.pams.module.routine.entity.Attendance;
import com.pams.module.routine.entity.Schedule;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** Server-side check-in window shared by self check-in, activity QR, and settlement. */
public final class AttendanceWindow {
    private AttendanceWindow() {}

    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0);
    }

    public static boolean isOpen(Schedule schedule, LocalDateTime now) {
        return Schedule.STATUS_PUBLISHED.equals(schedule.getStatus())
                && schedule.getScheduleDate() != null
                && schedule.getStartTime() != null && schedule.getEndTime() != null
                && !now.isBefore(schedule.getScheduleDate().atTime(schedule.getStartTime()).minusMinutes(30))
                && now.isBefore(schedule.getScheduleDate().atTime(schedule.getEndTime()));
    }

    public static String status(Schedule schedule, LocalDateTime time) {
        return time.isAfter(schedule.getScheduleDate().atTime(schedule.getStartTime()))
                ? Attendance.STATUS_LATE : Attendance.STATUS_NORMAL;
    }
}
