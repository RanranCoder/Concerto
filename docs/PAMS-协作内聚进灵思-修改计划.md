# 协作能力内聚进灵思 —— 修改计划

> 日期：2026-09-22
> 状态：**待拍板**（关键决策点见 §7，确认后再动代码）
> 触发需求：协作不应作为独立功能页面，应把业务集成进灵思 Agent，在标准模式下自动判断启用
> 关联文档：`PAMS-通用化与多Agent协作改造策划.md`（本文**修订**其 §3.8 与批次 D 的前端交付形态）

---

## 0. 一句话结论

把多 Agent 协作从「一个需要用户主动进入并手选 P1–P4 的独立页面」，改为**灵思标准模式内部的一个执行策略**：由服务端按任务特征**自动判定**单 Agent 还是协作组，协作过程**就近呈现**在对话流里（复用现有过程区/时间轴），而不是另开一屏。前端删除 `/agent/team` 页面与「协作」菜单项，后端新增一个**轻量路由器**决定是否升级为协作 Run。

---

## 1. 现状盘点（为什么要改）

### 1.1 现在存在两条并行链路

| | 单 Agent 链路（灵思） | 多 Agent 链路（协作） |
|---|---|---|
| 入口 | `/agent` 灵思对话页 | `/agent/team` 独立页面 + 侧边栏「协作」菜单 |
| 后端 | `AgentOrchestrator.run()`：LLM ↔ 工具循环 | `CoordinatorAgent` + `SpecialistExecutor` + `Blackboard` |
| 模式 | `standard` / `readonly` / `kb` 三档（输入框分段控件切换） | **用户手选 P1/P2/P3/P4** |
| 事件 | `AgentStreamEvent`（同一套 WebSocket） | 同一套 WebSocket，但事件类型是 `AGENT_*`/`REVIEW_RESULT` |
| 前端呈现 | 三栏：会话列表 / 消息文档流 / 产物面板 | `AgentTeam` 独立页：协作拓扑 + 团队面板 + 黑板 |

### 1.2 问题

1. **能力被藏起来了**：用户必须先知道「协作」这个功能存在、再手选一个协作模式（P1 串行 / P2 并行 / P3 反思 / P4 委派），而多数用户并不知道自己的任务该用哪种模式 —— 模式选择本该是系统的判断，不该是用户的负担。
2. **与灵思割裂**：协作结果（策划书、推文、排班）产出后，用户在协作页看到的是拓扑与黑板，而不是他要的东西；真正能接着干活的地方在灵思。
3. **同一套事件、两套壳**：两边已经共用 WebSocket 与 `agent_run/step` 表，独立页面属于额外的呈现成本。
4. **与论文叙事不符**：论文的创新点是「多 Agent 协作编排」，而不是「多了一个页面」。自动编排（何时该协作）比手动选模式更有论证价值。

### 1.3 已具备的复用基础（改动面因此可控）

- 后端已有 `AgentRegistry` / `Blackboard` / `CoordinatorAgent` / `SpecialistExecutor`（批次 B、C 已落地），`AgentTeamController` 提供 `plan` / `run` / `runs/{id}` / `agents`。
- 前端已有事件驱动的过程区：`LingsiProcessZone`、`LingsiTimelineRow`、`stores/agent.ts` 的 `mergeTimeline`；`stores/agentTeam.ts` 已能消费 `AGENT_START/END`、`TOOL_START/END`、`REVIEW_RESULT`、`RUN_DONE`。
- 权限与门禁是共享的：`PolicyGuard`、`TaskGate`、`ToolContext.mode` 在协作链路里同样生效（策划 §3.5 已论证）。

---

## 2. 目标形态

```
用户在灵思输入一句话（标准模式）
        │
        ▼
┌─────────────────────────────────────────────┐
│ ① 路由判定（新增，服务端，一次轻量 LLM 调用）│
│   单 Agent 够用？ ──是──▶ 现有工具循环（零改动）│
│         │否                                  │
│         ▼                                    │
│ ② 升级为协作 Run                             │
│   自动选模式 P1/P2/P3/P4（不由用户选）        │
│   协调者分解 → 专家并行/串行 → 审查 → 汇总     │
└─────────────────────────────────────────────┘
        │  同一 WebSocket 推同一套事件
        ▼
   灵思对话流内联呈现：
   · 过程区逐行显示「检索专家 正在查活动」「写作专家 生成草稿」
   · 审查结论以卡片内联出现（PASS / REVISE + 理由）
   · 需要人工确认时照旧出确认卡（TaskGate 不变）
   · 产物照旧进右侧产物面板
```

