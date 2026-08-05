package com.pams.module.activity.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class SignInGroupVO {
    private Long id;
    private Long activityId;
    private String groupName;
    private String sourceFilename;
    private Integer sortOrder;
    private long count;
    private long signedCount;
    private long unsignedCount;
    private LocalDateTime createdAt;
    private List<GroupPersonVO> people;
}
