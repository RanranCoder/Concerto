package com.pams.module.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UserSaveRequest {
    @NotBlank(message = "用户名不能为空")
    private String username;
    private String password;
    @NotBlank(message = "姓名不能为空")
    private String realName;
    private String studentNo;
    private String phone;
    private Long deptId;
    @NotNull(message = "角色不能为空")
    private Long roleId;
    private Integer status;
}
