package com.pams.module.permission.repository;

import com.pams.module.permission.entity.RolePermission;
import com.pams.module.permission.entity.RolePermissionId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermissionId> {
    List<RolePermission> findByRole(String role);
    void deleteByRole(String role);
    List<RolePermission> findByPermissionIdIn(java.util.Collection<Long> permissionIds);
}
