package com.pams.module.activity.controller;

import com.pams.common.Result;
import com.pams.module.activity.dto.ScoreRecordRequest;
import com.pams.module.activity.dto.ScoreRuleRequest;
import com.pams.module.activity.service.ScoreService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/scores")
public class ScoreController {
    private final ScoreService service;
    private static final String LEADER = "hasAnyRole('TEACHER','DIRECTOR','SECRETARY_LEADER','ORG_LEADER','MEDIA_LEADER','TECH_LEADER')";

    public ScoreController(ScoreService service) { this.service = service; }

    /** 评分：返回 {rules, records} */
    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam Long activityId) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("rules", service.listRules(activityId));
        vo.put("records", service.listRecords(activityId));
        return Result.ok(vo);
    }

    @PreAuthorize(LEADER)
    @PostMapping("/rules")
    public Result<Long> createRule(@Valid @RequestBody ScoreRuleRequest req) {
        return Result.ok(service.createRule(req));
    }

    @PreAuthorize(LEADER)
    @PostMapping("/records")
    public Result<Long> createRecord(@Valid @RequestBody ScoreRecordRequest req) {
        return Result.ok(service.createRecord(req));
    }

    @PreAuthorize(LEADER)
    @PutMapping("/rules/{id}")
    public Result<Void> updateRule(@PathVariable Long id, @Valid @RequestBody ScoreRuleRequest req) {
        service.updateRule(id, req);
        return Result.ok();
    }

    @PreAuthorize(LEADER)
    @PutMapping("/records/{id}")
    public Result<Void> updateRecord(@PathVariable Long id, @Valid @RequestBody ScoreRecordRequest req) {
        service.updateRecord(id, req);
        return Result.ok();
    }

    @PreAuthorize(LEADER)
    @DeleteMapping("/rules/{id}")
    public Result<Void> deleteRule(@PathVariable Long id) {
        service.deleteRule(id);
        return Result.ok();
    }

    @PreAuthorize(LEADER)
    @DeleteMapping("/records/{id}")
    public Result<Void> deleteRecord(@PathVariable Long id) {
        service.deleteRecord(id);
        return Result.ok();
    }
}
