# 无课表页面渲染生成结果 + 移除手工字段 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 批量导入生成的无课表网格存入数据库，「无课表」页面（`/routine/free-schedules`）直接渲染该网格（样式同批量导入预览），并删除旧 free_schedule 手工字段全链路。

**Architecture:** 新增 `no_class_schedule` 表（部门+学期唯一，覆盖式存最新网格 JSON）+ 实体/仓库；`NoClassScheduleImportService` 生成后持久化并新增 `getGenerated`；`CourseScheduleController` 加 `GET /api/course-schedules/generated`；`FreeScheduleList.tsx` 重构为部门+学期选择 + 网格渲染；删除旧 free_schedule 后端（V11 删表）与前端 `api/freeSchedule.ts`。

**Tech Stack:** Java 21 · Spring Boot 4.0.2 · JPA · Flyway · Jackson · React 18 + TS + antd 5 + Vite

## Global Constraints

- 命名统一用「无课表」/ `NoClassSchedule`（禁用「干部无课表」）；沿用现有 `com.pams.module.schedule` 包结构。
- 同部门+学期只保留**最新**一份（覆盖式，不保留历史）。
- BizException 码：新逻辑用 2702~2705（2704 用于存/读失败）。
- 不新增后端依赖（Jackson 由 spring-boot-starter-web 提供）。
- 迁移版本：当前已到 V9，本计划用 **V10**（建 `no_class_schedule`）与 **V11**（删 `free_schedule`）。
- 前端文案中文；路由/菜单不变（`/routine/free-schedules`「无课表」）。
- 后端验证：`cd pams-backend && mvn test`；前端验证：`cd pams-web && npm run build`。

---

## 文件结构

**后端新增**：
- `pams-backend/src/main/resources/db/migration/V10__no_class_schedule.sql`
- `pams-backend/src/main/resources/db/migration/V11__drop_free_schedule.sql`
- `pams-backend/src/main/java/com/pams/module/schedule/entity/NoClassScheduleRecord.java`
- `pams-backend/src/main/java/com/pams/module/schedule/repository/NoClassScheduleRecordRepository.java`
- `pams-backend/src/main/java/com/pams/module/schedule/dto/NoClassScheduleGeneratedVO.java`

**后端修改**：
- `service/NoClassScheduleImportService.java`（持久化 + getGenerated）
- `controller/CourseScheduleController.java`（GET /generated）

**后端删除**：
- `module/routine/controller/FreeScheduleController.java`
- `module/routine/dto/FreeScheduleRequest.java`
- `module/routine/entity/FreeSchedule.java`
- `module/routine/repository/FreeScheduleRepository.java`
- `module/routine/service/RoutineService.java`（free-schedule 段 + 字段 + 构造参数）
- `module/config/DataSeeder.java`（free-schedule 种子 + 引用）

**前端修改**：
- `pams-web/src/api/courseSchedule.ts`（加 getGenerated + VO 类型）
- `pams-web/src/pages/routine/FreeScheduleList.tsx`（重构）
**前端删除**：
- `pams-web/src/api/freeSchedule.ts`

---

## Task 1: 迁移 + 实体 + 仓库 + VO

**Files:**
- Create: `pams-backend/src/main/resources/db/migration/V10__no_class_schedule.sql`
- Create: `pams-backend/src/main/java/com/pams/module/schedule/entity/NoClassScheduleRecord.java`
- Create: `pams-backend/src/main/java/com/pams/module/schedule/repository/NoClassScheduleRecordRepository.java`
- Create: `pams-backend/src/main/java/com/pams/module/schedule/dto/NoClassScheduleGeneratedVO.java`

**Interfaces:**
- Produces: `NoClassScheduleRecordRepository.findByDeptIdAndSemester(Long, String) → Optional<NoClassScheduleRecord>`；`NoClassScheduleGeneratedVO{deptName, semester, rows(List<NoClassScheduleRowVO>), createdAt}`

- [ ] **Step 1: 写迁移 `V10__no_class_schedule.sql`**

