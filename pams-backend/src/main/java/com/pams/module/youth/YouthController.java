package com.pams.module.youth;

import com.pams.common.PageResult;
import com.pams.common.Result;
import com.pams.module.org.service.PackWorkspace;
import com.pams.security.LoginUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/youth")
public class YouthController {
    private final YouthService service;
    private final PackWorkspace workspace;

    public YouthController(YouthService service, PackWorkspace workspace) {
        this.service = service;
        this.workspace = workspace;
    }

    public record Version(@jakarta.validation.constraints.NotNull Integer version) {}

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('youth:view')")
    public Result<Map<String, Object>> overview() { return Result.ok(service.overview()); }

    @GetMapping("/choices/{source}")
    @PreAuthorize("hasAuthority('youth:manage')")
    public Result<List<Map<String, Object>>> choices(@PathVariable String source, @RequestParam(defaultValue = "") String keyword) {
        return Result.ok("youth-members".equals(source) ? service.memberChoices(keyword) : workspace.choices(source, keyword));
    }

    @GetMapping("/members")
    @PreAuthorize("hasAuthority('youth:view')")
    public Result<PageResult<Map<String, Object>>> members(@RequestParam(defaultValue = "1") int page) {
        return Result.ok(service.members(page));
    }

    @PostMapping("/members")
    @PreAuthorize("hasAuthority('youth:manage')")
    public Result<Void> addMember(@Valid @RequestBody YouthService.MemberInput input, @AuthenticationPrincipal LoginUser user) {
        service.saveMember(null, input, user.getId());
        return Result.ok();
    }

    @PutMapping("/members/{id}")
    @PreAuthorize("hasAuthority('youth:manage')")
    public Result<Void> editMember(@PathVariable long id, @Valid @RequestBody YouthService.MemberInput input,
                                   @AuthenticationPrincipal LoginUser user) {
        service.saveMember(id, input, user.getId());
        return Result.ok();
    }

    @GetMapping("/members/{id}/history")
    @PreAuthorize("hasAuthority('youth:view')")
    public Result<List<Map<String, Object>>> history(@PathVariable long id) { return Result.ok(service.history(id)); }

    @GetMapping("/life")
    @PreAuthorize("hasAuthority('youth:view')")
    public Result<PageResult<Map<String, Object>>> life(@RequestParam(defaultValue = "1") int page) {
        return Result.ok(service.life(page));
    }

    @PostMapping("/life")
    @PreAuthorize("hasAuthority('youth:manage')")
    public Result<Void> addLife(@Valid @RequestBody YouthService.LifeInput input, Authentication auth) {
        service.saveLife(null, input, auth.getAuthorities().stream().anyMatch(p -> "activity:create".equals(p.getAuthority())));
        return Result.ok();
    }

    @PutMapping("/life/{id}")
    @PreAuthorize("hasAuthority('youth:manage')")
    public Result<Void> editLife(@PathVariable long id, @Valid @RequestBody YouthService.LifeInput input) {
        service.saveLife(id, input, false);
        return Result.ok();
    }

    @PutMapping("/life/{id}/archive")
    @PreAuthorize("hasAuthority('youth:manage')")
    public Result<Void> archiveLife(@PathVariable long id, @Valid @RequestBody Version input, @AuthenticationPrincipal LoginUser user) {
        service.archiveLife(id, input.version(), user.getId());
        return Result.ok();
    }

    @GetMapping("/cases")
    @PreAuthorize("hasAuthority('youth:view')")
    public Result<PageResult<Map<String, Object>>> cases(@RequestParam String kind, @RequestParam(defaultValue = "1") int page) {
        return Result.ok(service.cases(kind, page));
    }

    @PostMapping("/cases")
    @PreAuthorize("hasAuthority('youth:manage')")
    public Result<Void> submitCase(@Valid @RequestBody YouthService.CaseInput input, @AuthenticationPrincipal LoginUser user) {
        service.submitCase(input, user.getId());
        return Result.ok();
    }

    @PutMapping("/cases/{id}/review")
    @PreAuthorize("hasAuthority('youth:review')")
    public Result<Void> review(@PathVariable long id, @Valid @RequestBody YouthService.Review input, @AuthenticationPrincipal LoginUser user) {
        service.review(id, input, user.getId());
        return Result.ok();
    }

    @PutMapping("/cases/{id}/archive")
    @PreAuthorize("hasAuthority('youth:review')")
    public Result<Void> archiveCase(@PathVariable long id, @Valid @RequestBody Version input, @AuthenticationPrincipal LoginUser user) {
        service.archiveCase(id, input.version(), user.getId());
        return Result.ok();
    }

    @PutMapping("/cases/{id}/receive")
    @PreAuthorize("hasAuthority('youth:view') and hasAuthority('party:manage')")
    public Result<Void> receive(@PathVariable long id, @Valid @RequestBody YouthService.Receipt input, @AuthenticationPrincipal LoginUser user) {
        service.receive(id, input, user.getId());
        return Result.ok();
    }

    @GetMapping("/cases/{id}/party-matches")
    @PreAuthorize("hasAuthority('youth:view') and hasAuthority('party:manage')")
    public Result<List<Map<String, Object>>> partyMatches(@PathVariable long id) { return Result.ok(service.partyMatches(id)); }

    @GetMapping("/cases/{id}/history")
    @PreAuthorize("hasAuthority('youth:view')")
    public Result<List<Map<String, Object>>> caseHistory(@PathVariable long id) { return Result.ok(service.caseHistory(id)); }

    @GetMapping("/party-receipts/{id}")
    @PreAuthorize("hasAuthority('youth:view') and hasAuthority('party:manage')")
    public Result<List<Map<String, Object>>> partyReceipts(@PathVariable long id) { return Result.ok(service.partyReceipts(id)); }

    @GetMapping("/cases/{id}/export")
    @PreAuthorize("hasAuthority('youth:review')")
    public Result<Map<String, Object>> export(@PathVariable long id) { return Result.ok(service.exportCase(id)); }
}