**关键点：用户视角只有「灵思」一个入口**，协作是它在背后换了一种执行策略。

---

## 3. 改动范围

### 3.1 前端

| 文件 | 改动 | 说明 |
|---|---|---|
| `router/index.tsx` | **删除** `/agent/team` 路由 | 保留 `/agent` |
| `layouts/MainLayout.tsx` | **删除**「协作」菜单项；`selectedKey` 去掉 `/agent/team` 分支 | — |
| `pages/agent/AgentTeam.tsx` | **删除**（301 行） | 其能力下沉为灵思内联组件 |
| `components/agent/LingsiTeamPanel.tsx` | **新增** | 由 `AgentTeam` 的拓扑/团队面板抽取，作为**可折叠**的内联块（默认折叠，点开看协作细节） |
| `components/agent/LingsiReviewCard.tsx` | **新增** | 审查结论卡（PASS / REVISE + 理由 + 轮次） |
| `components/agent/LingsiTimelineRow.tsx` | 扩展 | 支持「专家进度」行：`检索专家 · 查活动概览 · 1.2s` |
| `components/agent/LingsiMessageBody.tsx` | 扩展 | AI 回复带 `agent_name` 时显示协作者身份标识 |
| `stores/agent.ts` | 扩展 `mergeTimeline` | 增加 `AGENT_*` / `REVIEW_RESULT` 分支（`stores/agentTeam.ts` 已有等价逻辑，抽取共用） |
| `stores/agentTeam.ts` | 改造 | 由「页面驱动」改为「随灵思对话流消费事件」，去掉 `start()` 的用户手选模式入参 |
| `components/agent/LingsiComposer.tsx` | 微调 | 模式标识区显示「自动协作」状态（见 §7 D3） |
| `components/agent/LingsiEmptyState.tsx` | 微调 | 示例任务里体现「它会自己组队」 |

### 3.2 后端

| 文件 | 改动 | 说明 |
|---|---|---|
| `service/CollaborationRouter.java` | **新增** | 判定是否升级为协作 + 选模式。见 §4 |
| `service/AgentOrchestrator.java` | 接入 | 在 `run()` 的工具循环前调用路由器；命中则委托 `CoordinatorAgent`，否则走既有循环 |
| `orchestration/CoordinatorAgent.java` | 扩展 | 模式由「入参」改为「路由器给定」，保留手动指定能力（供实验对比用） |
| `controller/AgentTeamController.java` | 收缩 | `/run` 不再对前端开放手选模式；保留 `/runs/{id}` 供回放；`/agents` 保留 |
| `stream/AgentStreamEvent.java` | 扩展 | 补 `AGENT_DELEGATE`、`COLLAB_DECISION`（携带判定理由，便于前端解释「为什么组队」） |
| `core/ToolContext.java` | 扩展 | 增加协作开关（默认开）与预算上限，避免协作失控 |

### 3.3 文档

- 修订 `PAMS-通用化与多Agent协作改造策划.md` §3.8（原文：新增「协作视图」）与批次 D 的交付形态。
- 本文作为该 §3.8 的替代方案。

---

## 4. 自动判定规则（核心设计）

### 4.1 判定放在哪一层

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A 路由前置** | 工具循环前做一次轻量判定，决定走单 Agent 还是协作 | 判定与执行解耦；可解释、可实验对比；单 Agent 路径零改动 | 多一次 LLM 调用（约百毫秒级） |
| B 工具化 | 把「召集协作」做成一个工具，由 LLM 在循环中自行调用 | 复用现有 function calling，最省事 | 模型可能滥用或该用不用；判定逻辑埋进提示词，不利于论文论证 |
| C 协调者常驻 | 所有请求都先过协调者 | 架构统一 | 简单问答也要付协调成本，TTFE 明显变差 |

**取 A**。理由：判定要能被单独评估（对应策划批次 E 的对比实验「任务完成率 / 平均轮次 / 耗时」），而 B 的判定散在提示词里，无法单独度量；C 会牺牲现有做得不错的 TTFE。

### 4.2 触发信号（路由器的判据）

**升级为协作（命中任一）**：

