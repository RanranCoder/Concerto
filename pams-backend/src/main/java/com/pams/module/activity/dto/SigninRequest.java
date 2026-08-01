package com.pams.module.activity.dto;

import com.pams.module.activity.entity.Signin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SigninRequest {
    @NotNull(message = "活动ID不能为空")
    private Long activityId;
    private Long personId;
    @NotBlank(message = "姓名不能为空")
    private String name;
    private String studentNo;
    private String className;
    private String identityType;
    private Signin.SignType signType;
    private LocalDateTime signTime;
    private String location;
    private String phone;
    private String remark;
}
