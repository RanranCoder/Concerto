package com.pams.module.permission.service;

import com.pams.common.BizException;
import com.pams.module.activity.repository.ActivityPlanRepository;
import com.pams.module.activity.service.PlanAccessService;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.archive.service.FileService;
import com.pams.module.routine.repository.AttendanceRepository;
import com.pams.module.routine.repository.ScheduleRepository;
import com.pams.repository.UserRepository;
import com.pams.security.LoginUser;
import java.util.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explains a bounded operation, never returns resource contents or impersonates a user. */
@Service
@Transactional(readOnly = true)
public class PermissionExplanationService {
  private final TenantAuthorizationService auth;
  private final UserRepository users;
  private final ActivityPlanRepository plans;
  private final PlanAccessService access;
  private final ScheduleRepository schedules;
  private final AttendanceRepository attendances;
  private final FileService files;

  public PermissionExplanationService(
      TenantAuthorizationService auth,
      UserRepository users,
      ActivityPlanRepository plans,
      PlanAccessService access,
      ScheduleRepository schedules,
      AttendanceRepository attendances,
      FileService files) {
    this.auth = auth;
    this.users = users;
    this.plans = plans;
    this.access = access;
    this.schedules = schedules;
    this.attendances = attendances;
    this.files = files;
  }

  public Map<String, Object> explain(long userId, String operation, long resourceId) {
    auth.effective(userId); // governance authorization and target organization boundary
    if (!Set.of("plan:review", "schedule:publish", "attendance:manage", "file:view")
        .contains(operation)) throw new BizException(400, "仅支持策划审核、排班发布、考勤修正和文件读取校验");
    if (!auth.valid(userId)) return result(false, "账号或组织已停用");
    var permissions = auth.business(userId);
    if (!permissions.contains(operation)) return result(false, "当前业务角色未授予此操作或授权已失效");
    if (operation.equals("plan:review")) {
      var plan = plans.findById(resourceId).orElse(null);
      if (plan == null) return result(false, "对象不存在或不可见");
      if (plan.getReviewers().stream().noneMatch(r -> Objects.equals(r.getUserId(), userId)))
        return result(false, "不是此版本的指定审核人");
      if (!access.isCurrent(plan) || !plan.getStatus().name().equals("PENDING"))
        return result(false, "仅当前待审版本允许审核");
      return result(true, "具有审核资格且为指定审核人，提交时仍重新校验状态");
    }
    if (operation.equals("schedule:publish")) {
      var schedule = schedules.findById(resourceId).orElse(null);
      return schedule == null
          ? result(false, "对象不存在或不可见")
          : result(
              Set.of("DRAFT", "PUBLISHED").contains(Objects.toString(schedule.getStatus(), "")),
              "仅草稿或已发布班次允许发布");
    }
    if (operation.equals("attendance:manage")) {
      var attendance = attendances.findById(resourceId).orElse(null);
      return attendance == null
          ? result(false, "对象不存在或不可见")
          : result(!"CANCELED".equals(attendance.getStatus()), "已取消的记录不可人工修正");
    }
    var u = users.findById(userId).orElseThrow(() -> new BizException(404, "用户不存在"));
    var principal =
        new LoginUser(
            u.getId(),
            u.getUsername(),
            u.getRealName(),
            u.getRole().getCode(),
            u.getRole().getLevel(),
            u.getDept() == null ? null : u.getDept().getId(),
            u.getDept() == null ? null : u.getDept().getName(),
            u.getOrgId());
    var ctx =
        new ToolContext(
            principal,
            permissions.stream().map(SimpleGrantedAuthority::new).toList(),
            null,
            null,
            "STANDARD");
    try {
      files.getReadableRecord(resourceId, ctx);
      return result(true, "当前文件归属、敏感级别和来源权限均允许读取");
    } catch (BizException denied) {
      return result(false, "文件不存在，或文件归属、敏感级别、来源权限不满足");
    }
  }

  private Map<String, Object> result(boolean allowed, String reason) {
    return Map.of(
        "allowed",
        allowed,
        "reason",
        allowed && reason.startsWith("仅") ? "当前允许操作，执行时仍重新校验" : reason);
  }
}
