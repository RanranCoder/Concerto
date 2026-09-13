# 移除液态玻璃样式系统 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 移除全站 `backdrop-filter`（液态玻璃），Glass* 组件改为原生实底，配色固化为不透明等效色，观感与现状一致、GPU 负载下降。

**Architecture:** 这是一次纯样式层重构，无业务逻辑改动。玻璃 token 从 `tokens.css` 换成 `--surface-*` 不透明色板；`.glass-card`（`glass.css`）与 antd 覆盖（`global.css`）去掉 backdrop-filter/高光/动画；`App.tsx` 的 antd `colorBgContainer`/`colorBgElevated`/`colorBorder` 设为等效实底色；`Glass*` 组件调用点（约 30 个文件）的 JSX 零改动。

**Tech Stack:** CSS 自定义属性、antd v5（cssVar + hashed:false）、React 18 + Vite + TS。

## Global Constraints

- 全站 `backdrop-filter` 归零（`grep -rn "backdrop-filter" pams-web/src` 结果为 0）。
- `--glass-*` token 名彻底移除（`grep -rn -- "--glass-" pams-web/src` 结果为 0），不得保留别名。
- 不透明等效色值必须精确使用下表（规格已批准）：
  - `--surface`：亮 `#f7f8fa` / 暗 `#1b1e26`
  - `--surface-strong`：亮 `#fbfcfe` / 暗 `#23262f`
  - `--surface-border`：亮 `#e4e8ef` / 暗 `#2e313a`
  - `--surface-shadow`：亮 `0 12px 40px rgba(31, 38, 60, 0.14)` / 暗 `0 12px 40px rgba(0, 0, 0, 0.5)`
  - antd `colorBgContainer`：亮 `#f7f8fa` / 暗 `#1b1e26`
  - antd `colorBgElevated`：亮 `#fbfcfe` / 暗 `#23262f`
  - antd `colorBorder`：亮 `#e4e8ef` / 暗 `#2e313a`
- `Glass*` 组件调用点 JSX 零改动（唯一例外：`GlassModal` 自身文件去掉 `glass-modal` class）。
- 保留：`--color-red`、`--color-red-soft`、`--color-text`、`--color-text-secondary`、`--color-bg-page`（页面渐变）、`--radius-*`、`--easing`、`body::before` 装饰渐变、`.page-transition`、`.glass-card` 入场动画 `card-fade-in`（opacity-only）、滚动条隐藏规则、word-paper 全部规则。
- `npm run build`（pams-web 目录下 `npm run build`，含 tsc）必须通过。
- git 提交只 `git add` 本任务涉及的文件；不包含工作区其他未提交/未跟踪文件（README、V7/V8 迁移、`vite.config.ts`、`start.bat`、`.claude/`、`.workbuddy/`、docs 未跟踪文档）。
- 规格文档：`docs/superpowers/specs/2026-08-09-remove-liquid-glass-design.md`（已批准）。

---

### Task 1: tokens.css — 玻璃 token 换成表面 token

**Files:**
- Modify: `pams-web/src/styles/tokens.css`

**Interfaces:**
- Produces: `--surface`、`--surface-strong`、`--surface-border`、`--surface-shadow`（亮暗两套）；`--glass-*` 全部移除。后续 Task 2/3/6 引用这些新 token。

- [ ] **Step 1: 编辑 `:root` 块——删除玻璃参数**

当前：
```css
:root {
  --color-red: #DE2910;
  --color-red-soft: rgba(222, 41, 16, 0.14);
  --glass-blur: 20px;
  --glass-saturate: 180%;
  --radius-lg: 16px;
  --radius-md: 12px;
  --easing: cubic-bezier(0.22, 1, 0.36, 1);
}
```
改为（删掉 `--glass-blur` 与 `--glass-saturate` 两行）：
```css
:root {
  --color-red: #DE2910;
  --color-red-soft: rgba(222, 41, 16, 0.14);
  --radius-lg: 16px;
  --radius-md: 12px;
  --easing: cubic-bezier(0.22, 1, 0.36, 1);
}
```

- [ ] **Step 2: 编辑亮主题块**

