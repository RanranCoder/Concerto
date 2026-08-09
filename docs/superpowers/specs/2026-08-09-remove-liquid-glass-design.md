# 移除液态玻璃样式系统 — 设计文档

> **日期**: 2026-08-09
> **状态**: 已获用户批准（保留 Glass* 组件内部改原生样式 + 固化为不透明等效色）

## 背景与目标

当前前端（`pams-web`）自研了一套 liquid glass（液态玻璃）样式系统，核心是 `backdrop-filter: blur(...) saturate(...)`，铺在卡片、弹窗、抽屉等所有表面。`backdrop-filter` 是 GPU 高开销特性：每帧对背板做采样 + 模糊 + 饱和，并且与之叠加的动画/悬浮会产生逐帧重采样毛刺（此前已引发多轮悬停 bug）。

**目标**：移除整个液态玻璃样式系统，全站 `backdrop-filter` 归零，降低渲染负载与运营成本；**保留当前配色**（把半透明玻璃叠加页面渐变后的等效色固化为不透明色板，明暗主题观感不变）。

## 关键决策（已与用户确认）

1. **Glass\* 组件保留，内部改原生样式**：`GlassCard`/`GlassModal`/`GlassTable`/`PageHeader` 被约 30 个文件引用，调用点 JSX **零改动**，只在组件/样式层去玻璃。
2. **配色固化为不透明等效色**：不用 antd 默认表面色，而是把当前 55%/6%（卡片）、72%/10%（弹窗）白玻璃叠加页面渐变后的等效色，算成不透明 token。

## 当前玻璃系统盘点

- **tokens.css**：`--glass-blur: 20px`、`--glass-saturate: 180%`、`--glass-bg`/`--glass-bg-strong`/`--glass-border`/`--glass-shadow`/`--glass-highlight`（亮暗两套）。
- **glass.css**：`.glass-card`（backdrop-filter blur+saturate、`::before` 白色高光渐变、hover 上浮+阴影+描边）、`.login-card`。
- **global.css**：`.ant-modal-content`/`.ant-drawer-content` 玻璃背景 + `backdrop-filter: blur(28px) saturate(...)`；`.ant-modal .ant-modal-header` 透明覆盖；`.ant-layout*`/`.ant-menu`/`.ant-table` 透明；`.glass-card` 入场动画；`.ant-layout-sider.glass-card::before`/hover 规则。
- **App.tsx**：antd `colorBgContainer` = 半透明玻璃值（亮 `rgba(255,255,255,0.55)` / 暗 `rgba(255,255,255,0.06)`），`colorBorder` 半透明。
- **内联用量**：Dashboard、SeatMapView、SeatExcelEditor、PlanRichEditor、ActivityDetail 内联 `var(--glass-bg)`/`var(--glass-bg-strong)`/`var(--glass-border)` 共约 15 处。

## 设计

### 1. tokens.css — 玻璃 token → 表面 token

删除全部 `--glass-*` token，新增不透明等效色（亮/暗两套）：

| token | 用途 | 亮主题 | 暗主题 | 说明 |
|---|---|---|---|---|
| `--surface` | 卡片/容器表面 | `#f7f8fa` | `#1b1e26` | ≈ 55% / 6% 白玻璃叠加渐变 |
| `--surface-strong` | 弹窗/抽屉表面 | `#fbfcfe` | `#23262f` | ≈ 72% / 10% 白玻璃 |
| `--surface-border` | 表面边框 | `#e4e8ef` | `#2e313a` | 原玻璃边框等效 |
| `--surface-shadow` | 表面阴影 | `0 12px 40px rgba(31,38,60,0.14)` | `0 12px 40px rgba(0,0,0,0.5)` | 原值不变 |

保留：`--color-red`、`--color-red-soft`、`--color-text`、`--color-text-secondary`、`--color-bg-page`（页面渐变）、`--radius-*`、`--easing`。

