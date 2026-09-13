# 无课表页面直接渲染生成结果 + 移除手工字段 — 设计文档

> **日期**: 2026-08-10
> **状态**: 已获用户批准（决策：生成存库、只保留最新、删除旧手工字段全链路）

## 背景与目标

批量导入已能在「无课表制作」页生成无课表网格（节次×周一~周五，每格 `姓名（无课周次）`），但生成结果目前只存在于导入接口的**响应体**和磁盘 xlsx/markdown 文件里，没有持久化；「无课表」页面（`/routine/free-schedules`）仍在显示**手工维护**的字段（personName / freeWeeks JSON + 增删改）。

**目标**：
1. 批量导入生成的无课表网格**存入数据库**，「无课表」页面直接读取并渲染（样式与批量导入预览一致）。
2. 废弃并**删除**旧 free_schedule 手工字段的后端实体/表/CRUD、前端页面字段与调用。

## 关键决策（已与用户确认）

1. **生成时存入数据库**；同部门+学期**只保留最新**（覆盖式，不保留历史）。
2. 「无课表」页面重构为渲染生成网格：部门下拉 + 学期下拉 → 显示该部门+学期最新生成的网格。
3. 旧的 free_schedule 手工后端（实体/表/CRUD/RoutineService 方法/DataSeeder 种子）与前端 `api/freeSchedule.ts` **一并删除**。

## 设计

### 1. 后端：新表 + 实体 + 存储

**迁移 `V10__no_class_schedule.sql`**（放在 `pams-backend/src/main/resources/db/migration/`）：
```sql
CREATE TABLE IF NOT EXISTS no_class_schedule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  dept_id BIGINT,
  dept_name VARCHAR(50),
  semester VARCHAR(20) NOT NULL,
  grid_json TEXT NOT NULL,
  created_at DATETIME,
  UNIQUE KEY uk_dept_semester (dept_id, semester)
);
DROP TABLE IF EXISTS free_schedule;
```

- 实体 `com.pams.module.schedule.entity.NoClassScheduleRecord`（`@Table("no_class_schedule")`，字段同上）。
- 仓库 `NoClassScheduleRecordRepository extends JpaRepository<NoClassScheduleRecord, Long>`，方法 `Optional<NoClassScheduleRecord> findByDeptIdAndSemester(Long deptId, String semester)`。

**`NoClassScheduleImportService` 改动**：
- 注入 `ObjectMapper`（Spring 已有 bean）与 `NoClassScheduleRecordRepository`。
- `importTimetables` 生成成功后（`people` 非空时）：删除 `(deptId, semester)` 旧记录 → 保存新记录，`grid_json = ObjectMapper.writeValueAsString(rows VO)`（`List<NoClassScheduleRowVO>`，即导入响应里的 `rows`，前端已能渲染该结构），`createdAt = now`。
- 新增 `getGenerated(Long deptId, String semester)`：按 `(deptId, semester)` 读记录，反序列化 `grid_json` → `List<NoClassScheduleRowVO>`；返回 `NoClassScheduleGeneratedVO`；无记录返回 `null`。

**DTO `NoClassScheduleGeneratedVO`**（`com.pams.module.schedule.dto`）：
```
deptName, semester, rows(List<NoClassScheduleRowVO>), createdAt
```

**`CourseScheduleController` 新增端点**：
```
GET /api/course-schedules/generated?deptId=&semester=  → Result<NoClassScheduleGeneratedVO>（无记录时 data=null）
```

### 2. 删除旧 free_schedule 全链路

**后端删除**：
- `module/routine/controller/FreeScheduleController.java`
- `module/routine/dto/FreeScheduleRequest.java`
- `module/routine/entity/FreeSchedule.java`
- `module/routine/repository/FreeScheduleRepository.java`
- `module/routine/service/RoutineService.java`：删除 free-schedule 方法（`listFreeSchedules`/`createFreeSchedule`/`updateFreeSchedule`/`deleteFreeSchedule`/`applyFreeSchedule`）、`freeScheduleRepository` 字段与对应构造参数、相关 import。
- `module/config/DataSeeder.java`：删除无课表种子块（李想）与 `FreeSchedule`/`FreeScheduleRepository` 引用、注入参数。

**前端删除**：
- `pams-web/src/api/freeSchedule.ts`（整文件）
- `FreeScheduleList.tsx` 不再引用它（见 §3）。

### 3. 前端「无课表」页面重构（`FreeScheduleList.tsx`）

- 顶部：**部门下拉**（复用 `listDepts`，默认当前用户 deptId）+ **学期下拉**（复用 `SEMESTERS`，默认 `2025-2026-2`）。
- 主体：调 `GET /api/course-schedules/generated` → 渲染无课表网格（节次 × 周一~周五，每格 `姓名（无课周次）` 换行），复用「批量导入」预览同一套 `table` 样式。
- 无数据（`data=null`）时显示空态引导："先去「无课表制作 → 批量导入」生成"。
- 移除手工字段表、展开人员行、增删改按钮与 `api/freeSchedule.ts` 引用。
- 路由/菜单不变（`/routine/free-schedules`，菜单「无课表」）。

**`api/courseSchedule.ts` 新增**：
```ts
export const getGeneratedNoClassSchedule = (deptId?: number, semester?: string) =>
  get<NoClassScheduleGeneratedVO>('/course-schedules/generated', { deptId, semester })
```
（`NoClassScheduleGeneratedVO` 类型：deptName, semester, rows, createdAt。）

### 4. 测试

**后端**（`pams-backend/src/test`）：
- `NoClassScheduleImportServiceTest` 增补：导入后 `no_class_schedule` 出现该 dept+semester 记录、再次导入覆盖、`getGenerated` 返回反序列化后的 rows、无记录返回 null。
- 删除 FreeSchedule 后全量 `mvn test` 通过（无 FreeSchedule 测试引用，已确认）。

**前端**：`npm run build`（tsc + vite）通过。

## 验收标准

1. 批量导入生成后，数据库 `no_class_schedule` 出现该部门+学期记录；同部门+学期再生成则覆盖。
2. 「无课表」页面选部门+学期 → 渲染生成的网格，样式与批量导入预览一致。
3. 无课表页面不再有手工字段/增删改；无数据时给出引导。
4. 旧 `free_schedule` 表被删，代码无 `FreeSchedule` 残留。
5. 后端 `mvn test` + 前端 `npm run build` 全绿。

## 非目标

- 不改批量导入的生成/解析/xlsx/markdown 输出逻辑。
- 不保留生成历史（只最新一份）。
- 不做无课表网格的在线编辑（只读展示）。