| 信号 | 例子 | 目标模式 |
|---|---|---|
| 跨域复合任务：检索 + 成文/配置 | 「查上届迎新晚会的材料，照着写一篇推文」 | P1 串行 |
| 多源证据比对 | 「对比去年和今年的素拓加分规则差异」 | P2 并行 |
| 高质量长文产出 | 「写一份主题党日活动的完整策划书」 | P3 反思 |
| 需要审核的敏感/对外内容 | 「把这篇文章发公告」 | P3 反思（Reviewer 必过） |
| 显式要求 | 用户说「多人协作/仔细核对/交叉验证」 | 按语义选 |

**保持单 Agent（不升级）**：

| 信号 | 例子 |
|---|---|
| 单一事实查询 | 「这周我有几节课」 |
| 单工具写操作 | 「给张三加 2 分素拓」 |
| 纯知识问答（已在 `kb` 档位分流） | 「入党流程是什么」 |
| 轻量寒暄/澄清 | 「你好」「刚才那个再改一下」 |

**兜底**：判定失败或超时 → **一律走单 Agent**（协作是增强，不是必需路径，不能因判定故障让对话不可用）。

### 4.3 约束

- **预算**：一次 Run 的 Agent 调用数与轮数上限（沿用 `props.getMaxToolIterations()` 的思路新增 `maxCollaborationAgents`），超限强制收口。
- **幂等**：同一 `streamId` 不允许并发发起两个协作 Run。
- **降级**：协调者/专家任一环节异常 → 回落到单 Agent 重跑该请求，并在过程区提示「已降级为单 Agent 处理」。
- **可解释**：`COLLAB_DECISION` 事件带上判定理由（命中了哪条信号），前端可展示「因为需要跨域检索+成文，已启用 3 个专家协作」。

---

## 5. 分批实施

| 批次 | 内容 | 出口条件 |
|---|---|---|
| **C1 前端收口** | 删 `/agent/team` 路由与菜单、删 `AgentTeam.tsx`、抽 `LingsiTeamPanel`（折叠块）与 `LingsiReviewCard` | 侧边栏不再有「协作」；`/agent/team` 404；`tsc` + 测试 + 构建通过 |
| **C2 事件内联** | `mergeTimeline` 支持 `AGENT_*` / `REVIEW_RESULT`；过程区显示专家行；回复带协作者身份 | 手工触发一次协作 Run，对话流内能看到专家进度与审查卡 |
| **C3 路由器** | 新增 `CollaborationRouter` + `AgentOrchestrator` 接入 + `COLLAB_DECISION` 事件 | ✅ **已完成**，见 §10 |
| **C4 默认启用** | 标准模式默认开自动协作；加预算/幂等/降级兜底 | 默认已开 + 预算/降级已实现（§10.1）；**幂等（同一 stream 不并发两个协作 Run）未做** |
| **C5 回归与实验** | 单 Agent 与协作两条路径的对比数据（供论文） | 未做 |

**C1 可独立交付**（纯前端删除 + 抽取），风险最低；C3 是技术核心。

---

## 6. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 判定错误：该协作没协作 / 不该协作却协作 | 体验劣化、成本上升 | 兜底走单 Agent；判定理由可见可反馈；C5 用数据调阈值 |
| 协作延迟明显高于单 Agent | 用户等不了 | 仅在命中信号时启用；并行模式用线程池；保留 TTFE 指标持续观测 |
| 删除独立页面后，协作过程「看不见了」 | 论文演示效果下降 | `LingsiTeamPanel` 可折叠但**默认在协作时自动展开**，拓扑仍然可见；`/agent/team` 的能力不是删除而是内联 |
| 与既有策划文档冲突 | 后续按旧文档施工会走偏 | §3.3 已列出要修订的章节 |

---

## 7. 关键决策点（已拍板 2026-09-22）

| # | 决策 | 选项 | **结论** |
|---|---|---|---|
| **D1** | 自动判定的实现路径 | A 路由前置 / B 做成工具由 LLM 自调 / C 协调者常驻 | **A** —— 判定可单独评估，能出论文数据 |
| **D2** | 是否保留用户手动开关 | 全自动 / 输入框开关默认开 | **留开关、默认开启** —— 误判时用户可干预，也便于对比实验控制变量 |
| **D3** | 协作过程可见度 | 默认折叠 / 命中协作时自动展开 | **自动展开** |
| **D4** | `/agent/team` 是否保留 | 真删 / 保留隐藏入口 | **彻底删除路由和页面** —— 用户明确选择；后续若需对比实验，按 §7.1 恢复 |
| **D5** | 模式还让不让用户选 | 不让选 / 保留调试入口 | **不让选** |

