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
    /** 所属组织（租户）。阶段 1 只用于写入归属，不参与查询过滤。 */
    private Long orgId;
}
