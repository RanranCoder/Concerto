package com.pams.module.party.controller;

import com.pams.common.Result;
import com.pams.module.party.dto.PartyTransferRequest;
import com.pams.module.party.entity.PartyTransfer;
import com.pams.module.party.service.PartyRecordService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/party/transfers")
@PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')")
public class PartyTransferController {
    private final PartyRecordService service;

    public PartyTransferController(PartyRecordService service) { this.service = service; }

    @GetMapping
    public Result<List<PartyTransfer>> list(@RequestParam(required = false) Long memberId) {
        return Result.ok(service.listTransfers(memberId));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody PartyTransferRequest req) {
        return Result.ok(service.createTransfer(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody PartyTransferRequest req) {
        service.updateTransfer(id, req);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.deleteTransfer(id);
        return Result.ok();
    }
}