当前：
```css
:root, :root[data-theme='light'] {
  --color-bg-page: linear-gradient(135deg, #f5f6f8 0%, #e9ecf1 50%, #e2e6ec 100%);
  --color-text: rgba(17, 24, 39, 0.92);
  --color-text-secondary: rgba(17, 24, 39, 0.6);
  --glass-bg: rgba(255, 255, 255, 0.55);
  --glass-bg-strong: rgba(255, 255, 255, 0.72);
  --glass-border: rgba(255, 255, 255, 0.65);
  --glass-shadow: 0 12px 40px rgba(31, 38, 60, 0.14);
  --glass-highlight: rgba(255, 255, 255, 0.85);
}
```
改为：
```css
:root, :root[data-theme='light'] {
  --color-bg-page: linear-gradient(135deg, #f5f6f8 0%, #e9ecf1 50%, #e2e6ec 100%);
  --color-text: rgba(17, 24, 39, 0.92);
  --color-text-secondary: rgba(17, 24, 39, 0.6);
  --surface: #f7f8fa;
  --surface-strong: #fbfcfe;
  --surface-border: #e4e8ef;
  --surface-shadow: 0 12px 40px rgba(31, 38, 60, 0.14);
}
```

- [ ] **Step 3: 编辑暗主题块**

当前：
```css
:root[data-theme='dark'] {
  --color-bg-page: linear-gradient(135deg, #0b0d12 0%, #14171f 50%, #0d1016 100%);
  --color-text: rgba(240, 244, 250, 0.94);
  --color-text-secondary: rgba(240, 244, 250, 0.55);
  --glass-bg: rgba(255, 255, 255, 0.06);
  --glass-bg-strong: rgba(255, 255, 255, 0.10);
  --glass-border: rgba(255, 255, 255, 0.14);
  --glass-shadow: 0 12px 40px rgba(0, 0, 0, 0.5);
  --glass-highlight: rgba(255, 255, 255, 0.10);
}
```
改为：
```css
:root[data-theme='dark'] {
  --color-bg-page: linear-gradient(135deg, #0b0d12 0%, #14171f 50%, #0d1016 100%);
  --color-text: rgba(240, 244, 250, 0.94);
  --color-text-secondary: rgba(240, 244, 250, 0.55);
  --surface: #1b1e26;
  --surface-strong: #23262f;
  --surface-border: #2e313a;
  --surface-shadow: 0 12px 40px rgba(0, 0, 0, 0.5);
}
```

- [ ] **Step 4: 验证**

Run: `grep -n -- "--glass-" pams-web/src/styles/tokens.css`（用 Grep 工具）
Expected: 无输出（tokens.css 已无 `--glass-`）。

- [ ] **Step 5: Commit**

```bash
git add pams-web/src/styles/tokens.css
git commit -m "refactor: replace glass tokens with opaque surface tokens"
```
注意：此任务后 `.glass-card` 等引用 `--glass-*` 的样式暂时失效（背景变透明），属计划内中间态，后续 Task 2 恢复。

---

### Task 2: glass.css — `.glass-card` 原生实底

**Files:**
- Modify: `pams-web/src/styles/glass.css`

**Interfaces:**
- Consumes: Task 1 的 `--surface`/`--surface-border`/`--surface-shadow`。
- Produces: 实底 `.glass-card`（无 backdrop-filter、无 `::before`、无 transform hover）。Login/PageHeader/侧边栏依赖此行为。

- [ ] **Step 1: 用以下完整内容覆盖 `glass.css`**（删除 backdrop-filter、`transition`、`::before` 高光块、hover 的 transform/box-shadow；保留 `.login-page` 与 `.login-card`）：

```css
.glass-card {
  position: relative;
  background: var(--surface);
  border: 1px solid var(--surface-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--surface-shadow);
}

.glass-card:hover {
  border-color: rgba(222, 41, 16, 0.35);
}

.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  background: var(--color-bg-page);
  padding: 24px;
}

.login-card { width: 380px; max-width: 100%; padding: 40px 32px; }
```

- [ ] **Step 2: 验证**

用 Grep 检查 `pams-web/src/styles/glass.css`：
- `backdrop-filter` → 无输出
- `--glass-` → 无输出
- `::before` → 无输出
用 Read 读一遍确认结构与上一致。

- [ ] **Step 3: Commit**

