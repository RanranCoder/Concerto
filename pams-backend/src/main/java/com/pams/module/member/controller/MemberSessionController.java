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
// 写操作（建/改/删/设当前届）需 member:manage；届别列表是成员页渲染依赖，
// 下沉到方法级放行 member:view（M12：只配 member:view 的自定义角色不再整页瘫痪）
@PreAuthorize("hasAuthority('member:manage')")
public class MemberSessionController {
    private final MemberSessionService service;
    public MemberSessionController(MemberSessionService service) { this.service = service; }

    @PreAuthorize("hasAuthority('member:view')")
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
