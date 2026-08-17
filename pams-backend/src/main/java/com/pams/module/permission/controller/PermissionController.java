package com.pams.module.permission.controller;

import com.pams.common.Result;
import com.pams.module.permission.service.PermissionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/permissions")
public class PermissionController {

    private final PermissionService service;

    public PermissionController(PermissionService service) { this.service = service; }

    /** 权限树 + 各角色映射（权限管理页） */
    @PreAuthorize("hasAuthority('user:permission')")
    @GetMapping
    public Result<Map<String, Object>> rolePermissions() {
        return Result.ok(service.rolePermissions());
    }

    /** 保存某角色的权限配置 */
    @PreAuthorize("hasAuthority('user:permission')")
    @PutMapping("/roles/{role}")
    public Result<Void> saveRole(@PathVariable String role, @RequestBody List<String> permissionCodes) {
        service.saveRolePermissions(role, permissionCodes);
        return Result.ok();
    }

    /** 恢复默认权限 */
    @PreAuthorize("hasAuthority('user:permission')")
    @PostMapping("/restore-default")
    public Result<Void> restoreDefault() {
        service.restoreDefault();
        return Result.ok();
    }
}
