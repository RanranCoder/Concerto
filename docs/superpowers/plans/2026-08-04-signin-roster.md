# 签到应签名单与核验字段实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有签到（手动+扫码）基础上，新增应签名单（Excel 上传作为核验来源）、自定义核验字段配置、已签/未签显示与筛选、手动补签。

**Architecture:** 前后端分离。后端新增两张表（`signin_roster` 应签名单行、`signin_field_config` 核验字段配置）+ SigninRosterService（Excel 解析/列表/汇总/补签），改造 scan 接口支持核验字段匹配。前端签到 Tab 加应签名单区（上传/名单列表/筛选/补签/汇总）+ 字段配置面板；扫码落地页按字段配置动态生成表单。

**Tech Stack:** Spring Boot 4（POI 解析 xlsx，复用 RosterImportService 模式）· React 18 · Vite · TS · AntD 5 · 上传组件用 antd Upload

---

## Global Constraints

- **数据模型**：新增 `signin_roster`（id/activity_id/fields_json TEXT/created_at）与 `signin_field_config`（id/activity_id/field_name/field_key/required/field_type/sort_order/created_at）两张表，DDL 走 Flyway `V3__signin_roster.sql`，兼容 MySQL 与 H2（禁 ENGINE=/UNSIGNED/ON UPDATE；JSON 用 TEXT）。
- **已签/未签判定**：按核验字段匹配——某签到记录的**所有必填字段值**与某应签名单行 fields_json 相等即算匹配。默认核验字段配置含"姓名(必填)+学号(选填)"；重名提示用户加学号。
- **scan 接口兼容**：不设名单/字段配置时行为不变（仍姓名+学号）；设了名单但签到人不匹配——**宽松策略**（仍可签到但不计入应签，现场进不去时避免尴尬）。
- **补签**：`POST /api/signins/backfill` 从应签名单写入签到记录，signType=MANUAL + remark="应签名单补签"。
- **Excel 格式**：表头 = 核验字段名（姓名/学号/手机号/班级…），每行 = 一个应签人；复用 RosterImportService 的表头文字匹配、跳过标题行、过滤 ~$、空行/去重逻辑思路。
- **UI**：沿用 liquid-glass 设计系统（GlassCard/GlassModal/GlassTable、`--color-*`）；签到 Tab 顶部汇总（应签/已签/未签）+ 名单列表筛选（全部/已签/未签）。
- **权限**：应签名单上传/字段配置/补签需部长及以上（`@PreAuthorize`，与签到管理一致）；扫码落地页免登录。
- **质量门**：每个 Task 结束前跑对应测试并 `git commit`；前端任务 `npm run build` + 浏览器点验后声明完成。

---

## 文件结构总览

```
pams-backend/src/main/resources/db/migration/V3__signin_roster.sql   # 两张新表
pams-backend/src/main/java/com/pams/module/activity/
├─ entity/SigninRoster.java / SigninFieldConfig.java
├─ repository/SigninRosterRepository.java / SigninFieldConfigRepository.java
├─ dto/SigninRosterVO.java / SigninFieldConfigRequest.java / SigninSummaryVO.java
├─ service/SigninRosterService.java    # Excel解析/列表/汇总/补签/字段配置
├─ controller/SigninRosterController.java
├─ controller/SigninController.java    # 改 scan 支持核验字段
└─ test/.../SigninRosterServiceTest.java + SigninControllerTest 扩展

pams-web/src/
├─ api/signin.ts                        # + roster/fields/backfill/summary
├─ components/signin/SigninRosterUpload.tsx   # Excel 上传
├─ components/signin/SigninFieldConfig.tsx    # 核验字段配置面板
├─ components/signin/SigninRosterList.tsx     # 名单列表(已签/未签/筛选/补签)
├─ pages/activity/SigninPanel.tsx      # + 应签名单区 + 汇总 + 字段配置入口
└─ pages/signin/SigninScan.tsx         # 动态表单(按字段配置生成)
```

---

## M1 · 后端数据模型 + 应签名单核心

### Task 1: V3 建表 + 实体/Repository + 名单上传/列表/汇总/补签

