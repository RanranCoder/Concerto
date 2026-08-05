package com.pams.module.activity.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.BizException;
import com.pams.module.activity.dto.GroupPersonVO;
import com.pams.module.activity.dto.GroupUploadResultVO;
import com.pams.module.activity.dto.SignInGroupSummaryVO;
import com.pams.module.activity.dto.SignInGroupVO;
import com.pams.module.activity.dto.SigninFieldConfigRequest;
import com.pams.module.activity.dto.SigninRosterVO;
import com.pams.module.activity.dto.SigninSummaryVO;
import com.pams.module.activity.entity.SignInGroup;
import com.pams.module.activity.entity.Signin;
import com.pams.module.activity.entity.SigninFieldConfig;
import com.pams.module.activity.entity.SigninRoster;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.activity.repository.SignInGroupRepository;
import com.pams.module.activity.repository.SigninFieldConfigRepository;
import com.pams.module.activity.repository.SigninRepository;
import com.pams.module.activity.repository.SigninRosterRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class SigninRosterService {
    private final SigninRosterRepository rosterRepo;
    private final SigninFieldConfigRepository fieldRepo;
    private final SigninRepository signinRepo;
    private final ActivityRepository activityRepo;
    private final SignInGroupRepository groupRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SigninRosterService(SigninRosterRepository rosterRepo, SigninFieldConfigRepository fieldRepo,
                               SigninRepository signinRepo, ActivityRepository activityRepo) {
        this(rosterRepo, fieldRepo, signinRepo, activityRepo, null);
    }

    @Autowired
    public SigninRosterService(SigninRosterRepository rosterRepo, SigninFieldConfigRepository fieldRepo,
                               SigninRepository signinRepo, ActivityRepository activityRepo,
                               SignInGroupRepository groupRepo) {
        this.rosterRepo = rosterRepo; this.fieldRepo = fieldRepo; this.signinRepo = signinRepo;
        this.activityRepo = activityRepo; this.groupRepo = groupRepo;
    }

    // ===== 核验字段配置 =====
    public List<SigninFieldConfig> getFields(Long activityId) {
        return fieldRepo.findByActivityIdOrderBySortOrderAsc(activityId);
    }

    /**
     * 获取某个活动的名单表头字段列表（从已上传的名单中提取）
     */
    public List<String> getRosterHeaders(Long activityId) {
        List<SigninRoster> rosterList = rosterRepo.findByActivityId(activityId);
        if (rosterList.isEmpty()) return new ArrayList<>();
        // 从第一条记录中提取字段名作为表头
        Map<String, String> firstRecord = parseJson(rosterList.get(0).getFieldsJson());
        return new ArrayList<>(firstRecord.keySet());
    }

    @Transactional
    public void saveFields(Long activityId, List<SigninFieldConfigRequest> fields) {
        if (!activityRepo.existsById(activityId)) throw new BizException(2001, "活动不存在");
        fieldRepo.deleteByActivityId(activityId);
        int order = 0;
        for (SigninFieldConfigRequest req : fields) {
            SigninFieldConfig c = new SigninFieldConfig();
            c.setActivityId(activityId);
            c.setFieldName(req.getFieldName());
            c.setFieldKey(req.getFieldKey());
            c.setRequired(req.getRequired() == null || req.getRequired() ? 1 : 0);
            c.setFieldType(req.getFieldType() == null ? "TEXT" : req.getFieldType());
            c.setSortOrder(++order);
            c.setCreatedAt(LocalDateTime.now());
            fieldRepo.save(c);
        }
    }

    // ===== Excel 上传 =====
    @Transactional
    public Map<String, Integer> uploadFromXlsx(Long activityId, MultipartFile file) {
        return uploadRows(activityId, file, null);
    }

    private Map<String, Integer> uploadRows(Long activityId, MultipartFile file, Long groupId) {
        if (!activityRepo.existsById(activityId)) throw new BizException(2001, "活动不存在");
        // B9 fix: 空文件守卫
        if (file == null || file.isEmpty()) throw new BizException(2403, "上传文件不能为空");

        List<SigninRoster> toSave = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (groupId != null) {
            for (SigninRoster existing : rosterRepo.findByGroupId(groupId)) {
                seen.add(rowKey(parseJson(existing.getFieldsJson())));
            }
        }
        int skipped = 0;
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = findHeaderRow(sheet);
            Map<String, Integer> col = new HashMap<>();
            for (int c = header.getFirstCellNum(); c < header.getLastCellNum(); c++) {
                String name = cellStr(header.getCell(c)).trim();
                if (!name.isEmpty()) col.put(name, c);
            }
            if (col.isEmpty()) throw new BizException(2403, "Excel 表头为空");
            // 校验必填字段配置在 Excel 表头中是否齐全
            List<SigninFieldConfig> fieldConfigs = fieldRepo.findByActivityIdOrderBySortOrderAsc(activityId);
            for (SigninFieldConfig fc : fieldConfigs) {
                if (fc.getRequired() != null && fc.getRequired() == 1 && !col.containsKey(fc.getFieldName())) {
                    throw new BizException(2402, "缺少必填列: " + fc.getFieldName());
                }
            }
            for (int i = header.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row r = sheet.getRow(i);
                if (r == null) continue;
                Map<String, String> values = new LinkedHashMap<>();
                boolean any = false;
                for (Map.Entry<String, Integer> entry : col.entrySet()) {
                    String fieldName = entry.getKey();
                    Integer ci = entry.getValue();
                    String v = ci == null ? "" : cellStr(r.getCell(ci)).trim();
                    if (!v.isEmpty()) any = true;
                    values.put(fieldName, v);
                }
                if (!any) continue; // 全空行跳过
                String key = rowKey(values);
                if (!seen.add(key)) { skipped++; continue; } // B8 fix: 计数跳过的重复行
                SigninRoster rr = new SigninRoster();
                rr.setActivityId(activityId);
                rr.setGroupId(groupId);
                rr.setFieldsJson(toJson(values));
                rr.setCreatedAt(LocalDateTime.now());
                toSave.add(rr);
            }
        } catch (java.io.IOException e) {
            throw new BizException(2403, "Excel 解析失败");
        }
        rosterRepo.saveAll(toSave);
        Map<String, Integer> res = new HashMap<>();
        res.put("added", toSave.size());
        res.put("skipped", skipped);
        return res;
    }

    private String rowKey(Map<String, String> values) {
        return values.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce("", (a, b) -> a + "|" + b);
    }

    private String toJson(Map<String, String> m) {
        try { return objectMapper.writeValueAsString(m); }
        catch (Exception e) { throw new BizException(2404, "名单数据序列化失败"); }
    }

    private Map<String, String> parseJson(String s) {
        try { return objectMapper.readValue(s, new TypeReference<Map<String, String>>() {}); }
        catch (Exception e) { return Collections.emptyMap(); }
    }

    private Row findHeaderRow(Sheet sheet) {
        for (int i = 0; i <= Math.min(5, sheet.getLastRowNum()); i++) {
            Row r = sheet.getRow(i);
            if (r == null) continue;
            for (int c = r.getFirstCellNum(); c < r.getLastCellNum(); c++) {
                String v = cellStr(r.getCell(c));
                if (v.contains("姓名")) return r;
            }
        }
        throw new BizException(2405, "未找到名单表头（需包含「姓名」列）");
    }

    private String cellStr(org.apache.poi.ss.usermodel.Cell c) {
        if (c == null) return "";
        return new org.apache.poi.ss.usermodel.DataFormatter().formatCellValue(c).trim();
    }

    // ===== 名单列表（含已签/未签状态）=====
    public List<SigninRosterVO> listRoster(Long activityId, String status) {
        List<SigninRoster> rows = rosterRepo.findByActivityId(activityId);
        List<Signin> signins = signinRepo.findByActivityId(activityId);
        List<SigninRosterVO> vos = rows.stream().map(r -> {
            Map<String, String> v = parseJson(r.getFieldsJson());
            boolean signed = signins.stream().anyMatch(s -> matches(s, v));
            SigninRosterVO vo = new SigninRosterVO();
            vo.setId(r.getId());
            vo.setActivityId(activityId);
            vo.setFields(v);
            vo.setSigned(signed);
            return vo;
        }).toList();
        if ("SIGNED".equalsIgnoreCase(status)) return vos.stream().filter(SigninRosterVO::isSigned).toList();
        if ("UNSIGNED".equalsIgnoreCase(status)) return vos.stream().filter(v -> !v.isSigned()).toList();
        return vos;
    }

    /** 匹配规则：签到记录与应签名单行的所有非空字段值相等即匹配 */
    private boolean matches(Signin s, Map<String, String> rosterFields) {
        for (Map.Entry<String, String> e : rosterFields.entrySet()) {
            String expect = e.getValue();
            if (expect == null || expect.isEmpty()) continue;
            String actual = fieldValueOfSignin(s, e.getKey());
            if (!expect.equals(actual)) return false;
        }
        return true;
    }

    /** 从签到记录取某字段的值：按字段名（姓名/学号/手机号/班级/身份）映射到 signin 实体列 */
    private String fieldValueOfSignin(Signin s, String fieldName) {
        return switch (fieldName) {
            case "姓名" -> s.getName() == null ? "" : s.getName();
            case "学号" -> s.getStudentNo() == null ? "" : s.getStudentNo();
            case "手机号" -> s.getPhone() == null ? "" : s.getPhone();
            case "班级" -> s.getClassName() == null ? "" : s.getClassName();
            case "身份" -> s.getIdentityType() == null ? "" : s.getIdentityType();
            default -> "";
        };
    }

    /**
     * 宽松匹配：扫码提供的 fields 与某应签名单行的所有非空字段值相等即视为命中。
     * 供 scan 接口使用——命中则标记（remark 加"应签名单"），不命中不拒绝仍签到。
     */
    public boolean isInRoster(Long activityId, Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) return false;
        for (SigninRoster r : rosterRepo.findByActivityId(activityId)) {
            Map<String, String> v = parseJson(r.getFieldsJson());
            boolean anyExpect = false;
            boolean allMatch = true;
            for (Map.Entry<String, String> e : v.entrySet()) {
                String expect = e.getValue();
                if (expect == null || expect.isEmpty()) continue;
                anyExpect = true;
                if (!expect.equals(fields.get(e.getKey()))) { allMatch = false; break; }
            }
            if (anyExpect && allMatch) return true;
        }
        return false;
    }

    public SigninSummaryVO summary(Long activityId) {
        List<SigninRosterVO> all = listRoster(activityId, "ALL");
        long expected = all.size();
        long signed = all.stream().filter(SigninRosterVO::isSigned).count();
        SigninSummaryVO vo = new SigninSummaryVO();
        vo.setExpected(expected);
        vo.setSigned(signed);
        vo.setUnsigned(expected - signed);
        return vo;
    }

    @Transactional
    public void deleteRoster(Long id) {
        SigninRoster r = rosterRepo.findById(id).orElseThrow(() -> new BizException(2406, "名单行不存在"));
        rosterRepo.delete(r);
    }

    // ===== 手动补签 =====
    /**
     * 手动补签（幂等）：对每个名单行，先按姓名+学号查该活动是否已有匹配签到记录，
     * 已有则跳过，避免重复补签产生重复签到。返回实际补签条数。
     */
    @Transactional
    public int backfill(Long activityId, List<Long> rosterIds, Long operatorId) {
        int n = 0;
        List<Signin> existing = signinRepo.findByActivityId(activityId);
        for (Long id : rosterIds) {
            SigninRoster r = rosterRepo.findById(id).orElseThrow(() -> new BizException(2406, "名单行不存在"));
            if (!r.getActivityId().equals(activityId)) throw new BizException(2407, "名单行不属于该活动");
            Map<String, String> v = parseJson(r.getFieldsJson());
            if (existsSigninFor(existing, v)) continue; // 幂等：已有匹配签到则跳过
            Signin s = new Signin();
            s.setActivityId(activityId);
            s.setName(v.getOrDefault("姓名", ""));
            s.setStudentNo(v.getOrDefault("学号", null));
            s.setClassName(v.getOrDefault("班级", null));
            s.setPhone(v.getOrDefault("手机号", null));
            s.setIdentityType(v.getOrDefault("身份", null));
            s.setSignType(Signin.SignType.MANUAL);
            s.setSignTime(LocalDateTime.now());
            s.setRemark("应签名单补签");
            s.setCreatedAt(LocalDateTime.now());
            signinRepo.save(s);
            n++;
        }
        return n;
    }

    /** 该活动已有签到记录是否命中名单行（按名单行所有非空字段值匹配，同 matches 规则） */
    private boolean existsSigninFor(List<Signin> signins, Map<String, String> rosterFields) {
        return signins.stream().anyMatch(s -> matches(s, rosterFields));
    }

    // ===== 名单分组 =====

    public List<SignInGroupVO> listGroups(Long activityId, String keyword) {
        if (!activityRepo.existsById(activityId)) throw new BizException(2001, "活动不存在");
        List<SignInGroup> groups = groupRepo.findByActivityIdOrderBySortOrderAsc(activityId);
        List<Signin> signins = signinRepo.findByActivityId(activityId);
        boolean kwFilter = keyword != null && !keyword.isBlank();
        String kw = kwFilter ? keyword.trim() : null;
        List<SignInGroupVO> vos = new ArrayList<>();
        for (SignInGroup g : groups) {
            List<GroupPersonVO> people = new ArrayList<>();
            for (SigninRoster r : rosterRepo.findByGroupId(g.getId())) {
                Map<String, String> fields = parseJson(r.getFieldsJson());
                if (kwFilter && !matchesKeyword(fields, kw)) continue;
                GroupPersonVO p = new GroupPersonVO();
                p.setId(r.getId());
                p.setGroupId(g.getId());
                p.setFields(fields);
                p.setSigned(signins.stream().anyMatch(s -> matches(s, fields)));
                people.add(p);
            }
            SignInGroupVO vo = new SignInGroupVO();
            vo.setId(g.getId());
            vo.setActivityId(activityId);
            vo.setGroupName(g.getGroupName());
            vo.setSourceFilename(g.getSourceFilename());
            vo.setSortOrder(g.getSortOrder());
            vo.setCreatedAt(g.getCreatedAt());
            vo.setPeople(people);
            vo.setSignedCount(people.stream().filter(GroupPersonVO::isSigned).count());
            vo.setUnsignedCount(people.size() - vo.getSignedCount());
            vo.setCount(people.size());
            vos.add(vo);
        }
        return vos;
    }

    /** 跨分组搜索：按姓名/学号模糊匹配 */
    private boolean matchesKeyword(Map<String, String> fields, String kw) {
        String name = fields.getOrDefault("姓名", "");
        String no = fields.getOrDefault("学号", "");
        return name.contains(kw) || no.contains(kw);
    }

    public SignInGroupSummaryVO groupSummary(Long activityId) {
        SignInGroupSummaryVO vo = new SignInGroupSummaryVO();
        List<Signin> signins = signinRepo.findByActivityId(activityId);
        long total = 0, signed = 0, groupCount = 0;
        for (SignInGroup g : groupRepo.findByActivityIdOrderBySortOrderAsc(activityId)) {
            List<SigninRoster> rows = rosterRepo.findByGroupId(g.getId());
            if (rows.isEmpty()) continue;
            long s = rows.stream().filter(r -> signins.stream().anyMatch(sn -> matches(sn, parseJson(r.getFieldsJson())))).count();
            total += rows.size();
            signed += s;
            groupCount++;
        }
        vo.setTotal(total);
        vo.setSigned(signed);
        vo.setUnsigned(total - signed);
        vo.setGroupCount(groupCount);
        return vo;
    }

    @Transactional
    public GroupUploadResultVO uploadGroupXlsx(Long activityId, MultipartFile file, Long groupId) {
        if (!activityRepo.existsById(activityId)) throw new BizException(2001, "活动不存在");
        if (file == null || file.isEmpty()) throw new BizException(2403, "上传文件不能为空");
        SignInGroup group;
        if (groupId != null) {
            group = groupRepo.findById(groupId).orElseThrow(() -> new BizException(2406, "分组不存在"));
            if (!group.getActivityId().equals(activityId)) throw new BizException(2407, "分组不属于该活动");
        } else {
            String name = file.getOriginalFilename();
            String groupName = name == null ? "未命名分组" : name.replaceFirst("(?i)\\.(xlsx|xls)$", "");
            if (groupName.isBlank()) groupName = "未命名分组";
            group = new SignInGroup();
            group.setActivityId(activityId);
            group.setGroupName(groupName);
            group.setSourceFilename(name);
            int next = groupRepo.findByActivityId(activityId).stream()
                    .mapToInt(g -> g.getSortOrder() == null ? 0 : g.getSortOrder()).max().orElse(0) + 1;
            group.setSortOrder(next);
            group.setCreatedAt(LocalDateTime.now());
            group = groupRepo.save(group);
        }
        Map<String, Integer> res = uploadRows(activityId, file, group.getId());
        GroupUploadResultVO vo = new GroupUploadResultVO();
        vo.setGroupId(group.getId());
        vo.setGroupName(group.getGroupName());
        vo.setAdded(res.get("added"));
        vo.setSkipped(res.get("skipped"));
        return vo;
    }

    @Transactional
    public void renameGroup(Long id, String groupName) {
        if (groupName == null || groupName.isBlank()) throw new BizException(400, "分组名不能为空");
        SignInGroup g = groupRepo.findById(id).orElseThrow(() -> new BizException(2406, "分组不存在"));
        g.setGroupName(groupName.trim());
    }

    @Transactional
    public void sortGroups(List<Long> ids) {
        int order = 0;
        for (Long id : ids) {
            SignInGroup g = groupRepo.findById(id).orElse(null);
            if (g != null) {
                g.setSortOrder(++order);
                groupRepo.save(g);
            }
        }
    }

    @Transactional
    public void deleteGroup(Long id) {
        SignInGroup g = groupRepo.findById(id).orElseThrow(() -> new BizException(2406, "分组不存在"));
        rosterRepo.deleteByGroupId(g.getId());
        groupRepo.delete(g);
    }

    @Transactional
    public int deleteGroups(List<Long> ids) {
        int n = 0;
        for (Long id : ids) {
            if (groupRepo.findById(id).isEmpty()) continue;
            rosterRepo.deleteByGroupId(id);
            groupRepo.deleteById(id);
            n++;
        }
        return n;
    }

    @Transactional
    public void deletePerson(Long rosterId) {
        deleteRoster(rosterId);
    }

    @Transactional
    public int deletePersons(List<Long> rosterIds) {
        int n = 0;
        for (Long id : rosterIds) {
            if (rosterRepo.existsById(id)) {
                rosterRepo.deleteById(id);
                n++;
            }
        }
        return n;
    }
}
