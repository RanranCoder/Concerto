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
  private final com.pams.module.permission.service.PermissionExplanationService explanations;

  public RoleManagementController(
      TenantAuthorizationService auth,
      com.pams.module.permission.service.PermissionExplanationService explanations) {
    this.auth = auth;
    this.explanations = explanations;
  }

  @GetMapping("/users/{userId}/permission-explanation")
  public Result<?> explain(
      @PathVariable long userId, @RequestParam String operation, @RequestParam long resourceId) {
    return Result.ok(explanations.explain(userId, operation, resourceId));
  }

  public record Create(
      String name,
      String description,
      Long sourceRoleId,
      String templateCode,
      List<String> templateCodes) {}

  public record Metadata(long version, String name, String description) {}

  public record Draft(long draftVersion, List<Map<String, Object>> permissionChanges) {}

  public record Action(String action, long version, String reason) {}

  public record Assign(long userId, long roleId, String reason) {}

  public record Delegate(
      boolean active,
      Set<String> grantable,
      LocalDateTime expiresAt,
      long version,
      String reason) {}

  @GetMapping("/permissions/catalog")
  public Result<?> catalog() {
    return Result.ok(auth.catalog());
  }

  @GetMapping("/permissions/migration-reviews")
  public Result<?> migrationReviews() {
    return Result.ok(auth.migrationReviews());
  }

  @GetMapping("/orgs/current/management-summary")
  public Result<?> managementSummary() {
    return Result.ok(auth.managementSummary());
  }

  @GetMapping("/orgs/current/audit-log-page")
  public Result<?> auditPage(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "") String keyword,
      @RequestParam(defaultValue = "") String action,
      @RequestParam(defaultValue = "") String from,
      @RequestParam(defaultValue = "") String to) {
    return Result.ok(auth.auditPage(page, size, keyword, action, from, to));
  }

  @PostMapping("/orgs/current/role-delegations/{userId}/change-previews")
  public Result<?> previewDelegation(@PathVariable long userId, @RequestBody Delegate req) {
    return Result.ok(
        auth.previewDelegation(
            userId, req.active(), req.grantable(), req.expiresAt(), req.version(), req.reason()));
  }

  @GetMapping("/roles/{id}")
  public Result<?> detail(@PathVariable long id) {
    return Result.ok(auth.detail(id));
  }

  @PostMapping("/roles")
  public Result<?> create(@RequestBody Create req) {
    return Result.ok(
        auth.createCombined(
            req.name(),
            req.description(),
            req.sourceRoleId(),
            req.templateCode(),
            req.templateCodes()));
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
  public Result<?> delete(
      @PathVariable long id, @RequestParam long version, @RequestParam long draftVersion) {
    auth.deleteDraft(id, version, draftVersion);
    return Result.ok();
  }

  @PutMapping("/roles/{id}/draft")
  public Result<?> draft(@PathVariable long id, @RequestBody Draft req) {
    return Result.ok(auth.saveDraftChanges(id, req.draftVersion(), req.permissionChanges()));
  }

  @GetMapping("/roles/page")
  public Result<?> page(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "") String keyword,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "false") boolean drafts) {
    return Result.ok(auth.rolePage(page, size, keyword, status, drafts));
  }

  @DeleteMapping("/roles/{id}/draft")
  public Result<?> discard(
      @PathVariable long id, @RequestParam long version, @RequestParam long draftVersion) {
    auth.discardDraft(id, version, draftVersion);
    return Result.ok();
  }

  @GetMapping("/roles/{id}/references")
  public Result<?> references(
      @PathVariable long id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return Result.ok(auth.references(id, page, size));
  }

  @GetMapping("/roles/{id}/members")
  public Result<?> members(
      @PathVariable long id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return Result.ok(auth.members(id, page, size));
  }

  @GetMapping("/roles/{id}/history")
  public Result<?> history(
      @PathVariable long id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return Result.ok(auth.history(id, page, size));
  }

  @GetMapping("/roles/assignable")
  public Result<?> assignable() {
    return Result.ok(auth.assignable());
  }

  @PostMapping("/roles/{id}/change-previews")
  public Result<?> preview(@PathVariable long id, @RequestBody Action req) {
    return Result.ok(auth.preview(id, req.action(), req.version(), req.reason()));
  }

  @PostMapping("/users/role-change-previews")
  public Result<?> assignment(@RequestBody Assign req) {
    return Result.ok(auth.previewAssignment(req.userId(), req.roleId(), req.reason()));
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
    auth.delegation(userId);
    throw new com.pams.common.BizException(409, "管理员变更须先生成预览并确认");
  }
}