### 7.1 D4 = 彻底删除的连带影响（须记录，避免以后返工）

删除 `/agent/team` 后，**暂时没有「手动指定 P1–P4」的入口**。而策划批次 E 的对比实验（单 Agent vs 多 Agent 的任务完成率/轮次/耗时/事实一致性）需要可控地指定协作模式。

**缓解方式（不恢复页面）**：把模式指定改为**请求参数**，由脚本/测试直接打接口，而不是靠 UI：

- `AgentTeamController.POST /run` 保留 `mode` 入参（供实验脚本调用，不再有前端页面消费它）；
- 前端在 C3 之后不再调用 `/run`，改由 `AgentOrchestrator` 内部委托；
- 实验通过 `docs/` 下的脚本或 `AgentBenchController` 走接口，无需 UI。

这样既满足「协作不是独立页面」，又不丢掉实验能力。

---

## 8. 确认后的第一步

按 **C1 + C2 一起做**（用户已确认）：删路由与菜单项、删 `AgentTeam.tsx`、抽 `LingsiTeamPanel` / `LingsiReviewCard`、把 `AGENT_*` / `REVIEW_RESULT` 事件接进 `mergeTimeline` 并在过程区内联呈现。本批**不改后端**，判定仍由后端在 C3 落地；本批完成后需保证 `tsc` + 测试 + 构建通过。

---

## 9. 实施记录（C1 + C2 已完成）

### 9.1 前端收口（C1）

**删除**（4 个文件，共约 470 行）：

| 文件 | 说明 |
|---|---|
| `pages/agent/AgentTeam.tsx` | 独立协作页（322 行） |
| `stores/agentTeam.ts` | 页面驱动的 store（其 `start()` 要求用户手选 P1–P4） |
| `styles/agent-team.css` | 该页专用样式 |
| `api/agentTeam.ts` | 该页专用接口（`runTeam`/`planTeam`/`listAgentDefinitions`） |

**同时清理**：

- `router/index.tsx`：删除 `/agent/team` 路由与 lazy 导入；
- `layouts/MainLayout.tsx`：删除侧边栏「协作」菜单项与 `selectedKey` 的 `/agent/team` 分支（`TeamOutlined` 保留 —— 「用户与权限」仍在用）；
- `hooks/useWebSocket.ts`：**删掉「再转发一份给协作页 store」那一行**（`useAgentTeamStore.applyEvent`）。协作事件现在只走 `useAgentStore.applyStreamEvent`，与单 Agent 事件同一个入口、同一条时间轴。

### 9.2 事件内联（C2）

| 改动 | 内容 |
|---|---|
| `api/agent.ts` | `AgentStreamEventType` 增加 `COLLAB_DECISION` / `AGENT_START` / `AGENT_END` / `AGENT_DELEGATE` / `REVIEW_RESULT`（与后端共用同一套 WebSocket 与 seq） |
| `stores/agent.ts` | `TimelineItem.kind` 增加 `collab` / `agent` / `review`，并新增 `agent` / `verdict` / `reason` 字段；`mergeTimeline` 增加 4 个分支。`AGENT_END` 用与 `TOOL_END` 相同的「从后往前找同名未完成行」策略配对，并发/多轮都正确 |
| `stores/agent.ts` | 新增 `autoCollab: boolean`（默认 `true`）与 `setAutoCollab` |
| `components/agent/LingsiTimelineRow.tsx` | 支持三类新行：`collab`（判定说明 + 模式标签）、`agent`（身份色头像替代通用图标，进行中显示三点脉冲）、`review`（渲染成一块结论区，PASS 绿 / REVISE 琥珀） |
| `components/agent/LingsiTeamPanel.tsx`（新） | 原独立页的「团队 + 拓扑」信息，改为**可折叠内联块**，置于过程区之上（先汇总「派了哪些专家」、再明细「每步做了什么」）。**无协作事件时整块不渲染**，单 Agent 轮次零占位 |
| `components/agent/LingsiMessageBody.tsx` | 在过程区上方挂载 `LingsiTeamPanel` |
| `components/agent/LingsiComposer.tsx` | 标准档位下显示「自动协作」开关（`--brand` 描边 + 浅主色底表示开启）。只读/对话档位不显示 —— 那两档不会升级协作 |
| `styles/categoryColors.ts` | 抽出 `agentColorOf` / `agentShortOf`（过程区行与团队面板共用，避免两处各写一份身份色） |

### 9.3 本批的边界（重要）

