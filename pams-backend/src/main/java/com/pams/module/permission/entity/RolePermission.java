package com.pams.module.permission.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.io.Serializable;

/**
 * 角色-权限关联（PRD F07.2 RBAC）
 */
@Data
@Entity
@Table(name = "role_permission")
@IdClass(RolePermissionId.class)
public class RolePermission implements Serializable {
    @Id
    @Column(length = 20)
    private String role;

    @Id
    @Column(name = "permission_id")
    private Long permissionId;
}
