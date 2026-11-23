package com.pams.module.schedule.dto;

import lombok.Data;

@Data
public class ImportFileFailureVO {
    /** 文件在 multipart files 列表中的下标，从 0 开始。 */
    private int fileIndex;
    private String fileName;
    private String reason;
}