**Files:**
- Create: `pams-backend/src/main/resources/db/migration/V3__signin_roster.sql`
- Create: `pams-backend/src/main/java/com/pams/module/activity/entity/SigninRoster.java` / `SigninFieldConfig.java`
- Create: `pams-backend/src/main/java/com/pams/module/activity/repository/SigninRosterRepository.java` / `SigninFieldConfigRepository.java`
- Create: `pams-backend/src/main/java/com/pams/module/activity/dto/SigninRosterVO.java` / `SigninFieldConfigRequest.java` / `SigninSummaryVO.java`
- Create: `pams-backend/src/main/java/com/pams/module/activity/service/SigninRosterService.java`
- Create: `pams-backend/src/main/java/com/pams/module/activity/controller/SigninRosterController.java`
- Create: `pams-backend/src/test/java/com/pams/module/activity/SigninRosterServiceTest.java`
- Modify: `pams-backend/src/main/resources/db/migration/V1__init.sql`（不改，仅确认 V3 独立）

**Interfaces:**
- Produces:
  - `SigninRosterService.uploadFromXlsx(Long activityId, MultipartFile file)` → `{added, skipped}`（按活动字段配置解析 Excel → fields_json）
  - `SigninRosterService.listRoster(Long activityId, String status)` → `List<SigninRosterVO>`（status=ALL/SIGNED/UNSIGNED，每行含 `signed: boolean`）
  - `SigninRosterService.deleteRoster(Long id)`
  - `SigninRosterService.summary(Long activityId)` → `SigninSummaryVO{expected, signed, unsigned}`
  - `SigninRosterService.backfill(Long activityId, List<Long> rosterIds, Long operatorId)` → 补签条数
  - `SigninFieldConfigService`（并入 SigninRosterService）`getFields(activityId)` / `saveFields(activityId, List<SigninFieldConfigRequest>)`
- 供 M2 scan 改造、M3 前端使用。

- [ ] **Step 1: 写 V3__signin_roster.sql**

```sql
CREATE TABLE IF NOT EXISTS signin_roster (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  fields_json TEXT NOT NULL COMMENT '核验字段值 JSON，键=字段名，值=该人对应值',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_roster_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS signin_field_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  field_name VARCHAR(50) NOT NULL COMMENT '字段显示名，如 姓名/学号/手机号',
  field_key VARCHAR(50) NOT NULL COMMENT '字段键，如 name/studentNo/phone',
  required TINYINT DEFAULT 0,
  field_type VARCHAR(20) DEFAULT 'TEXT' COMMENT 'TEXT/NUMBER/PHONE',
  sort_order INT DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_field_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);
```

- [ ] **Step 2: 实体与 Repository**

`SigninRoster`：id/activityId(LocalLong)/fieldsJson(TEXT @Column(columnDefinition="TEXT"))/createdAt。无 deleted。
`SigninFieldConfig`：id/activityId/fieldName/fieldKey/required(Integer)/fieldType/sortOrder/createdAt。无 deleted。

Repository：
```java
public interface SigninRosterRepository extends JpaRepository<SigninRoster, Long> {
    List<SigninRoster> findByActivityId(Long activityId);
    void deleteByActivityId(Long activityId);
    long countByActivityId(Long activityId);
}
public interface SigninFieldConfigRepository extends JpaRepository<SigninFieldConfig, Long> {
    List<SigninFieldConfig> findByActivityIdOrderBySortOrderAsc(Long activityId);
    void deleteByActivityId(Long activityId);
}
```

- [ ] **Step 3: 写 SigninRosterServiceTest（先红）**

