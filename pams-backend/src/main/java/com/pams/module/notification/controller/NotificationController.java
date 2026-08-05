package com.pams.module.notification.controller;

import com.pams.common.PageResult;
import com.pams.common.Result;
import com.pams.module.notification.dto.NotificationPreferenceVO;
import com.pams.module.notification.dto.NotificationVO;
import com.pams.module.notification.entity.NotificationType;
import com.pams.module.notification.service.NotificationService;
import com.pams.security.LoginUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<NotificationVO>> list(@AuthenticationPrincipal LoginUser user) {
        List<NotificationVO> list = service.findForUser(user.getId(), user.getRoleCode(), user.getDeptId());
        if (list.size() > 50) {
            list = list.subList(0, 50);
        }
        return Result.ok(list);
    }

    @GetMapping("/page")
    public Result<PageResult<NotificationVO>> page(@RequestParam(required = false) String type,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "10") int size,
                                                   @AuthenticationPrincipal LoginUser user) {
        NotificationType typeEnum = null;
        if (type != null && !type.isBlank()) {
            try {
                typeEnum = NotificationType.valueOf(type);
            } catch (IllegalArgumentException ignored) {
                typeEnum = null;
            }
        }
        return Result.ok(service.pageForUser(user.getId(), user.getRoleCode(), user.getDeptId(), typeEnum, page, size));
    }

    @GetMapping("/unread-count")
    public Result<Long> unreadCount(@AuthenticationPrincipal LoginUser user) {
        return Result.ok(service.countUnreadForUser(user.getId(), user.getRoleCode(), user.getDeptId()));
    }

    @PutMapping("/{id}/read")
    public Result<Void> markAsRead(@PathVariable Long id, @AuthenticationPrincipal LoginUser user) {
        service.markAsRead(id, user.getId());
        return Result.ok();
    }

    @PutMapping("/read-all")
    public Result<Void> markAllAsRead(@AuthenticationPrincipal LoginUser user) {
        service.markAllAsRead(user.getId(), user.getRoleCode(), user.getDeptId());
        return Result.ok();
    }

    @GetMapping("/preferences")
    public Result<List<NotificationPreferenceVO>> getPreferences(@AuthenticationPrincipal LoginUser user) {
        return Result.ok(service.getPreferences(user.getId()));
    }

    @PutMapping("/preferences")
    public Result<Void> savePreferences(@RequestBody List<NotificationPreferenceVO> prefs,
                                        @AuthenticationPrincipal LoginUser user) {
        service.savePreferences(user.getId(), prefs);
        return Result.ok();
    }
}
