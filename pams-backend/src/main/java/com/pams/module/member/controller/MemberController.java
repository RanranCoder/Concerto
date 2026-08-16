package com.pams.module.member.controller;

import com.pams.common.PageResult;
import com.pams.common.Result;
import com.pams.module.member.dto.MemberDetailVO;
import com.pams.module.member.dto.MemberRequest;
import com.pams.module.member.dto.MemberStatsVO;
import com.pams.module.member.dto.MemberVO;
import com.pams.module.member.service.MemberService;
import com.pams.security.LoginUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/members")
@PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')")
public class MemberController {
    private final MemberService service;
    public MemberController(MemberService service) { this.service = service; }

    @GetMapping
    public Result<PageResult<MemberVO>> page(
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String position,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(service.page(sessionId, deptId, position, status, keyword, page, size));
    }

    @GetMapping("/stats")
    public Result<MemberStatsVO> stats(@RequestParam(required = false) Long sessionId) {
        return Result.ok(service.stats(sessionId));
    }

    @GetMapping("/{id}")
    public Result<MemberDetailVO> detail(@PathVariable Long id) {
        return Result.ok(service.detail(id));
    }

    @PostMapping
    public Result<Long> create(@RequestBody MemberRequest req, @AuthenticationPrincipal LoginUser current) {
        return Result.ok(service.create(req, current == null ? null : current.getId()));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody MemberRequest req) {
        service.update(id, req); return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) { service.delete(id); return Result.ok(); }

    @PostMapping("/batch-delete")
    public Result<Void> batchDelete(@RequestBody Map<String, List<Long>> body) {
        service.batchDelete(body.get("ids")); return Result.ok();
    }

    @PostMapping("/{sessionId}/archive")
    public Result<Map<String, Integer>> archive(@PathVariable Long sessionId) {
        return Result.ok(Map.of("count", service.archive(sessionId)));
    }
}
