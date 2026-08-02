package com.pams.module.party.controller;

import com.pams.common.Result;
import com.pams.module.party.dto.PartyInvestigationRequest;
import com.pams.module.party.entity.PartyInvestigation;
import com.pams.module.party.service.PartyRecordService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/party/investigations")
@PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')")
public class PartyInvestigationController {
    private final PartyRecordService service;

    public PartyInvestigationController(PartyRecordService service) { this.service = service; }

    @GetMapping
    public Result<List<PartyInvestigation>> list(@RequestParam(required = false) Long memberId) {
        return Result.ok(service.listInvestigations(memberId));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody PartyInvestigationRequest req) {
        return Result.ok(service.createInvestigation(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody PartyInvestigationRequest req) {
        service.updateInvestigation(id, req);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.deleteInvestigation(id);
        return Result.ok();
    }
}
