package com.pams.module.user;

import com.pams.common.Result;
import com.pams.entity.Role;
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
    public UserController(UserService userService, RoleRepository roleRepository) {
        this.userService = userService;
        this.roleRepository = roleRepository;
    }

    @PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')")
    @GetMapping
    public Result<com.pams.common.PageResult<Map<String, Object>>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long deptId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal LoginUser current) {
        // 数据权限兜底（Task 26）：按当前用户角色的 dataScope 判断，而非硬编码 STAFF。
        // 角色 dataScope == "DEPT"（如干事 STAFF）时强制只能查看本部门，忽略前端传入的 deptId。
        // 更高层的角色 dataScope == "ALL" 可全量查看，并保留前端部门筛选。
        boolean forceOwnDept = false;
        Long currentDeptId = current == null ? null : current.getDeptId();
        if (current != null) {
            Role role = roleRepository.findByCode(current.getRoleCode()).orElse(null);
            forceOwnDept = userService.isDeptScoped(role);
        }
        return Result.ok(userService.page(keyword, deptId, currentDeptId, forceOwnDept, page, size));
    }

    /** 当前登录用户角色级别，供 createUser / updateUser 防提权校验使用。 */
    @PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody UserSaveRequest req,
                               @AuthenticationPrincipal LoginUser current) {
        Integer currentLevel = current == null ? null : current.getRoleLevel();
        return Result.ok(userService.createUser(req, currentLevel));
    }

    @PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody UserSaveRequest req,
                               @AuthenticationPrincipal LoginUser current) {
        Integer currentLevel = current == null ? null : current.getRoleLevel();
        userService.updateUser(id, req, currentLevel);
        return Result.ok();
    }

    @PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, @AuthenticationPrincipal LoginUser current) {
        Integer currentLevel = current == null ? null : current.getRoleLevel();
        userService.deleteUser(id, currentLevel);
        return Result.ok();
    }

    @PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')")
    @PostMapping("/{id}/reset-password")
    public Result<Void> resetPassword(@PathVariable Long id) {
        userService.resetPassword(id);
        return Result.ok();
    }
}