```bash
git add pams-web/src/styles/glass.css
git commit -m "refactor: glass-card becomes native solid surface"
```

---

### Task 3: global.css — 移除玻璃覆盖，替换剩余 `--glass-*`

**Files:**
- Modify: `pams-web/src/styles/global.css`

**Interfaces:**
- Consumes: Task 1 的 `--surface`/`--surface-strong`/`--surface-border`。

- [ ] **Step 1: 更新第 25-28 行布局透明规则注释**

当前：
```css
/* antd 组件玻璃化覆盖 */
.ant-layout, .ant-layout-header, .ant-layout-sider, .ant-card {
  background: transparent !important;
}
```
改为：
```css
/* 布局容器透明，露出 body 的 --color-bg-page 渐变 */
.ant-layout, .ant-layout-header, .ant-layout-sider, .ant-card {
  background: transparent !important;
}
```

- [ ] **Step 2: 更新第 30-32 行菜单注释**

当前：
```css
/* 左侧菜单：跟随主题算法取字色，背景交给玻璃 Sider 露出，不保留不透明容器白/深底 */
.ant-menu {
  background: transparent !important;
}
```
改为：
```css
/* 左侧菜单透明，露出实底 Sider 表面 */
.ant-menu {
  background: transparent !important;
}
```

- [ ] **Step 3: 替换零宽触发器背景（第 33-37 行）**

当前：
```css
/* 收起态的零宽触发条（<lg 断点）跟随玻璃 token，避免 light 下残留 antd 深色 #002140 */
.ant-layout-sider-zero-width-trigger {
  background: var(--glass-bg) !important;
  color: var(--color-text) !important;
}
```
改为：
```css
/* 收起态的零宽触发条（<lg 断点）跟随表面色，避免 light 下残留 antd 深色 #002140 */
.ant-layout-sider-zero-width-trigger {
  background: var(--surface) !important;
  color: var(--color-text) !important;
}
```

- [ ] **Step 4: 替换表格表头/表体（第 41-48 行）**

当前：
```css
.ant-table-thead > tr > th {
  background: var(--glass-bg-strong) !important;
  color: var(--color-text-secondary);
  border-bottom: 1px solid var(--glass-border) !important;
}
.ant-table-tbody > tr > td {
  border-bottom: 1px solid var(--glass-border) !important;
}
```
改为：
```css
.ant-table-thead > tr > th {
  background: var(--surface-strong) !important;
  color: var(--color-text-secondary);
  border-bottom: 1px solid var(--surface-border) !important;
}
.ant-table-tbody > tr > td {
  border-bottom: 1px solid var(--surface-border) !important;
}
```

- [ ] **Step 5: 删除弹窗/抽屉玻璃覆盖（第 49-53 行整块）**

删除以下整块（改由 antd 原生 `colorBgContainer`/`colorBgElevated` 提供实底，见 Task 4）：
```css
.ant-modal-content, .ant-drawer-content {
  background: var(--glass-bg-strong) !important;
  backdrop-filter: blur(28px) saturate(var(--glass-saturate));
  border: 1px solid var(--glass-border);
}
```

- [ ] **Step 6: 删除弹窗标题栏覆盖（第 54-60 行整块）**

删除以下整块（antd 原生 header 与内容同为实底色）：
```css
/* 弹窗标题栏衬底：默认实心白块（--ant-modal-header-bg）+ 只圆上方两角的直角矩形会从圆角玻璃衬底底部穿出，
   这里让所有 antd 弹窗的标题栏透明并跟随内容衬底圆角，融入玻璃底。 */
.ant-modal .ant-modal-header {
  background: transparent;
  border-radius: var(--radius-lg) var(--radius-lg) 0 0;
  overflow: hidden;
}
```

- [ ] **Step 7: 更新动画注释（第 62-63 行、第 79 行）**

第 62-63 行当前：
```css
/* ============ 页面切换过渡动画 ============ */
/* 每个路由页挂载时淡入 + 上移 + 轻微缩放，克制不花哨，符合 liquid glass 调性 */
```
改为：
```css
/* ============ 页面切换过渡动画 ============ */
/* 每个路由页挂载时淡入 + 上移 + 轻微缩放，克制不花哨 */
```

