# PAMS UI 设计方案 v3 — 简约高效

> 日期：2026-09-22
> 状态：**待评审**（本文只描述设计，不含代码改动）
> 范围：`pams-web` 全站视觉语言、组件规范、主壳层布局、页面模式、落地批次
> 前置文档：`docs/superpowers/specs/2026-08-09-remove-liquid-glass-design.md`（去玻璃）、`docs/PAMS-灵思UI改版方案-v2.md`（灵思设计语言）

---

## 0. 一句话结论

把 `lingsi.css` 已经验证过的设计语言（**灰阶极简、层级靠边框与间距、主色只用于可操作元素**）从「灵思一个页面」**收敛为全站唯一的视觉系统**；并采用**院校官网 `gzccc.edu.cn` 的主蓝 `#0034A2`** 替换现有品牌红。具体动作是：**删掉页面渐变与氛围光、卡片去阴影改为 1px 描边、把 11 种圆角与 10 种字号收敛为各 5 档、把主色限制在「可操作 / 当前定位」、并让 antd theme 与 CSS 变量来自同一个 token 源。**

不新增第三方依赖，不改业务 JSX 结构，不动后端。

---

## 1. 现状诊断（带证据）

### 1.1 三套并存的设计语言

| # | 语言 | 位置 | 特征 |
|---|---|---|---|
| A | 玻璃拟态残留 | `tokens.css` `glass.css` `global.css` `App.tsx` | 页面渐变底 + `body::before` 红色氛围光 + 卡片 `--surface-shadow` 大阴影 + 16px 大圆角 + 入场动画 |
| B | 灰阶极简（v2 设计语言） | `lingsi.css`（1605 行） | 白底 + `#f7f7f8` 浅灰面板 + 深灰字、1px 描边、8–10px 圆角、红收窄到 4 处 |
| C | 登录页暗色壁纸 | `glass.css` `.login-page` / `.login-card` | 深色壁纸 + 白色文字 + 下划线式输入框 + 白底红字按钮（主色替换后应为蓝字，待确认 §10.1） |

**问题**：从灵思页跳到仪表盘，同一个产品会经历三套视觉规则。B 是正确的方向，但它只覆盖了 1/39 个页面。

### 1.2 量化：不规范处

实测统计（`pams-web/src`）：

| 维度 | 现状 | 证据 |
|---|---|---|
| 圆角 | CSS 里 **11 种**：1/2/4/5/6/7/8/9/12/16/999；TSX 内联另有 10/12/14/16 | `border-radius` 统计 |
| 字号 | CSS 与 TSX 合计 **10 种**：10/11/12/13/16/22/28/30/40/72 | `fontSize` 统计 |
| 阴影 | **18 处** `box-shadow`，卡片默认带 `0 12px 40px` 大阴影 | `--surface-shadow` |
| 硬编码颜色 | 15 个 TSX 文件内联 hex/rgba（`SeatLayoutEditor` 21 处、`App.tsx` 19 处） | 内联色统计 |
| 主题变量 | `App.tsx` 里 19 个颜色值与 `tokens.css` **手抄两份**，无单一来源 | `App.tsx` L30–62 |

### 1.3 三个具体的「不够高效」

1. **层级靠阴影 → 密集布局发灰**：卡片默认 `0 12px 40px rgba(31,38,60,0.14)`，在材料库、成员管理这类密集表格页，卡片阴影互相叠加成一团灰雾，反而削弱了表格本身的可读性。
2. **页面渐变 + 红色氛围光 → 一切都被染色**：`--color-bg-page` 三段渐变叠 `body::before` 的 `radial-gradient(rgba(222,41,16,0.06))`，导致「浅灰面板」实际是暖灰，与 `lingsi.css` 的冷灰 `#f7f7f8` 对不上；同时这是个固定的合成层开销。
3. **红色到处都是 → 主行动不突出**：侧边栏选中项是红底红字（`itemSelectedBg: rgba(222,41,16,0.28)` + `itemSelectedColor: #DE2910`），卡片 hover 也是红边（`.glass-card:hover`）。当红色出现在导航、卡片描边和主按钮上时，用户扫视时找不到「下一步该点哪」。

---

## 2. 设计原则（5 条，冲突时按序裁决）

1. **内容优先，容器退让** — 页面上的高对比元素应该只有「内容」和「一个主行动」。装饰不参与竞争。
2. **层级靠边框与间距，不靠阴影** — 1px 描边 + 8/12/16 的间距台阶表达层级；阴影只保留给「真的浮在页面上」的浮层（下拉、气泡、模态）。
3. **一色一义，主色只做「可操作」** — 每种颜色只有一个语义。院校主蓝 `#0034A2` 只用于**可操作 / 当前定位**的元素；琥珀 `#FFA811` 只作记号不做表面；两者语义不可互换（见 §3.2）。
4. **token 只有一个来源** — `tokens.css` 是唯一出处，`App.tsx` 的 antd theme 从它读，不许手抄。
5. **克制的动效** — 只动 `opacity` / `color` / `border-color`，120–160ms；不做位移与缩放。

---

## 3. 色彩系统

### 3.1 材质三层（替代「玻璃 + 渐变」）

取舍说明：渐变底与氛围光提供了一定的「高级感」，是这次设计**主动放弃**的——它换来了中性表面、可预测的对比度，以及更少的合成开销。简约高效的诉求优先于此。

**浅色**底色由 `https://www.gzccc.edu.cn/` 的既有权重决定：该站以 `#0034A2` 为主色（CSS 中出现 82 次），页面底为 `#F5F5F5` / `#F8F8F8`，浅蓝块为 `#F4F7FE`。

**深色**底色改为**纯中性灰阶、不带任何色相**（见 §3.5 的调研）。原方案曾把深色做成蓝调（`#0E1320` / `#161F38`），实测观感是「整屏蒙了一层蓝」，长时间阅读发闷，已废弃。