```java
package com.pams.module.activity;

import com.pams.common.BizException;
import com.pams.module.activity.dto.SigninFieldConfigRequest;
import com.pams.module.activity.entity.SigninFieldConfig;
import com.pams.module.activity.entity.SigninRoster;
import com.pams.module.activity.repository.SigninFieldConfigRepository;
import com.pams.module.activity.repository.SigninRosterRepository;
import com.pams.module.activity.repository.SigninRepository;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.activity.service.SigninRosterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SigninRosterServiceTest {

    SigninRosterRepository rosterRepo;
    SigninFieldConfigRepository fieldRepo;
    SigninRepository signinRepo;
    ActivityRepository activityRepo;
    SigninRosterService service;

    @BeforeEach
    void setup() {
        rosterRepo = mock(SigninRosterRepository.class);
        fieldRepo = mock(SigninFieldConfigRepository.class);
        signinRepo = mock(SigninRepository.class);
        activityRepo = mock(ActivityRepository.class);
        service = new SigninRosterService(rosterRepo, fieldRepo, signinRepo, activityRepo);
    }

    @Test
    void saveFields_persistsInOrder() {
        var req = List.of(
            new SigninFieldConfigRequest("姓名", "name", true, "TEXT", 1),
            new SigninFieldConfigRequest("学号", "studentNo", false, "TEXT", 2)
        );
        when(fieldRepo.save(any(SigninFieldConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        service.saveFields(1L, req);
        verify(fieldRepo, times(2)).save(any(SigninFieldConfig.class));
    }

    @Test
    void summary_countsSignedAndUnsigned() {
        // roster: 2 行（张三/李四），signin: 1 条 name=张三
        SigninRoster r1 = new SigninRoster(); r1.setId(1L); r1.setActivityId(1L);
        r1.setFieldsJson("{\"姓名\":\"张三\",\"学号\":\"2025001\"}");
        SigninRoster r2 = new SigninRoster(); r2.setId(2L); r2.setActivityId(1L);
        r2.setFieldsJson("{\"姓名\":\"李四\",\"学号\":\"2025002\"}");
        when(rosterRepo.findByActivityId(1L)).thenReturn(List.of(r1, r2));
        // signin 需匹配：注入 signinRepo，返回一条 name=张三 的记录
        var s = new com.pams.module.activity.entity.Signin();
        s.setName("张三");
        when(signinRepo.findByActivityId(1L)).thenReturn(List.of(s));

        var summary = service.summary(1L);
        assertThat(summary.getExpected()).isEqualTo(2);
        assertThat(summary.getSigned()).isEqualTo(1);
        assertThat(summary.getUnsigned()).isEqualTo(1);
    }

    @Test
    void deleteRoster_missing_throws() {
        when(rosterRepo.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteRoster(9L)).isInstanceOf(BizException.class);
    }
}
```

- [ ] **Step 4: 实现 SigninRosterService**

```java
package com.pams.module.activity.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.BizException;
import com.pams.module.activity.dto.SigninFieldConfigRequest;
import com.pams.module.activity.dto.SigninRosterVO;
import com.pams.module.activity.dto.SigninSummaryVO;
import com.pams.module.activity.entity.Signin;
import com.pams.module.activity.entity.SigninFieldConfig;
import com.pams.module.activity.entity.SigninRoster;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.activity.repository.SigninFieldConfigRepository;
import com.pams.module.activity.repository.SigninRepository;
import com.pams.module.activity.repository.SigninRosterRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SigninRosterService(SigninRosterRepository rosterRepo, SigninFieldConfigRepository fieldRepo,
                               SigninRepository signinRepo, ActivityRepository activityRepo) {
        this.rosterRepo = rosterRepo; this.fieldRepo = fieldRepo; this.signinRepo = signinRepo; this.activityRepo = activityRepo;
    }

    // ===== 核验字段配置 =====
    public List<SigninFieldConfig> getFields(Long activityId) {
        return fieldRepo.findByActivityIdOrderBySortOrderAsc(activityId);
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
        if (!activityRepo.existsById(activityId)) throw new BizException(2001, "活动不存在");
        List<SigninFieldConfig> fields = getFields(activityId);
        if (fields.isEmpty()) throw new BizException(2401, "请先配置核验字段再上传名单");

        List<SigninRoster> toSave = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = findHeaderRow(sheet);
            Map<String, Integer> col = new HashMap<>();
            for (int c = header.getFirstCellNum(); c < header.getLastCellNum(); c++) {
                String name = cellStr(header.getCell(c)).trim();
                if (!name.isEmpty()) col.put(name, c);
            }
            // 必须能定位到必填字段的表头
            for (SigninFieldConfig f : fields) {
                if (f.getRequired() != null && f.getRequired() == 1 && !col.containsKey(f.getFieldName())) {
                    throw new BizException(2402, "Excel 缺少必填列「" + f.getFieldName() + "」");
                }
            }
            for (int i = header.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row r = sheet.getRow(i);
                if (r == null) continue;
                Map<String, String> values = new LinkedHashMap<>();
                boolean any = false;
                for (SigninFieldConfig f : fields) {
                    Integer ci = col.get(f.getFieldName());
                    String v = ci == null ? "" : cellStr(r.getCell(ci)).trim();
                    if (!v.isEmpty()) any = true;
                    values.put(f.getFieldName(), v);
                }
                if (!any) continue; // 全空行跳过
                String key = values.entrySet().stream()
                        .filter(e -> !e.getValue().isEmpty())
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .reduce("", (a, b) -> a + "|" + b);
                if (!seen.add(key)) continue; // 行内去重
                SigninRoster rr = new SigninRoster();
                rr.setActivityId(activityId);
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
        res.put("skipped", 0);
        return res;
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
    @Transactional
    public int backfill(Long activityId, List<Long> rosterIds, Long operatorId) {
        int n = 0;
        for (Long id : rosterIds) {
            SigninRoster r = rosterRepo.findById(id).orElseThrow(() -> new BizException(2406, "名单行不存在"));
            if (!r.getActivityId().equals(activityId)) throw new BizException(2407, "名单行不属于该活动");
            Map<String, String> v = parseJson(r.getFieldsJson());
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
}
```

