package com.pams.module.user;

import com.pams.common.BizException;
import com.pams.common.Result;
import com.pams.entity.Role;
import com.pams.module.user.dto.ChangePasswordRequest;
import com.pams.module.user.dto.UserSaveRequest;
import com.pams.repository.RoleRepository;
import com.pams.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService userService;
    private final RoleRepository roleRepository;
    private final UserAccountStatusService accountStatusService;
    public UserController(UserService userService, RoleRepository roleRepository, UserAccountStatusService accountStatusService) {
        this.userService = userService;
        this.roleRepository = roleRepository;
        this.accountStatusService = accountStatusService;
    }

    /** 用户选项（选人组件数据源，登录即可）：仅 id/姓名/部门/角色最小字段（PRD-v5 F5） */
    @GetMapping("/options")
    public Result<java.util.List<Map<String, Object>>> options() {
        return Result.ok(userService.options());
    }

    /** 用户列表：由当前有效治理身份的 user:view 控制。 */
    @PreAuthorize("hasAuthority('user:view')")
    @GetMapping
    public Result<com.pams.common.PageResult<Map<String, Object>>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String accountStatus,
            @RequestParam(required = false) Long roleId,
            @RequestParam(required = false) String managementSource,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal LoginUser current) {
        // 首版治理委派固定管理全组织普通账号，与主业务角色的旧 dataScope 分开。
        return Result.ok(userService.page(keyword, deptId, null, false, accountStatus, roleId, managementSource, page, size));
    }

    @PreAuthorize("hasAuthority('user:view')")
    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() { return Result.ok(userService.summary()); }

    @PreAuthorize("hasAuthority('user:manage')")
    @PostMapping("/status-changes")
    public Result<java.util.List<Map<String, Object>>> statusChanges(@Valid @RequestBody com.pams.module.user.dto.UserStatusRequest request) {
        return Result.ok(accountStatusService.apply(request));
    }

    /** 创建用户：user:manage（V7 种子：仅 TEACHER/DIRECTOR；级别防提权校验在 Service 层）。 */
    @PreAuthorize("hasAuthority('user:manage')")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody UserSaveRequest req,
                               @AuthenticationPrincipal LoginUser current) {
        Integer currentLevel = current == null ? null : current.getRoleLevel();
        return Result.ok(userService.createUser(req, currentLevel));
    }

    @PreAuthorize("hasAuthority('user:manage')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody UserSaveRequest req,
                               @AuthenticationPrincipal LoginUser current) {
        Integer currentLevel = current == null ? null : current.getRoleLevel();
        userService.updateUser(id, req, currentLevel);
        return Result.ok();
    }

    @PreAuthorize("hasAuthority('user:manage')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, @AuthenticationPrincipal LoginUser current) {
        Integer currentLevel = current == null ? null : current.getRoleLevel();
        userService.deleteUser(id, currentLevel);
        return Result.ok();
    }

    @PreAuthorize("hasAuthority('user:manage')")
    @PostMapping("/{id}/reset-password")
    public Result<Void> resetPassword(@PathVariable Long id,
                                      @AuthenticationPrincipal LoginUser current) {
        Integer currentLevel = current == null ? null : current.getRoleLevel();
        userService.resetPassword(id, currentLevel);
        return Result.ok();
    }

    /** 当前登录用户自行修改密码（头像下拉入口） */
    @PostMapping("/change-password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req,
                                       @AuthenticationPrincipal LoginUser current) {
        if (current == null) {
            throw new BizException(401, "未登录");
        }
        userService.changePassword(current.getId(), req.getOldPassword(), req.getNewPassword());
        return Result.ok();
    }
}
