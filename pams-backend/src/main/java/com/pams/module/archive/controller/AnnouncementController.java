package com.pams.module.archive.controller;

import com.pams.common.Result;
import com.pams.module.archive.dto.AnnouncementRequest;
import com.pams.module.archive.entity.Announcement;
import com.pams.module.archive.service.AnnouncementService;
import com.pams.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/announcements")
public class AnnouncementController {
    @org.springframework.beans.factory.annotation.Autowired
    private com.pams.module.archive.service.AnnouncementDeliveryService delivery;
    @GetMapping("/{id}/confirmation")
    public Result<java.util.List<java.util.Map<String,Object>>> confirmation(@PathVariable long id,@AuthenticationPrincipal LoginUser user) {
        return Result.ok(delivery.receipts(id,user.getId()));
    }
    public record Confirmation(int version) {}
    @PostMapping("/{id}/confirm")
    public Result<Void> confirm(@PathVariable long id,@RequestBody Confirmation request,@AuthenticationPrincipal LoginUser user) {
        delivery.confirm(id,request.version(),user.getId());return Result.ok();
    }
    private final AnnouncementService service;
    public AnnouncementController(AnnouncementService service) { this.service = service; }

    @GetMapping
    public Result<List<Announcement>> list() {
        return Result.ok(service.list());
    }

    /** 发布/修改公告：notice:publish（V7 种子：部长级及以上） */
    @PreAuthorize("hasAuthority('notice:publish')")
    @PostMapping
    public Result<Announcement> create(@Valid @RequestBody AnnouncementRequest req,
                                       @AuthenticationPrincipal LoginUser current) {
        return Result.ok(service.create(current == null ? null : current.getId(), req));
    }

    @PreAuthorize("hasAuthority('notice:publish')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody AnnouncementRequest req) {
        service.update(id, req);
        return Result.ok();
    }

    /** 删除公告：notice:manage（V7 种子：仅 TEACHER/DIRECTOR） */
    @PreAuthorize("hasAuthority('notice:manage')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }

    /** 标记公告已读（幂等）。已读状态落库，跨设备一致（PRD-v5 F4） */
    @PostMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id,
                                 @AuthenticationPrincipal LoginUser current) {
        if (current == null) {
            return Result.ok();
        }
        service.markRead(id, current.getId());
        return Result.ok();
    }

    /** 当前用户已读公告 id 列表 */
    @GetMapping("/read-ids")
    public Result<List<Long>> readIds(@AuthenticationPrincipal LoginUser current) {
        if (current == null) {
            return Result.ok(List.of());
        }
        return Result.ok(service.readIds(current.getId()));
    }
}
