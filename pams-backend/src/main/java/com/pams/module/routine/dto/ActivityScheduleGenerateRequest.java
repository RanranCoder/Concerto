package com.pams.module.routine.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** 一键生成活动排班请求。 */
@Data
public class ActivityScheduleGenerateRequest {
    /** 选择现有活动时的活动 id；自定义活动时为空 */
    private Long activityId;
    /** 自定义活动名称（activityId 为空时创建活动） */
    private String customActivityName;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalTime startTime;
    private LocalTime endTime;
    /** 参与部门（多选） */
    private List<Long> deptIds;
    /** 每个时间段需要的人数；为空或<=0 表示不限制（取全部候选） */
    private Integer peoplePerSlot;
    private String semester;
    /** 开学（第一周周一）日期；可为空（空则跳过周次过滤） */
    private LocalDate semesterStartDate;
}