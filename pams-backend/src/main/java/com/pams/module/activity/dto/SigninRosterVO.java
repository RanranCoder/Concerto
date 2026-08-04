package com.pams.module.activity.dto;

import lombok.Data;

import java.util.Map;

@Data
public class SigninRosterVO {
    private Long id;
    private Long activityId;
    private Map<String, String> fields;
    private boolean signed;
}
