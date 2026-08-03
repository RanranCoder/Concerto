package com.pams.module.activity.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SigninTokenDTO {
    private String token;
    private Long activityId;
    private LocalDateTime expiresAt;
    /** 扫码 URL：{origin}/signin/{token}，由 controller 依据请求 origin 拼好 */
    private String qrContent;
}
