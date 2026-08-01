package com.pams.security;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginUser {
    private Long id;
    private String username;
    private String realName;
    private String roleCode;
    private Integer roleLevel;
    private Long deptId;
    private String deptName;
}
