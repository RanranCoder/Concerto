package com.pams.module.permission.service;

import com.pams.common.BizException;
import com.pams.common.OrgContext;
import com.pams.repository.RoleRepository;
import java.util.*;
import org.springframework.stereotype.Service;

/** Legacy reads are tenant-bound; global role-code writes are disabled. */
@Service
public class PermissionService {
  private final TenantAuthorizationService auth;
  private final RoleRepository roles;

  public PermissionService(TenantAuthorizationService auth, RoleRepository roles) {
    this.auth = auth;
    this.roles = roles;
  }

  public Set<String> permissionsOfRole(String code) {
    return roles
        .findByCodeAndOrgId(code, OrgContext.getOrDefault())
        .map(r -> auth.rolePermissions(r.getOrgId(), r.getId()))
        .orElse(Set.of());
  }

  public Set<String> permissionsOfUser(long uid) {
    return auth.authorities(uid);
  }

  public boolean validUser(long uid) {
    return auth.valid(uid);
  }

  public Map<String, Object> rolePermissions() {
    return Map.of("roles", auth.list(), "tree", auth.catalog());
  }

  public void saveRolePermissions(String role, List<String> codes) {
    throw new BizException(409, "旧全局授权入口已关闭，请使用角色变更预览");
  }

  public void restoreDefault() {
    throw new BizException(409, "全局恢复已关闭，请对本组织单个角色应用模板");
  }
}
