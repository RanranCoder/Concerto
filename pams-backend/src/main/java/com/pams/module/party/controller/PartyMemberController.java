package com.pams.module.party.controller;

import com.pams.common.PageResult;
import com.pams.common.Result;
import com.pams.module.party.dto.PartyMemberRequest;
import com.pams.module.party.dto.PartyStageRequest;
import com.pams.module.party.entity.PartyStage;
import com.pams.module.party.service.PartyMemberService;
import com.pams.security.LoginUser;
import com.pams.security.RoleExprs;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
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
     *
     * <p><b>2026-09-14 补注解（权限审计 A9）</b>：此前无 {@code @PreAuthorize}，
     * 而前端 `/party/*` 路由用 {@code RequireRole(LEADER_ROLES)} 守卫（部长及以上）、
     * 菜单也对干事隐藏。也就是说**干事看不到党务台账页面，却能直连本接口拿到名册**。
     *
     * <p>口径统一为「角色级别」而非权限码 {@code party:view}：后者被授予了 STAFF，
     * 用它表达"部长及以上"是错的——而且会让权限管理页产生新的失败模式
     * （管理员为开放某功能勾了 {@code party:view}，顺带把干事放进了党务台账）。
     * 详见 `docs/permission-audit-2026-09-14.md` §2.3.A9。
     *
     * <p>下面的 {@code staff} 标志保留：注解已经把非领导挡在门外，
     * 但脱敏不应依赖"调用方一定过了注解"这一假设（多一层无害，且将来口径若放开仍正确）。
     */
    @PreAuthorize(RoleExprs.LEADER)
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

    /** 成员详情。注解理由同上——列表与详情必须同口径，否则"列表挡住、详情绕过"。 */
    @PreAuthorize(RoleExprs.LEADER)
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id,
                                              @AuthenticationPrincipal LoginUser current) {
        boolean staff = current == null || "STAFF".equals(current.getRoleCode());
        return Result.ok(service.detail(id, staff));
    }

    /** 写操作：party:manage（V7 种子：部长级；干事 STAFF 仅脱敏查看）。 */
    @PreAuthorize("hasAuthority('party:manage')")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody PartyMemberRequest req) {
        return Result.ok(service.create(req));
    }

    @PreAuthorize("hasAuthority('party:manage')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody PartyMemberRequest req) {
        service.update(id, req);
        return Result.ok();
    }

    @PreAuthorize("hasAuthority('party:manage')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }

    /**
     * 追加流转阶段并更新政治面貌。
     */
    @PreAuthorize("hasAuthority('party:manage')")
    @PutMapping("/{id}/stage")
    public Result<Void> changeStage(@PathVariable Long id, @Valid @RequestBody PartyStageRequest req) {
        service.changeStage(id, req.getStage(), req.getIssueNo(), req.getStartDate(), req.getEndDate(), req.getRemark());
        return Result.ok();
    }

    /**
     * 流转历史。
     *
     * <p>与分页/详情同口径（审计 A9）：流转历史含阶段变更日期与文号，敏感度不低于名册本身。
     * 只挡列表不挡它，等于没挡。
     */
    @PreAuthorize(RoleExprs.LEADER)
    @GetMapping("/stages")
    public Result<List<PartyStage>> stages(@RequestParam Long memberId) {
        return Result.ok(service.stages(memberId));
    }
}
