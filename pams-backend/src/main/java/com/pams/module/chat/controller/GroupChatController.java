package com.pams.module.chat.controller;

import com.pams.common.Result;
import com.pams.module.chat.dto.GroupChatRequest;
import com.pams.module.chat.service.GroupChatService;
import com.pams.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 群聊管理（PRD F06）
 * 权限（PRD-v5 S2 收敛为 V7 权限码）：chat:view 查看（登录即可），
 * chat:manage 群聊增改归档删（V7 种子：部长级），chat:category 分类管理（V7 种子：仅 TEACHER/DIRECTOR）
 */
@RestController
@RequestMapping("/api/chat")
public class GroupChatController {

    private final GroupChatService service;

    public GroupChatController(GroupChatService service) { this.service = service; }

    // ===== 分类 =====

    @GetMapping("/categories")
    public Result<List<Map<String, Object>>> categories() {
        return Result.ok(service.listCategories());
    }

    @PreAuthorize("hasAuthority('chat:category')")
    @PostMapping("/categories")
    public Result<Long> createCategory(@RequestBody Map<String, String> body) {
        return Result.ok(service.createCategory(body.get("name")));
    }

    @PreAuthorize("hasAuthority('chat:category')")
    @PutMapping("/categories/{id}")
    public Result<Void> renameCategory(@PathVariable Long id, @RequestBody Map<String, String> body) {
        service.renameCategory(id, body.get("name"));
        return Result.ok();
    }

    @PreAuthorize("hasAuthority('chat:category')")
    @DeleteMapping("/categories/{id}")
    public Result<Void> deleteCategory(@PathVariable Long id) {
        service.deleteCategory(id);
        return Result.ok();
    }

    @PreAuthorize("hasAuthority('chat:category')")
    @PutMapping("/categories/sort")
    public Result<Void> sortCategories(@RequestBody Map<String, List<Long>> body) {
        service.sortCategories(body.get("ids"));
        return Result.ok();
    }

    // ===== 群聊 =====

    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) Long categoryId,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String department) {
        return Result.ok(service.list(keyword, categoryId, status, department));
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> get(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    @PreAuthorize("hasAuthority('chat:manage')")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody GroupChatRequest req,
                               @AuthenticationPrincipal LoginUser current) {
        return Result.ok(service.create(current == null ? null : current.getId(), req));
    }

    @PreAuthorize("hasAuthority('chat:manage')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody GroupChatRequest req) {
        service.update(id, req);
        return Result.ok();
    }

    @PreAuthorize("hasAuthority('chat:manage')")
    @PostMapping("/{id}/archive")
    public Result<Void> archive(@PathVariable Long id) {
        service.archive(id);
        return Result.ok();
    }

    @PreAuthorize("hasAuthority('chat:manage')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
