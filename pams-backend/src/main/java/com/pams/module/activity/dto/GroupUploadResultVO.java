package com.pams.module.activity.dto;

import lombok.Data;

@Data
public class GroupUploadResultVO {
    private Long groupId;
    private String groupName;
    private long added;
    private long skipped;
}
