# 批次 D 设计文档：策划书在线 Word 编辑器（wangEditor 5）改造

日期：2026-08-05 · 状态：待评审 · 关联 PRD：策划书编辑器（既有能力）、F09 消息通知触发点 #1/#4

## 1. 背景与目标

现策划书编辑器（`pams-web/src/components/word/WordEditor.tsx`）为自研 contenteditable 实现，存在明显局限：

- **12 节模板锁死**：`PLAN_TEMPLATE_SECTIONS` 固定 12 章，仅 7 章可编辑（background/purpose/content/flow/notice/emergency/budget），其余 5-6 章（活动名称/主题/时间/地点/组织单位/对象）只读、由活动 meta 自动填充，用户无法在线改动这些章节，也无法增删/重排章节。
- **工具栏极小**：仅 加粗、编号列表、插入表格、3 档字号，依赖已废弃的 `document.execCommand`；无 斜体/下划线/颜色/高亮/对齐/缩进/撤销重做/图片/链接/标题样式。
- **导出丢格式**：`planToDocx` 用 `stripHtml` 把富文本还原成纯文本段落，行内加粗、列表、除预算外的表格全部丢失；导入 `docxToPlan` 是 mammoth 纯文本按章节标题粗粒度切分。
- **无撤销重做、无图片**。

**目标**：用 **wangEditor 5**（已确认选型）替换自研编辑器，给足编辑自由度——富文本工具栏（标题/加粗/斜体/下划线/文字颜色/高亮/对齐/缩进/有序无序列表/表格/图片/链接/清除格式/撤销重做），支持图片上传，文档结构不再被 12 节模板锁死。

## 2. 方案总览

| 维度 | 决策 |
|------|------|
| 编辑器 | wangEditor 5（`@wangeditor/editor` + `@wangeditor/editor-for-react`） |
| 内容存储 | 后端 `activity_plan` 表现有 7 个 TEXT 字段（background/purpose/content/flow/notice/emergency/budget）**保持不变**，编辑内容仍落这 7 字段 |
| 展示形态 | 沿用现有「预览 / 编辑」双模式切换；预览仍用 `PagedWordPreview` |
| 导出/导入 | 导出 docx：保留富文本（用 docx 库把 HTML 逐元素转为段落/文本节点，避免 stripHtml 丢格式）；导入 docx：mammoth 保留 HTML 结构（`convertToHtml`），回填编辑器 |
| 图片 | 图片上传走现有 `/api/files/upload`，编辑器内容存图片 URL（引用 file_record） |
| 通知联动 | 保持现有「提交审核/审核通过/驳回」状态流；批次 C 补的「策划书编辑完成→通知所有部门」「主任修改→通知组织部」触发点在本编辑器保存动作上同样生效 |
| 新依赖 | `@wangeditor/editor`、`@wangeditor/editor-for-react`（均 MIT） |

### 2.1 为什么选 wangEditor 5 而非 TipTap / 增强现有

- **wangEditor 5**：中文生态成熟、API 面向 React、开箱即用富文本工具栏与图片上传、MIT 许可。与「给足自由度」目标最匹配，开发量最小。
- **TipTap（ProseMirror）**：扩展性最强但需自建工具栏/图片/撤销栈/导出管线，开发量最大，且本项目已有 `docx`/`mammoth` 导出导入基建，无需 ProseMirror 的 schema 约束。
- **增强现有 contenteditable**：仍受 execCommand 废弃、XSS 面、浏览器差异限制，无法真正「给足自由度」。

### 2.2 章节模板与自由编辑的平衡

用户要求「给足自由度」，但既有 12 节模板是业务惯例（参考真实策划书 docx）。方案：

1. **默认仍按 12 节骨架渲染**，但每节正文是自由富文本（wangEditor 实例）。
2. **放开节名编辑**：保留现有「双击节名重命名」交互，并新增「新增章节」「删除章节」「上移/下移章节」能力。
3. **只读章节（活动名称/主题/时间/地点/组织单位/对象）改为可覆盖**：默认显示 meta 填充值，用户可改为自由编辑文本（存 planFields 对应新字段或并入 content）。
   - **简化决策**：为避免数据模型大改，只读章节改为「可编辑但默认预填 meta 值」，编辑结果存回对应新字段（新增 6 个可选列，见 §3 数据模型）。