> 说明：`matches` 用"应签名单行的所有非空字段值"匹配——即名单行里填了姓名+学号，则签到记录的姓名和学号都要相等才算匹配。这满足"自定义字段匹配"。

- [ ] **Step 5: SigninRosterController + DTO**

```java
@RestController
@RequestMapping("/api/signins")
public class SigninRosterController {
    // GET /api/signins/roster?activityId=&status= → listRoster
    // POST /api/signins/roster/upload → uploadFromXlsx（multipart，@RequestParam activityId + file）
    // DELETE /api/signins/roster/{id} → deleteRoster
    // GET /api/signins/roster/summary?activityId= → summary
    // GET /api/signins/fields?activityId= → getFields
    // PUT /api/signins/fields → saveFields（body {activityId, fields:[{fieldName,fieldKey,required,fieldType,sortOrder}]}）
    // POST /api/signins/backfill → backfill（body {activityId, rosterIds:[], operatorId 由 LoginUser 取}）
    // 全部 @PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','SECRETARY_LEADER','ORG_LEADER','MEDIA_LEADER','TECH_LEADER')") 部长及以上
}
```

DTO：`SigninRosterVO{id, activityId, fields: Map<String,String>, signed: boolean}`；`SigninFieldConfigRequest{fieldName, fieldKey, required, fieldType, sortOrder}`；`SigninSummaryVO{expected, signed, unsigned}`（Lombok @Data）。

- [ ] **Step 6: 跑测试 + 提交**

```bash
cd /d/MyApp/PAMS/pams-backend
mvn -q test
git add pams-backend/src
git commit -m "feat: 签到应签名单与核验字段配置后端"
```

Expected: SigninRosterServiceTest 3 用例 + 既有全绿。

---

## M2 · scan 接口改造（核验字段匹配）

### Task 2: scan 支持核验字段动态校验 + 匹配应签名单

**Files:**
- Modify: `pams-backend/src/main/java/com/pams/module/activity/controller/SigninController.java`
- Modify: `pams-backend/src/main/java/com/pams/module/activity/service/SigninService.java`
- Modify: `pams-backend/src/test/java/com/pams/module/activity/SigninControllerTest.java`

**Interfaces:**
- Produces:
  - `POST /api/signins/scan` body 从 `{token, name, studentNo}` 扩展为 `{token, fields: {字段名: 值}}`（兼容旧 body：仍接受 name/studentNo）
  - `SigninService.scanSignin(token, Map<String,String> fields)` → Signin（宽松匹配：匹配到名单标记，不匹配仍签到）
- 供 M4 前端扫码落地页动态表单。

- [ ] **Step 1: 写 SigninControllerTest 扩展（先红）**

```java
@Test
void scan_withCustomFields_matchesRoster_loosely() throws Exception {
    // 生成 token → POST /scan body {"token": t, "fields": {"姓名":"张三","学号":"2025001"}}
    // 期望 200 且返回 Signin
}
```

> 说明：scan 校验活动存在 + token 有效后，用 SigninRosterService 的匹配逻辑（若该活动配置了字段）宽松匹配——匹配到则签到记录标记（可在 remark 加"应签名单"），不匹配仍签到。Controller 收到 `fields` Map 后拼成 name/studentNo 等字段写入 Signin。

- [ ] **Step 2: 实现 scan 扩展**

`SigninController.scan` 改为接收 `Map<String,Object> body`，兼容两种：
- 旧格式：`{token, name, studentNo}`（保持向后兼容）
- 新格式：`{token, fields: {姓名:..., 学号:..., 手机号:...}}`

从 fields 提取 name/studentNo/phone/className/identityType 映射到 Signin 列。SigninRosterService 提供 `isInRoster(activityId, fields)`（宽松：匹配到返回 true 用于标记，不匹配不拒绝）。