```sql
CREATE TABLE IF NOT EXISTS no_class_schedule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  dept_id BIGINT,
  dept_name VARCHAR(50),
  semester VARCHAR(20) NOT NULL,
  grid_json TEXT NOT NULL,
  created_at DATETIME,
  UNIQUE KEY uk_no_class_dept_semester (dept_id, semester)
);
```

- [ ] **Step 2: 写实体**

```java
package com.pams.module.schedule.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 批量导入生成的无课表（按部门+学期只保留最新一份）。gridJson 为 NoClassScheduleRowVO 列表的 JSON。 */
@Data
@Entity
@Table(name = "no_class_schedule")
public class NoClassScheduleRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long deptId;
    private String deptName;
    @Column(nullable = false, length = 20)
    private String semester;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String gridJson;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 3: 写仓库**

```java
package com.pams.module.schedule.repository;

import com.pams.module.schedule.entity.NoClassScheduleRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NoClassScheduleRecordRepository extends JpaRepository<NoClassScheduleRecord, Long> {
    Optional<NoClassScheduleRecord> findByDeptIdAndSemester(Long deptId, String semester);
}
```

- [ ] **Step 4: 写 DTO**

```java
package com.pams.module.schedule.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class NoClassScheduleGeneratedVO {
    private String deptName;
    private String semester;
    private List<NoClassScheduleRowVO> rows;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 5: 验证编译与迁移生效**

Run: `cd pams-backend && mvn -q test -Dtest='AuthIntegrationTest,PermissionGuardIntegrationTest'`
Expected: PASS —— 这两个测试加载完整 Spring 上下文 + Flyway，V10 在 H2 应用成功（否则上下文启动失败）。

- [ ] **Step 6: 提交**

```bash
git add pams-backend/src/main/resources/db/migration/V10__no_class_schedule.sql pams-backend/src/main/java/com/pams/module/schedule/entity/NoClassScheduleRecord.java pams-backend/src/main/java/com/pams/module/schedule/repository/NoClassScheduleRecordRepository.java pams-backend/src/main/java/com/pams/module/schedule/dto/NoClassScheduleGeneratedVO.java
git commit -m "feat(schedule): add no_class_schedule table, entity, repository and VO"
```

---

## Task 2: ImportService 持久化 + getGenerated

**Files:**
- Modify: `pams-backend/src/main/java/com/pams/module/schedule/service/NoClassScheduleImportService.java`
- Test: `pams-backend/src/test/java/com/pams/module/schedule/NoClassScheduleImportServiceTest.java`

**Interfaces:**
- Consumes: `NoClassScheduleRecordRepository`、`NoClassScheduleRowVO`、`NoClassScheduleGeneratedVO`、`ObjectMapper`
- Produces:
  - `importTimetables(...)` 生成成功后覆盖式持久化该 dept+semester 的无课表网格
  - `getGenerated(Long deptId, String semester) → NoClassScheduleGeneratedVO`（无记录或 semester 为空返回 null）
  - 构造器改为 `NoClassScheduleImportService(DepartmentRepository, NoClassScheduleRecordRepository)`

- [ ] **Step 1: 先改失败测试**

在 `NoClassScheduleImportServiceTest.java`：构造器改为传 mock 的 `NoClassScheduleRecordRepository`；新增两个测试：

```java
package com.pams.module.schedule;

// 现有 imports 保留，追加：
import com.pams.module.schedule.dto.NoClassScheduleGeneratedVO;
import com.pams.module.schedule.repository.NoClassScheduleRecordRepository;

class NoClassScheduleImportServiceTest {

    private NoClassScheduleRecordRepository recordRepo;

    // setup 里：recordRepo = mock(NoClassScheduleRecordRepository.class);

    @Test
    void importPersistsGeneratedGrid(@TempDir Path tempDir) throws Exception {
        DepartmentRepository deptRepo = mock(DepartmentRepository.class);
        Department dept = new Department(); dept.setId(1L); dept.setName("文秘部");
        when(deptRepo.findById(1L)).thenReturn(Optional.of(dept));
        NoClassScheduleImportService service = new NoClassScheduleImportService(deptRepo, recordRepo);
        service.setUploadDir(tempDir.toString());

        MockMultipartFile f = new MockMultipartFile("files", "张三-文件-2025物联网3班-班级课表.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ClassTimetableParserTest.buildTimetable());
        service.importTimetables(List.of(f), 1L, "2025-2026-2", null);

        verify(recordRepo).save(any(NoClassScheduleRecord.class));
    }

    @Test
    void getGenerated_returnsStoredRows(@TempDir Path tempDir) throws Exception {
        // 预置一条记录
        NoClassScheduleRecord rec = new NoClassScheduleRecord();
        rec.setId(1L); rec.setDeptId(1L); rec.setDeptName("文秘部"); rec.setSemester("2025-2026-2");
        rec.setGridJson("[{\"period\":1,\"label\":\"第一二节\",\"halfDay\":\"上午\",\"days\":{\"1\":[{\"name\":\"张三\",\"freeWeeks\":\"17-18\"}]}}]");
        rec.setCreatedAt(java.time.LocalDateTime.now());
        when(recordRepo.findByDeptIdAndSemester(1L, "2025-2026-2")).thenReturn(Optional.of(rec));

        NoClassScheduleImportService service = new NoClassScheduleImportService(mock(DepartmentRepository.class), recordRepo);
        NoClassScheduleGeneratedVO vo = service.getGenerated(1L, "2025-2026-2");
        assertThat(vo.getDeptName()).isEqualTo("文秘部");
        assertThat(vo.getRows()).hasSize(1);
        assertThat(vo.getRows().get(0).getDays().get("1").get(0).getName()).isEqualTo("张三");

        // 无记录返回 null
        when(recordRepo.findByDeptIdAndSemester(2L, "2025-2026-2")).thenReturn(Optional.empty());
        assertThat(service.getGenerated(2L, "2025-2026-2")).isNull();
        // semester 为空返回 null
        assertThat(service.getGenerated(1L, "  ")).isNull();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd pams-backend && mvn -q test -Dtest=NoClassScheduleImportServiceTest`
Expected: FAIL（构造器签名不匹配，`getGenerated` 不存在）。

- [ ] **Step 3: 改 Service**

在 `NoClassScheduleImportService.java`：

```java
// imports 追加
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.module.schedule.dto.NoClassScheduleGeneratedVO;
import com.pams.module.schedule.dto.NoClassScheduleRowVO;
import com.pams.module.schedule.entity.NoClassScheduleRecord;
import com.pams.module.schedule.repository.NoClassScheduleRecordRepository;
import java.util.Optional;

// 字段追加
private final ObjectMapper objectMapper = new ObjectMapper();
private final NoClassScheduleRecordRepository recordRepository;

// 构造器改为
public NoClassScheduleImportService(DepartmentRepository departmentRepository,
                                    NoClassScheduleRecordRepository recordRepository) {
    this.departmentRepository = departmentRepository;
    this.recordRepository = recordRepository;
}
```

在 `importTimetables` 里 `markdown = NoClassScheduleMarkdownWriter.write(...)` 之后、`return toVO(...)` 之前加：

```java
            persistRecord(deptId, deptName, safeSem, rows);
```

新增方法（放在 `resolveDownload` 前）：

```java
    /** 生成后覆盖式持久化：同部门+学期只保留最新一份。 */
    private void persistRecord(Long deptId, String deptName, String semester, List<NoClassScheduleRow> rows) {
        try {
            recordRepository.findByDeptIdAndSemester(deptId, semester).ifPresent(recordRepository::delete);
            NoClassScheduleRecord rec = new NoClassScheduleRecord();
            rec.setDeptId(deptId);
            rec.setDeptName(deptName);
            rec.setSemester(semester);
            rec.setGridJson(objectMapper.writeValueAsString(toRowVOs(rows)));
            rec.setCreatedAt(LocalDateTime.now());
            recordRepository.save(rec);
        } catch (Exception e) {
            throw new BizException(2704, "无课表保存失败: " + e.getMessage());
        }
    }

    /** 读取某部门+学期最新生成的无课表；无记录或 semester 为空返回 null。 */
    public NoClassScheduleGeneratedVO getGenerated(Long deptId, String semester) {
        if (semester == null || semester.isBlank()) return null;
        Optional<NoClassScheduleRecord> opt = recordRepository.findByDeptIdAndSemester(deptId, semester);
        if (opt.isEmpty()) return null;
        NoClassScheduleRecord rec = opt.get();
        try {
            List<NoClassScheduleRowVO> rows = objectMapper.readValue(rec.getGridJson(), new TypeReference<>() {});
            NoClassScheduleGeneratedVO vo = new NoClassScheduleGeneratedVO();
            vo.setDeptName(rec.getDeptName());
            vo.setSemester(rec.getSemester());
            vo.setRows(rows);
            vo.setCreatedAt(rec.getCreatedAt());
            return vo;
        } catch (Exception e) {
            throw new BizException(2704, "无课表读取失败: " + e.getMessage());
        }
    }
```

把 `toVO` 里「generator rows → VO rows」的映射提取为私有方法（供 toVO 与 persistRecord 复用）：

```java
    private List<NoClassScheduleRowVO> toRowVOs(List<NoClassScheduleRow> rows) {
        return rows.stream().map(r -> {
            NoClassScheduleRowVO rv = new NoClassScheduleRowVO();
            rv.setPeriod(r.period());
            rv.setLabel(r.label());
            rv.setHalfDay(r.halfDay());
            Map<String, List<NoClassScheduleCellVO>> days = new LinkedHashMap<>();
            r.cells().forEach((day, cells) -> days.put(String.valueOf(day),
                    cells.stream().map(c -> {
                        NoClassScheduleCellVO cv = new NoClassScheduleCellVO();
                        cv.setName(c.name());
                        cv.setFreeWeeks(c.freeWeeks());
                        return cv;
                    }).toList()));
            rv.setDays(days);
            return rv;
        }).toList();
    }
```

（`toVO` 改用 `vo.setRows(toRowVOs(rows))`。）

- [ ] **Step 4: 运行测试确认通过**

Run: `cd pams-backend && mvn -q test -Dtest=NoClassScheduleImportServiceTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add pams-backend/src/main/java/com/pams/module/schedule/service/NoClassScheduleImportService.java pams-backend/src/test/java/com/pams/module/schedule/NoClassScheduleImportServiceTest.java
git commit -m "feat(schedule): persist generated no-class-schedule and add getGenerated"
```

---

## Task 3: Controller 端点

**Files:**
- Modify: `pams-backend/src/main/java/com/pams/module/schedule/controller/CourseScheduleController.java`
- Test: `pams-backend/src/test/java/com/pams/module/schedule/CourseScheduleControllerTest.java`

**Interfaces:**
- Consumes: `NoClassScheduleImportService.getGenerated(Long, String)`
- Produces: `GET /api/course-schedules/generated?deptId=&semester= → Result<NoClassScheduleGeneratedVO>`（无记录 data=null）

- [ ] **Step 1: 写失败测试**

在 `CourseScheduleControllerTest.java` 追加：

```java
    @Test
    void generatedEndpointDelegatesToService() throws Exception {
        NoClassScheduleImportService importService = mock(NoClassScheduleImportService.class);
        NoClassScheduleGeneratedVO vo = new NoClassScheduleGeneratedVO();
        vo.setDeptName("文秘部");
        when(importService.getGenerated(1L, "2025-2026-2")).thenReturn(vo);
        CourseScheduleController ctl = new CourseScheduleController(mock(CourseScheduleService.class), importService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(ctl).build();
        mvc.perform(get("/api/course-schedules/generated").param("deptId", "1").param("semester", "2025-2026-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deptName").value("文秘部"));
    }
```

（imports 追加：`NoClassScheduleGeneratedVO`、`static get`、`static MockMvcRequestBuilders.get`。）

- [ ] **Step 2: 运行测试确认失败**

Run: `cd pams-backend && mvn -q test -Dtest=CourseScheduleControllerTest`
Expected: FAIL（`getGenerated` 端点不存在，404）。

- [ ] **Step 3: 实现端点**

在 `CourseScheduleController.java` 的 `download` 方法后新增：

```java
    /** 读取某部门+学期最新生成的无课表（无记录 data=null）。 */
    @GetMapping("/generated")
    public Result<NoClassScheduleGeneratedVO> generated(@RequestParam(required = false) Long deptId,
                                                        @RequestParam(required = false) String semester) {
        return Result.ok(importService.getGenerated(deptId, semester));
    }
```

（imports 追加：`com.pams.module.schedule.dto.NoClassScheduleGeneratedVO`。）

- [ ] **Step 4: 运行测试确认通过**

Run: `cd pams-backend && mvn -q test -Dtest=CourseScheduleControllerTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add pams-backend/src/main/java/com/pams/module/schedule/controller/CourseScheduleController.java pams-backend/src/test/java/com/pams/module/schedule/CourseScheduleControllerTest.java
git commit -m "feat(schedule): add generated no-class-schedule read endpoint"
```

---

## Task 4: 删除旧 free_schedule 全链路

**Files:**
- Delete: `pams-backend/src/main/java/com/pams/module/routine/controller/FreeScheduleController.java`
- Delete: `pams-backend/src/main/java/com/pams/module/routine/dto/FreeScheduleRequest.java`
- Delete: `pams-backend/src/main/java/com/pams/module/routine/entity/FreeSchedule.java`
- Delete: `pams-backend/src/main/java/com/pams/module/routine/repository/FreeScheduleRepository.java`
- Modify: `pams-backend/src/main/java/com/pams/module/routine/service/RoutineService.java`
- Modify: `pams-backend/src/main/java/com/pams/config/DataSeeder.java`
- Create: `pams-backend/src/main/resources/db/migration/V11__drop_free_schedule.sql`

**Interfaces:**
- Consumes: 无（纯删除）
- Produces: 无 FreeSchedule 残留；V11 删表；`RoutineService` 构造器改为 3 参（无 FreeScheduleRepository）

- [ ] **Step 1: 删除 4 个 FreeSchedule 文件**

```bash
git rm pams-backend/src/main/java/com/pams/module/routine/controller/FreeScheduleController.java pams-backend/src/main/java/com/pams/module/routine/dto/FreeScheduleRequest.java pams-backend/src/main/java/com/pams/module/routine/entity/FreeSchedule.java pams-backend/src/main/java/com/pams/module/routine/repository/FreeScheduleRepository.java
```

- [ ] **Step 2: 清理 `RoutineService.java`**

- 删 imports：`FreeScheduleRequest`、`FreeSchedule`、`FreeScheduleRepository`。
- 删字段 `private final FreeScheduleRepository freeScheduleRepository;`。
- 主构造器（@Autowired）从 4 参改为 3 参，删除 `FreeScheduleRepository freeScheduleRepository` 参数与 `this.freeScheduleRepository = freeScheduleRepository;`：
```java
    public RoutineService(ScheduleRepository scheduleRepository,
                          SchedulePersonRepository schedulePersonRepository,
                          AttendanceRepository attendanceRepository) {
        this.scheduleRepository = scheduleRepository;
        this.schedulePersonRepository = schedulePersonRepository;
        this.attendanceRepository = attendanceRepository;
    }
```
- 测试构造器调用改为 3 参：
```java
    public RoutineService(AttendanceRepository attendanceRepository) {
        this(null, null, attendanceRepository);
    }
    public RoutineService(ScheduleRepository scheduleRepository, AttendanceRepository attendanceRepository) {
        this(scheduleRepository, null, attendanceRepository);
    }
```
- 删除整个 `// ==================== 无课表 ====================` 段（`listFreeSchedules`/`createFreeSchedule`/`updateFreeSchedule`/`deleteFreeSchedule`/`applyFreeSchedule`）。

- [ ] **Step 3: 清理 `DataSeeder.java`**

- 删 imports：`FreeSchedule`、`FreeScheduleRepository`。
- 删字段 `private final FreeScheduleRepository freeScheduleRepository;`、构造参数、赋值。
- 删除「---------- 10. 无课表（一条） ----------」种子块（李想，约 `FreeSchedule fs = new FreeSchedule(); ... freeScheduleRepository.save(fs);`）。

- [ ] **Step 4: 写迁移 `V11__drop_free_schedule.sql`**

```sql
DROP TABLE IF EXISTS free_schedule;
```

- [ ] **Step 5: 全量后端测试**

Run: `cd pams-backend && mvn test`
Expected: 全绿（含新增 V10/V11 迁移在 H2 应用、上下文测试通过；无 FreeSchedule 残留导致编译错误）。

- [ ] **Step 6: 确认无残留**

Run: `grep -rn "FreeSchedule\|free_schedule\|freeSchedule" pams-backend/src --include="*.java" | grep -v "NoClassSchedule" || echo "clean"`
Expected: `clean`（或仅无课表相关类名含 NoClassSchedule 被排除）。

- [ ] **Step 7: 提交**

```bash
git add -A pams-backend/src/main/java/com/pams/module/routine pams-backend/src/main/java/com/pams/config/DataSeeder.java pams-backend/src/main/resources/db/migration/V11__drop_free_schedule.sql
git commit -m "refactor(schedule): remove manual free_schedule backend and drop its table"
```

---

## Task 5: 前端重构

**Files:**
- Modify: `pams-web/src/api/courseSchedule.ts`
- Modify: `pams-web/src/pages/routine/FreeScheduleList.tsx`
- Delete: `pams-web/src/api/freeSchedule.ts`

**Interfaces:**
- Consumes: `get`（./http）、`listDepts`、`useAuthStore`
- Produces: `getGeneratedNoClassSchedule(deptId?, semester?) → Promise<NoClassScheduleGeneratedVO>`；FreeScheduleList 渲染生成网格

- [ ] **Step 1: `api/courseSchedule.ts` 追加类型与函数**

```ts
export interface NoClassScheduleGeneratedVO {
  deptName: string
  semester: string
  rows: NoClassScheduleRowVO[]
  createdAt: string
}

/** 读取某部门+学期最新生成的无课表（无记录返回 null） */
export const getGeneratedNoClassSchedule = (deptId?: number, semester?: string) =>
  get<NoClassScheduleGeneratedVO | null>('/course-schedules/generated', { deptId, semester })
```

- [ ] **Step 2: 重写 `FreeScheduleList.tsx`**

```tsx
import { useEffect, useState } from 'react'
import { Button, Empty, Select, Space, Spin } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import GlassCard from '@/components/glass/GlassCard'
import PageHeader from '@/components/glass/PageHeader'
import { getGeneratedNoClassSchedule, type NoClassScheduleGeneratedVO } from '@/api/courseSchedule'
import { listDepts, type DeptVO } from '@/api/dept'
import { useAuthStore } from '@/stores/auth'

const SEMESTERS = ['2025-2026-2', '2026-2027-1', '2025-2026-1', '2024-2025-2']
const WEEKDAYS = ['周一', '周二', '周三', '周四', '周五']

export default function FreeScheduleList() {
  const user = useAuthStore((s) => s.user)
  const [depts, setDepts] = useState<DeptVO[]>([])
  const [deptId, setDeptId] = useState<number | undefined>(user?.deptId ?? undefined)
  const [semester, setSemester] = useState('2025-2026-2')
  const [data, setData] = useState<NoClassScheduleGeneratedVO | null>(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    listDepts()
      .then((res) => setDepts(res ?? []))
      .catch(() => { /* 拦截已提示 */ })
  }, [])

  const load = () => {
    setLoading(true)
    getGeneratedNoClassSchedule(deptId, semester)
      .then((res) => setData(res ?? null))
      .catch(() => setData(null))
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() /* eslint-disable-line react-hooks/exhaustive-deps */ }, [deptId, semester])

  return (
    <div>
      <PageHeader title="无课表" description="批量导入课表自动生成的无课表，按部门+学期查看" />
      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space wrap>
          <Select
            placeholder="部门"
            allowClear
            style={{ width: 180 }}
            options={depts.map((d) => ({ value: d.id, label: d.name }))}
            value={deptId}
            onChange={setDeptId}
          />
          <Select
            style={{ width: 180 }}
            options={SEMESTERS.map((s) => ({ value: s, label: s }))}
            value={semester}
            onChange={setSemester}
          />
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          {data && <span style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>生成时间：{data.createdAt?.replace('T', ' ')}</span>}
        </Space>
      </GlassCard>
      <GlassCard style={{ padding: 16 }}>
        <Spin spinning={loading}>
          {!data ? (
            <Empty description="该部门+学期还没有生成的无课表，请到「无课表制作 → 批量导入」生成" />
          ) : (
            <div style={{ overflow: 'auto' }}>
              <table style={{ borderCollapse: 'collapse', fontSize: 13, minWidth: 720 }}>
                <thead>
                  <tr>
                    <th style={{ padding: '6px 10px', border: '1px solid var(--color-border)', background: 'var(--color-bg-2)' }}>节次</th>
                    {WEEKDAYS.map((d) => (
                      <th key={d} style={{ padding: '6px 10px', border: '1px solid var(--color-border)', background: 'var(--color-bg-2)' }}>{d}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((row) => (
                    <tr key={row.period}>
                      <td style={{ padding: '6px 10px', border: '1px solid var(--color-border)', whiteSpace: 'nowrap', color: 'var(--color-text-secondary)' }}>{row.label}</td>
                      {[1, 2, 3, 4, 5].map((day) => (
                        <td key={day} style={{ padding: '6px 10px', border: '1px solid var(--color-border)', verticalAlign: 'top', minWidth: 140 }}>
                          {(row.days[String(day)] ?? []).map((c) => (
                            <div key={c.name}>{c.name}（{c.freeWeeks}）</div>
                          ))}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Spin>
      </GlassCard>
    </div>
  )
}
```

- [ ] **Step 3: 删除 `api/freeSchedule.ts`**

```bash
git rm pams-web/src/api/freeSchedule.ts
```

- [ ] **Step 4: 构建**

Run: `cd pams-web && npm run build`
Expected: tsc 零错误、vite 构建通过（确认无残留 import 引用 freeSchedule）。

- [ ] **Step 5: 提交**

```bash
git add pams-web/src/api/courseSchedule.ts pams-web/src/pages/routine/FreeScheduleList.tsx
git commit -m "feat(web): render generated no-class-schedule on 无课表 page, drop manual fields"
```

---

## Task 6: 全量验证

**Files:** 无新增（验证性任务）

- [ ] **Step 1: 后端全量测试**

Run: `cd pams-backend && mvn test`
Expected: 全绿。

- [ ] **Step 2: 前端全量构建**

Run: `cd pams-web && npm run build`
Expected: 通过。

- [ ] **Step 3: 浏览器冒烟**

1. 后端、前端已运行（`cmd //c start.bat` 或我重启）。
2. 用 `secleader`/123456 登录 → 无课表制作 → 批量导入 → 上传 2 个真实课表生成（文秘部、2025-2026-2）。
3. 进入排班考勤 → 无课表 → 选部门=文秘部、学期=2025-2026-2 → 应直接渲染刚才生成的网格（与批量导入预览一致）。
4. 换一个没生成过的部门/学期 → 显示空态引导。
5. 确认无课表页面没有手工字段/增删改按钮。

- [ ] **Step 4: 如发现问题，回对应任务修复并重跑**

- [ ] **Step 5: 提交剩余改动（如有）**

---

## Self-Review

- **Spec 覆盖**：存库（R1+R2）→ 端点（R3）→ 删除旧全链路（R4）→ 前端重构（R5）→ 验证（R6）。全部设计项有对应任务。
- **顺序安全**：V10（建新表）在 R1，早于 R2 的持久化；V11（删旧表）在 R4，与旧代码删除同任务，避免「表已删但 DataSeeder 仍插入」的启动崩溃。
- **占位符**：无 TBD/TODO；代码步骤含完整实现。
- **类型一致**：`NoClassScheduleGeneratedVO` 在 R1 定义、R2/R3 消费；`getGenerated(Long,String)` 签名 R2 产出、R3 消费；前端 `NoClassScheduleGeneratedVO` 类型与后端 VO 字段一致；`NoClassScheduleRowVO.days` 为 `Record<string, NoClassScheduleCellVO[]>`，与后端 `Map<String, List<...>>` 序列化一致。
