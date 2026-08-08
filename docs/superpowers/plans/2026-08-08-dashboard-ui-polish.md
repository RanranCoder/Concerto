# 仪表盘 UI 优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成 4 项前端 UI 调整——隐藏侧边栏滚动条、去掉侧边栏悬停白色遮罩、仪表盘内容区瀑布流、活动动态限 3 条+滚动+清除按钮。

**Architecture:** 全部为前端改动，无后端/数据库/路由改动。瀑布流用自研轻量 `Masonry` 组件（flex 列 + 最短列优先贪心分配，`ResizeObserver` 响应式列数），列分配逻辑抽成纯函数便于单测。侧边栏样式覆盖追加到 `global.css`。仪表盘活动动态加本地 `cleared` 状态实现清除。

**Tech Stack:** React 18、TypeScript、Ant Design 5、Vite、Vitest、浏览器预览。

## Global Constraints

- 不增加第三方依赖（瀑布流自实现）。
- 不改后端、数据库、路由、权限。
- 侧边栏背景保持透明现状（不补回玻璃底色）。
- 只改动侧边栏上的样式，其它玻璃卡片的 hover 高光与 `::before` 渐变不受影响。
- 提交时只 `git add` 本任务涉及的文件，不得包含工作区其它未跟踪/未提交文件（`V7/V8` 迁移、`vite.config.ts`、`start.bat`、`README.md`、`.claude/`、`.workbuddy/`、`docs/PRD-*.md` 等）。

---

### Task 1: 瀑布流列分配纯函数 + 单测

**Files:**
- Create: `pams-web/src/components/glass/masonry.utils.ts`
- Create: `pams-web/src/components/glass/masonry.utils.test.ts`

**Interfaces:**
- Produces（Task 2 使用）:
  - `columnCountFor(width: number, minColWidth: number, gap: number): number` —— 容器宽度 → 列数（至少 1）。
  - `distributeToColumns(heights: number[], columnCount: number, gap: number): number[]` —— 返回每项应放入的列下标，采用"最短列优先"贪心。

- [ ] **Step 1: 写失败测试**

```ts
// pams-web/src/components/glass/masonry.utils.test.ts
import { describe, it, expect } from 'vitest'
import { columnCountFor, distributeToColumns } from './masonry.utils'

describe('masonry.utils', () => {
  it('columnCountFor never returns 0', () => {
    expect(columnCountFor(0, 340, 16)).toBe(1)
    expect(columnCountFor(300, 340, 16)).toBe(1)
  })

  it('columnCountFor scales with width', () => {
    expect(columnCountFor(1200, 340, 16)).toBe(3)
    expect(columnCountFor(800, 340, 16)).toBe(2)
    expect(columnCountFor(500, 340, 16)).toBe(1)
  })

  it('distributeToColumns puts tall items first then fills other columns', () => {
    expect(distributeToColumns([400, 100, 100], 2, 0)).toEqual([0, 1, 1])
  })

  it('distributeToColumns balances alternating items', () => {
    expect(distributeToColumns([200, 200, 200, 200], 2, 0)).toEqual([0, 1, 0, 1])
  })

  it('distributeToColumns counts gap toward column height', () => {
    expect(distributeToColumns([300, 100, 100], 2, 16)).toEqual([0, 1, 1])
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run（在 `pams-web` 下）: `npx vitest run src/components/glass/masonry.utils.test.ts`
Expected: FAIL，报 `Cannot find module './masonry.utils'`。

- [ ] **Step 3: 实现纯函数**

```ts
// pams-web/src/components/glass/masonry.utils.ts
/** 瀑布流"最短列优先"分配：给定每项高度与列数，返回每项应放入的列下标。 */
export function distributeToColumns(heights: number[], columnCount: number, gap: number): number[] {
  const colHeights = new Array<number>(columnCount).fill(0)
  return heights.map((h) => {
    let minCol = 0
    for (let c = 1; c < columnCount; c++) {
      if (colHeights[c] < colHeights[minCol]) minCol = c
    }
    colHeights[minCol] += h + gap
    return minCol
  })
}

