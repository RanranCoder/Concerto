package com.pams.module.permission.repository;

import com.pams.module.permission.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PermissionRepository extends JpaRepository<Permission, Long> {
    List<Permission> findByModuleOrderBySortOrderAsc(String module);
    List<Permission> findAllByOrderByModuleAscSortOrderAsc();
}