### 2.3 页面结构（目标）

```
活动详情 → 策划书 Tab
┌──────────────────────────────────────────────┐
│ [预览/编辑 切换] [导入docx] [导出docx] [保存]   │  ← 保留现有工具栏
├──────────────────────────────────────────────┤
│ 编辑模式：                                      │
│ ┌──────────┐ ┌──────────────────────────────┐ │
│ │ 章节导航   │ │ wangEditor 5 工具栏           │ │
│ │ ·活动名称 │ │ （标题/加粗/斜体/下划线/颜色/   │ │
│ │ ·活动主题 │ │  高亮/对齐/缩进/列表/表格/图片/  │ │
│ │ ·背景     │ │  链接/清除/撤销/重做）          │ │
│ │ ...       │ │ ┌──────────────────────────┐ │ │
│ │ [新增章节] │ │ │  编辑区（当前章节正文）      │ │ │
│ └──────────┘ │ │                            │ │ │
│              │ └──────────────────────────┘ │ │
│              └──────────────────────────────┘ │
└──────────────────────────────────────────────┘
```

编辑模式核心变化：由「12 个独立 contenteditable 区块」改为「左侧章节导航 + 右侧单个 wangEditor 实例」。选中章节时把该节正文载入编辑器，保存时写回对应字段。`planFields` 结构不变（7 字段 + 新增 6 个可覆盖字段），章节顺序/自定义节名存前端 state + 后端新列。

## 3. 数据模型

`activity_plan` 表**新增列**（V7 迁移，可选，默认 NULL，向后兼容）：

| 列 | 类型 | 说明 |
|----|------|------|
| name_override | TEXT | 活动名称可覆盖值（meta 优先，覆盖时非空） |
| theme_override | TEXT | 活动主题可覆盖值 |
| time_override | TEXT | 活动时间可覆盖值 |
| location_override | TEXT | 活动地点可覆盖值 |
| organizer_override | TEXT | 组织单位可覆盖值 |
| target_override | TEXT | 活动对象可覆盖值 |
| section_order | TEXT | 章节顺序 + 自定义节名 JSON（如 `[{"label":"三、活动背景","field":"background","customLabel":"活动背景"}]`，默认 NULL = 用默认模板） |

现有 7 字段（background/purpose/content/flow/notice/emergency/budget）**不变**，编辑内容仍存其中。`flow` 字段保留 JSON 数组或纯文本兼容。

> 简化取舍：第 2.2 节的「新增/删除/重排章节」能力归入 `section_order` 列承载；若评审认为该能力超出本期范围，可先只做「自由富文本 + 节名可改 + 只读章节可覆盖」，`section_order` 列为空即可（默认模板渲染）。此取舍见 §6 待定。

## 4. 前端改造（pams-web）

### 4.1 新增依赖
`@wangeditor/editor` + `@wangeditor/editor-for-react`（MIT）。包体积较大，用动态 import（`React.lazy` 或按需加载）避免拖慢首屏。

### 4.2 组件改造

- **新增 `PlanRichEditor.tsx`**：封装 wangEditor 5。
  - Props：`value`（当前章节 HTML）、`onChange`、`placeholder`。
  - 初始化 wangEditor 实例；工具栏配置（标题/加粗/斜体/下划线/文字颜色/高亮/对齐/缩进/有序无序列表/表格/图片/链接/清除格式/撤销重做）。
  - 图片：自定义上传（`customUpload` 调 `/api/files/upload`，返回文件 URL 插入）。
  - 用 `useEffect` 同步外部 value 变化（仅当外部值变化且与当前编辑器内容不同时 setHtml，避免光标跳动——沿用现有 WordEditor 的同步策略）。
- **改造 `WordEditor.tsx`** → 保留文件名与对外 props（`value: PlanFields`、`onChange`、`meta`、`customLabels`、`onCustomLabelChange`），内部渲染改为：左侧章节导航（含新增/删除/上移/下移）+ 右侧单个 `PlanRichEditor`。节名双击重命名交互保留。章节导航数据源改为 `section_order`（或默认模板）。
- **`PagedWordPreview.tsx`**：预览模式继续复用（它吃 HTML 字符串；只读章节可覆盖值由 meta 或 override 拼装传入）。

