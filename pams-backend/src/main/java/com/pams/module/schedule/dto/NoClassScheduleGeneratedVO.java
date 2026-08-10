package com.pams.module.schedule.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class NoClassScheduleGeneratedVO {
    private String deptName;
    private String semester;
    private List<NoClassScheduleRowVO> rows;
    private LocalDateTime createdAt;
}
