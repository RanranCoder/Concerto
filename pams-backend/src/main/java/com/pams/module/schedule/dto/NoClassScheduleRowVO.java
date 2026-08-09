package com.pams.module.schedule.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class NoClassScheduleRowVO {
    private int period;
    private String label;
    private String halfDay;
    /** day "1".."5" -> 该列人员 */
    private Map<String, List<NoClassScheduleCellVO>> days;
}