`SigninService.scanSignin` 内部逻辑：校验 token/活动 → 构造 Signin（字段映射）→ 若活动有应签名单且匹配到，remark 追加"（应签名单）"→ save。

- [ ] **Step 3: 跑测试 + 提交**

```bash
mvn -q test
git add pams-backend/src
git commit -m "feat: 扫码签到支持核验字段与应签名单宽松匹配"
```

---

## M3 · 前端应签名单（上传/列表/筛选/补签/汇总）

### Task 3: 签到 Tab 应签名单区 + 字段配置面板

**Files:**
- Modify: `pams-web/src/api/signin.ts`（+ roster/fields/backfill/summary 接口）
- Create: `pams-web/src/components/signin/SigninRosterUpload.tsx`
- Create: `pams-web/src/components/signin/SigninFieldConfig.tsx`
- Create: `pams-web/src/components/signin/SigninRosterList.tsx`
- Modify: `pams-web/src/pages/activity/SigninPanel.tsx`（+ 应签名单区 + 汇总 + 字段配置入口）

**Interfaces:**
- Produces:
  - `api/signin.ts`：`listRoster(activityId, status)`、`uploadRoster(activityId, file)`、`deleteRoster(id)`、`rosterSummary(activityId)`、`getSigninFields(activityId)`、`saveSigninFields(activityId, fields)`、`backfillSignins(activityId, rosterIds)`
  - `SigninRosterUpload.tsx`：props `{activityId, onUploaded}`（antd Upload + 上传进度 + 成功返回 added）
  - `SigninFieldConfig.tsx`：props `{activityId}`（字段列表增删改 + 保存）
  - `SigninRosterList.tsx`：props `{activityId, status, onChanged}`（名单表格，列=核验字段+状态，补签按钮）
- 供 M4 扫码落地页复用字段配置。

- [ ] **Step 1: api/signin.ts 加接口**

```ts
export interface SigninRosterVO { id: number; activityId: number; fields: Record<string, string>; signed: boolean }
export interface SigninFieldConfigVO { id: number; fieldName: string; fieldKey: string; required: boolean; fieldType: string; sortOrder: number }
export interface SigninSummaryVO { expected: number; signed: number; unsigned: number }

export const listRoster = (activityId: number, status?: string) => get<SigninRosterVO[]>('/signins/roster', { activityId, status })
export const uploadRoster = (activityId: number, file: File) => {
  const form = new FormData()
  form.append('activityId', String(activityId))
  form.append('file', file)
  return http.post('/signins/roster/upload', form) as unknown as Promise<{ added: number; skipped: number }>
}
export const deleteRoster = (id: number) => del<void>(`/signins/roster/${id}`)
export const rosterSummary = (activityId: number) => get<SigninSummaryVO>('/signins/roster/summary', { activityId })
export const getSigninFields = (activityId: number) => get<SigninFieldConfigVO[]>('/signins/fields', { activityId })
export const saveSigninFields = (activityId: number, fields: Array<{ fieldName: string; fieldKey: string; required: boolean; fieldType: string }>) =>
  put<void>('/signins/fields', { activityId, fields })
export const backfillSignins = (activityId: number, rosterIds: number[]) =>
  post<void>('/signins/backfill', { activityId, rosterIds })
```

> 注意：uploadRoster 用原生 `http.post`（FormData，不走 get/post 包装）。若 http.ts 的 post 包装接收对象，用 `http.post('/signins/roster/upload', form)` 并确保不设 JSON header。

- [ ] **Step 2: SigninRosterUpload.tsx**

antd `Upload.Dragger` 拖拽上传（accept=".xlsx,.xls"），customRequest 调 uploadRoster，成功 message 显示 `导入 N 人`，onUploaded 回调刷新列表。

- [ ] **Step 3: SigninFieldConfig.tsx**

字段配置面板（GlassModal）：列出当前字段（fieldName/是否必填/类型 Select[TEXT/NUMBER/PHONE]），可增删行；保存调 saveSigninFields。默认给出"姓名(必填)+学号(选填)"两条（无配置时）。

- [ ] **Step 4: SigninRosterList.tsx**

名单表格（GlassTable）：列 = 当前核验字段名（动态）+ 状态列（Tag：已签绿/未签红）+ 操作（删除）。顶部筛选 Select（全部/已签/未签）。未签行勾选（rowSelection）+ "补签"按钮调 backfillSignins。空态"尚未上传应签名单"。

