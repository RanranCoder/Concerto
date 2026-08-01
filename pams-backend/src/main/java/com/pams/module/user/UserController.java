package com.pams.module.user;

import com.pams.common.Result;
import com.pams.module.user.dto.UserSaveRequest;
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
    public UserController(UserService userService) { this.userService = userService; }

    @PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')")
    @GetMapping
    public Result<com.pams.common.PageResult<Map<String, Object>>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long deptId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal LoginUser current) {
        // 数据权限兜底：干事（STAFF）强制只能查看本部门，忽略前端传入的 deptId。
        // Task 26 将改为按 role.dataScope 判断（Role.getDataScope() == "DEPT"），UserService.isDeptScoped 可复用。
        boolean forceOwn = current != null && userService.isStaff(current.getRoleCode());
        return Result.ok(userService.page(keyword, deptId, current == null ? null : current.getDeptId(),
                                          forceOwn, page, size));
    }

    @PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody UserSaveRequest req) {
        return Result.ok(userService.createUser(req));
    }

    @PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody UserSaveRequest req) {
        userService.updateUser(id, req);
        return Result.ok();
    }

    @PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        userService.deleteUser(id);
        return Result.ok();
    }

    @PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')")
    @PostMapping("/{id}/reset-password")
    public Result<Void> resetPassword(@PathVariable Long id) {
        userService.resetPassword(id);
        return Result.ok();
    }
}
