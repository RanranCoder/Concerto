package com.pams.module.activity.controller;

import com.pams.common.BizException;
import com.pams.common.Result;
import com.pams.module.activity.dto.GroupUploadResultVO;
import com.pams.module.activity.dto.SignInGroupSummaryVO;
import com.pams.module.activity.dto.SignInGroupVO;
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

    /** 获取名单表头字段列表（从已上传的名单中提取） */
    @GetMapping("/roster/headers")
    public Result<List<String>> rosterHeaders(@RequestParam Long activityId) {
        return Result.ok(service.getRosterHeaders(activityId));
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
        // B7 fix: null 守卫，防止 NPE
        Object rawActivityId = body.get("activityId");
        if (rawActivityId == null) throw new BizException(400, "activityId 不能为空");
        Long activityId = Long.valueOf(rawActivityId.toString());
        @SuppressWarnings("unchecked")
        List<Number> ids = (List<Number>) body.get("rosterIds");
        if (ids == null || ids.isEmpty()) throw new BizException(400, "rosterIds 不能为空");
        List<Long> rosterIds = ids.stream().map(Number::longValue).toList();
        Long operatorId = current == null ? null : current.getId();
        return Result.ok(service.backfill(activityId, rosterIds, operatorId));
    }

    // ===== 名单分组 =====

    /** 分组列表，keyword 跨分组按姓名/学号过滤人员 */
    @GetMapping("/groups")
    public Result<List<SignInGroupVO>> groups(@RequestParam Long activityId,
                                              @RequestParam(required = false) String keyword) {
        return Result.ok(service.listGroups(activityId, keyword));
    }

    /** 分组汇总：total/signed/unsigned/groupCount */
    @GetMapping("/groups/summary")
    public Result<SignInGroupSummaryVO> groupSummary(@RequestParam Long activityId) {
        return Result.ok(service.groupSummary(activityId));
    }

    /** 上传名单（新建分组或并入已有分组）：multipart activityId + file + 可选 groupId */
    @PostMapping("/groups/upload")
    public Result<GroupUploadResultVO> uploadGroup(@RequestParam Long activityId,
                                                   @RequestParam("file") MultipartFile file,
                                                   @RequestParam(required = false) Long groupId) {
        return Result.ok(service.uploadGroupXlsx(activityId, file, groupId));
    }

    /** 分组重命名 */
    @PutMapping("/groups/{id}/rename")
    public Result<Void> renameGroup(@PathVariable Long id, @RequestBody Map<String, String> body) {
        service.renameGroup(id, body.get("groupName"));
        return Result.ok();
    }

    /** 分组排序，body {ids:[]} */
    @PutMapping("/groups/sort")
    public Result<Void> sortGroups(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> ids = (List<Number>) body.get("ids");
        if (ids == null || ids.isEmpty()) throw new BizException(400, "ids 不能为空");
        service.sortGroups(ids.stream().map(Number::longValue).toList());
        return Result.ok();
    }

    /** 删除分组及其名单行 */
    @DeleteMapping("/groups/{id}")
    public Result<Void> deleteGroup(@PathVariable Long id) {
        service.deleteGroup(id);
        return Result.ok();
    }

    /** 批量删除分组，body {ids:[]} */
    @DeleteMapping("/groups/batch")
    public Result<Integer> deleteGroups(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> ids = (List<Number>) body.get("ids");
        if (ids == null || ids.isEmpty()) throw new BizException(400, "ids 不能为空");
        return Result.ok(service.deleteGroups(ids.stream().map(Number::longValue).toList()));
    }

    /** 删除单个名单行（人员） */
    @DeleteMapping("/groups/persons/{rosterId}")
    public Result<Void> deletePerson(@PathVariable Long rosterId) {
        service.deletePerson(rosterId);
        return Result.ok();
    }

    /** 批量删除名单行，body {ids:[]} */
    @DeleteMapping("/groups/persons/batch")
    public Result<Integer> deletePersons(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> ids = (List<Number>) body.get("ids");
        if (ids == null || ids.isEmpty()) throw new BizException(400, "ids 不能为空");
        return Result.ok(service.deletePersons(ids.stream().map(Number::longValue).toList()));
    }
}