第 79 行当前：
```css
/* 玻璃卡片入场：页面内的卡片逐个有轻微层次，随父级动画一起呈现 */
```
改为：
```css
/* 卡片入场：页面内的卡片逐个淡入，有轻微层次，随父级动画一起呈现 */
```

- [ ] **Step 8: 删除侧边栏玻璃残留规则（第 300-305 行整块）**

删除以下整块（`.glass-card` 已无 `::before`、无 transform hover，两条规则均失效）：
```css
/* 去掉侧边栏悬停时的白色遮罩：移除左上角白色高光渐变与白色内发光，只保留红边/阴影加深 */
.ant-layout-sider.glass-card::before { content: none; }
.ant-layout-sider.glass-card:hover {
  transform: none;
  box-shadow: 0 16px 48px rgba(31, 38, 60, 0.18);
}
```

- [ ] **Step 9: 验证**

用 Grep 检查 `pams-web/src/styles/global.css`：
- `backdrop-filter` → 无输出
- `--glass-` → 无输出
用 Read 确认弹窗/抽屉覆盖与侧边栏规则已删除，其余规则（word-paper、滚动条、`.page-transition`、`card-fade-in`、`@media prefers-reduced-motion`）原样保留。

- [ ] **Step 10: Commit**

```bash
git add pams-web/src/styles/global.css
git commit -m "refactor: drop glass overrides, use surface tokens natively"
```

---

### Task 4: App.tsx — antd token 实底化

**Files:**
- Modify: `pams-web/src/App.tsx`

**Interfaces:**
- Consumes: Task 1 的等效色值。
- Produces: antd 全局实底容器/浮层/边框色；弹窗、抽屉、下拉、表格、输入框原生取色。

- [ ] **Step 1: 修改 `colorBgContainer` 与 `colorBorder`，新增 `colorBgElevated`**

当前（`src/App.tsx` 第 28-34 行）：
```tsx
        token: {
          colorPrimary: '#DE2910',
          colorBgContainer: mode === 'dark' ? 'rgba(255,255,255,0.06)' : 'rgba(255,255,255,0.55)',
          colorBorder: mode === 'dark' ? 'rgba(255,255,255,0.14)' : 'rgba(0,0,0,0.10)',
          borderRadius: 12,
          fontFamily: "'PingFang SC','Microsoft YaHei','HarmonyOS Sans SC','Noto Sans SC',sans-serif",
        },
```
改为：
```tsx
        token: {
          colorPrimary: '#DE2910',
          colorBgContainer: mode === 'dark' ? '#1b1e26' : '#f7f8fa',
          colorBgElevated: mode === 'dark' ? '#23262f' : '#fbfcfe',
          colorBorder: mode === 'dark' ? '#2e313a' : '#e4e8ef',
          borderRadius: 12,
          fontFamily: "'PingFang SC','Microsoft YaHei','HarmonyOS Sans SC','Noto Sans SC',sans-serif",
        },
```
Menu 组件 token 块（`itemSelectedBg`/`itemSelectedColor`/`itemColor`）保持不变。

- [ ] **Step 2: 验证**

用 Read 确认 `token` 块含 `colorBgContainer`/`colorBgElevated`/`colorBorder` 三个新值，Menu 块原样。

- [ ] **Step 3: Commit**

```bash
git add pams-web/src/App.tsx
git commit -m "refactor: antd tokens use opaque surface colors"
```

---

### Task 5: GlassModal — 去掉 `glass-modal` class

**Files:**
- Modify: `pams-web/src/components/glass/GlassModal.tsx`

**Interfaces:**
- Consumes: 无。玻璃系统移除后 `.glass-modal` 无对应 CSS。
- Produces: `GlassModal` 不再挂自定义 class，纯 antd Modal。

- [ ] **Step 1: 修改组件返回**

当前：
```tsx
export default function GlassModal({ className, children, ...props }: GlassModalProps) {
  return (
    <Modal
      className={`glass-modal${className ? ` ${className}` : ''}`}
      destroyOnHidden
      footer={null}
      {...props}
    >
      {children}
    </Modal>
  )
}
```
改为：
```tsx
export default function GlassModal({ className, children, ...props }: GlassModalProps) {
  return (
    <Modal
      className={className}
      destroyOnHidden
      footer={null}
      {...props}
    >
      {children}
    </Modal>
  )
}
```
保留文件顶部的 `destroyOnHidden` 说明注释（行为不变）。

