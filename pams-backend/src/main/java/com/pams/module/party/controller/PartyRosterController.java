package com.pams.module.party.controller;

import com.pams.common.Result;
import com.pams.module.party.dto.PartyRosterRequest;
import com.pams.module.party.entity.PartyRoster;
import com.pams.module.party.service.PartyRecordService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/party/rosters")
@PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')")
public class PartyRosterController {
    private final PartyRecordService service;

    public PartyRosterController(PartyRecordService service) { this.service = service; }

    @GetMapping
    public Result<List<PartyRoster>> list(@RequestParam(required = false) String type,
                                          @RequestParam(required = false) String issueNo) {
        return Result.ok(service.listRosters(type, issueNo));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody PartyRosterRequest req) {
        return Result.ok(service.createRoster(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody PartyRosterRequest req) {
        service.updateRoster(id, req);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.deleteRoster(id);
        return Result.ok();
    }
}
