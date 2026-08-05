package com.pams.module.activity.dto;

import lombok.Data;

import java.util.Map;

@Data
public class GroupPersonVO {
    private Long id;
    private Long groupId;
    private Map<String, String> fields;
    private boolean signed;
}
