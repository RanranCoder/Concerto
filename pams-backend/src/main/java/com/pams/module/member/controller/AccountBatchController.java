package com.pams.module.member.controller;

import com.pams.common.BizException;
import com.pams.common.Result;
import com.pams.module.member.dto.AccountBatchVO;
import com.pams.module.member.dto.AccountImportRequest;
import com.pams.module.member.dto.MemberSessionVO;
import com.pams.module.member.service.MemberAccountImportService;
import com.pams.module.member.service.MemberSessionService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/members/account-batches")
@PreAuthorize("hasAuthority('member:import_account') and hasAuthority('user:manage') and hasAuthority('role:assign')")
public class AccountBatchController {
    private final MemberAccountImportService service;
    private final MemberSessionService sessions;

    public AccountBatchController(MemberAccountImportService service, MemberSessionService sessions) {
        this.service = service; this.sessions = sessions;
    }

    @GetMapping("/sessions")
    public Result<List<MemberSessionVO>> sessions() { return Result.ok(sessions.list()); }

    @PostMapping("/previews")
    public Result<AccountBatchVO.Preview> preview(@RequestBody AccountImportRequest request) {
        return Result.ok(service.preview(request));
    }

    @PostMapping("/{id}/apply")
    public Result<AccountBatchVO.Result> apply(@PathVariable String id) {
        try { return Result.ok(service.apply(id)); }
        catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new BizException(409, "账号已被其他操作占用，本批次未创建，请刷新后重新预览");
        }
    }

    @GetMapping
    public Result<List<AccountBatchVO.Result>> recent() { return Result.ok(service.recent()); }

    @PostMapping("/{id}/credentials")
    public ResponseEntity<Result<List<AccountBatchVO.Credential>>> credentials(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.ok(service.credentials(id)));
    }
}