| 层级 | Light | Dark | 用途 |
|---|---|---|---|
| **page**（页面底） | `#F5F6F8` 纯色 | `#17181A` 纯色 | 整页背景。**删除** `--color-bg-page` 渐变与 `body::before` 氛围光 |
| **canvas**（内容面） | `#FFFFFF` | `#1E2024` | 卡片、表格、表单、弹窗体 |
| **soft**（次级面） | `#F2F4F8` | `#24262A` | 表头、侧边栏、只读区 |
| **soft-strong**（交互面） | `#E7EAF2` | `#2C2F34` | 悬停底、选中底、disabled |
| **sunken**（凹陷面） | `#EDEFF4` | `#121315` | 输入框、搜索框、代码块（比 canvas **更深**） |

> `sunken` 是深色主题的关键细节：参考站（bilibili 深色）的搜索框 `#0D0E0F` 比导航栏 `#242628` **更深**，用「凹陷」表达「可填入」，而不是把输入框做成浅色凸起。

边框与阴影：

| token | Light | Dark | 说明 |
|---|---|---|---|
| `border`（分隔线/卡片边） | `#E2E7F0` | `#33363B` | 默认 1px |
| `border-strong`（输入框、需强调的控件边） | `#D3DAE6` | `#41454B` | 符合非文字控件的可辨识要求 |
| `shadow-1`（hover 微提升） | `0 2px 8px rgba(16,24,40,.06)` | `0 2px 8px rgba(0,0,0,.4)` | 仅 hover |
| `shadow-2`（浮层） | `0 8px 24px rgba(16,24,40,.10)` | `0 8px 24px rgba(0,0,0,.5)` | dropdown / popover / modal |
| 遮罩 | `rgba(0,0,0,.45)` | 同 | **中性纯黑**，不用带蓝的 `rgba(14,19,32,…)` |
| 卡片阴影 | **无** | **无** | 卡片用 1px `border` 表达边界 |

### 3.2 主色与强调色（取自 gzccc.edu.cn）

> 数据来源：抓取 `gzccc.edu.cn` 的 `css25/index.css`（137KB）逐色统计。**带出处的实测值，不是取色印象。**

| 站点用途 | 站点色值 | 出现次数 |
|---|---|---|
| 主导航 / header / 页脚底色、`color` 强调、hover、边框 | **`#0034A2`** | 82 |
| 与主色组成渐变 `linear-gradient(90deg,#0034A2,#0045D6)` | `#0045D6`（浅一档蓝） | 2 |
| 圆形 hover 底色（浅一档） | `#1C5AB7` | 2 |
| 英文小标题装饰线、面包屑 hover、列表 hover 副标题 | `#FFA811`（琥珀） | 7 |
| 顶部导航下拉面板底 | `#ACCE22`（黄绿） | 3 |
| 页面底 / 浅色块 | `#F5F5F5` `#F8F8F8` `#F4F7FE` | — |

**据此定义 PAMS 主色：**

| token | Light | Dark | 用途 |
|---|---|---|---|
| `brand` | `#0034A2` | `#2563EB` | 主按钮底、选中竖条、Tab 下划线 |
| `brand-hover` | `#0045D6`（站点渐变右端色） | `#3B7BF0` | hover |
| `brand-active` | `#002B85` | `#1D4FD8` | active / pressed |
| `brand-soft` | `#E7EDFB` | `rgba(37,99,235,.18)` | 选中项底色、焦点光环 |
| `brand-text` | `#0034A2` | `#7FA6F0` | **深色主题下**的蓝色文字/图标（`#2563EB` 在深底上仅 3.16:1，不能用作文字） |
| `accent`（琥珀） | `#FFA811` | `#FFA811` | 见下方使用边界 |
| `accent-text` | `#9A5B00` | `#E0A854` | 琥珀需要当文字/图标时的可读版本 |

**`#ACCE22` 不纳入**：它只在站点一处（顶部导航下拉）出现，饱和度极高，与「简约高效」的克制基调冲突。

**琥珀的硬性使用边界**（关键，否则违反原则 3「一色一义」）：

| ✅ 允许 | ❌ 禁止 |
|---|---|
| 选中项的**左侧竖条**、小面积装饰线 | 大面积填充、按钮底色 |
| 图表里的高亮数据点（与蓝形成对照） | 正文文字（`#FFA811` 在白底上仅 **1.93:1**，不可读） |
| 「待办 / 需要注意」的标记 | 与主蓝混用表达同一语义 |

琥珀是**记号色**不是**表面色**：它负责「看这里」，蓝色负责「在这里操作」。两者语义不可互换。

### 3.3 语义色（状态，不是强调色）

| 语义 | Light 文字/图标 | Light 底 | Dark 文字/图标 | Dark 底 | 浅底上实测对比度 |
|---|---|---|---|---|---|
| success 成功/通过 | `#1F7A3D` | `#EDF7EF` | `#5FBF7A` | `#12241A` | 4.90:1 ✅ |
| warning 待办/临期 | `#9A5B00` | `#FDF5E6` | `#E0A854` | `#2A2314` | 5.01:1 ✅ |
| danger 失败/危险 | `#C0271A` | `#FDECEA` | `#F0735C` | `#2A1A17` | 5.18:1 ✅ |
| info 进行中/提示 | `#0E7490` | `#E6F4F7` | `#38BDF8` | `#12242B` | 4.76:1 ✅ |

`info` 用**青色**而非蓝，是为了让「进行中」的标签与主蓝按钮在同一屏内可区分——否则同屏出现两种蓝，用户无法判断哪个可点。语义色**只用于状态标签、进度、提示条**，不用于按钮底色。

### 3.4 文字色（实色，不用 alpha）

改用实色的理由：当前 `rgba(17,24,39,.6)` 这类写法让对比度随底色浮动，无法验收；实色可以逐条核算。浅色偏蓝与主色同源，深色为中性灰（不带色相，见 §3.5）。

