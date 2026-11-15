package com.pams.module.volunteer;

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
@RequestMapping("/api/volunteer")
public class VolunteerController {
    private final VolunteerService service;
    private final PackWorkspace workspace;

    public VolunteerController(VolunteerService service, PackWorkspace workspace) {
        this.service = service;
        this.workspace = workspace;
    }

    @GetMapping("/choices/{source}")
    @PreAuthorize("hasAuthority('volunteer:manage')")
    public Result<List<Map<String, Object>>> choices(@PathVariable String source, @RequestParam(defaultValue = "") String keyword) {
        if (!List.of("members", "activities").contains(source)) throw new com.pams.common.BizException(400, "不支持的查询类型");
        return Result.ok(workspace.choices(source, keyword));
    }

    @GetMapping("/activities")
    @PreAuthorize("hasAuthority('volunteer:view')")
    public Result<PageResult<Map<String, Object>>> activities(@RequestParam(defaultValue = "1") int page) {
        return Result.ok(service.activities(page));
    }

    @PostMapping("/activities")
    @PreAuthorize("hasAuthority('volunteer:manage')")
    public Result<Void> addActivity(@Valid @RequestBody VolunteerService.ActivityInput input, Authentication auth) {
        service.addActivity(input, auth.getAuthorities().stream().anyMatch(p -> "activity:create".equals(p.getAuthority())));
        return Result.ok();
    }

    @GetMapping("/activities/{id}")
    @PreAuthorize("hasAuthority('volunteer:view')")
    public Result<Map<String, Object>> details(@PathVariable long id, @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(required = false) String status) {
        return Result.ok(service.details(id, page, status));
    }

    @PostMapping("/activities/{id}/registrations")
    @PreAuthorize("hasAuthority('volunteer:manage')")
    public Result<Void> register(@PathVariable long id, @Valid @RequestBody VolunteerService.Registration input) {
        service.register(id, input);
        return Result.ok();
    }

    @PutMapping("/records/{id}/submit")
    @PreAuthorize("hasAuthority('volunteer:manage')")
    public Result<Void> submit(@PathVariable long id, @Valid @RequestBody VolunteerService.Submission input,
                               @AuthenticationPrincipal LoginUser user) {
        service.submit(id, input, user.getId());
        return Result.ok();
    }

    @PutMapping("/records/{id}/review")
    @PreAuthorize("hasAuthority('volunteer:review')")
    public Result<Void> review(@PathVariable long id, @Valid @RequestBody VolunteerService.Review input,
                               @AuthenticationPrincipal LoginUser user) {
        service.review(id, input, user.getId());
        return Result.ok();
    }

    @GetMapping("/activities/{id}/export")
    @PreAuthorize("hasAuthority('volunteer:review')")
    public Result<List<Map<String, Object>>> export(@PathVariable long id) { return Result.ok(service.approvedRecords(id)); }
}
