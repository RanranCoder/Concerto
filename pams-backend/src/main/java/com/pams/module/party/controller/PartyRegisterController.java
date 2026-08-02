package com.pams.module.party.controller;

import com.pams.common.Result;
import com.pams.module.party.dto.PartyRegisterRequest;
import com.pams.module.party.entity.PartyRegister;
import com.pams.module.party.service.PartyRecordService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/party/registers")
@PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')")
public class PartyRegisterController {
    private final PartyRecordService service;

    public PartyRegisterController(PartyRecordService service) { this.service = service; }

    @GetMapping
    public Result<List<PartyRegister>> list(@RequestParam(required = false) Long memberId) {
        return Result.ok(service.listRegisters(memberId));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody PartyRegisterRequest req) {
        return Result.ok(service.createRegister(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody PartyRegisterRequest req) {
        service.updateRegister(id, req);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.deleteRegister(id);
        return Result.ok();
    }
}
