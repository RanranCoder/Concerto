package com.pams.module.permission.service;

import com.pams.module.permission.entity.Permission;
import com.pams.module.permission.entity.RolePermission;
import com.pams.module.permission.repository.PermissionRepository;
import com.pams.module.permission.repository.RolePermissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class PermissionService {

    private final PermissionRepository permissionRepo;
    private final RolePermissionRepository rolePermissionRepo;

    public PermissionService(PermissionRepository permissionRepo,
                             RolePermissionRepository rolePermissionRepo) {
        this.permissionRepo = permissionRepo;
        this.rolePermissionRepo = rolePermissionRepo;
    }

    /** 权限树：按模块分组（模块 → 权限点列表） */
    public List<Map<String, Object>> permissionTree() {
        List<Permission> all = permissionRepo.findAllByOrderByModuleAscSortOrderAsc();
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Permission p : all) {
            String module = p.getModule() == null ? "other" : p.getModule();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", p.getId());
            item.put("code", p.getCode());
            item.put("name", p.getName());
            item.put("module", module);
            grouped.computeIfAbsent(module, k -> new ArrayList<>()).add(item);
        }
        List<Map<String, Object>> tree = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : grouped.entrySet()) {
            Map<String, Object> moduleNode = new LinkedHashMap<>();
            moduleNode.put("module", e.getKey());
            moduleNode.put("children", e.getValue());
            tree.add(moduleNode);
        }
        return tree;
    }

    /** 获取某个角色的权限码集合 */
    public Set<String> permissionsOfRole(String role) {
        List<RolePermission> rps = rolePermissionRepo.findByRole(role);
        if (rps.isEmpty()) return Set.of();
        Set<Long> ids = rps.stream().map(RolePermission::getPermissionId).collect(Collectors.toSet());
        return permissionRepo.findAllById(ids).stream().map(Permission::getCode).collect(Collectors.toSet());
    }

    /** 获取所有角色的权限映射（权限管理页左侧列表 + 右侧勾选树） */
    public Map<String, Object> rolePermissions() {
        List<Map<String, Object>> roles = new ArrayList<>();
        for (String role : List.of("TEACHER", "DIRECTOR", "ORG_LEADER", "SECRETARY_LEADER", "MEDIA_LEADER", "TECH_LEADER", "STAFF")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", role);
            m.put("permissions", permissionsOfRole(role));
            roles.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roles", roles);
        result.put("tree", permissionTree());
        return result;
    }

    /** 保存某角色的权限配置（全量覆盖） */
    @Transactional
    public void saveRolePermissions(String role, List<String> permissionCodes) {
        // 1. 删除该角色现有映射
        rolePermissionRepo.deleteByRole(role);
        if (permissionCodes == null || permissionCodes.isEmpty()) return;
        // 2. 按权限码查找权限点并重建
        List<Permission> perms = permissionRepo.findAll().stream()
                .filter(p -> permissionCodes.contains(p.getCode()))
                .toList();
        for (Permission p : perms) {
            RolePermission rp = new RolePermission();
            rp.setRole(role);
            rp.setPermissionId(p.getId());
            rolePermissionRepo.save(rp);
        }
    }

    /** 恢复默认权限：清空后按 V7 迁移脚本中的预设方案重建（由调用方在 V7 脚本中定义） */
    @Transactional
    public void restoreDefault() {
        // 简单方案：清空全部自定义映射（预设已固化在数据库，这里删除后重新加载 V7 预设）
        rolePermissionRepo.deleteAll();
        // 重新插入预设（与 V7 迁移脚本一致的核心预设）
        insertDefaultForRole("TEACHER", null, null); // 全部
        insertDefaultForRole("DIRECTOR", null, Set.of("user:permission"));
        for (String leader : List.of("ORG_LEADER", "SECRETARY_LEADER", "MEDIA_LEADER", "TECH_LEADER")) {
            insertDefaultForRole(leader, Set.of(
                    "activity:view","activity:create","activity:edit","activity:review",
                    "checkin:view","checkin:manage","checkin:export",
                    "material:view","material:upload","material:download","material:preview",
                    "template:view","template:use",
                    "seat:view","seat:edit","seat:template",
                    "quality:view","quality:add","quality:activity_add",
                    "chat:view","chat:manage",
                    "notice:view","notice:publish",
                    "schedule:view","schedule:manage","schedule:check","schedule:free_table",
                    "party:view","party:manage","party:letter","party:entry",
                    "notification:view","notification:preference"), null);
        }
        insertDefaultForRole("STAFF", Set.of(
                "activity:view",
                "checkin:view","checkin:manage","checkin:export",
                "material:view","material:upload","material:download","material:preview",
                "template:view","template:use",
                "seat:view","seat:edit",
                "quality:view",
                "chat:view",
                "notice:view",
                "schedule:view","schedule:check","schedule:free_table",
                "party:view",
                "notification:view","notification:preference"), null);
    }

    private void insertDefaultForRole(String role, Set<String> include, Set<String> exclude) {
        List<Permission> all = permissionRepo.findAll();
        for (Permission p : all) {
            if (exclude != null && exclude.contains(p.getCode())) continue;
            if (include != null && !include.contains(p.getCode())) continue;
            RolePermission rp = new RolePermission();
            rp.setRole(role);
            rp.setPermissionId(p.getId());
            rolePermissionRepo.save(rp);
        }
    }
}