- [ ] **Step 5: SigninPanel 集成**

签到 Tab 顶部加"应签名单"GlassCard：
- 汇总统计：应签 X / 已签 Y / 未签 Z（rosterSummary）
- 操作区：上传名单（SigninRosterUpload）+ 核验字段配置（SigninFieldConfig 入口按钮）
- 名单列表（SigninRosterList，含筛选/补签）
下方保留原签到记录列表。

- [ ] **Step 6: 构建 + 浏览器验证 + 提交**

浏览器点验：签到 Tab 配置字段（姓名+学号）→ 上传 Excel 名单 → 汇总更新 → 名单列表显示已签/未签 → 扫码签到一个 → 该行变已签 → 筛选未签 → 补签 → 已签+1。curl --noproxy 确认接口。

```bash
git add pams-web/src
git commit -m "feat: 签到应签名单上传/列表/筛选/补签与字段配置"
```

---

## M4 · 扫码落地页动态表单

### Task 4: SigninScan 按字段配置生成表单

**Files:**
- Modify: `pams-web/src/pages/signin/SigninScan.tsx`
- Modify: `pams-web/src/api/signin.ts`（scan 接口支持 fields）

**Interfaces:**
- Produces: 扫码落地页加载活动核验字段（需 scan 接口先返回字段配置，或单独公开接口 `GET /api/signins/fields/public?token=`）
- 动态表单按字段配置生成（必填标记），提交 `{token, fields}`

- [ ] **Step 1: scan 接口支持 fields + 公开字段配置**

前端 `scanSignin({token, fields})` 发送新格式。落地页需知道活动字段配置——**方案**：后端 `GET /api/signins/scan-config?token=`（公开，按 token 查活动+字段配置返回），落地页先调它拿字段，再渲染表单。

- [ ] **Step 2: SigninScan 动态表单**

```tsx
// 加载 scan-config → 若返回 fields 数组（活动配置了字段）则动态渲染；否则渲染默认 姓名+学号
// 表单项：fieldName 标签 + 是否必填 required + 类型（TEXT→Input / PHONE→Input / NUMBER→InputNumber）
// 提交 {token, fields: {姓名:..., 学号:...}}
```

- [ ] **Step 3: 构建 + 浏览器验证 + 提交**

浏览器点验：配置了字段的活动 → 扫码打开落地页 → 表单按配置生成（必填项标红）→ 填对匹配名单 → 签到成功该名单行变已签；未配置字段的活动 → 仍显示默认姓名+学号。

```bash
git add pams-web/src
git commit -m "feat: 扫码落地页按核验字段配置动态生成表单"
```

---

## M5 · 联调打磨

### Task 5: 全流程联调 + 回归

**Files:**
- Modify: 各组件按联调结果微调

- [ ] **Step 1: 全流程联调**

走一遍：配置字段 → 上传 Excel 名单 → 扫码签到（匹配/不匹配宽松）→ 已签/未签筛选 → 手动补签 → 汇总统计 → 导出签到记录。验证明暗主题、边界（空名单、表头缺列、重名提示）。

- [ ] **Step 2: 回归**

```bash
cd /d/MyApp/PAMS/pams-backend && mvn -q test
cd /d/MyApp/PAMS/pams-web && npm run test && npm run build
```

- [ ] **Step 3: 提交收尾**

```bash
git add pams-backend/src pams-web/src
git commit -m "fix: 签到应签名单与核验字段联调打磨"
```

---

## 运行与测试命令速查

| 用途 | 命令 |
|---|---|
| 后端测试 | `cd pams-backend && mvn -q test` |
| 前端构建 | `cd pams-web && npm run build` |
| 前端开发 | `cd pams-web && npm run dev` |
| 一键启动 | `cmd //c start.bat` |

## 自审说明

- **已签/未签判定**：应签名单行所有非空字段值都匹配到某签到记录才算已签（避免空字段误匹配）。默认字段姓名+学号，重名靠学号区分。
- **宽松策略**：设名单但签到人不匹配，仍可签到（不拒绝），remark 不加"应签名单"标记。
- **Excel 解析**：复用 RosterImportService 的表头文字匹配 + 跳过标题行 + 全空行跳过；必填列缺失报 2402。
- **权限**：应签名单/字段配置/补签仅部长及以上；扫码落地页免登录。
- **向后兼容**：scan 接口旧格式 `{token,name,studentNo}` 仍可用。