- **后端尚未产生协作事件**。`COLLAB_DECISION` / `AGENT_*` / `REVIEW_RESULT` 的发射属于 **C3**（`CollaborationRouter` + `AgentOrchestrator` 接入）。因此本批完成后：**单 Agent 对话行为完全不变**，协作 UI 处于「就绪但静默」状态。
- `autoCollab` 目前只有前端状态，**尚未随请求发给后端** —— 待 C3 定了后端字段名再接（避免先编一个字段名）。届时在 `api.agentChat(...)` 的入参里带上。

### 9.4 验证结果

| 项目 | 结果 |
|---|---|
| `npx tsc -b` | ✅ 0 error |
| `npx vitest run` | ✅ 9 文件 / **58 用例全绿** |
| `npm run build` | ✅ 通过 |
| 全仓 `AgentTeam`/`agentTeam`/`agent-team` 残留 | ✅ 0（仅一处文档注释已改写） |
| `/agent/team` 路由与菜单 | ✅ 已移除 |
| 侧边栏守卫测试（字号/圆角/颜色/变量） | ✅ 新增样式未破线（期间被守卫拦下一次 `font-size: 10px`，已改用 `--fs-micro`） |

---

## 10. 实施记录（C3 已完成）

### 10.1 后端：路由与编排接入

| 文件 | 改动 |
|---|---|
| `orchestration/CollaborationRouter.java`（新） | 判定「这一轮要不要组队」。**输入是 `CoordinatorAgent.plan()` 的确定性分解结果**，不是原始文本 —— 于是判定退化成「计划里有几个专家、有没有产出型环节」，比在关键词上叠启发式可靠。升级条件：有产出环节 **且**（跨域检索+成产 / 复合动作 / 正式文稿）。对外发布类走 P3，其余 P1。预算上限 5 个专家，超出回落单 Agent |
| `service/AgentOrchestrator.java` | 新增协作分流：`run()` 在工具循环**之前**调路由器，命中则委托协调者并把黑板组织成回复。与 `kbMode` 同样是前置分流，单 Agent 那 500 行循环**零改动** |
| `stream/AgentStreamEvent.java` | 新增 `COLLAB_DECISION` 事件与 `collabDecision(...)`；`agentStart/End/Delegate` 补 `displayName` + `conversationId` |
| `registry/AgentCatalog.java` | 新增 `displayName(name)`：英文 id → 中文展示名 |
| `orchestration/CoordinatorAgent.java` | 新增 `RunOutcome`（同时返回 Run 与黑板，避免黑板 JSON 往返）；`runWithBlackboard(...)` 供灵思用，旧的 `run(...)` 签名保留不动既有调用点 |
| `controller/AgentController.java` | `/chat` 读取 `collab` 开关（缺省 true，老客户端不受影响） |

### 10.2 前端：开关与归属校验

- `api/agent.ts`：`agentChat(..., collab)` 把开关传给后端。
- `stores/agent.ts`：协作事件按 **conversationId** 过滤 —— 过程事件是按用户广播的，不过滤会把后台/其它会话的专家行串进当前时间轴（这是实测发现的真实缺陷）。用会话 id 而非 streamId：后者要等 REST 返回，事件可能先到。

### 10.3 端到端实测（真实模型 deepseek，非 stub）

| 用例 | 结果 |
|---|---|
| 单点查询「有多少个活动」 | 2.5s，**无协作事件**（事件只有 THINKING/TOOL_*/MESSAGE_*），正确走单 Agent |
| 正式文稿「写完整策划书」 | 1s，**自动升级协作**，事件含 `COLLAB_DECISION` + `AGENT_START x3` + `AGENT_DELEGATE x2` + `REVIEW_RESULT`；payload 形如 `{"conversationId":10,"mode":"P1","reason":"需要产出正式材料，已派 2 个专家（策划专家、审查专家）"}` |

回复由黑板组织而成，形如「已由 N 个专家协作完成（P1）。**结果要点** … **审查结论** …」，不再把内部字段名暴露给用户。

### 10.4 验证

| 项目 | 结果 |
|---|---|
| 后端 `mvn test` | ✅ **440/440**（含新增 `CollaborationRouterTest` 13 例：单点查询不升级、跨域升级、正式文稿走 P3、关开关不升级、只读/对话档不升级、分解异常回落单 Agent、超预算回落、理由含中文专家名、专家去重） |
| 前端 `tsc` / `vitest` / `build` | ✅ 0 error / 58 用例 / 构建通过 |

