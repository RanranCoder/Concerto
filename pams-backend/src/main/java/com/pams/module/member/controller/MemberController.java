package com.pams.module.member.controller;

import com.pams.common.BizException;
import com.pams.common.PageResult;
import com.pams.common.Result;
import com.pams.module.member.dto.AccountImportRequest;
import com.pams.module.member.dto.AccountImportResultVO;
import com.pams.module.member.dto.MemberDetailVO;
import com.pams.module.member.dto.MemberImportResultVO;
import com.pams.module.member.dto.MemberRequest;
import com.pams.module.member.dto.MemberStatsVO;
import com.pams.module.member.dto.MemberVO;
import com.pams.module.member.dto.UnregisteredMemberVO;
import com.pams.module.member.service.MemberAccountImportService;
import com.pams.module.member.service.MemberImportService;
import com.pams.module.member.service.MemberService;
import com.pams.security.LoginUser;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/members")
@PreAuthorize("hasAuthority('member:view')")
public class MemberController {
    private final MemberService service;
    private final MemberImportService importService;
    private final MemberAccountImportService accountImportService;
    public MemberController(MemberService service, MemberImportService importService,
                            MemberAccountImportService accountImportService) {
        this.service = service; this.importService = importService;
        this.accountImportService = accountImportService;
    }

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

    @PreAuthorize("hasAuthority('member:manage')")
    @PostMapping
    public Result<Long> create(@RequestBody MemberRequest req, @AuthenticationPrincipal LoginUser current) {
        return Result.ok(service.create(req, current == null ? null : current.getId()));
    }

    @PreAuthorize("hasAuthority('member:manage')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody MemberRequest req) {
        service.update(id, req); return Result.ok();
    }

    @PreAuthorize("hasAuthority('member:manage')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) { service.delete(id); return Result.ok(); }

    @PreAuthorize("hasAuthority('member:manage')")
    @PostMapping("/batch-delete")
    public Result<Void> batchDelete(@RequestBody Map<String, List<Long>> body) {
        service.batchDelete(body.get("ids")); return Result.ok();
    }

    @PreAuthorize("hasAuthority('member:manage')")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<MemberImportResultVO> importMembers(@RequestParam("sessionId") Long sessionId,
                                                      @RequestParam("file") MultipartFile file) {
        try (InputStream in = new ByteArrayInputStream(file.getBytes())) {
            return Result.ok(importService.importFromXlsx(in, sessionId));
        } catch (IOException e) { throw new BizException(4001, "名单文件解析失败"); }
    }

    @PreAuthorize("hasAuthority('member:export')")
    @GetMapping("/import/template")
    public ResponseEntity<Resource> template() throws IOException {
        byte[] data = importService.buildTemplate();
        return xlsxResponse(data, "成员导入模板.xlsx");
    }

    @PreAuthorize("hasAuthority('member:export')")
    @GetMapping("/export")
    public ResponseEntity<Resource> export(@RequestParam(required = false) Long sessionId,
                                           @RequestParam(required = false) Long deptId,
                                           @RequestParam(required = false) String position,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String keyword) throws IOException {
        byte[] data = importService.exportXlsx(sessionId, deptId, position, status, keyword);
        return xlsxResponse(data, "成员花名册.xlsx");
    }

    @PreAuthorize("hasAuthority('member:manage')")
    @PostMapping("/{sessionId}/archive")
    public Result<Map<String, Integer>> archive(@PathVariable Long sessionId) {
        return Result.ok(Map.of("count", service.archive(sessionId)));
    }

    @PreAuthorize("hasAuthority('member:import_account')")
    @GetMapping("/unregistered")
    public Result<List<UnregisteredMemberVO>> unregistered(@RequestParam(required = false) Long sessionId) {
        return Result.ok(accountImportService.unregistered(sessionId));
    }

    @PreAuthorize("hasAuthority('member:import_account')")
    @PostMapping("/import-accounts")
    public Result<AccountImportResultVO> importAccounts(@RequestBody AccountImportRequest req,
                                                        @AuthenticationPrincipal LoginUser current) {
        return Result.ok(accountImportService.importAccounts(req,
                current == null ? null : current.getId(),
                current == null ? null : current.getRoleLevel()));
    }

    private ResponseEntity<Resource> xlsxResponse(byte[] data, String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .body(new ByteArrayResource(data));
    }
}
