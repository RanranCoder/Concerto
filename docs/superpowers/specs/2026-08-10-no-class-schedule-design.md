# 无课表生成（批量导入课表 → 自动生成无课表）— 设计文档

> **日期**: 2026-08-10
> **状态**: 已获用户批准（方案三：算法模块 + 上传接口 + 前端批量导入入口）

## 背景与目标

文秘部每学期要收集全部门干事的班级课表，手动汇总生成每个人的"无课表"（有课的相反：哪些周没课），用于值班排班、开会、活动安排。目前系统内"无课表制作"只支持**在线一格一格点选**录入课程（`CourseSchedule.tsx`「我的课程表」Tab），全部门几十人手工点选极其耗时；PRD-F08 规划的"录入方式二：Excel 上传 → AI 自动解析"一直未实现（实现进度文档标注：后端已具备 POI 能力，可后续补上传接口）。

**目标**：实现"批量导入班级课表 Excel → 自动生成无课表"，覆盖从上传到成表的完整链路：

1. 后端新增**独立算法模块**（纯 Java、仅依赖已有 POI、不依赖 Spring/DB，可独立单测）解析班级课表并计算无课周；
2. 暴露**批量上传生成接口**，业务系统可调用；
3. 前端"无课表制作"页新增**「批量导入」Tab**，用户选部门+学期+多文件即可生成无课表并下载。

## 关键决策（已与用户确认）

1. **方案三**：算法模块 + 上传接口 + 前端批量导入入口（非纯模块，也非仅后端）。
2. **命名通用化**：一律用"无课表"/`NoClassSchedule`，**不用**"干部无课表"/`CadreFreeSchedule` 等窄词。
3. **输出双产物**：xlsx（匹配现在部门使用的无课表版式）+ Markdown 表格（对话/文档预览）。
4. **姓名来源**：文件名自动解析（覆盖 `张子睿-文件-…`、`刘如倩_…`、`罗展标-…`、`凌健锦…` 四种模式），解析失败记入失败清单，不阻塞其他文件。
5. **无地点后缀**：无课表是"空闲周次"，与上课地点无关；输出不加"（X教）"等地点标注（地点只在原始班级课表里保留）。
6. **周次与列范围**：全集默认第 1~18 周（可配置）；输出列仅周一~周五（周六日课表全空，无信息量）。
7. **输出目录**：统一写到 `uploads/无课表/<部门名>/<学期>_无课表.xlsx`（复用 `pams.upload-dir`）。

## 现状盘点

- 后端 `com.pams.module.schedule`：`CourseSchedule`（个人课表矩阵 `course_schedule` 表）、`ScheduleConfig`（时间格）、`CourseScheduleController`（`/api/course-schedules` 的 configs/mine/analyze）、`CourseScheduleService.analyzeFreeTime`（共同空闲热力图）。
- 后端 `com.pams.module.routine`：`FreeSchedule`（`free_schedule` 表，手工维护空闲周）、`RoutineService`（排班/考勤/无课表 CRUD + `exportExcel`）。
- `pom.xml` 已有 `poi-ooxml 5.4.1`。
- 前端 `CourseSchedule.tsx`：「我的课程表」（点选）+「共同空闲分析」（热力图）两个 Tab；`api/courseSchedule.ts` 已有 HTTP 封装。
- 输入课表格式（实测 `文秘部/新媒体中心/组织部/青年科技部` 四目录 50 个文件）：单 sheet（名如 `2025-2026-2课表`），A1 班级代码/院系、E1 班级名、H1 学期；第 2 行表头 `节次 | 星期一…星期日`（C~I 列）；第 3~8 行节次 `第1 2节…第11 12节`；单元格内多门课用 `\n`（实际为 `\n\n`）分隔，格式 `课程名(周次)[节次]◇教室◇教师`。周次覆盖 `(1-16)`、`(1-13 单)`、`(2-18 双)`、`(3;7)`、`(3-6;11-18)`、`(1-4;9-18)`、`(5-7 单)`、单周 `(8)` 等；体育/公共选修地点为"预占位"。

## 设计

### 1. 后端模块划分（`com.pams.module.schedule.generator`）

**算法层**（纯 Java，仅依赖 POI，无 Spring/DB 依赖，可独立单测）：

