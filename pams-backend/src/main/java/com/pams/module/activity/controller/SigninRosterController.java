package com.pams.module.activity.controller;

import com.pams.common.Result;
import com.pams.module.activity.dto.SigninFieldConfigRequest;
import com.pams.module.activity.dto.SigninRosterVO;
import com.pams.module.activity.dto.SigninSummaryVO;
import com.pams.module.activity.entity.SigninFieldConfig;
import com.pams.module.activity.service.SigninRosterService;
import com.pams.security.LoginUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/signins")
@PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','SECRETARY_LEADER','ORG_LEADER','MEDIA_LEADER','TECH_LEADER')")
public class SigninRosterController {
    private final SigninRosterService service;
    public SigninRosterController(SigninRosterService service) { this.service = service; }

    /** 应签名单列表，status=ALL/SIGNED/UNSIGNED，每行含 signed 状态 */
    @GetMapping("/roster")
    public Result<List<SigninRosterVO>> roster(@RequestParam Long activityId,
                                               @RequestParam(required = false, defaultValue = "ALL") String status) {
        return Result.ok(service.listRoster(activityId, status));
    }

    /** Excel 上传应签名单：multipart，activityId + file */
    @PostMapping("/roster/upload")
    public Result<Map<String, Integer>> upload(@RequestParam Long activityId,
                                               @RequestParam("file") MultipartFile file) {
        return Result.ok(service.uploadFromXlsx(activityId, file));
    }

    /** 删除名单行 */
    @DeleteMapping("/roster/{id}")
    public Result<Void> deleteRoster(@PathVariable Long id) {
        service.deleteRoster(id);
        return Result.ok();
    }

    /** 应签汇总：expected/signed/unsigned */
    @GetMapping("/roster/summary")
    public Result<SigninSummaryVO> summary(@RequestParam Long activityId) {
        return Result.ok(service.summary(activityId));
    }

    /** 核验字段配置列表 */
    @GetMapping("/fields")
    public Result<List<SigninFieldConfig>> fields(@RequestParam Long activityId) {
        return Result.ok(service.getFields(activityId));
    }

    /** 保存核验字段配置，body {activityId, fields:[{fieldName,fieldKey,required,fieldType,sortOrder}]} */
    @PutMapping("/fields")
    public Result<Void> saveFields(@RequestParam Long activityId,
                                   @RequestBody List<SigninFieldConfigRequest> fields) {
        service.saveFields(activityId, fields);
        return Result.ok();
    }

    /** 手动补签，body {activityId, rosterIds:[]}，operatorId 由当前登录用户取 */
    @PostMapping("/backfill")
    public Result<Integer> backfill(@RequestBody Map<String, Object> body,
                                    @AuthenticationPrincipal LoginUser current) {
        Long activityId = Long.valueOf(body.get("activityId").toString());
        @SuppressWarnings("unchecked")
        List<Number> ids = (List<Number>) body.get("rosterIds");
        List<Long> rosterIds = ids.stream().map(Number::longValue).toList();
        Long operatorId = current == null ? null : current.getId();
        return Result.ok(service.backfill(activityId, rosterIds, operatorId));
    }
}