/** 响应式列数：容器宽度 → 列数，至少 1。 */
export function columnCountFor(width: number, minColWidth: number, gap: number): number {
  if (width <= 0) return 1
  return Math.max(1, Math.floor((width + gap) / (minColWidth + gap)))
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `npx vitest run src/components/glass/masonry.utils.test.ts`
Expected: 5 个用例全 PASS。

- [ ] **Step 5: Commit**

```bash
git add pams-web/src/components/glass/masonry.utils.ts pams-web/src/components/glass/masonry.utils.test.ts
git commit -m "feat: add masonry column distribution utils"
```

---

### Task 2: Masonry 组件

**Files:**
- Create: `pams-web/src/components/glass/Masonry.tsx`

**Interfaces:**
- Consumes: Task 1 的 `columnCountFor`、`distributeToColumns`。
- Produces（Task 4 使用）:
  - `<Masonry gap?: number; minColWidth?: number; className?: string>`，children 为任意 React 节点数组。默认 `gap=16, minColWidth=340`。

- [ ] **Step 1: 创建组件**

```tsx
// pams-web/src/components/glass/Masonry.tsx
import { Children, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { columnCountFor, distributeToColumns } from './masonry.utils'

interface MasonryProps {
  children: ReactNode[]
  gap?: number
  minColWidth?: number
  className?: string
}

export default function Masonry({ children, gap = 16, minColWidth = 340, className }: MasonryProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [cols, setCols] = useState(1)
  const [assign, setAssign] = useState<number[]>([])

  // 用 useMemo 稳定 cards 引用，避免 Children.toArray 每次 render 都生成新数组导致测高 effect 死循环
  const cards = useMemo(() => Children.toArray(children), [children])

  // 容器宽度 → 列数
  useLayoutEffect(() => {
    const el = containerRef.current
    if (!el) return
    const update = () => setCols(columnCountFor(el.clientWidth, minColWidth, gap))
    update()
    const ro = new ResizeObserver(update)
    ro.observe(el)
    return () => ro.disconnect()
  }, [minColWidth, gap])

  // 测量每张卡实际高度 → 最短列优先分配
  const itemRefs = useRef<Array<HTMLDivElement | null>>([])
  useLayoutEffect(() => {
    itemRefs.current.length = cards.length
    const heights = itemRefs.current.map((el) => el?.offsetHeight ?? 0)
    setAssign(distributeToColumns(heights, cols, gap))
  }, [cols, cards, gap])

  // 首次渲染尚未测量时，全部放第 0 列以便测高（layout effect 会在绘制前完成分配，无闪烁）
  const eff = assign.length === cards.length ? assign : cards.map(() => 0)

  return (
    <div ref={containerRef} className={className} style={{ display: 'flex', gap, alignItems: 'flex-start' }}>
      {Array.from({ length: cols }, (_, c) => (
        <div key={c} style={{ display: 'flex', flexDirection: 'column', gap, flex: 1, minWidth: 0 }}>
          {cards.map((card, i) =>
            eff[i] === c ? (
              <div key={i} ref={(el) => { itemRefs.current[i] = el }}>
                {card}
              </div>
            ) : null
          )}
        </div>
      ))}
    </div>
  )
}
```

- [ ] **Step 2: 构建检查**

Run（在 `pams-web` 下）: `npm run build`
Expected: TypeScript 编译与 Vite 构建成功，无类型错误。

- [ ] **Step 3: Commit**

```bash
git add pams-web/src/components/glass/Masonry.tsx
git commit -m "feat: add Masonry component for dashboard"
```

---

### Task 3: 侧边栏滚动条隐藏 + 去掉悬停白色遮罩

**Files:**
- Modify: `pams-web/src/styles/global.css`（文件末尾追加一段）

**Interfaces:**
- Consumes: 现有 `.ant-layout-sider`、`.glass-card` 类名（`MainLayout.tsx` L135-144 的 Sider `className="glass-card"`）。
- Produces: 侧边栏滚动条隐藏但可滚动；悬停无白色遮罩。

- [ ] **Step 1: 追加 CSS**

在 `pams-web/src/styles/global.css` 末尾追加：

```css
/* ============ 侧边栏：隐藏滚动条但保留滚动 ============ */
.ant-layout-sider {
  scrollbar-width: none;
  -ms-overflow-style: none;
}
.ant-layout-sider::-webkit-scrollbar { display: none; }

/* 去掉侧边栏悬停时的白色遮罩：移除左上角白色高光渐变与白色内发光，只保留红边/阴影加深 */
.ant-layout-sider.glass-card::before { content: none; }
.ant-layout-sider.glass-card:hover {
  box-shadow: 0 16px 48px rgba(31, 38, 60, 0.18);
}
```

- [ ] **Step 2: 浏览器验证**

浏览器预览（端口 4173，账号 `teacher` / `123456`）登录后检查：
- 侧边栏菜单足够长时不显示滚动条，但用鼠标滚轮仍可滚动菜单；
- 鼠标悬停侧边栏，无白色泛白遮罩，只有轻微红边/阴影变化；
- 其它玻璃卡片（仪表盘各卡）悬停高光渐变仍正常。

- [ ] **Step 3: Commit**

```bash
git add pams-web/src/styles/global.css
git commit -m "fix: hide sidebar scrollbar and remove hover mask"
```

---

### Task 4: 仪表盘内容区接入瀑布流

**Files:**
- Modify: `pams-web/src/pages/Dashboard.tsx`（import 区 + 下方 2 列 grid 结构 L175-496）

**Interfaces:**
- Consumes: Task 2 的 `Masonry`。
- Produces: 仪表盘下方 7 张内容卡以瀑布流排列，底部对齐。

- [ ] **Step 1: 引入 Masonry**

在 `Dashboard.tsx` 的 import 区（`import GlassCard from '@/components/glass/GlassCard'` 附近）加：

```tsx
import Masonry from '@/components/glass/Masonry'
```

- [ ] **Step 2: 用 Masonry 替换 2 列 grid**

将当前 `L175` 起的 `2 列 grid` 结构：

```tsx
<div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1.9fr) minmax(0, 1fr)', gap: 16, alignItems: 'start' }}>
  {/* 左列：本周排班 + 活动动态 + 我的待办 */}
  <div style={{ display: 'grid', gap: 16 }}> ... 3 张卡 ... </div>
  {/* 右列：最新推文 / 最新材料 / 最新公告 / 底部信息 */}
  <div style={{ display: 'grid', gap: 16 }}> ... 4 张卡 ... </div>
</div>
```

改为（删除两个内层列容器 div 与外层 grid div，把 7 张 GlassCard 平铺为 Masonry 的 children，顺序即当前左列 3 张 → 右列 4 张）：

```tsx
<Masonry gap={16} minColWidth={340}>
  <GlassCard style={{ padding: 20 }}> {/* 本周排班（当前 L178-288 整块，内容不动） */} </GlassCard>
  <GlassCard style={{ padding: 20 }}> {/* 活动动态（当前 L290-346 整块，内容不动） */} </GlassCard>
  <GlassCard style={{ padding: 20 }}> {/* 我的待办（当前 L348-388 整块，内容不动） */} </GlassCard>
  <GlassCard style={{ padding: 20 }}> {/* 最新推文（当前 L393-427 整块，内容不动） */} </GlassCard>
  <GlassCard style={{ padding: 20 }}> {/* 最新材料（当前 L429-461 整块，内容不动） */} </GlassCard>
  <GlassCard style={{ padding: 20 }}> {/* 最新公告（当前 L463-487 整块，内容不动） */} </GlassCard>
  <GlassCard style={{ padding: 20, textAlign: 'center', color: 'var(--color-text-secondary)' }}> {/* 底部信息（当前 L489-494 整块，内容不动） */} </GlassCard>
</Masonry>
```

注意：只动容器结构，7 张卡内部 JSX（排班表、列表、按钮等）整块原样保留、保持现有先后顺序。顶部 4 个统计卡行（`L141-173`）不动。

- [ ] **Step 3: 构建检查**

Run（在 `pams-web` 下）: `npm run build`
Expected: TypeScript 编译与 Vite 构建成功。

- [ ] **Step 4: 浏览器验证**

浏览器预览检查：
- 1280×800 下，下方内容卡分 3 列瀑布流，各列底部对齐（列高差不超过一张卡高度）；
- 收窄窗口到 ~800px 变 2 列、~700px 以下变 1 列，底部仍对齐；
- 顶部 4 个统计卡保持一行，页面无横向滚动条。

- [ ] **Step 5: Commit**

```bash
git add pams-web/src/pages/Dashboard.tsx
git commit -m "feat: dashboard content area uses masonry layout"
```

---

### Task 5: 活动动态只显示 3 条 + 滚动 + 清除按钮

**Files:**
- Modify: `pams-web/src/pages/Dashboard.tsx`（活动动态卡片 L290-346，`Space` 已导入）

**Interfaces:**
- Consumes: 现有 `upcomingActivities`、`Empty`、`Button`、`Space`。
- Produces: 活动动态卡片展示 3 条、超出滚动、可本地清除。

- [ ] **Step 1: 加本地清除状态**

在 `Dashboard.tsx` 状态区（`const [loading, setLoading] = useState(true)` 附近）加：

```tsx
const [cleared, setCleared] = useState(false)
```

- [ ] **Step 2: 头部加"清除"按钮**

将活动动态卡片的头部右侧（当前只有"全部活动"链接）：

```tsx
<Button type="link" onClick={() => navigate('/activities')}>
  全部活动
</Button>
```

改为：

```tsx
<Space size={4}>
  {!cleared && (
    <Button type="link" size="small" onClick={() => setCleared(true)}>
      清除
    </Button>
  )}
  <Button type="link" onClick={() => navigate('/activities')}>
    全部活动
  </Button>
</Space>
```

- [ ] **Step 3: 列表容器限高滚动 + 清除空态**

将活动动态卡片的内容区（当前 `upcomingActivities.length === 0 ? <Empty/> : <div style={{ display: 'grid', gap: 8 }}>...</div>`）改为：

```tsx
{cleared || upcomingActivities.length === 0 ? (
  <Empty description={cleared ? '已清除活动动态' : '暂无进行中的活动'} style={{ padding: 24 }} />
) : (
  <div style={{ display: 'grid', gap: 8, maxHeight: 194, overflowY: 'auto', paddingRight: 6 }}>
    {upcomingActivities.map((a) => {
      return ( /* 当前 L304-342 的单条活动项 JSX 整段原样保留，不改 */ )
    })}
  </div>
)}
```

`maxHeight: 194` 为恰好容纳 3 条的估算值（单条约 59px + 2×8px 间隙），实现时以浏览器实测为准微调。

- [ ] **Step 4: 构建检查**

Run（在 `pams-web` 下）: `npm run build`
Expected: TypeScript 编译与 Vite 构建成功。

- [ ] **Step 5: 浏览器验证**

浏览器预览检查：
- 活动动态默认只露出 3 条，超出部分有滚动条并可滚动；
- 点"清除"后按钮消失、卡片显示"已清除活动动态"空态；
- 刷新页面后活动列表恢复。

- [ ] **Step 6: Commit**

```bash
git add pams-web/src/pages/Dashboard.tsx
git commit -m "feat: activity feed limits to 3 with clear button"
```

---

### Task 6: 全量验证

**Files:**
- Modify: 无（纯验证，有问题则回前序任务修正）

**Interfaces:**
- Consumes: Task 1-5 全部产物。

- [ ] **Step 1: 跑全部前端单测**

Run（在 `pams-web` 下）: `npm test`
Expected: 原有测试 + 新增 `masonry.utils` 5 个用例全 PASS。

- [ ] **Step 2: 构建**

Run: `npm run build`
Expected: TypeScript 编译与 Vite 构建成功。

- [ ] **Step 3: 浏览器回归**

浏览器预览（`teacher` / `123456`）逐项确认：
1. 侧边栏无滚动条、悬停无白色遮罩；
2. 仪表盘瀑布流在不同宽度下列数正确、底部对齐；
3. 活动动态 3 条 + 滚动 + 清除按钮工作正常；
4. 登录态、菜单高亮、页面切换、其它页面（排班/材料库等）无回归。

若某项不满足，回到对应 Task 修正后重新验证，不得宣称未验证的结果。
