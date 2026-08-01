package com.pams.module.routine.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** 排班保存请求。persons 为值班人员数组。 */
@Data
public class ScheduleRequest {
    @NotBlank(message = "排班类型不能为空")
    private String scheduleType;
    private Long activityId;
    private Integer weekNo;
    /** 1-7 周一~周日 */
    private Integer weekday;
    private String sessionName;
    private String location;
    private LocalDate scheduleDate;
    private String notes;
    private List<SchedulePersonItem> persons;

    @Data
    public static class SchedulePersonItem {
        private Long userId;
        private String personName;
        /** 1 主班 / 0 副班 */
        private Integer isPrimary;
    }
}