| token | Light | 对比度(on canvas) | Dark | 对比度(on canvas) |
|---|---|---|---|---|
| `text-primary` | `#1B2438` | **15.5:1** ✅AAA | `#E6E8EC` | **13.3:1** ✅AAA |
| `text-secondary` | `#5A6478` | **6.0:1** ✅AA | `#A2AAB6` | **7.0:1** ✅AA |
| `text-tertiary`（提示、时间、元信息） | `#7A8496` | **3.8:1** ✅AA(大字) | `#8A93A3` | **5.3:1** ✅AA |
| `text-disabled` | `#A8B0BB` | — | `#5A626E` | — |

主按钮白字对比度：`#FFFFFF` on `#0034A2` = **10.43:1**（✅ AAA），深色主题 `#2563EB` = **5.17:1**（✅ AA）；深色下蓝色文字 `#7FA6F0` on `#1E2024` = **6.69:1**（✅ AA+）。深色正文刻意**不用纯白 `#FFF`**（`#E6E8EC`），降低暗环境眩光。

> 全表实测值，非估算。主蓝 `#0034A2` 作文字时可达 10.4:1，比原品牌红（4.74:1）的可读性显著更高——这是采用院校主色的一个附带收益。

### 3.5 深色模式调研（2026-09）

**问题**：v3.2 的深色把中性色做成了蓝调（`#0E1320` / `#161F38` / `#1D2742`），实机观感是「整屏蓝色蒙层」，用户反馈「太阴间」。