### 10.5 一处坦白：我一度把审查改坏了，被回归测试抓住

修那个「专家反问却被判 REVISE」的误报时，我的第一版改动**直接删掉了 reviewer 里的产物判定**，等于让审查永远返回 PASS —— 安全机制被静默废掉。`MultiAgentBaseTest` 的 P3 反思回路用例当场失败（原本期望 3 轮 REVISE，变成 0/1）。

我一度怀疑是测试过时，用 `git stash` 回到改动前确认**基线是通过的**，从而定位到是自己引入的缺陷。最终改成正确的分层：

- **跳过审查**只在「专家没调工具、且回的是问句」时发生（那是正常追问，不是产出失败）；
- **保留**评审内的产物判定：没产物 → REVISE → 反思回路重做。这条不能删。

### 10.6 已知遗留：表单等待仍会被判 REVISE

实测用例 2 暴露出一个新的误报模式：策划专家返回的是**待用户填写的表单**（`createActivity[FORM]`）而非产物，审查因此报「尚无可用产物，需修订」。功能上人畜无害（表单照常弹出、澄清流程正常），但用户会看到一条多余的修订结论。

**根因**：`isClarifyingQuestion` 只认「回问句」，没认「等表单输入」这种同样合法的中间态。

**建议修法**：产出专家的 `Result` 里区分「调用了工具但状态为 `FORM`（等用户填）」与「工具 FAILED」——前者跳过审查，后者照旧 REVISE。属于 `SpecialistExecutor` 的小改，建议下一批处理。

---

## 11. 表单补全卡缺陷修复（2026-10-03）

### 11.1 现象

用户点「提交并生成清单」时提示「**开始日期为必填**」，但日期明明已经填了。

### 11.2 排查过程（每步都有实测证据）

| 步骤 | 结论 |
|---|---|
| 定位文案来源 | 「XX为必填」只由前端 `LingsiFormCard.tsx` 的 antd 校验规则生成，**不来自后端** |
| 接口实测后端 | 传 `2026-10-05` → 正确解析；传 ISO 带时间 / `2026/10/05` / `2026年10月5日` / 空串 → **全部被拒并重新弹表单**。`CreateActivityTool.parseDate` 只认严格 `yyyy-MM-dd` |
| 检查前端归一化 | `normalize()` 的 `dayjs → 'YYYY-MM-DD'` 正确 |
| 组件测试（jsdom，新增 `LingsiFormCard.test.tsx`） | **病根**：预填值进了 Form 的 store，却**没进控件** —— `dbgStore` 有 `startDate`，而 DOM 里两个日期输入框都是空字符串 |

### 11.3 根因（三个因素叠加）

1. 弹窗原用 `destroyOnHidden`：关闭即卸载 Form，而 antd 的 `initialValues` **只在挂载那一刻读一次**；
2. 预填值往往在弹窗打开**之后**才随表单到达；
3. `Form.Item` 自动注入的 value 在这种「先挂载、后取值」的时序下没有被控件认作受控值。

结果：用户看到空的必填日期框 → 点提交 → antd 校验失败 → 报「开始日期为必填」。

### 11.4 修复（三处，缺一不可）

| # | 改动 | 作用 |
|---|---|---|
| 1 | `destroyOnHidden` → `forceRender` | 表单常驻，不再反复卸载重建 |
| 2 | 显式 `antdForm.setFieldsValue(toInitialValues(form))`（依赖 formKey + 预填值指纹） | 与挂载时序无关地写入预填值 |
| 3 | `FormFieldControl` 所有控件从 `Form.useFormInstance()` 显式读写值 | 绕开 value 注入时序问题，保证「所见即所提交」 |

顺带修掉两个同源缺陷：

- **首次下发的表单没有 `taskId`**（后端只在 `submitInput` 的返回里补），而自动打开与 `key` 都依赖它 → 自动弹出从不触发、预填拿不到。改用 `formKey + 预填值指纹` 作识别键。
- `openedFor` 的 `useRef` 类型从 `number` 改为 `string`。

### 11.5 验证

| 项目 | 结果 |
|---|---|
| 新增 `LingsiFormCard.test.tsx` | ✅ 3/3（**修复前实测为红**：日期与文本都不显示） |
| 前端全量 | ✅ **61/61**（原 58 + 新 3） |
| `tsc` / `build` | ✅ 0 error / 通过 |
| 后端接口格式校验 | ✅ 逐个格式实测，确认只接受 `yyyy-MM-dd` |
