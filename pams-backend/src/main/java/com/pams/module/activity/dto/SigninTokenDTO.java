package com.pams.module.activity.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SigninTokenDTO {
    private String token;
    private Long activityId;
    private LocalDateTime expiresAt;
}