- [ ] **Step 2: 验证**

用 Grep 检查 `pams-web/src`：`glass-modal` → 无输出（或仅剩注释中无此词）。

- [ ] **Step 3: Commit**

```bash
git add pams-web/src/components/glass/GlassModal.tsx
git commit -m "refactor: GlassModal drops glass-modal class, pure antd Modal"
```

---

### Task 6: 内联 `--glass-*` 替换（5 个文件）

**Files:**
- Modify: `pams-web/src/pages/Dashboard.tsx`
- Modify: `pams-web/src/components/seat/SeatMapView.tsx`
- Modify: `pams-web/src/components/seat/SeatExcelEditor.tsx`
- Modify: `pams-web/src/components/word/PlanRichEditor.tsx`
- Modify: `pams-web/src/pages/activity/ActivityDetail.tsx`

**Interfaces:**
- Consumes: Task 1 的新 token 名。

- [ ] **Step 1: 全局替换**

对下列每个文件执行统一的字符串替换（用 Edit 工具 `replace_all`）：

| 原字符串 | 新字符串 |
|---|---|
| `var(--glass-bg-strong)` | `var(--surface-strong)` |
| `var(--glass-bg)` | `var(--surface)` |
| `var(--glass-border)` | `var(--surface-border)` |

替换顺序必须 **先** `--glass-bg-strong` **后** `--glass-bg`（避免 `--glass-bg` 把 `--glass-bg-strong` 吞掉）。

每个文件涉及的当前出现位置（供核对）：
- `Dashboard.tsx`：`var(--glass-border)` 于 ~321/373/498 行，`var(--glass-bg)` 于 ~322/374/499 行。
- `SeatMapView.tsx`：`var(--glass-border)` 于 ~119/166/171 行。
- `SeatExcelEditor.tsx`：`var(--glass-border)` 于 ~158 行，`var(--glass-bg-strong)` 于 ~159 行。
- `PlanRichEditor.tsx`：`var(--glass-border)` 与 `var(--glass-bg-strong)` 于 ~87 行，`var(--glass-bg)` 于 ~101 行。
- `ActivityDetail.tsx`：`var(--glass-border)` 于 ~1131/1163 行。

- [ ] **Step 2: 验证**

用 Grep 检查 `pams-web/src`：`--glass-` → 无输出。

- [ ] **Step 3: Commit**

```bash
git add pams-web/src/pages/Dashboard.tsx pams-web/src/components/seat/SeatMapView.tsx pams-web/src/components/seat/SeatExcelEditor.tsx pams-web/src/components/word/PlanRichEditor.tsx pams-web/src/pages/activity/ActivityDetail.tsx
git commit -m "refactor: replace inline glass tokens with surface tokens"
```

---

### Task 7: 全量验证

**Files:**
- 无代码改动（验证与回归）

**Interfaces:**
- 验收规格 `docs/superpowers/specs/2026-08-09-remove-liquid-glass-design.md` 的验收标准。

- [ ] **Step 1: grep 归零**

用 Grep 检查 `pams-web/src`：
- `backdrop-filter` → 无输出
- `--glass-` → 无输出
- `glass-modal` → 无输出

- [ ] **Step 2: 构建**

Run（在 `pams-web` 目录）: `npm run build`
Expected: tsc 无错误，vite 打包成功（`dist/` 生成）。

- [ ] **Step 3: 浏览器双主题巡检**

启动 dev server（若已由 preview 运行则复用），逐一确认：
- 亮/暗两主题都切一遍（`preview_resize` 或主题切换）。
- 登录页卡片：实底、无模糊、无高光条。
- 仪表盘：瀑布流卡片实底、hover 仅红边。
- 列表页（如用户/活动）：GlassTable 表头与表体实底、边框颜色正常。
- 打开一个 GlassModal 与抽屉：背景为实底等效色（暗主题 ≈ `#23262f`，亮主题 ≈ `#fbfcfe`），无模糊。
- 侧边栏：实底、菜单选中红底正常、收起触发条颜色正常。

- [ ] **Step 4: 记录**

无代码提交。若发现回归，回退到对应 Task 修复后再验证。
