package com.pams.module.schedule.dto;

import lombok.Data;
import java.util.List;

@Data
public class NoClassScheduleImportVO {
    private Long deptId;
    private String deptName;
    private String semester;
    private List<NoClassScheduleRowVO> rows;
    private String markdown;
    private String downloadUrl;
    /** True once this import is eligible for use as the department's scheduling source. */
    private boolean published;
    private int totalFiles;
    private int successCount;
    private List<ImportFileFailureVO> failed;
    private List<String> warnings;
}