| 类 | 职责 | 关键逻辑 |
|---|---|---|
| `WeekRangeParser` | 周次字符串 → 有课周 `Set<Integer>` | 按 `;` 切多段；每段识别 `单`/`双` 标记；提取 `a-b` 范围或单点；按 `maxWeek`（默认 18）截断；过滤单/双周 |
| `ClassTimetableParser` | 读班级课表 → `Map<SlotKey, List<Course>>` | 定位"节次"单元格 → 周一到周日列 + 6 行节次；按 `\n` 拆多课；非贪婪正则抽 `(周次)`（防课程名带括号）；`◇` 切教室/教师 |
| `FreeWeekCalculator` | 每时段无课周 | 全集 `{1..maxWeek}` − 该时段所有课有课周**并集**（补集） |
| `FreeWeekFormatter` | 无课周 → 显示串 | 步长 1 连续段 `1-8`、步长 2 单双段 `10-12 双`、逗号拼接；空集→`0`；全集→`1-18` |
| `TimetableNameExtractor` | 文件名 → 姓名 | 依次尝试 `-文件-`、首段 `[_-]`、前导中文名 `^[一-龥]{2,4}`；失败返回 null |
| `NoClassScheduleGenerator` | 批量编排 | 输入 `(List<Path>, deptName, semester, maxWeek)` → 输出"无课表"模型（每行=节次，每列=星期1~5，每格=按姓名排序的 `姓名（无课周）` 列表）+ 每文件失败信息 |

**输出层**：

- `NoClassScheduleExcelWriter`：写 xlsx（版式见 §3），返回 `Path`。
- `NoClassScheduleMarkdownWriter`：写 Markdown 表格字符串。

**Spring 集成层**：

- `NoClassScheduleImportService`：收 multipart 多文件 + `deptId` + `semester` → 写临时文件 → 调 `NoClassScheduleGenerator` → 生成 xlsx/md 到统一输出目录 → 组装结果 VO。
- `CourseScheduleController` 新增：
  - `POST /api/course-schedules/import`：multipart `files`(可多个) + `deptId` + `semester` + 可选 `maxWeek` → `Result<NoClassScheduleImportVO>`。
  - `GET /api/course-schedules/import/download?file=<文件名>`：流式下载生成的 xlsx。

### 2. 解析与计算规则

- **表头定位**：找到值为"节次"的单元格；其右侧一行的 `星期一…星期日` 为列（映射 1~7），其下方 `第1 2节…` 为行（映射时段 1~6）。用关键词定位而非硬编码行列，兼容模板差异。
- **单元格拆分**：先按 `\n`/`\r\n` 拆多条课程记录；每条用非贪婪正则从尾部找 `(周次)`；`课程名(周次)[节次]◇教室◇教师` 中 `◇` 切出教室/教师字段。
- **有课判定**：某课程周次覆盖的周即该(星期,节次)有课；同格多课取并集。
- **无课周** = 全集 − 有课并集（补集）；补集为空 → `0`。
- **预占位课程**（体育/公共选修，教室="预占位"）：计入有课周（占用时段），无地点信息。
- **输出维度**：周一~周五 × 6 行节次（第一二节…第十一十二节）。
- **姓名失败**：文件无法提取姓名 → 记入 `failed` 清单（含文件名），跳过该文件，不阻塞整体。
- **学期校验**：以接口参数为准；课表 sheet 名或 H1 中的学期与所选不一致时，结果里给出 `warnings`。
- **无法解析的课程记录**：跳过并记 warning；整个文件结构无法识别 → 记入 `failed`。

### 3. 输出格式

**xlsx（匹配现有部门无课表版式）**：

```
A1（合并 A1:G1）: 无课表 · <部门名> · <学期>
第 2 行:  节次 | 星期一 | 星期二 | 星期三 | 星期四 | 星期五   （C~G）
第 3~8 行: A列 上午(3-4)/下午(5-6)/晚上(7-8) 合并；B列 第一二节…第十一十二节；
           C~G 每格多行「姓名（无课周次）」，例：张子睿（17-18）
```

- 边框、列宽、自动换行；sheet 名 = 部门名（`文秘部`）。

