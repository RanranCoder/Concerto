package com.pams.module.permission.controller;

import com.pams.common.Result;
import com.pams.module.permission.service.TenantAuthorizationService;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RoleManagementController {
  private final TenantAuthorizationService auth;

  public RoleManagementController(TenantAuthorizationService auth) {
    this.auth = auth;
  }

  public record Create(String name, String description, Long sourceRoleId, String templateCode) {}

  public record Metadata(long version, String name, String description) {}

  public record Draft(long draftVersion, List<Map<String, Object>> permissionChanges) {}

  public record Action(String action, long version) {}

  public record Assign(long userId, long roleId) {}

  public record Delegate(
      boolean active, Set<String> grantable, LocalDateTime expiresAt, long version) {}

  @GetMapping("/permissions/catalog")
  public Result<?> catalog() {
    return Result.ok(auth.catalog());
  }

  @GetMapping("/roles/{id}")
  public Result<?> detail(@PathVariable long id) {
    return Result.ok(auth.detail(id));
  }

  @PostMapping("/roles")
  public Result<?> create(@RequestBody Create req) {
    return Result.ok(
        auth.create(req.name(), req.description(), req.sourceRoleId(), req.templateCode()));
  }

  @GetMapping("/roles/templates")
  public Result<?> templates() {
    return Result.ok(auth.templates());
  }

  @PutMapping("/roles/{id}")
  public Result<?> metadata(@PathVariable long id, @RequestBody Metadata req) {
    auth.metadata(id, req.version(), req.name(), req.description());
    return Result.ok();
  }

  @DeleteMapping("/roles/{id}")
  public Result<?> delete(@PathVariable long id) {
    auth.deleteDraft(id);
    return Result.ok();
  }

  @PutMapping("/roles/{id}/draft")
  public Result<?> draft(@PathVariable long id, @RequestBody Draft req) {
    return Result.ok(auth.saveDraftChanges(id, req.draftVersion(), req.permissionChanges()));
  }

  @GetMapping("/roles/assignable")
  public Result<?> assignable() {
    return Result.ok(auth.assignable());
  }

  @PostMapping("/roles/{id}/change-previews")
  public Result<?> preview(@PathVariable long id, @RequestBody Action req) {
    return Result.ok(auth.preview(id, req.action(), req.version()));
  }

  @PostMapping("/users/role-change-previews")
  public Result<?> assignment(@RequestBody Assign req) {
    return Result.ok(auth.previewAssignment(req.userId(), req.roleId()));
  }

  @PostMapping("/orgs/current/authorization-changes/{id}/apply")
  public Result<?> apply(@PathVariable String id) {
    return Result.ok(auth.apply(id));
  }

  @GetMapping("/orgs/current/authorization-changes/{id}")
  public Result<?> change(@PathVariable String id) {
    return Result.ok(auth.change(id));
  }

  @GetMapping("/users/{id}/effective-permissions")
  public Result<?> effective(@PathVariable long id) {
    return Result.ok(auth.effective(id));
  }

  @GetMapping("/orgs/current/audit-logs")
  public Result<?> audit() {
    return Result.ok(auth.auditLogs());
  }

  @GetMapping("/orgs/current/role-delegations/{userId}")
  public Result<?> delegation(@PathVariable long userId) {
    return Result.ok(auth.delegation(userId));
  }

  @PutMapping("/orgs/current/role-delegations/{userId}")
  public Result<?> delegate(@PathVariable long userId, @RequestBody Delegate req) {
    auth.delegate(userId, req.active(), req.grantable(), req.expiresAt(), req.version());
    return Result.ok();
  }
}