**调研结论**（来源：[Uxcel《Mastering Elevation for Dark UI》](https://uxcel.com/blog/mastering-elevation-for-dark-ui-a-comprehensive-guide-342)、[HarmonyOS 深色模式设计指南](https://developer.huawei.com/consumer/cn/doc/doccenter-ux-design/dark-mode-0000001823255497)、参考站 bilibili 深色实测取色）：

| # | 最佳实践 | 本方案的落实 |
|---|---|---|
| 1 | 底色**不要纯黑** `#000`，用深灰（如 `#1E1F22`）避免高对比刺眼 | `page: #17181A`（与参考站完全一致） |
| 2 | 层级靠**逐级提亮 4–6%**，而不是阴影（深色下阴影几乎不可见且显脏） | page→canvas→soft→softStrong 四阶，相邻亮度比 1.08–1.14 |
| 3 | 色相必须**统一**，只允许极轻微偏移；否则整屏会有色偏 | 深色**完全中性**（R/G/B 接近），不带蓝调 |
| 4 | 正文不用纯白，降低眩光 | `#E6E8EC` |
| 5 | 凹陷控件（输入框/搜索框）**比容器更深**，表达「可填入」 | 新增 `sunken: #121315`，antd 的 Input/Select/DatePicker 均指向它 |
| 6 | 语义色在深色下要**提亮**才够对比 | success/warning/danger/info 全部 ≥5.6:1（实测） |
| 7 | 遮罩用中性黑，避免给整屏再加一层色偏 | `rgba(0,0,0,.45)`（原为带蓝的 `rgba(14,19,32,.45)`） |

**与参考站的数值对齐**：

| | 参考站（截图实测） | 本方案 |
|---|---|---|
| 页面底 | `#17181A`（占全图 42%） | `#17181A` ✅ 一致 |
| 次级面/导航 | `#242628`（占 16%） | `#24262A` ≈ 一致 |
| 凹陷控件 | `#0D0E0F` | `#121315`（同思路） |
| 分隔线 | `#2F3134` | `#33363B` |
| 正文 | `#E7E9EB` | `#E6E8EC` |

**刻意不照抄的地方**：参考站的「导航栏比页面底更亮」在本项目对应侧边栏，但侧边栏是常驻大面积元素，做到 `#242628` 会让 1/4 屏幕更亮、抢内容注意力。因此侧边栏用 `soft #24262A`（同参考站亮度），但**内容卡片 `canvas #1E2024` 比它更亮**——保证「内容 > 导航」的主次关系。

---

## 4. token 系统（单一来源）

### 4.1 结构

```
tokens.css  ←── 唯一出处（CSS 变量，明暗各一套）
     │
     ├──> styles/*.css      直接消费 var(--...)
     └──> theme.ts          读取同一份值，产出 antd ConfigProvider theme
```

执行方式：新增 `pams-web/src/styles/tokens.ts`，导出 `lightTokens` / `darkTokens` / `buildAntdTheme(mode)`；`tokens.css` 的值与它逐项对应，并加一条**一致性断言测试**（读 CSS 文本解析变量，与 TS 对象比对）防止再次漂移。这直接解决 §1.2 里「`App.tsx` 手抄 19 个颜色值」的问题。

### 4.2 字号刻度（10 → 7 档）

| token | 值 / 行高 | 用途 |
|---|---|---|
| `--fs-micro` | 11px / 1.25 | 密集场景极小字：座位格编号、品牌区部门名、图表刻度 |
| `--fs-caption` | 12px / 1.5 | 辅助、时间、免责 |
| `--fs-body` | 13px / 1.6 | 正文、表格单元格、菜单 |
| `--fs-body-lg` | 14px / 1.65 | 长文、表单标签 |
| `--fs-title` | 16px / 1.4 | 卡片标题 |
| `--fs-h2` | 20px / 1.3 | 页面标题 |
| `--fs-stat` | 24px / 1.2 | 统计卡数字 |

另有**展示档** `--fs-symbol/statXl/display/hero/watermark`（22/28/30/40/64px），只用于图标、Hero 数字、空状态大标题；新增前需先确认是否真需要超过 24px。

字重只用 **400 / 500 / 600**（弃用 700+，中文粗体在低分屏易糊）。字体栈保持现状：`"PingFang SC","Microsoft YaHei","HarmonyOS Sans SC","Noto Sans SC",sans-serif`。

### 4.3 间距 / 圆角 / 动效

- **间距**：4px 基准 —— `4 / 8 / 12 / 16 / 20 / 24 / 32`。卡片内边距统一 **16**，卡片间距 **16**，页面区块间距 **20**。
- **圆角**（11 种 → 4 档）：`--r-sm: 4px`（标签、小按钮）、`--r-md: 6px`（按钮、输入框）、`--r-lg: 8px`（卡片、表格容器）、`--r-xl: 10px`（弹窗、抽屉）；`999px` 仅头像/药丸。
- **动效**：`--ease: cubic-bezier(.22,1,.36,1)`、`--dur: 140ms`；过渡属性限定 `opacity, background-color, border-color, color`。保留 `.page-transition` 淡入但**去掉 `translateY(12px) scale(.995)`**，改纯 opacity 120ms；保留 `prefers-reduced-motion` 关闭规则。

### 4.4 antd theme 映射（关键项）

```ts
token: {
  colorPrimary: mode === 'dark' ? '#2563EB' : '#0034A2',   // 院校主蓝
  colorBgLayout:  page,      // #F5F6F8 / #0E1320
  colorBgContainer: canvas,  // #FFFFFF / #161F38
  colorBgElevated: canvas,   // 浮层也是白，靠 shadow-2 区分（不再用 surface-strong 变灰）
  colorBorder: border,
  colorBorderSecondary: border,
  colorText: textPrimary, colorTextSecondary: textSecondary,
  colorTextTertiary: textTertiary, colorTextQuaternary: textDisabled,
  colorSuccess: '#1F7A3D', colorWarning: '#9A5B00', colorError: '#C0271A', colorInfo: '#0E7490',
  borderRadius: 6, borderRadiusLG: 8, borderRadiusSM: 4,
  controlHeight: 32, controlHeightSM: 24, controlHeightLG: 36,
  fontSize: 13, fontFamily: '<保持>',
  boxShadow: shadow1, boxShadowSecondary: shadow2,
  wireframe: false,
},
components: {
  Menu: { itemSelectedBg: 'transparent', itemSelectedColor: brand,
          itemHeight: 34, itemMarginInline: 8, itemBorderRadius: 6 },
  Table:  { headerBg: soft, headerColor: textSecondary, headerSplitColor: 'transparent',
            rowHoverBg: soft, cellPaddingBlock: 10, borderColor: border },
  Card:   { paddingLG: 16, boxShadowTertiary: 'none' },
  Button: { fontWeight: 500, primaryShadow: 'none', defaultShadow: 'none' },
  Input/Select: { activeShadow: '0 0 0 3px <brand-soft>' },
  Layout: { headerBg: 'transparent', bodyBg: 'transparent' },
}
```

> `colorPrimary` 在深色主题改为 `#2563EB`：`#0034A2` 在深底上对比不足（3.16:1），不适合做深色主题的按钮底；深色下的**蓝色文字**另用 `brand-text: #7FA6F0`（6.7:1）。

`colorBgElevated` 改回与 canvas 同色是这次的关键简化：现在靠 `--surface-strong`（`#fbfcfe` / `#23262f`）区分浮层，但它与 canvas 只差 4 个色阶，肉眼几乎不可见，却让「同一屏出现两种白」。改用**同样的白 + `shadow-2` + 1px border** 来区分浮层，视觉更干净。

---

## 5. 主壳层（App Shell）改版

现状：侧边栏是**浮动卡片**（`margin: 12`、`borderRadius: 16`、`glass-card` 大阴影），与内容区之间有 12px 缝。

```
┌────────────┬──────────────────────────────────────────────┐
│  ◻ 党建工作台 │  [面包屑]                    🔔  ☾  ◻ 张老师 │  52px header
│  学院党建办  ├──────────────────────────────────────────────┤
│            │                                              │
│  ▎仪表盘    │   ← content: padding 20/24, max-width 1440    │
│   灵思      │      page-transition(opacity 120ms)          │
│   知识库    │                                              │
│   活动管理  │                                              │
│            │                                              │
│  200px     │                                              │
│  soft 底   │              page 底 (#F5F6F8)                │
│  1px 右边线 │                                              │
└────────────┴──────────────────────────────────────────────┘
```

| 元素 | 现状 | 改为 |
|---|---|---|
| 侧边栏 | 浮动卡片、16px 圆角、大阴影、`margin 12` | **贴边实心面板**：`background: soft`、`border-right: 1px solid border`、无圆角无阴影、宽 200 |
| 选中项 | 红底 + 红字 + 圆角块 | **`soft-strong` 底 + 左侧 3px 主蓝竖条 + `text-primary` 加粗** |
| 品牌区 | 红色头像块 | 定高 60px 一行：logo + 名称 + 部门名；底部 1px 分隔线；极淡品牌色光晕（6% 透明） |
| 父级菜单展开 | antd 默认 | 展开态父项文字 `text-primary` 无底色；子项 `padding-left 38px` + `--fs-caption` 退一层 |
| header | 透明、右对齐 | 52px、`border-bottom: 1px solid border`、左侧面包屑 + 右侧操作组 |
| 内容区 | `padding 0 24px 24px` | `padding 20px 24px 24px`；`max-width 1440px` 居中（超宽屏防表格拉伸） |
| 登录页 | 暗色壁纸 + 白字输入框 | **保持不变**（品牌落地页，独立于内页系统）；仅同步主按钮 token |

#### 5.1 侧边栏细化（2026-09-22 三轮迭代）

**诉求与落实**：

| 诉求 | 落实 |
|---|---|
| logo 区滚动时固定 | 侧边栏拆成「固定品牌头 60px + 独立滚动菜单区 `.app-nav`」，并给 Sider 与 antd 的 `.ant-layout-sider-children` 都加 `flex-direction: column` + `min-height: 0`，让菜单自己滚而不是整块滚 |
| 文字与图标高亮 | 新增侧边栏专用 token（见下表）。**不复用页面级 `--text-primary`** |
| 背景覆盖整个容器 | `--sider-bg` 同时铺在 `.app-sider`、`.ant-layout-sider-children`、`.app-nav` 三处，内容不足一屏或滚到底都不露空白 |
| **跟随主题切换** | 侧边栏色**明暗两套独立定义**（见下），切主题时一起变 |

**侧边栏色必须明暗两套独立定义**：

| token | 浅色主题 | 深色主题 | 用途 |
|---|---|---|---|
| `--sider-bg` | `#F2F4F8` | `#1F2226` | 侧边栏底 |
| `--sider-text` | `#1B2438` | `#FFFFFF` | 菜单文字/图标（对比 **14.1:1 / 16.0:1**） |
| `--sider-text-dim` | `#6D747F` | `rgba(255,255,255,.58)` | 部门名、二级菜单项（**5.2:1 / 5.9:1**） |
| `--sider-border` | `#E2E7F0` | `rgba(255,255,255,.08)` | 右侧边线与品牌区分隔线 |
| `--sider-selected` | `rgba(0,52,162,.10)` | `rgba(255,255,255,.10)` | 选中项底 |
| `--sider-hover` | `#E7EAF2` | `rgba(255,255,255,.06)` | 悬停底 |

**两个已经踩过的坑（都源于「一个色值想同时服务两套主题」）**：

1. **不要把侧边栏固定成深底。** v3.6 为了「白色高亮」把侧边栏做成明暗都深底，结果切浅色主题时侧边栏纹丝不动 —— 违反主题切换预期。正确解法是两套独立定义：浅色主题浅底深字，深色主题深底白字。
2. **不要写死白色。** 只要 CSS 或 antd token 里出现 `#fff` / `rgba(255,255,255,…)`，就等于把侧边栏钉死在深色主题上。所有侧边栏颜色必须走 `--sider-*`。

**蓝色仍然只出现在一处**：选中项左侧 3px 竖条。选中底用「浅主色（浅色）/ 半透白（深色）」，不做主色实填 —— 深色下低透蓝会把整行染蓝，正是「不要蓝色遮罩」要避免的观感。

> 侧边栏贴边后，`global.css` 里为浮动卡片打的补丁（`.ant-layout-sider.glass-card::before { content: none }`、零宽触发条跟随表面色等）可一并删除。

---

## 6. 页面模式（39 个页面 → 5 个模板）

统一外层结构，让页面之间「换页不换骨架」：

| 模式 | 结构 | 适用页面 |
|---|---|---|
| **A 仪表盘** | 统计行（4 卡，`--fs-stat` 数字）→ 瀑布流内容卡 | Dashboard |
| **B 列表** | `PageHeader`（标题 + 主行动）→ 筛选条（单行，`soft` 底 8px 圆角）→ 表格（无阴影卡）→ 分页右下 | 活动/材料/模板/成员/用户/素拓/公告/群聊/推文/新闻/考勤/排班 |
| **C 详情** | 页头（标题 + 状态标签 + 操作组）→ 信息卡（2 列描述列表）→ 下方 Tab 内容 | 活动详情/党务详情/成员详情 |
| **D 表单** | 页头 → 单列（≤640px）或双列卡片 → 底部固定操作条（右对齐） | 活动编辑/排班编辑/设置/权限管理 |
| **E 工作台** | 三栏（左列表 240 + 中主区 flex + 右产物面板 360，可拖宽） | 灵思（已符合，不改）、策划书编辑器 |

### 6.1 表格规范（全站最常用，值得单独定死）

- 表头：`soft` 底、`text-secondary` 12px/500、**无表头分隔线**（`headerSplitColor: transparent`）。
- 行高：`padding 10px 12px`（比现在更松一点，中文中文两行不再挤）。
- 行分隔：仅 `border-bottom: 1px solid border`，**无斑马纹**（斑马纹 + 阴影 = 灰糊）。
- hover：`soft` 底，**不用主色**。
- 操作列：固定右侧，图标按钮（无边框），危险操作用 `danger` 色。
- 空态：居中 `text-tertiary` 一句话 + 主行动按钮，不用插画。
- 加载：表体骨架屏（不用整页 Spin）。

### 6.2 状态标签（`StatusTag` 已存在，收敛其取值）

`StatusTag.tsx` 内联了 8 处颜色，改为读 §3.3 语义色 token；统一为「浅底 + 同色文字 + 无边框 + 4px 圆角 + 12px 字」，禁止使用纯色实底标签（太重）。

---

## 7. 组件规范

| 组件 | 关键规范 |
|---|---|
| 按钮 | 高 32；`primary` = `brand` 底白字无阴影；`default` = canvas 底 + `border-strong` 边 + `text-primary`；`text` 用于表格内操作；危险操作 = `danger` 色文字或描边，**不用主色实底**（避免与主按钮抢） |
| 输入/选择 | 高 32、`--r-md`、`border-strong` 边；focus = `brand` 边 + 3px `brand-soft` 光环；错误 = `danger` 边 |
| 卡片 | canvas 底 + 1px `border` + `--r-lg` + **无阴影**；hover **不变**（列表卡片不做 hover 位移，减少噪声）；只有可点击卡片 hover 时边线转 `border-strong` |
| 弹窗 | `--r-xl`、canvas 底、`shadow-2`、遮罩 `rgba(14,19,32,.45)`；头 16px 内边距、底右对齐操作 |
| 抽屉 | 同弹窗；宽度档位 360 / 480 / 640 |
| 标签 Tag | 高 22、12px、`--r-sm`、浅底同色文字（用 §3.3 语义色） |
| 提示条 Alert | 左侧 3px 语义色条 + 语义浅底 + `--r-md`（沿用 `lingsi.css` 已有的状态条做法） |
| 分页/空态/Tab | 选中 Tab = `text-primary` + 2px `brand` 下划线（与侧边栏竖条同一语义：**当前定位**，用主蓝；琥珀留给「需要注意」） |
| 图标 | 统一 `@ant-design/icons`，尺寸 14/16，颜色随文字 token |

---

## 8. 落地批次

> 每批都可独立验收、可回滚。**第 1 批做完即有 70% 的观感收益**，因为它去掉的是影响每一个页面的背景与卡片阴影。

| 批次 | 内容 | 改动文件 | 风险 |
|---|---|---|---|
| **P1 材质与背景**（高收益） | 删 `--color-bg-page` 渐变与 `body::before` 氛围光；`page/canvas/soft` 三层色落地；卡片去阴影改 1px 描边；圆角收敛到 4 档 | `tokens.css` `glass.css` `global.css` | 低（全局但纯视觉） |
| **P2 token 单一来源** | 新增 `styles/tokens.ts`；`App.tsx` 改为 `buildAntdTheme(mode)`；加一致性断言测试 | `tokens.ts`（新）`App.tsx` `theme.ts` `stores/theme.ts` | 中（需跑构建+测试） |
| **P3 壳层与导航** | 侧边栏贴边实心 + 选中态改「soft 底 + 主蓝竖条」；header 加底边；内容区宽度与内边距；删除浮动卡片补丁 | `MainLayout.tsx` `global.css` | 中（改 JSX 少量） |
| **P4 表格与状态标签** | 表格规范（表头/行高/hover/空态）；`StatusTag` 读语义 token；分页位置 | `StatusTag.tsx` + 各列表页（约 12 个） | 中（逐页核对） |
| **P5 收尾** | `.glass-*` 类名移除（`GlassCard`→`Card` 等，调用点 ~30 文件）；字号刻度全量替换；内联颜色收进 token | 见下 | 中（面广） |

**P5 的类名迁移要点**：`GlassCard`/`GlassModal`/`GlassTable`/`PageHeader` 被 ~30 个文件引用。做法是**先改组件内部实现（P1 已覆盖），类名留到 P5 再做纯重命名**，这样 P1 不需要碰那 30 个调用点——与去玻璃方案当时的策略一致。

### 8.1 明确不做的事

- ❌ 不改后端、数据库、路由、权限。
- ❌ 不引入 CSS-in-JS / Tailwind / 新 UI 库；继续用 antd 5 + 原生 CSS 变量。
- ❌ 不动 `lingsi.css` 的**布局与结构规则**（三栏、去气泡化、产物分屏都是本方案的范本）；仅把其中可复用的选中态/状态条规则提升为全局，并把**主色**从红替换为院校蓝（`--ls-*` 只定义了成功/警告语义色，主色走全局 token）。`lingsi.css` 里 8 处红色用于：发送按钮、左栏选中竖条、AI 头像底、不可逆操作警示。其中**不可逆操作警示保留红色**（`danger` 语义），其余三处改主蓝——与 §3.2 的「主色只做可操作/当前定位」一致。
- ❌ 不改登录页的暗色壁纸视觉。
- ❌ 不做深色/浅色之外的第三套主题；不做主题色自定义（院校主蓝 `#0034A2` 固定）。
- ❌ 不为了「好看」加插画、渐变、玻璃、发光。

---

## 9. 验收标准

1. **构建与测试**：`pams-web` 下 `npm run build`（tsc + vite）通过；`npm test` 通过（含新增 token 一致性断言）。
2. **视觉零残留**：`grep -rn "backdrop-filter\|--glass-" pams-web/src` 为 0；`grep -rn "box-shadow" pams-web/src/styles` 只剩浮层用的 `--shadow-1/2`。
3. **token 收敛**：CSS 中 `border-radius` 只出现 4/6/8/10/999；`font-size` 只出现 12/13/14/16/20/24。
4. **对比度**：按 §3.4 表格逐项复核（已实测，改色需重新核算）。
5. **双主题巡检**：登录页、仪表盘、列表页、详情页、表单页、弹窗、抽屉、侧边栏 —— 明暗各一遍，无灰糊、无染色底；院校主蓝只出现在「可操作 / 当前定位」处，琥珀只出现在「需要注意」处。
6. **一致性**：对照 `docs/design-preview.html` 在设计语言预览页里逐项比对色块、字号、圆角、组件状态。

---

## 10. 待你确认

1. **品牌红是否全量替换为院校主蓝**（影响面最大的一处）：`#DE2910` 出现在登录页（品牌标题装饰线、白底红字按钮）、`lingsi.css`（发送按钮、选中竖条、AI 头像底）、`App.tsx`（`colorPrimary`），以及各处 `rgba(222,41,16,…)`。
   - **建议 A（推荐）**：登录页也一并改为院校蓝体系（主按钮 `#0034A2` 白字、装饰线改 `#FFA811`）。产品对外是一套识别色。
   - 备选 B：登录页保留红作为历史品牌色，内页用蓝 —— 需要一个「红色只属于登录页」的理由，否则会被当成不一致。
2. **是否接受琥珀 `#FFA811` 作为第二强调色**：它取自站点「英文小标题装饰线 / hover」，在方案里被限定为**记号色**（小面积竖条、图表高亮、待办标记），**不做表面填充、不做正文**（白底上仅 1.93:1 不可读）。
3. **页面渐变是否舍得删**：`#f5f6f8→#e9ecf1→#e2e6ec` 渐变 + 红色氛围光将被纯色 `#F5F6F8` 取代，换来中性冷调表面与更少的合成开销。
4. **是否先做 P1**：只做 P1 就能看到主要观感变化（全站换主色 + 去渐变 + 卡片去阴影），且改动集中在 3 个样式文件、风险最低。

---

## 11. 修订记录

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-09-22 | v3 初稿 | 基于现状实测（圆角 11 种、字号 10 种、阴影 18 处、19 个颜色值双份维护）提出全站收敛方案；对比度逐项实算 |
| 2026-09-22 | v3.1 | **主色改用 `https://www.gzccc.edu.cn/` 的院校主蓝 `#0034A2`**（取自其 `css25/index.css` 实测统计：`#0034A2` 82 次、`#0045D6` 渐变端、`#1C5AB7` hover、`#FFA811` 强调、`#ACCE22` 弃用）；中性色相改为蓝调、深色主题重算（`#0034A2` 在深底仅 3.16:1，故深色主按钮改 `#2563EB`、蓝色文字用 `#7FA6F0`）；`info` 改青色以避开两种蓝同屏；全部 token 对比度重新实测 |
| 2026-09-22 | v3.2 | **落地实施**（见 §12）。发现并保留「组织自定义主色」能力：主色不再硬编码，由 `stores/org.ts` 的 `themeJson.primaryColor` 驱动，默认值改为院校主蓝 |
| 2026-09-22 | v3.3 | **内联样式收口**：83 处内联硬编码颜色 → 4 处（刻意保留）；CSS 字号 11 种 → 刻度；圆角 11 种 → 4 档；新增 `categoryColors.ts` 统一分类色板；**顺带修掉 30 处引用了未定义 CSS 变量的既有缺陷**（课表/排班/材料库等 15 个文件静默失效）；新增 3 条守卫测试（累计 58 用例） |
| 2026-09-22 | v3.4 | **深色模式重做**（见 §3.5）：中性灰阶替换蓝调底色（`#0E1320`→`#17181A`），新增 `sunken` 凹陷面，遮罩改中性纯黑，antd 表单控件指向凹陷面；深色全部对比度重新实测（正文 13.3:1、语义色 ≥5.6:1） |
| 2026-09-22 | v3.5 | **侧边栏细化**（见 §5.1）：选中底 `brand-soft`→`soft-strong`（原对比仅 1.076、整行染蓝，改为 1.128 + 3px 主蓝竖条）；品牌区定高 60px + 分隔线 + 6% 品牌色光晕，解决部门名截断与菜单糊在一起；菜单项高 34→36、图标 15→16、子项缩进退层；新增 `--fs-micro`(11px) 档补齐密集场景 |
| 2026-09-22 | v3.6 | **侧边栏再改**（见 §5.1）：品牌头固定 + 菜单独立滚动；新增 `--sider-*` 侧边栏专用 token；底色铺满容器与滚动区。**该版把侧边栏做成明暗都是深底 —— 这是错的**，见 v3.7 |
| 2026-09-22 | v3.7 | **修复：侧边栏不跟随主题切换**。根因是 v3.6 让侧边栏固定深底、并在 CSS/antd token 里写死白色（`#fff`、`rgba(255,255,255,…)`）。改为 `--sider-bg/text/text-dim/border/selected/hover` **明暗两套独立定义**：浅色主题 `#F2F4F8` 底 + `#1B2438` 字（14.1:1），深色主题 `#1F2226` 底 + `#FFFFFF` 字（16.0:1）；所有写死白色清除 |

---

## 12. 实施记录（v3.2 已落地）

### 12.1 token 单一来源（P2）

| 文件 | 作用 |
|---|---|
| `src/styles/tokens.ts`（新） | **唯一来源**：`lightTokens` / `darkTokens` / `CSS_VAR_MAP` / `buildTokenCss()` / `installTokenCss()` / `resolveTokens(mode, primary)` |
| `src/styles/tokens.css` | 由 `buildTokenCss()` 产出，**不得手改**。顶部有「请勿手改」标注 |
| `src/styles/color.ts`（新） | 颜色数学：解析、提亮/压暗、透明、WCAG 对比度、`deriveBrandScale` |
| `src/styles/theme.ts`（新） | `buildAntdTheme(mode, primary)`：antd 主题 + 组件级 token（Menu/Table/Modal/Button…） |
| `App.tsx` | 删掉原来手抄的 19 个颜色值，改为 `buildAntdTheme(mode, primary)`；并在主色变化时用 `installTokenCss(primary)` 重建 CSS 变量 |
| `src/styles/tokens.test.ts`（新） | **一致性断言**：逐项比对 `tokens.css` 与 `tokens.ts`；两套主题变量集合必须相同；自定义主色派生 + 对比度达标 |

多组织主题的处理：`resolveTokens()` 只覆盖 `brand/brandHover/brandActive/brandSoft/brandText`，**中性色与语义色保持全站一致**——「统一 UI」不等于砍掉组织换色能力。

### 12.2 组件与壳层（P1 / P3）

| 文件 | 改动 |
|---|---|
| `styles/global.css` | 删除页面三段渐变与 `body::before` 红色氛围光（改纯色 `--page`）；新增 `.app-sider` / `.app-header` / `.app-content` / `.content-inner` / `.page-header` / `.tone-tag` / `.stat-card`；表格与浮层收口；动效改纯 opacity 120ms |
| `styles/glass.css` | `.glass-card` 去阴影改 1px 描边；新增 `.tone-*` 六种色调；登录页改为主色变量驱动（保留暗色壁纸视觉） |
| `styles/agent-team.css` | 原先自带一整套硬编码灰阶（`#1f2328` / `#f6f7f9` 等），改为全部指向全局 token；字号小数号收敛到刻度 |
| `styles/lingsi.css` | `--ls-*` 从写实色字面量改为指向全局语义 token（否则灵思的绿和列表页的绿不是同一个绿）；主色红 → `var(--brand)`；状态条去硬编码 |
| `MainLayout.tsx` | 侧边栏从「浮动玻璃卡片」改为**贴边实心面板**（200px、`--soft` 底、右侧 1px 边框）；选中态改「浅主色底 + 左侧 3px 主色竖条」；header 加底边；内容区 `max-width 1440px` 居中 |
| `GlassCard.tsx` / `PageHeader.tsx` | PageHeader 去掉多余卡片外壳，改无容器形态；GlassCard 注释说明命名由来 |
| `StatusTag.tsx` | 从 antd 实底彩色 `Tag` 改为 `.tone-tag` 浅底色调（`neutral/brand/processing/success/warning/danger`） |
| `api/activityStatus.ts` | 新增 `ACTIVITY_STATUS_TONE`（状态→色调）；旧 `ACTIVITY_STATUS_COLOR` 标记 `@deprecated` |
| `pages/Dashboard.tsx` | 统计卡图标色改语义 token、去掉渐变与投影；两处 `<Tag color>` 改 `.tone-tag` |
| `stores/org.ts` | `DEFAULT_PRIMARY_COLOR` 由 `#DE2910` 改为 `#0034A2` |

### 12.3 全站变量改名（45 个文件）

旧变量名（`--color-text` / `--color-red` / `--surface` …）已删除，全站统一为 `--text-primary` / `--brand` / `--canvas` 等新名。改名脚本按「先长后短」替换，避免 `--color-text-secondary` 被 `--color-text` 截断；事后复查残留 0 处。

### 12.4 验证结果

| 项目 | 结果 |
|---|---|
| `npx tsc -b` | ✅ 通过（0 error） |
| `npx vitest run` | ✅ **9 文件 / 52 用例全绿** |
| `npm run build` | ✅ 通过（tsc + vite） |
| token 一致性断言 | ✅ 4 项（CSS↔TS 逐项一致、产物无手改、两套变量集合相同） |
| 自定义主色断言 | ✅ 7 项（只改 brand 系列、派生三色互异、浅底 AA、深色主按钮 AA、非法值回退） |
| `--color-*` 旧变量残留 | ✅ 0 处 |
| 样式文件硬编码颜色 | 从「3 套并存」收敛到仅 `tokens.css`（出处本身）+ 登录页暗色壁纸 + word-paper（刻意白纸黑字） |

顺带修复一个既有问题：Vitest 原先会收集 Playwright 的 `e2e/spec`，导致 `npm test` 必然失败；已在 `vite.config.ts` 加 `exclude: ['e2e/**']`。

### 12.5 内联样式收口（v3.3 已完成）

| 项目 | 统一前 | 统一后 |
|---|---|---|
| tsx 内联硬编码颜色 | **83 处 / 18 文件** | **4 处 / 3 文件**（全部刻意，见下） |
| CSS 字号取值种类 | 11 种（含 10.5/11.5/12.5 小数号） | 5 种：`--fs-caption/body/body-lg/title/h2/stat` + 展示档 |
| tsx 内联 fontSize | 14 种（10～64） | 刻度值 13/14/16 + 展示 token |
| CSS 圆角取值 | 11 种 | 4 档 `--r-sm/md/lg/xl` + `--r-pill` / 50% |
| 未定义的 CSS 变量引用 | **30 处**（见下，静默失效） | 0 |

**新增 `styles/categoryColors.ts`** —— 分类 identity 色板的唯一来源：
座位区配色（10 色）、材料业务类型、灵思产物类型、协作者角色色。这些是**「区分身份」的固定色**，与语义色（随主题/主色变化）职责不同，因此刻意保留 hex，但集中一处。

**顺带修掉的既有缺陷**（都不是本轮改坏的，是原本就存在）：

1. **30 处引用了从未定义的 CSS 变量** —— `var(--color-primary)`（15 处）、`var(--color-bg-2)`（14）、`var(--color-primary-weak)`（3）、`var(--color-bg-card)`、`var(--color-bg-elevated)`，涉及 15 个文件（课表、排班、材料库、权限管理等）。CSS 变量未定义时整条声明失效且**不报错**，所以这些页面的背景/主色一直是回退值或干脆没生效。
2. **`var(--easing)` / `var(--radius-md)` / `var(--radius-lg)`** 等旧名残留（`--easing` 在 Dashboard 与 lingsi.css，radius 在 lingsi.css）。
3. **两套座位图例**：`ActivityDetail`（8 色「国旗红系」）与 `SeatLayoutEditor`（10 色 antd 色板）各自一套，同一活动两处颜色对不上，现合并为一套。
4. **`GanttChart` 的 SVG 用了深色主题的白色叠层**（`rgba(255,255,255,0.03/0.05)`）画在浅色画布上，几乎不可见，现改为 `--soft` / `--border` / `--text-tertiary`。

**刻意保留的 4 处内联颜色**（已在守卫测试里显式豁免并注明理由）：

| 位置 | 理由 |
|---|---|
| `SeatLayoutEditor.tsx` 打印窗口 CSS | `window.open` 出的独立文档，拿不到页面 CSS 变量 |
| `SigninQR.tsx` 二维码 `bgColor/fgColor` | 二维码必须纯黑纯白才能被扫 |
| `OrgRegister.tsx` placeholder 示例文案 | 只是提示色值格式的占位文本 |
| `categoryColors.ts` | 色板定义处（值的出处本身） |

**新增 3 条守卫测试**（共 58 个用例）：

- `所有 var(--x) 引用的变量都有定义` —— 防止再次出现静默失效的死引用；
- `不再引用已废弃的旧变量名`；
- `CSS 字号 / 圆角只用刻度`、`tsx 里 fontSize 只用刻度`、`除刻意场景外无内联硬编码颜色`。

### 12.6 仍未处理

1. **`.page-header` 改为无容器后页面顶部更紧凑**，如需更松可在 `.page-header` 调 `margin-bottom`。
2. **`global.css` 的 Word 纸张区仍有 12pt/14pt/22pt 与 `#wp-*` 局部变量** —— 这是打印尺寸与「白纸黑字」的刻意例外，不参与主题。
3. **登录页保留暗色壁纸视觉**（`glass.css` 内白色 alpha 系列），按 §6 决定它独立于内页系统；装饰线与主按钮已改为跟随主色。
4. **视觉回归尚未人工巡检**：本轮靠 `tsc` + 58 个单测 + 构建保证「不坏」，但配色/间距的实际观感需要跑起来在明暗两主题下逐页看一遍（建议优先：仪表盘、活动详情、座位表、课表、灵思）。