**Markdown**：同结构表格（表头 `节次 | 星期一…星期五`，每格 `姓名（无课周次）` 用 `<br>` 换行），便于对话/文档直接看。

### 4. 结果 VO（`NoClassScheduleImportVO`）

```
{
  deptName, semester,
  rows: [ { period, label, days: { "1": [{name, freeWeeks},...], ... "5": [...] } } ],  // 无课表网格
  markdown: "...",                     // 可直接展示/复制的 md
  fileName, downloadUrl,               // 生成的 xlsx
  summary: { totalFiles, success, failed: [{fileName, reason}] },
  warnings: [...]
}
```

### 5. 前端（`CourseSchedule.tsx` 新增「批量导入」Tab）

- 表单：部门下拉（默认当前用户部门，部长以上可切）+ 学期下拉（复用 `SEMESTERS`）+ `Upload`（多选 `.xlsx`，`beforeUpload` 拦截非 xlsx）。
- 「生成无课表」→ `POST /api/course-schedules/import` → 展示：
  - 无课表预览（复用热力图那套 `table` 样式，每格渲染 `姓名（无课周次）` 换行）；
  - 「下载 Excel」按钮（`downloadUrl` 下载）+ 「复制 Markdown」按钮；
  - 成功/失败统计与失败文件清单、warnings。
- `api/courseSchedule.ts` 新增 `importNoClassSchedules(formData)` 与 `downloadNoClassScheduleXlsx(name)`。

### 6. 错误处理与边界

- 非 xlsx / 损坏文件：`beforeUpload` 前端拦截 + 后端校验，记入 `failed`。
- `.doc`（如 `许涣楠-文件-学生课表.doc`）：不支持，记入 `failed`（v1 仅支持 `.xlsx`）。
- 空部门/空文件列表：接口返回业务错误（`BizException`）。
- `maxWeek` 越界（<1 或 >30）：默认回 18。

### 7. 测试

**后端单元测试**（`src/test`）：

| 用例 | 断言 |
|---|---|
| `WeekRangeParser`：`(1-16)`/`(1-13 单)`/`(2-18 双)`/`(3;7)`/`(3-6;11-18)`/`(1-4;9-18)`/`(8)`/`(5-7 单)` 及超 18 周截断 | 有课周集合正确 |
| `FreeWeekCalculator`：多课并集、补集 | 无课周正确 |
| `FreeWeekFormatter`：连续段/单双段/混合/空集→`0`/全集→`1-18` | 显示串正确 |
| `TimetableNameExtractor`：四种文件名模式 + 识别失败 | 姓名正确 / null |
| `ClassTimetableParser` + 计算：真实课表文件（张子睿/文佳沁/陈立权/司徒锦豪/凌健锦） | 已知时段无课周正确（如张子睿 周一第1-2节：Web前端(1-16)→无课 17-18；周五第1-2节：习概(1-13单)+Windows(2-18双)=1-18 全覆盖→`0`） |
| 端到端：临时目录真实文件 → xlsx/md 生成 | 文件存在、内容含 `姓名（…）` |

**前端**：`tsc` + `vite build` 通过；浏览器手动验证上传→预览→下载链路。

## 验收标准

1. `mvn -pl pams-backend test`（新算法单测）全绿；启动后 `POST /api/course-schedules/import` 传 2 个真实课表能返回正确无课表网格。
2. `uploads/无课表/<部门名>/<学期>_无课表.xlsx` 生成，版式与现有部门无课表一致。
3. 前端「批量导入」Tab 能完成：选部门学期 → 传多文件 → 预览 → 下载 Excel / 复制 Markdown；失败文件有提示。
4. 周次格式、满课 `0`、单双周、分段等边界用例在单测中覆盖。

## 非目标 / 后续

- **不落库**：v1 不写入 `course_schedule`/`free_schedule` 表（现有模型是"周次无关"的有课/无课布尔，无法表达单双周；供热力图复用需先扩展周次感知模型）。生成结果以文件 + 预览交付。
- 不做 .doc/.docx 解析。
- 不做前端"逐文件改名/姓名覆盖"编辑（姓名识别失败 → 改文件名重传）。
- 不实现"最优时段推荐"的导入联动（沿用现有 analyzeFreeTime）。
