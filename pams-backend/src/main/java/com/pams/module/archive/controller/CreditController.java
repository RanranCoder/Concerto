package com.pams.module.archive.controller;

import com.pams.common.BizException;
import com.pams.common.PageResult;
import com.pams.common.Result;
import com.pams.module.archive.dto.CreditRequest;
import com.pams.module.archive.entity.CreditRecord;
import com.pams.module.archive.service.CreditService;
import com.pams.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/credits")
public class CreditController {
    private final CreditService service;
    public CreditController(CreditService service) { this.service = service; }

    @GetMapping
    public Result<PageResult<Map<String, Object>>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long activityId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(service.page(keyword, userId, activityId, page, size));
    }

    @PostMapping
    public Result<CreditRecord> create(@Valid @RequestBody CreditRequest req,
                                       @AuthenticationPrincipal LoginUser current) {
        return Result.ok(service.create(current == null ? null : current.getId(), req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody CreditRequest req) {
        service.update(id, req);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }

    /** 活动批量加分，body {sourceActivityId, project, credit, remark, people:[{personName,studentNo}]} */
    @PostMapping("/activity-batch")
    public Result<Map<String, Integer>> activityBatch(@RequestBody Map<String, Object> body,
                                                      @AuthenticationPrincipal LoginUser current) {
        Object rawSource = body.get("sourceActivityId");
        if (rawSource == null) throw new BizException(400, "sourceActivityId 不能为空");
        Long sourceActivityId = Long.valueOf(rawSource.toString());
        String project = body.get("project") == null ? null : body.get("project").toString();
        java.math.BigDecimal credit = body.get("credit") == null
                ? null : new java.math.BigDecimal(body.get("credit").toString());
        String remark = body.get("remark") == null ? null : body.get("remark").toString();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> people = (List<Map<String, String>>) body.get("people");
        Long operatorId = current == null ? null : current.getId();
        return Result.ok(service.batchAddFromActivity(sourceActivityId, project, credit, remark, people, operatorId));
    }

    /** 按批次撤回活动批量加分 */
    @DeleteMapping("/batch/{batchId}")
    public Result<Integer> batchRollback(@PathVariable String batchId) {
        return Result.ok(service.batchRollback(batchId));
    }
}
