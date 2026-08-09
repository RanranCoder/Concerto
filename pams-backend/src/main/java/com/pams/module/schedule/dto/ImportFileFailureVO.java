package com.pams.module.schedule.dto;

import lombok.Data;

@Data
public class ImportFileFailureVO {
    private String fileName;
    private String reason;
}
