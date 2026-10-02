package com.pams.module.routine.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** 手动创建/编辑活动排班请求。persons 复用 ScheduleRequest.SchedulePersonItem。 */
@Data
public class ActivityScheduleSaveRequest {
    private Long activityId;
    private LocalDate scheduleDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String location;
    private String note;
    private List<ScheduleRequest.SchedulePersonItem> persons;
}