### 2. glass.css — `.glass-card` 原生实底

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
```

- 删除：`backdrop-filter`（blur+saturate）、`::before` 高光渐变、`transition`、hover 的 transform 上浮与阴影变化。
- `.login-card`（宽高/内边距）保留。

### 3. global.css — 移除玻璃覆盖

- **删除** `.ant-modal-content`/`.ant-drawer-content` 的 `backdrop-filter` 与玻璃背景覆盖——改由 antd 原生 `colorBgContainer` 提供实底（第 4 节）。
- **删除** `.ant-modal .ant-modal-header` 透明覆盖——antd 原生 header 即同色实底。
- **删除** `.ant-layout-sider.glass-card::before { content:none }` 与 `.ant-layout-sider.glass-card:hover` 规则（`::before` 整体移除，无需再抑制）。
- **替换表内剩余 `--glass-*`**：
  - `.ant-table-thead > tr > th`：`background: var(--glass-bg-strong)` → `var(--surface-strong)`；`border-bottom: var(--glass-border)` → `var(--surface-border)`。
  - `.ant-table-tbody > tr > td`：`border-bottom: var(--glass-border)` → `var(--surface-border)`。
  - `.ant-layout-sider-zero-width-trigger`：`background: var(--glass-bg)` → `var(--surface)`。
- **保留**：
  - `.ant-layout`/`.ant-layout-header`/`.ant-layout-sider` 透明（露出 `--color-bg-page` 渐变；纯 paint，无 blur）。
  - `.ant-menu` 背景透明（露出实底侧边栏）。
  - `.ant-table` 背景透明（露出实底 GlassCard 容器）。
  - `body::before` 装饰渐变（纯 paint，廉价）。
  - `.page-transition` 与 `.glass-card` 入场动画 `card-fade-in`（opacity-only，GPU 廉价）。
  - 侧边栏/活动动态滚动条隐藏、`.hide-scrollbar`、word-paper 等与玻璃无关的规则。

### 4. App.tsx — antd token 实底化

- `colorBgContainer`: 亮 `#f7f8fa` / 暗 `#1b1e26`（与 `--surface` 同值，作用于 Table/输入框/下拉等所有容器，含弹窗/抽屉）。
- `colorBorder`: 亮 `#e4e8ef` / 暗 `#2e313a`（与 `--surface-border` 同值）。
- Menu 红系选中 token 保留（`itemSelectedBg`/`itemSelectedColor`/`itemColor`，无 blur、廉价）。
- `colorPrimary: '#DE2910'` 不变。

### 5. Glass 组件收口

- `GlassModal`：去掉 `glass-modal` class（当前无对应 CSS，去掉以彻底移除玻璃痕迹）。
- `GlassCard`/`GlassTable`/`PageHeader`：代码不变（`.glass-card` 已实底化）。

### 6. 内联 `--glass-*` 替换（5 文件 ~15 处）

| 文件 | 替换 |
|---|---|
| `Dashboard.tsx`（321/322/373/374/498/499 行） | `--glass-bg`→`--surface`、`--glass-border`→`--surface-border` |
| `SeatMapView.tsx`（119/166/171 行） | `--glass-border`→`--surface-border` |
| `SeatExcelEditor.tsx`（158/159 行） | `--glass-border`→`--surface-border`、`--glass-bg-strong`→`--surface-strong` |
| `PlanRichEditor.tsx`（87/101 行） | `--glass-bg-strong`→`--surface-strong`、`--glass-bg`→`--surface` |
| `ActivityDetail.tsx`（1131/1163 行） | `--glass-border`→`--surface-border` |

## 验收标准

1. `grep -rn "backdrop-filter" pams-web/src` 结果为 0。
2. `grep -rn -- "--glass-" pams-web/src` 结果为 0（token 名彻底移除）。
3. `npm run build`（tsc + vite）通过。
4. 浏览器亮/暗两主题巡检：登录页、仪表盘、列表页（表）、弹窗、抽屉、侧边栏，配色观感与改造前基本一致，无模糊层。

## 非目标

- 不改页面渐变 `--color-bg-page`、不改 `colorPrimary` 红。
- 不改任何业务 JSX 结构，`Glass*` 组件调用点零改动。
- 不引入新的动画/阴影系统。
