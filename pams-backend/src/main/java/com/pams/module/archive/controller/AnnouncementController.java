package com.pams.module.archive.controller;

import com.pams.common.Result;
import com.pams.module.activity.controller.ActivityController;
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
    private final AnnouncementService service;
    public AnnouncementController(AnnouncementService service) { this.service = service; }

    @GetMapping
    public Result<List<Announcement>> list() {
        return Result.ok(service.list());
    }

    @PreAuthorize(ActivityController.LEADER)
    @PostMapping
    public Result<Announcement> create(@Valid @RequestBody AnnouncementRequest req,
                                       @AuthenticationPrincipal LoginUser current) {
        return Result.ok(service.create(current == null ? null : current.getId(), req));
    }

    @PreAuthorize(ActivityController.LEADER)
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody AnnouncementRequest req) {
        service.update(id, req);
        return Result.ok();
    }

    @PreAuthorize(ActivityController.LEADER)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
