package com.pams.module.routine.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 无课表保存请求。freeWeeks 为 JSON 文本，如 [1,3,5] 或 {start:1,end:18} */
@Data
public class FreeScheduleRequest {
    private Long userId;
    @NotBlank(message = "姓名不能为空")
    private String personName;
    private String className;
    private Long deptId;
    private String freeWeeks;
    private String note;
}
