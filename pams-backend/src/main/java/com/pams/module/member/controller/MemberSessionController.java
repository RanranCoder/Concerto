package com.pams.module.member.controller;

import com.pams.common.Result;
import com.pams.module.member.dto.MemberSessionRequest;
import com.pams.module.member.dto.MemberSessionVO;
import com.pams.module.member.service.MemberSessionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/member-sessions")
@PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')")
public class MemberSessionController {
    private final MemberSessionService service;
    public MemberSessionController(MemberSessionService service) { this.service = service; }

    @GetMapping
    public Result<List<MemberSessionVO>> list() { return Result.ok(service.list()); }

    @PostMapping
    public Result<Long> create(@RequestBody MemberSessionRequest req) { return Result.ok(service.create(req)); }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody MemberSessionRequest req) {
        service.update(id, req); return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) { service.delete(id); return Result.ok(); }

    @PostMapping("/{id}/set-current")
    public Result<Void> setCurrent(@PathVariable Long id) { service.setCurrent(id); return Result.ok(); }
}
