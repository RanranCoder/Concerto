package com.pams.module.org.service;

import com.pams.common.BizException;
import com.pams.common.OrgContext;
import com.pams.entity.Department;
import com.pams.entity.Role;
import com.pams.entity.User;
import com.pams.module.org.dto.OrgRegisterRequest;
import com.pams.module.org.dto.OrgUnitVO;
import com.pams.module.org.dto.UnitSaveRequest;
import com.pams.module.org.entity.OrgInvitation;
import com.pams.module.org.entity.OrgPosition;
import com.pams.module.org.entity.Organization;
import com.pams.module.org.repository.OrgInvitationRepository;
import com.pams.module.org.repository.OrgPositionRepository;
import com.pams.module.org.repository.OrganizationRepository;
import com.pams.repository.DepartmentRepository;
import com.pams.repository.RoleRepository;
import com.pams.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 组织注册与组织架构（阶段 1）。
 *
 * <p>「复用而非新建」：角色/权限沿用全局 {@code sys_role}/{@code role_permission} 与
 * {@code PermissionService}；组织架构就是演进后的 {@code sys_department}。
 */
@Service
public class OrgService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.pams.module.org.repository.OrgStructureReferences structureReferences;

    private static final Set<String> UNIT_TYPES = Set.of("部门", "中心", "小组", "分队");
    @org.springframework.beans.factory.annotation.Autowired
    private com.pams.module.permission.service.TenantAuthorizationService authorization;

    private final OrganizationRepository organizationRepository;
    private final DepartmentRepository departmentRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final OrgInvitationRepository invitationRepository;
    private final OrgPositionRepository positionRepository;
    private final PasswordEncoder passwordEncoder;

    public OrgService(OrganizationRepository organizationRepository,
                      DepartmentRepository departmentRepository,
                      RoleRepository roleRepository,
                      UserRepository userRepository,
                      OrgInvitationRepository invitationRepository,
                      OrgPositionRepository positionRepository,
                      PasswordEncoder passwordEncoder) {
        this.organizationRepository = organizationRepository;
        this.departmentRepository = departmentRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.invitationRepository = invitationRepository;
        this.positionRepository = positionRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // ==================== 注册 ====================

    /**
     * 注册组织：创建组织 + 架构树 + 管理员账号。匿名可调（SecurityConfig permitAll），
     * 因此所有新建实体的 orgId 必须显式设置（不能依赖 TenantFillListener 的默认组织兜底）。
     */
    @Transactional
    public Map<String, Object> register(OrgRegisterRequest req) {
        if (req == null || req.getOrg() == null || isBlank(req.getOrg().getName())) {
            throw new BizException(2001, "组织名称不能为空");
        }
        if (req.getAdmin() == null || isBlank(req.getAdmin().getUsername())
                || isBlank(req.getAdmin().getPassword())) {
            throw new BizException(2002, "管理员账号与密码不能为空");
        }
        String orgName = req.getOrg().getName().trim();
        validateOrgFields(orgName, req.getOrg().getShortName(), req.getOrg().getThemeColor());
        if (req.getUnits() == null || req.getUnits().isEmpty())
            throw new BizException(2007, "请至少添加一个部门或小组");
        validateRegistrationUnits(req.getUnits(), new LinkedHashSet<>());
        if (organizationRepository.findByName(orgName).isPresent()) {
            throw new BizException(2003, "组织名称已存在");
        }
        if (userRepository.existsByUsername(req.getAdmin().getUsername().trim())) {
            throw new BizException(2004, "管理员用户名已存在");
        }

        // 1. 组织
        Organization org = new Organization();
        org.setName(orgName);
        org.setType(isBlank(req.getOrg().getType()) ? Organization.TYPE_OTHER : req.getOrg().getType());
        org.setShortName(req.getOrg().getShortName());
        org.setThemeJson(isBlank(req.getOrg().getThemeColor())
                ? null : "{\"primaryColor\":\"" + req.getOrg().getThemeColor() + "\"}");
        org.setEnabledPacks(toJsonArray(req.getEnabledPacks()));
        org.setStatus(1);
        org.setDeleted(0);
        org.setCreatedAt(LocalDateTime.now());
        org.setUpdatedAt(LocalDateTime.now());
        org = organizationRepository.save(org);
        Long orgId = org.getId();
        if (authorization != null) authorization.initializeOrg(orgId);

        // 2/3. 新组织的架构与管理员属于「新租户」。多租户判别器按 OrgContext 取值，
        //      故在供给期临时把租户上下文切到新组织，写完恢复（A3）。
        Long previousOrg = OrgContext.get();
        OrgContext.set(orgId);
        User admin;
        try {
            Department rootUnit = null;
            if (req.getUnits() != null) {
                int i = 0;
                for (OrgRegisterRequest.UnitNode node : req.getUnits()) {
                    Department saved = saveUnitRecursive(orgId, null, null, node, i++);
                    if (rootUnit == null) {
                        rootUnit = saved;
                    }
                }
            }
            initPositions(orgId, req.getUnits());

            Role director = roleRepository.findAll().stream()
                    .filter(r -> orgId.equals(r.getOrgId()))
                    .filter(r -> "DIRECTOR".equals(r.getCode()))
                    .findFirst()
                    .orElseThrow(() -> new BizException(2005, "系统角色 DIRECTOR 不存在，请先初始化基础数据"));
            admin = new User();
            admin.setUsername(req.getAdmin().getUsername().trim());
            admin.setPassword(passwordEncoder.encode(req.getAdmin().getPassword()));
            admin.setRealName(isBlank(req.getAdmin().getRealName())
                    ? req.getAdmin().getUsername().trim() : req.getAdmin().getRealName());
            admin.setPhone(req.getAdmin().getPhone());
            admin.setRole(director);
            admin.setDept(rootUnit);
            admin.setStatus(1);
            admin.setOrgId(orgId);
            admin.setDeleted(0L);
            admin.setCreatedAt(LocalDateTime.now());
            admin.setUpdatedAt(LocalDateTime.now());
            userRepository.save(admin);
        } finally {
            if (previousOrg == null) {
                OrgContext.clear();
            } else {
                OrgContext.set(previousOrg);
            }
        }

        org.setOwnerUserId(admin.getId());
        organizationRepository.save(org);
        if (authorization != null) authorization.markProvisioned(orgId);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("orgId", orgId);
        resp.put("orgName", orgName);
        resp.put("adminUsername", admin.getUsername());
        return resp;
    }

    private Department saveUnitRecursive(Long orgId, Long parentId, String parentPath,
                                         OrgRegisterRequest.UnitNode node, int sortOrder) {
        if (node == null || isBlank(node.getName())) {
            return null;
        }
        Department d = new Department();
        d.setOrgId(orgId);
        d.setParentId(parentId);
        d.setName(node.getName().trim());
        d.setUnitType(isBlank(node.getUnitType()) ? "部门" : node.getUnitType());
        d.setLevel(parentPath == null ? 1 : parentPath.split("/").length);
        d.setLeaderUserId(node.getLeaderUserId());
        d.setSortOrder(sortOrder);
        d.setCreatedAt(LocalDateTime.now());
        d.setUpdatedAt(LocalDateTime.now());
        d = departmentRepository.save(d);
        // 落定 id 后回填物化路径
        String path = (parentPath == null ? "" : parentPath) + "/" + d.getId();
        d.setPath(path);
        d = departmentRepository.save(d);
        if (node.getChildren() != null) {
            int i = 0;
            for (OrgRegisterRequest.UnitNode child : node.getChildren()) {
                saveUnitRecursive(orgId, d.getId(), path, child, i++);
            }
        }
        return d;
    }

    // ==================== 当前组织 ====================

    public Organization currentOrg() {
        return organizationRepository.findById(OrgContext.getOrDefault())
                .orElseThrow(() -> new BizException(2006, "组织不存在"));
    }

    /**
     * 组织公开列表（免登录）：登录页组织选择与品牌用。
     * 仅暴露非敏感字段——不含 ownerUserId / status / 时间戳等内部信息。
     */
    public List<Map<String, Object>> listPublic() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Organization o : organizationRepository.findByStatusOrderByIdAsc(1)) {
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("id", o.getId());
            vo.put("name", o.getName());
            vo.put("type", o.getType());
            vo.put("shortName", o.getShortName());
            vo.put("logoUrl", o.getLogoUrl());
            vo.put("themeJson", o.getThemeJson());
            list.add(vo);
        }
        return list;
    }

    @Transactional
    public void updateCurrent(String name, String shortName, String logoUrl, String themeColor,
                              List<String> enabledPacks) {
        Organization org = currentOrg();
        validateOrgFields(name == null ? org.getName() : name.trim(), shortName, themeColor);
        if (name != null && organizationRepository.findByName(name.trim())
                .filter(existing -> !existing.getId().equals(org.getId())).isPresent())
            throw new BizException(2003, "组织名称已存在");
        if (!isBlank(name)) {
            org.setName(name.trim());
        }
        org.setShortName(shortName);
        org.setLogoUrl(logoUrl);
        if (!isBlank(themeColor)) {
            org.setThemeJson("{\"primaryColor\":\"" + themeColor + "\"}");
        }
        if (enabledPacks != null) {
            if (authorization != null) authorization.updatePacks(org.getId(), enabledPacks.contains("party"));
            org.setEnabledPacks(toJsonArray(enabledPacks));
        }
        org.setUpdatedAt(LocalDateTime.now());
        organizationRepository.save(org);
    }

    // ==================== 架构树 ====================

    public List<OrgUnitVO> listUnits() {
        Long orgId = OrgContext.getOrDefault();
        List<Department> all = departmentRepository.findByOrgIdOrderBySortOrderAscIdAsc(orgId);
        return buildTree(all);
    }

    @Transactional(readOnly = true)
    public com.pams.module.org.dto.OrgStructureVO structure() {
        Long orgId = OrgContext.getOrDefault();
        var accounts = userRepository.findByOrgIdOrderByIdAsc(orgId).stream().map(u -> {
            Long deptId = u.getDept() != null && orgId.equals(u.getDept().getOrgId()) ? u.getDept().getId() : null;
            String roleName = u.getRole() != null && orgId.equals(u.getRole().getOrgId()) ? u.getRole().getName() : null;
            return new com.pams.module.org.dto.OrgAccountVO(u.getId(), u.getUsername(), u.getRealName(), deptId, roleName, u.getStatus());
        }).toList();
        return new com.pams.module.org.dto.OrgStructureVO(listUnits(), accounts);
    }

    @Transactional
    public Long createUnit(UnitSaveRequest req) {
        if (req == null || isBlank(req.getName())) {
            throw new BizException(2007, "节点名称不能为空");
        }
        Long orgId = OrgContext.getOrDefault();
        String name = req.getName().trim();
        validateUnitFields(name, req.getUnitType(), req.getSortOrder());
        validateLeader(orgId, req.getLeaderUserId());
        if (departmentRepository.existsByOrgIdAndName(orgId, name)) {
            throw new BizException(2008, "同组织下已存在同名节点");
        }
        Department parent = null;
        if (req.getParentId() != null) {
            parent = departmentRepository.findById(req.getParentId())
                    .orElseThrow(() -> new BizException(2009, "上级节点不存在"));
            if (!orgId.equals(parent.getOrgId())) {
                throw new BizException(2010, "上级节点不属于当前组织");
            }
        }
        Department d = new Department();
        d.setOrgId(orgId);
        d.setParentId(req.getParentId());
        d.setName(name);
        d.setUnitType(isBlank(req.getUnitType()) ? "部门" : req.getUnitType());
        d.setLeaderUserId(req.getLeaderUserId());
        d.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());
        d.setLevel(parent == null ? 1 : (parent.getLevel() == null ? 1 : parent.getLevel()) + 1);
        d.setCreatedAt(LocalDateTime.now());
        d.setUpdatedAt(LocalDateTime.now());
        d = departmentRepository.save(d);
        d.setPath((parent == null || parent.getPath() == null ? "" : parent.getPath()) + "/" + d.getId());
        return departmentRepository.save(d).getId();
    }

    @Transactional
    public void updateUnit(Long id, UnitSaveRequest req) {
        Long orgId = OrgContext.getOrDefault();
        Department d = departmentRepository.findById(id)
                .orElseThrow(() -> new BizException(2009, "节点不存在"));
        if (!orgId.equals(d.getOrgId())) {
            throw new BizException(2010, "节点不属于当前组织");
        }
        if (req == null) throw new BizException(400, "请填写节点信息");
        validateUnitFields(req.getName() == null ? d.getName() : req.getName().trim(), req.getUnitType(), req.getSortOrder());
        if (req.getParentId() != null && !req.getParentId().equals(d.getParentId()))
            throw new BizException(400, "编辑节点时不能更换上级节点");
        if (!java.util.Objects.equals(req.getLeaderUserId(), d.getLeaderUserId()))
            validateLeader(orgId, req.getLeaderUserId());
        if (!isBlank(req.getName()) && !req.getName().trim().equals(d.getName())) {
            if (departmentRepository.existsByOrgIdAndName(orgId, req.getName().trim())) {
                throw new BizException(2008, "同组织下已存在同名节点");
            }
            structureReferences.renameDepartmentLabels(orgId, id, d.getName(), req.getName().trim());
            d.setName(req.getName().trim());
        }
        if (!isBlank(req.getUnitType())) {
            d.setUnitType(req.getUnitType());
        }
        d.setLeaderUserId(req.getLeaderUserId());
        if (req.getSortOrder() != null) {
            d.setSortOrder(req.getSortOrder());
        }
        d.setUpdatedAt(LocalDateTime.now());
        departmentRepository.save(d);
    }

    @Transactional
    public void deleteUnit(Long id) {
        Long orgId = OrgContext.getOrDefault();
        Department d = departmentRepository.findById(id)
                .orElseThrow(() -> new BizException(2009, "节点不存在"));
        if (!orgId.equals(d.getOrgId())) {
            throw new BizException(2010, "节点不属于当前组织");
        }
        if (departmentRepository.existsByParentId(id)) {
            throw new BizException(2011, "请先删除下级节点");
        }
        if (structureReferences.hasAccounts(id)) {
            throw new BizException(2012, "该节点下还有成员或历史账号，无法删除；请先处理账号归属");
        }
        if (structureReferences.hasHistory(orgId, id, d.getName()))
            throw new BizException(2013, "该节点有关联花名册或历史工作资料，请保留节点或先处理关联记录");
        departmentRepository.delete(d);
    }

    private void validateLeader(Long orgId, Long userId) {
        if (userId == null) return;
        User leader = userRepository.findById(userId).filter(u -> orgId.equals(u.getOrgId()))
                .orElseThrow(() -> new BizException(400, "负责人必须是当前组织的账号"));
        if (!Integer.valueOf(1).equals(leader.getStatus()))
            throw new BizException(400, "请选择正常启用的账号作为负责人");
    }

    private void validateUnitFields(String name, String type, Integer sort) {
        if (isBlank(name) || name.length() > 50) throw new BizException(2007, "节点名称须为 1—50 个字");
        if (type != null && !UNIT_TYPES.contains(type)) throw new BizException(400, "节点类型请选择部门、中心、小组或分队");
        if (sort != null && (sort < 0 || sort > 9999)) throw new BizException(400, "显示排序须为 0—9999 的整数");
    }

    private void validateRegistrationUnits(List<OrgRegisterRequest.UnitNode> nodes, Set<String> names) {
        for (var node : nodes) {
            if (node == null) throw new BizException(2007, "请填写每个部门或小组的名称");
            String name = node.getName() == null ? "" : node.getName().trim();
            validateUnitFields(name, node.getUnitType(), null);
            if (!names.add(name)) throw new BizException(2008, "「" + name + "」名称重复，请使用不同的名称");
            if (node.getLeaderUserId() != null) throw new BizException(400, "请在组织创建后选择负责人");
            if (node.getChildren() != null) validateRegistrationUnits(node.getChildren(), names);
        }
    }

    private void validateOrgFields(String name, String shortName, String color) {
        if (isBlank(name) || name.length() > 100) throw new BizException(2001, "组织名称须为 1—100 个字");
        if (shortName != null && shortName.length() > 50) throw new BizException(400, "组织简称请控制在 50 字以内");
        if (color != null && !color.matches("(?i)^#[0-9a-f]{6}$")) throw new BizException(400, "主题色请使用 #RRGGBB 格式");
    }

    // ==================== 岗位（架构 → 角色映射） ====================

    /**
     * 按架构节点类型生成岗位（org_position）。「架构 → 角色」的默认模板：
     * 中心 → DIRECTOR、部门 → ORG_LEADER、小组/分队 → STAFF。
     * 复用全局内置角色，不新建角色体系（见策划 §2.3.4）。
     */
    private void initPositions(Long orgId, List<OrgRegisterRequest.UnitNode> units) {
        Set<String> types = new LinkedHashSet<>();
        collectUnitTypes(units, types);
        int sort = 0;
        savePosition(orgId, "总负责人", 5, "DIRECTOR", sort++);
        for (String t : types) {
            savePosition(orgId, t + "负责人", levelOfUnitType(t), roleOfUnitType(t), sort++);
        }
    }

    private void collectUnitTypes(List<OrgRegisterRequest.UnitNode> nodes, Set<String> out) {
        if (nodes == null) {
            return;
        }
        for (OrgRegisterRequest.UnitNode n : nodes) {
            out.add(isBlank(n.getUnitType()) ? "部门" : n.getUnitType());
            collectUnitTypes(n.getChildren(), out);
        }
    }

    private String roleOfUnitType(String unitType) {
        return switch (unitType) {
            case "中心" -> "DIRECTOR";
            case "部门" -> "ORG_LEADER";
            default -> "STAFF";
        };
    }

    private int levelOfUnitType(String unitType) {
        return switch (unitType) {
            case "中心" -> 4;
            case "部门" -> 3;
            default -> 1;
        };
    }

    private void savePosition(Long orgId, String name, int level, String roleCode, int sortOrder) {
        OrgPosition p = new OrgPosition();
        p.setOrgId(orgId);
        p.setName(name);
        p.setLevel(level);
        p.setRoleCode(roleCode);
        p.setSortOrder(sortOrder);
        positionRepository.save(p);
    }

    /** 当前组织的岗位列表。 */
    public List<OrgPosition> listPositions() {
        return positionRepository.findByOrgIdOrderBySortOrderAsc(OrgContext.getOrDefault());
    }

    // ==================== 邀请 ====================

    @Transactional
    public OrgInvitation createInvitation(String roleCode, Long orgUnitId, Integer ttlHours) {
        Long orgId = OrgContext.getOrDefault();
        Role invitedRole = roleRepository.findByCodeAndOrgId(isBlank(roleCode) ? "STAFF" : roleCode,orgId)
            .orElseThrow(() -> new BizException(404,"角色不存在"));
        if (authorization != null) authorization.verifyAssignableRole(invitedRole.getId());
        if (orgUnitId != null && departmentRepository.findById(orgUnitId).filter(d -> orgId.equals(d.getOrgId())).isEmpty()) throw new BizException(404,"组织节点不存在");
        if (ttlHours != null && (ttlHours < 1 || ttlHours > 720)) throw new BizException(400,"邀请有效期须为 1—720 小时");
        OrgInvitation inv = new OrgInvitation();
        inv.setOrgId(orgId);
        inv.setCode(UUID.randomUUID().toString().replace("-", ""));
        inv.setRoleCode(isBlank(roleCode) ? "STAFF" : roleCode);
        inv.setOrgUnitId(orgUnitId);
        inv.setExpireAt(LocalDateTime.now().plusHours(ttlHours == null ? 72 : ttlHours));
        inv.setCreatedAt(LocalDateTime.now());
        return invitationRepository.save(inv);
    }

    // ==================== 工具 ====================

    private List<OrgUnitVO> buildTree(List<Department> all) {
        Map<Long, OrgUnitVO> map = new LinkedHashMap<>();
        for (Department d : all) {
            map.put(d.getId(), toVO(d));
        }
        List<OrgUnitVO> roots = new ArrayList<>();
        for (Department d : all) {
            OrgUnitVO vo = map.get(d.getId());
            if (d.getParentId() != null && map.containsKey(d.getParentId())) {
                map.get(d.getParentId()).getChildren().add(vo);
            } else {
                roots.add(vo);
            }
        }
        return roots;
    }

    private OrgUnitVO toVO(Department d) {
        OrgUnitVO vo = new OrgUnitVO();
        vo.setId(d.getId());
        vo.setParentId(d.getParentId());
        vo.setName(d.getName());
        vo.setUnitType(d.getUnitType());
        vo.setCode(d.getCode());
        vo.setLevel(d.getLevel());
        vo.setPath(d.getPath());
        vo.setLeaderUserId(d.getLeaderUserId());
        vo.setSortOrder(d.getSortOrder());
        return vo;
    }

    private String toJsonArray(List<String> packs) {
        if (packs == null || packs.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < packs.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(packs.get(i).replace("\"", "")).append("\"");
        }
        return sb.append("]").toString();
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