### 4.3 页面接线（ActivityDetail PlanTab）
- 编辑模式加载 `plan` 的 7 字段 + 新增 override 列 + section_order 到 `planFields` 状态；保存时 `updatePlan`/`createPlan` 提交全部字段。
- 导入 docx：`mammoth.convertToHtml`（保留富文本结构），按章节切分后回填各字段（若无法精确切分，整体放入 content 并提供提示）。
- 导出 docx：见 §4.4。
- 审核/提交按钮、状态流、通知联动保持现有逻辑。

### 4.4 导出 docx（保真）
替换 `planToDocx` 的 `stripHtml` 逻辑：用 docx 库将每章 HTML 解析为段落/文本节点——块级（p/div/h1-6/ul/ol/table）映射为 docx Paragraph/Table/List，行内（b/strong/i/em/u/span[style=color/font-size]）映射为 TextRun 的 bold/italics/underline/color/size。图片（`<img src=...>`）解析为 docx ImageRun（需 fetch URL → buffer，若 URL 是 `/api/files/{id}/download` 需带 JWT，导出时由前端拉取二进制）。预算字段（HTML 表格）继续用 `parseBudgetMatrix` 转 docx Table。

### 4.5 导入 docx（保真）
替换 `docxToPlan`：`mammoth.convertToHtml({ arrayBuffer })` 得到 HTML，按现有 `normalizeSectionTitle`/`splitSections` 逻辑切分章节，每节正文保留 HTML（不再 stripHtml 成纯文本）。无法匹配到章节的正文归入 content。

## 5. 后端改造（pams-backend）

- `ActivityPlan` 实体新增 7 列（nameOverride/themeOverride/timeOverride/locationOverride/organizerOverride/targetOverride/sectionOrder）。
- `PlanRequest`/`PlanFields` DTO 同步新增字段；`PlanController`/`PlanService` 读写新列。
- **V7 迁移**：`ALTER TABLE activity_plan ADD COLUMN ...`（7 列，均 TEXT NULL）。若无其他 V7，脚本编号 `V7__plan_editor.sql`（现有到 V6；若批次 C 已建 V7，则顺延）。
- 图片上传复用 `/api/files/upload`，无需新接口。
- 通知触发点：批次 C 已为「策划书编辑完成」「主任修改」建事件；本批确保 `updatePlan`（保存）触发对应通知（若批次 C 尚未做，则在本批补上）。

## 6. 待确认取舍（评审时请选）

1. **章节可增删重排**：本期是否做？
   - 方案 A（推荐，先做核心）：自由富文本 + 节名可改 + 只读章节可覆盖；不做增删重排（`section_order` 列留空，默认模板）。
   - 方案 B（完整）：含新增/删除/上移/下移章节，`section_order` 列承载。
2. **只读章节可覆盖**：是否改为可编辑（默认预填 meta 值）？还是保持只读（仅 meta 填充）？
   - 推荐改为可编辑（「给足自由度」精神，且新增 override 列成本低）。
3. **图片处理**：编辑器图片上传走 `/api/files/upload`，文档引用其 URL（当前端导出时拉二进制）。是否接受「图片在活动详情编辑可见、导出 docx 需前端拉取」的形态？

## 7. 验证计划

- 前端 `tsc --noEmit` + `vite build` 通过。
- 后端 `mvn clean test` 通过（含新字段读写）。
- 浏览器验证：活动详情策划书 Tab 进入编辑模式 → 富文本工具栏可用（加粗/颜色/列表/表格/图片上传）→ 保存后预览正确 → 导出 docx 在 Word 打开格式保留（加粗/列表/表格）→ 导入 docx 回填富文本 → 审核提交/驳回流程正常。
- 回归：既有 7 字段数据（无 override、section_order NULL）编辑保存不丢内容。

## 8. 范围外（本期不做）

- 多人在线协同编辑（实时光标）。本期为单编辑器，多用户编辑走「保存产生新版本」既有机制。
- wangEditor 5 之外的复杂排版（页眉页脚/分节符/目录域）。
- 移动端深度适配（编辑器主要面向 PC）。
