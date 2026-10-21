package com.pams.module.member.controller;

import com.pams.common.Result;
import com.pams.module.member.service.MemberAccountImportService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/account-setup")
public class AccountSetupController {
    private final MemberAccountImportService service;
    public AccountSetupController(MemberAccountImportService service) { this.service = service; }
    public record Setup(String token, String password) {}

    @PostMapping
    public ResponseEntity<Result<Void>> setup(@RequestBody Setup request) {
        service.setupPassword(request.token(), request.password());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.ok());
    }
}
