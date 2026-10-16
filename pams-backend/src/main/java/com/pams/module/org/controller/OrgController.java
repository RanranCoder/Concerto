package com.pams.module.org.controller;

import com.pams.common.Result;
import com.pams.module.org.dto.OrgRegisterRequest;
import com.pams.module.org.dto.OrgUnitVO;
import com.pams.module.org.dto.OrgUpdateRequest;
import com.pams.module.org.dto.UnitSaveRequest;
import com.pams.module.org.entity.OrgInvitation;
import com.pams.module.org.entity.OrgPosition;
import com.pams.module.org.service.OrgRegisterRateLimiter;
import com.pams.module.org.service.OrgService;
import jakarta.servlet.http.HttpServletRequest;
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
    private final OrgRegisterRateLimiter rateLimiter;

    public OrgController(OrgService orgService, OrgRegisterRateLimiter rateLimiter) {
        this.orgService = orgService;
        this.rateLimiter = rateLimiter;
    }

    /** 注册组织（含架构树）。免登录 + 按来源限流。 */
    @PostMapping("/register")
    public Result<Map<String, Object>> register(@RequestBody OrgRegisterRequest req,
                                                HttpServletRequest request) {
        return Result.ok(rateLimiter.register(clientKey(request), () -> orgService.register(req)));
    }

    /** 取来源标识：优先 X-Forwarded-For 首段，其次 remoteAddr。 */
    private String clientKey(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** 组织公开列表（免登录）：登录页组织选择与品牌。仅返回非敏感字段。 */
    @GetMapping("/public")
    public Result<List<Map<String, Object>>> publicList() {
        return Result.ok(orgService.listPublic());
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
    @PreAuthorize("hasAuthority('org:manage')")
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

    /** 岗位列表（架构 → 角色映射）。 */
    @GetMapping("/current/positions")
    public Result<List<OrgPosition>> positions() {
        return Result.ok(orgService.listPositions());
    }

    @PostMapping("/current/units")
    @PreAuthorize("hasAuthority('org:structure:manage')")
    public Result<Long> createUnit(@RequestBody UnitSaveRequest req) {
        return Result.ok(orgService.createUnit(req));
    }

    @PutMapping("/current/units/{id}")
    @PreAuthorize("hasAuthority('org:structure:manage')")
    public Result<Void> updateUnit(@PathVariable Long id, @RequestBody UnitSaveRequest req) {
        orgService.updateUnit(id, req);
        return Result.ok(null);
    }

    @DeleteMapping("/current/units/{id}")
    @PreAuthorize("hasAuthority('org:structure:manage')")
    public Result<Void> deleteUnit(@PathVariable Long id) {
        orgService.deleteUnit(id);
        return Result.ok(null);
    }

    @PostMapping("/current/invitations")
    @PreAuthorize("hasAuthority('org:invite')")
    public Result<OrgInvitation> createInvitation(@RequestBody Map<String, Object> body) {
        String roleCode = (String) body.get("roleCode");
        Long orgUnitId = body.get("orgUnitId") == null ? null : Long.valueOf(body.get("orgUnitId").toString());
        Integer ttlHours = body.get("ttlHours") == null ? null : Integer.valueOf(body.get("ttlHours").toString());
        return Result.ok(orgService.createInvitation(roleCode, orgUnitId, ttlHours));
    }
}
