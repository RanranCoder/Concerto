package com.pams.module.party.controller;

import com.pams.common.PageResult;
import com.pams.common.Result;
import com.pams.module.party.dto.PartyMemberRequest;
import com.pams.module.party.dto.PartyStageRequest;
import com.pams.module.party.entity.PartyStage;
import com.pams.module.party.service.PartyMemberService;
import com.pams.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/party/members")
public class PartyMemberController {
    private final PartyMemberService service;

    public PartyMemberController(PartyMemberService service) { this.service = service; }

    /**
     * 成员分页。干事（STAFF）只能看到脱敏基础字段，不返回身份证/家庭地址/电话。
     */
    @GetMapping
    public Result<PageResult<Map<String, Object>>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String stage,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal LoginUser current) {
        boolean staff = current == null || "STAFF".equals(current.getRoleCode());
        return Result.ok(service.page(keyword, stage, page, size, staff));
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id,
                                              @AuthenticationPrincipal LoginUser current) {
        boolean staff = current == null || "STAFF".equals(current.getRoleCode());
        return Result.ok(service.detail(id, staff));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody PartyMemberRequest req) {
        return Result.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody PartyMemberRequest req) {
        service.update(id, req);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }

    /**
     * 追加流转阶段并更新政治面貌。
     */
    @PutMapping("/{id}/stage")
    public Result<Void> changeStage(@PathVariable Long id, @Valid @RequestBody PartyStageRequest req) {
        service.changeStage(id, req.getStage(), req.getIssueNo(), req.getStartDate(), req.getEndDate(), req.getRemark());
        return Result.ok();
    }

    /**
     * 流转历史。
     */
    @GetMapping("/stages")
    public Result<List<PartyStage>> stages(@RequestParam Long memberId) {
        return Result.ok(service.stages(memberId));
    }
}
