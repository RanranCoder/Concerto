package com.pams.module.org.controller;

import com.pams.common.Result;
import com.pams.module.org.dto.OrgRegisterRequest;
import com.pams.module.org.dto.OrgUnitVO;
import com.pams.module.org.dto.OrgUpdateRequest;
import com.pams.module.org.dto.UnitSaveRequest;
import com.pams.module.org.entity.OrgInvitation;
import com.pams.module.org.service.OrgService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组织注册与组织架构。
 *
 * <p>阶段 1：注册免登录（SecurityConfig permitAll）；组织信息读取登录即可；
 * 架构与组织信息修改限 ADMIN（TEACHER / DIRECTOR，与 RoleExprs.ADMIN 一致）。
 */
@RestController
@RequestMapping("/api/orgs")
public class OrgController {

    private final OrgService orgService;

    public OrgController(OrgService orgService) {
        this.orgService = orgService;
    }

    /** 注册组织（含架构树）。免登录。 */
    @PostMapping("/register")
    public Result<Map<String, Object>> register(@RequestBody OrgRegisterRequest req) {
        return Result.ok(orgService.register(req));
    }

    /** 当前组织信息（品牌 / 术语 / 领域包）。 */
    @GetMapping("/current")
    public Result<Map<String, Object>> current() {
        var org = orgService.currentOrg();
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", org.getId());
        vo.put("name", org.getName());
        vo.put("type", org.getType());
        vo.put("shortName", org.getShortName());
        vo.put("logoUrl", org.getLogoUrl());
        vo.put("themeJson", org.getThemeJson());
        vo.put("enabledPacks", org.getEnabledPacks());
        vo.put("terminologyJson", org.getTerminologyJson());
        return Result.ok(vo);
    }

    /** 修改当前组织信息。限 ADMIN。 */
    @PutMapping("/current")
    @PreAuthorize("hasAnyRole('TEACHER','DIRECTOR')")
    public Result<Void> updateCurrent(@RequestBody OrgUpdateRequest req) {
        orgService.updateCurrent(req.getName(), req.getShortName(), req.getLogoUrl(),
                req.getThemeColor(), req.getEnabledPacks());
        return Result.ok(null);
    }

    /** 架构树。 */
    @GetMapping("/current/units")
    public Result<List<OrgUnitVO>> units() {
        return Result.ok(orgService.listUnits());
    }

    @PostMapping("/current/units")
    @PreAuthorize("hasAnyRole('TEACHER','DIRECTOR')")
    public Result<Long> createUnit(@RequestBody UnitSaveRequest req) {
        return Result.ok(orgService.createUnit(req));
    }

    @PutMapping("/current/units/{id}")
    @PreAuthorize("hasAnyRole('TEACHER','DIRECTOR')")
    public Result<Void> updateUnit(@PathVariable Long id, @RequestBody UnitSaveRequest req) {
        orgService.updateUnit(id, req);
        return Result.ok(null);
    }

    @DeleteMapping("/current/units/{id}")
    @PreAuthorize("hasAnyRole('TEACHER','DIRECTOR')")
    public Result<Void> deleteUnit(@PathVariable Long id) {
        orgService.deleteUnit(id);
        return Result.ok(null);
    }

    @PostMapping("/current/invitations")
    @PreAuthorize("hasAnyRole('TEACHER','DIRECTOR')")
    public Result<OrgInvitation> createInvitation(@RequestBody Map<String, Object> body) {
        String roleCode = (String) body.get("roleCode");
        Long orgUnitId = body.get("orgUnitId") == null ? null : Long.valueOf(body.get("orgUnitId").toString());
        Integer ttlHours = body.get("ttlHours") == null ? null : Integer.valueOf(body.get("ttlHours").toString());
        return Result.ok(orgService.createInvitation(roleCode, orgUnitId, ttlHours));
    }
}
