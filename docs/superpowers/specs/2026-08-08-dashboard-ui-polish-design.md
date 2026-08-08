# 仪表盘 UI 优化 设计文档

> 日期：2026-08-08
> 状态：已确认方案，待写实现计划
> 范围：侧边栏滚动条隐藏 / 去掉悬停白色遮罩 / 仪表盘瀑布流 / 活动动态三条+滚动+清除

## 背景与目标

用户提出 4 项仪表盘与侧边栏的 UI 调整，全部为前端改动，不涉及后端与数据库：

1. 隐藏侧边栏（Sider）的滚动条，但保留滚动能力。
2. 去掉鼠标悬停在侧边栏时出现的"白色半透明遮罩"（被当作 bug 修复），悬停只保留轻微红边/阴影变化。
3. 仪表盘下方内容区改为瀑布流（masonry）排版，任意视口比例下各列底部对齐。
4. 活动动态卡片只展示 3 条，超出部分用滚动条承载；新增"清除"按钮，点击后本地清空列表（刷新恢复）。

**用户确认的关键决策**：
- 第 2 项：去掉遮罩（修 bug），**不**补回玻璃底色，背景保持现状。
- 第 3 项：顶部 4 个统计卡保持一行，**仅下方内容卡**参与瀑布流。
- 第 4 项：清除按钮 = 本地清空（纯前端状态，刷新恢复），不动数据库。

## 现状排查（已实测）

- 侧边栏：`pams-web/src/layouts/MainLayout.tsx` L135-144，Sider 设置 `overflowY: 'auto'`，菜单过长时露出滚动条。
- 白色遮罩组成：
  - `pams-web/src/styles/glass.css` L12-15 `.glass-card:hover` 的 `box-shadow` 含 `0 0 0 1px rgba(255,255,255,0.06) inset`（白色内发光）；
  - `glass.css` L18-26 `.glass-card::before` 左上角白色高光渐变 `rgba(255,255,255,0.85) @ opacity 0.55`（永久渲染）。
- 仪表盘：`pams-web/src/pages/Dashboard.tsx` L142-175 顶部统计卡行；L175 起 `2 列 grid`（左列 1.9fr：排班/活动动态/待办；右列 1fr：推文/材料/公告/底部信息），左列明显高于右列。
- 活动动态：`Dashboard.tsx` L290-346，`upcomingActivities`（进行中/待办，最多 8 条）列表无滚动上限、无清除按钮。

## 方案细节

### 1. 侧边栏滚动条隐藏

`pams-web/src/styles/global.css` 增加（仅作用于 Sider，页面右侧内容区滚动条不受影响）：

```css
.ant-layout-sider {
  scrollbar-width: none;      /* Firefox */
  -ms-overflow-style: none;   /* 旧 Edge/IE */
}
.ant-layout-sider::-webkit-scrollbar { display: none; }
```

### 2. 去掉悬停白色遮罩（仅作用于侧边栏）

`global.css` 增加两条覆盖，其它玻璃卡片不受影响：

```css
/* 去掉 Sider 上的左上角白色高光渐变 */
.ant-layout-sider.glass-card::before { content: none; }
/* 悬停只保留外阴影加深，去掉白色内发光 */
.ant-layout-sider.glass-card:hover {
  box-shadow: 0 16px 48px rgba(31, 38, 60, 0.18);
}
```

背景保持透明现状（用户明确不补回玻璃底色）。

### 3. 仪表盘瀑布流

**范围**：顶部 4 个统计卡保持一行；下方 6 张内容卡 + 底部信息卡参与瀑布流。

**新增** `pams-web/src/components/glass/Masonry.tsx`（无第三方依赖，约 50 行）：

- `Masonry` 组件：`flex` 容器 + `ResizeObserver` 监听容器宽度 → 响应式列数（`>=1100` → 3 列，`>=700` → 2 列，否则 1 列）。
- 用 `useLayoutEffect` 测量每张卡片实际高度，经典瀑布流"最短列优先"贪心算法：每张卡放入当前总高最小的列。
- 各列 `flex: 1; display: flex; flex-direction: column; align-items: flex-start;`，列间 `gap: 16`。
- 保证：任意视口比例下列高最多相差一张卡片高度，底部天然对齐。

**改造** `Dashboard.tsx`：
- 移除下方 `2 列 grid` 结构（L175 起的 `gridTemplateColumns: minmax(0,1.9fr) minmax(0,1fr)`），改为 `<Masonry>` 承载下方所有内容卡。
- 顶部统计卡行保留。

**已知边界**：卡片高度随宽度变化（如排班表换行），`Masonry` 只在列数变化或子项变化时重测重排，避免无限循环。

### 4. 活动动态：只显示 3 条 + 滚动 + 清除按钮

`Dashboard.tsx` 活动动态卡片（L290-346）改造：

- 列表容器 `max-height` 固定为恰好容纳 3 条的高度，`overflow-y: auto`；超过 3 条用滚动条浏览（具体 px 在实现时按实际项目高度实测微调）。
- 卡片头部新增"清除"按钮（放在"全部活动"链接旁），点击后 `setCleared(true)` → 显示"暂无活动动态"空态。
- **纯前端状态**：`const [cleared, setCleared] = useState(false)`，刷新页面即恢复，不改后端。
- 数据源不变：仍取 `upcomingActivities`（进行中/待办活动，最多 8 条），仅改变展示方式。

## 不做的事

- 不加第三方依赖（瀑布流自实现）。
- 不改后端、数据库、路由、权限。
- 不改动其它玻璃卡片的样式与动效。
- 不改变侧边栏背景（保持透明）。

## 测试计划

1. `pams-web` 下 `npm run build`（TypeScript + Vite 构建通过）。
2. 浏览器预览验证：
   - 侧边栏：无滚动条但菜单仍可内部滚动；悬停侧边栏无白色遮罩，仅红边/阴影变化；其它玻璃卡片悬停高光不受影响。
   - 仪表盘：1280×800 与更窄宽度下瀑布流底部对齐；列数随宽度正确变化。
   - 活动动态：默认显示 3 条，超出滚动；点"清除"后显示空态，刷新恢复。
   - 登录态、菜单高亮、页面切换无回归。
