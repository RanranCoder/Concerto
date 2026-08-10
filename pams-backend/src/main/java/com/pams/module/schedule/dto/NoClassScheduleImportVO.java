package com.pams.module.schedule.dto;

import lombok.Data;
import java.util.List;

@Data
public class NoClassScheduleImportVO {
    private String deptName;
    private String semester;
    private List<NoClassScheduleRowVO> rows;
    private String markdown;
    private String downloadUrl;
    private int totalFiles;
    private int successCount;
    private List<ImportFileFailureVO> failed;
    private List<String> warnings;
}
