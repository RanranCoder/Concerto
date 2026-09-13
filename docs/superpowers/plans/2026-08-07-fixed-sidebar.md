# 桌面端固定侧边栏 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让桌面端左侧导航栏固定在视口内，仅右侧内容区域滚动。

**Architecture:** 在现有 Ant Design `Layout` 层级上建立固定的 100vh 布局边界。外层禁止页面滚动，`Sider` 占满视口并允许自身内部滚动，右侧 `Content` 成为唯一纵向滚动容器；不使用 `position: fixed`，不改变路由和业务逻辑。

**Tech Stack:** React 18、TypeScript、Ant Design Layout、Vite、Vitest、浏览器预览工具。

## Global Constraints

- 仅处理桌面端，暂不增加移动端适配。
- 不修改路由、菜单选中逻辑、Header 操作、弹窗或后端代码。
- 不增加依赖，不修改全局样式。
- 保留现有 Sider 宽度、边距、圆角和视觉样式。
- 工作区已有的后端迁移文件改动不属于本任务，不得覆盖或回滚。

---

### Task 1: 建立固定侧边栏布局边界

**Files:**
- Modify: `pams-web/src/layouts/MainLayout.tsx:131-205`

**Interfaces:**
- Consumes: 现有 `menuItems`、`selectedKey`、`location`、`Outlet` 和 Ant Design `Layout` 组件。
- Produces: 外层布局固定为视口高度；Sider 固定占满视口并可内部滚动；右侧 Content 独立滚动。

- [ ] **Step 1: 修改外层 Layout 样式**

将当前：

```tsx
<Layout style={{ minHeight: '100vh' }}>
```

改为：

```tsx
<Layout style={{ height: '100vh', overflow: 'hidden' }}>
```

保留 `NotificationToast` 和所有子组件顺序。

- [ ] **Step 2: 修改 Sider 样式**

保留现有 `width`、`className`、`margin` 和 `borderRadius`，将 Sider 的样式调整为：

```tsx
style={{
  height: '100vh',
  margin: 12,
  borderRadius: 16,
  overflowY: 'auto',
}}
```

不要再使用 `overflow: 'hidden'`，否则菜单过长时无法在侧边栏内部滚动。若需要避免横向溢出，可同时设置 `overflowX: 'hidden'`。

- [ ] **Step 3: 修改右侧 Layout 样式**

将：

```tsx
<Layout>
```

改为：

```tsx
<Layout style={{ height: '100vh', minWidth: 0 }}>
```

这样右侧 Header 和 Content 共享视口高度，且长内容不会撑宽布局。

- [ ] **Step 4: 修改 Content 滚动样式**

保留现有 `padding`，增加独立滚动和最小高度：

```tsx
<Content
  style={{
    minHeight: 0,
    overflowY: 'auto',
    padding: '0 24px 24px',
  }}
>
```

`minHeight: 0` 用于允许 flex 子项在固定高度布局中收缩，避免 Content 把右侧 Layout 撑出视口。

- [ ] **Step 5: 运行 TypeScript 和构建检查**

Run from `pams-web`:

```bash
npm run build
```

Expected: TypeScript 编译和 Vite 构建成功，无布局代码引入的错误。

- [ ] **Step 6: Commit**

```bash
git add pams-web/src/layouts/MainLayout.tsx
git commit -m "fix: keep desktop sidebar fixed while content scrolls"
```

提交时不要包含现有的后端迁移文件、`.claude/`、`.workbuddy/` 或 docs 未跟踪文件。

---

### Task 2: 浏览器验证滚动行为

**Files:**
- Modify: 无
- Test: 浏览器预览中的 `MainLayout` 桌面布局

**Interfaces:**
- Consumes: Task 1 产生的 100vh 外层布局和独立 `Content` 滚动容器。
- Produces: 可观察的桌面端布局验证结果。

- [ ] **Step 1: 启动前端开发服务器**

使用项目预览配置启动 `pams-web` Vite 开发服务器，确认服务正常运行。

- [ ] **Step 2: 使用桌面视口检查结构和样式**

使用 1280×800 桌面视口，检查：

- 外层页面本身没有纵向滚动；
- `.ant-layout-sider` 高度为视口高度附近，位置不随 Content 滚动改变；
- `.ant-layout-content` 的 `overflow-y` 为 `auto`；
- 右侧长内容可以滚动；
- Header 保持在右侧顶部；
- 没有横向滚动条。

- [ ] **Step 3: 检查现有交互未回归**

确认菜单点击仍能切换路由，当前菜单项仍正确高亮，且浏览器控制台无新增错误。

- [ ] **Step 4: 记录验证结果**

最终回复中说明实际执行的构建命令、浏览器检查结果；如果发现问题，先回到 Task 1 修正后重新验证，不宣称未验证的结果。
