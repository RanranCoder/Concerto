# PAMS 通用化与「多 Agent 协作」改造策划

> **命题**：基于多 Agent 协作的高校学生组织智能工作台系统设计与实现
> **现状**：PAMS —— 面向高校党建办公室的党务管理系统（单 Agent「灵思」）
> **文档性质**：毕业设计改造策划（设计稿，待评审）
> **前置事实**：本文所有「现状」结论均来自对 `pams-backend` / `pams-web` 源码与 Flyway 迁移链（V1–V29）的直接核对。

---

## 0. 一句话结论

**这个项目已经完成了目标的一半，而且是最贵的一半。**

现有系统已经具备：单 Agent 编排器、21 个工具、工具级权限三层防护、写操作确认门禁、写时脱敏、出网唯一闸口、全链路审计、事件级流式、可插拔 LLM 网关、一整套知识库 RAG（知识源 SPI + 关键词检索 + 引用回填 + 聚合快照）、以及一整套前端 Agent 组件（19 个）。**这些在改造中都应原样复用，不要推倒重来。**

距离命题只剩两件事：

| # | 缺口 | 命题要求 | 改造量级 |
|---|---|---|---|
| 1 | **只有一个 Agent** | 「多 Agent 协作」 | 架构级，核心创新点 |
| 2 | **领域强绑定党建** | 「高校学生组织通用」 | 抽象级，主要是泛化与解耦 |

本策划的施工原则：**把现有单 Agent 作为「多 Agent 架构下的第一个专家 Agent」平滑降级接入，而不是废掉重写。**

---

## 1. 现状盘点（改造的起点）

### 1.1 已具备的能力（论文中可作为「工程基础」，改造中直接复用）

**后端 `com.pams.module.agent`（灵思）**

| 资产 | 位置 | 复用价值 |
|---|---|---|
| 单 Agent 编排器（function-calling 循环） | `service/AgentOrchestrator` | 直接降级为「专家 Agent 的执行内核」 |
| 工具契约 + 注册表 | `core/AgentTool`、`core/ToolRegistry` | 多 Agent 共享同一工具池，按 tag 切分 |
| 工具清单（21 个：读 11 / 写 10） | `tool/*` | 直接分配给各专家 Agent |
| 出网唯一闸口 + 写时脱敏 | `core/PolicyGuard` | 协作链路仍只走这一个闸口 |
| 确认门禁（dry-run + 一次性令牌 + 乐观锁） | `service/TaskGate` | **最终写操作**才触发，协作内部不写库 |
| 审计 | `service/AuditService` + `agent_audit_log` | 扩展 agent 维度即可 |
| 事件流 + WS + 轮询补拉 | `stream/AgentStreamEvent`、`AgentEventPublisher` | 扩展协作事件类型 |
| 可插拔 LLM + 三级降级 | `llm/LlmProvider`、`DeepSeekProvider`、`StubProvider` | 可做「按 Agent 角色路由不同模型」 |
| 可插拔知识检索 | `knowledge/retriever/KnowledgeRetriever` | 多 Agent 的共享检索能力 |

**后端 `com.pams.module.knowledge`（知识库）**

- `KnowledgeSource` SPI + 6 个实现（公告/推文/策划书/成员/手工文档/快照）→ **天然的「领域包扩展点」**。
- `KeywordRetriever`：MySQL ngram FULLTEXT × 源权重的 BM25 风格检索，权限下推到 SQL。
- `CitationMapper`：**服务端引用回填**，模型只能写 `[1]` 编号、越界丢弃 → 防幻觉的现成机制。
- `SnapshotGenerator`：聚合快照（`as_of` 强制），把「名单/组成」类隐私数据以聚合口径暴露。

**前端 `pams-web`**

- 灵思页面全套：`pages/agent/AgentHome` + `components/agent/*`（19 个 `Lingsi*` 组件）+ `stores/agent.ts`（含过程时间轴 `mergeTimeline`）+ `api/agent.ts`。
- 知识库页面：`pages/knowledge/KnowledgeBase`（台账 / 检索记录 / 快照三 Tab）。
- 设计语言：`styles/lingsi.css`（灰阶极简 + 文档流 + 产物分屏 + 单侧控制点）。

### 1.2 缺口清单（改造对象）

| # | 缺口 | 证据 | 影响 |
|---|---|---|---|
| G1 | **无多 Agent**：仅一个 Agent，无委派 / 并行 / 评审 / 协商 | `AgentOrchestrator` 是单条工具循环 | 命题核心 |
| G2 | **Agent 定义硬编码**：角色提示词、工具子集写在代码里，无注册表 | `KbPrompts`、编排器内联 | 无法动态装配 Agent |
| G3 | **无协作记忆**：只有会话消息（`agent_message`），无共享工作记忆（黑板） | — | 协作无法沉淀中间产物 |
| G4 | **无可观测的协作过程**：timeline 只表达「这个 Agent 在做什么」 | `stores/agent.ts` 的 timeline | 无法展示协作拓扑 |
| G5 | **领域强绑定党建**：菜单、权限码、知识源、工具、品牌色全部党建 | 侧栏「党务台账」；`#DE2910` 硬编码；`party` 模块 | 通用性问题 |
| G6 | **无组织档案 / 领域包概念**：一套部署只服务一种组织 | — | 无法「一个系统适配多类学生组织」 |
| G7 | **无组织注册与架构自定义**：组织架构（部门）由 `DataSeeder` 固定种子，用户不能自建 | `config/DataSeeder`、`sys_department` | 新组织无法自助接入；架构不可配置 |
| G8 | **无多组织隔离**：所有表隐含单一组织，无 `org_id` | `sys_user`/各业务表 | 无法一部署服务多组织 |

---

## 2. 目标系统定位

### 2.1 从「党务管理系统」到「学生组织智能工作台」

| 维度 | 现在（PAMS） | 目标（通用工作台） |
|---|---|---|
| 服务对象 | 学院党建办公室 | 高校各类学生组织（党建办 / 学生会 / 社团 / 青协 / 科协…） |
| 核心域 | 党务台账 + 活动管理 | **组织 / 成员 / 活动 / 任务 / 知识 / Agent** 通用域 |
| 领域实现 | 硬编码在核心 | **可插拔领域包（Domain Pack）**，党建是其中之一 |
| 智能形态 | 单个助手（灵思） | **多 Agent 协作团队**（规划 / 检索 / 策划 / 文书 / 排班 / 审查） |
| 品牌 | 党建红 `#DE2910` 固化 | 组织档案驱动的**主题令牌** |

### 2.2 领域抽象模型（通用化的地基）

把现有业务拆成「**通用核心** + **领域包**」两层：

```
┌──────────────────── 通用核心（与领域无关，长期稳定） ────────────────────┐
│  组织 Organization · 部门 Department · 成员 Member · 角色/权限 Role       │
│  活动 Activity · 任务 Task · 排班 Schedule · 考勤 Attendance             │
│  知识库 Knowledge · 通知 Notification · 文件/材料 File · 审计 Audit       │
│  Agent 编排 · 工具池 ToolPool · 黑板 Blackboard · 确认门禁 TaskGate       │
└───────────────────────────────────────────────────────────────────────┘
                                  ▲ 装配（按启用的领域包）
┌──────────────────── 领域包 Domain Pack（可插拔） ─────────────────────┐
│  party  党建包：党务台账（入党五阶段/函调/名册/转接）、党建术语、党建知识源 │
│  volunteer 志愿包：志愿服务时长、服务记录、志愿专项知识源                 │
│  club   社团包：社团建档、社员、社团活动专项                              │
│  …                                                                     │
│  每个包提供：菜单项 · 权限码 · 知识源 · 专家 Agent 定义 · 工具标签 · 术语表 │
└───────────────────────────────────────────────────────────────────────┘
```

**关键洞察：现有代码里已经埋好了三个「领域包扩展点」**，通用化主要是把它们「显式化 + 装配化」，而不是重构：

| 扩展点 | 现有实现 | 通用化动作 |
|---|---|---|
| 菜单 | 侧栏已按权限码/角色动态生成 | 增加「按启用的领域包过滤一级菜单」 |
| 权限 | 权限码已带模块前缀（`party:view` / `activity:edit`） | 权限管理页按启用包过滤展示 |
| 知识源 | `KnowledgeSource` SPI 已有 6 实现 | 按启用的包装配 source 集合 |
| 工具 | `AgentTool.requiredPermissions()` 已有 | 按包给工具打 tag，Agent 只装配本包工具 |

### 2.3 组织注册与架构自定义（通用化的入口）

> **补充需求**：改为通用领域后，需增加「**注册组织**」功能，注册时能**填写本组织的架构**（部门 / 中心 / 小组的层级关系）。
>
> 这一步是「一套系统适配多类学生组织」的落地起点，也直接决定系统的**多组织隔离策略**。

#### 2.3.1 两种实现模式（需拍板，见 §6 D6）

| 模式 | 含义 | 改造面 | 适用 |
|---|---|---|---|
| **M1 单组织初始化** | 注册 = 首次部署时初始化本实例的组织与架构；一个部署服务一个组织 | 小（不涉及数据隔离） | 若只需演示「架构可配置」，成本最低 |
| **M2 逻辑多租户**（推荐） | 一个部署服务多个组织，数据按 `org_id` 隔离，各组织自助注册 | 大（业务表加 `org_id` + 查询自动过滤） | **直接对应「高校学生组织」复数语义，通用性最完整** |

> **建议 M2，但按「先建字段、后开隔离」渐进落地**：注册与架构功能一期做且完整可演示；`org_id` 先落到 `sys_user` 及各业务表并写入，Hibernate 多租户过滤器二期开启——避免一次性改动几十张表的查询。

#### 2.3.2 注册向导（四步）

```
① 组织基本信息   名称 / 类型（党建办·学生会·社团·青协·科协·其他）/ 简称 / Logo / 主题色
        ↓
② 组织架构       树形编辑器：逐级添加「部门 / 中心 / 小组 / 分队」，设置负责人与层级
        ↓
③ 角色与权限     按架构节点类型自动生成角色 + 默认权限集，可勾选微调
        ↓
④ 启用领域包     勾选 party / volunteer / club…，并邀请成员（邀请码 / 批量导入）
```

#### 2.3.3 组织架构模型（树）

| 表 | 关键字段 | 说明 |
|---|---|---|
| `organization` | `id, name, type, short_name, logo, theme_json, status, owner_user_id, created_at` | 组织（租户）；注册的产物 |
| `org_unit` | `id, org_id, parent_id, name, unit_type(部门/中心/小组/分队), code, path, level, sort_order, leader_user_id` | **组织架构节点（树）**；逐级自引用 |
| `org_position` | `id, org_id, name, level, role_code` | 职务（主席/部长/干事…）→ 映射系统角色（可选） |
| `org_invitation` | `id, org_id, code, role_code, org_unit_id, expire_at, used_by` | 邀请码 / 邀请入组 |

**与现有 `sys_department` 的关系**：`sys_department` 应**演进为 `org_unit` 的一个特例**（`unit_type=部门`），而不是并存两套。现有按部门的数据权限（`data_scope=DEPT`）改为按 `org_unit` 子树判定。

#### 2.3.4 架构 → 角色/权限的自动生成

沿用现有 `PermissionService.saveRolePermissions()`（全量覆盖 + `restoreDefault()`），按节点类型套默认模板：

| 架构节点类型 | 默认角色 | 默认权限模板 |
|---|---|---|
| 主席团 / 主任 | `DIRECTOR`（ADMIN） | 全组织管理 |
| 部门 / 中心 | `*_LEADER`（LEADER） | 本部门业务读写 |
| 小组 / 分队 | `STAFF`（干事） | 只读 + 本人事务 |

生成后落入现有权限管理页，可继续微调——**复用而非另起一套权限体系**。

#### 2.3.5 多租户隔离（M2 时，对齐现有安全基线）

| 机制 | 做法 |
|---|---|
| 身份 | `sys_user` 增加 `org_id`；`LoginUser` 增加 `orgId` |
| 令牌 | JWT 增加 `org` claim；`JwtAuthenticationFilter` 解析并注入 |
| 数据 | Hibernate `@Filter` / `TenantContext` **自动注入 `org_id` 谓词**，避免每处手写 `WHERE org_id=?` |
| 权限 | 工具可见性仍以「发起用户身份」计算（§3.5），跨组织数据天然不可见 |
| 测试 | 跨组织越权纳入现有「越权矩阵」回归 |

#### 2.3.6 与 Agent / 领域包的关系

- **Agent 的工具池、知识源、领域包、菜单均按 `org` 装配**：注册后系统自动为新组织建立默认知识源与菜单。
- **组织架构直接影响 Agent 的「我的待办」与数据范围**（`data_scope` 由 `org_unit` 子树推导）。
- **多 Agent 协作**：`CoordinatorAgent` 携带 `org` 上下文；跨组织的知识与数据不得进入同一黑板。

#### 2.3.7 前端新增页面

- `pages/org/OrgRegister.tsx`：注册向导（四步 antd `Steps`）。
- `pages/org/OrgStructure.tsx`：**架构编辑器**（antd `Tree` 拖拽 + 节点属性面板：类型 / 负责人 / 角色）。
- 复用现有权限管理页作为「角色权限微调」入口。

---

## 3. 多 Agent 协作架构（核心创新点）

### 3.1 分层架构

```
┌─────────────────────────── 接入层（复用现有） ───────────────────────────┐
│  AgentController · WebSocket /user/queue/agent/stream · TaskGate 确认门禁 │
└──────────────────────────────────┬───────────────────────────────────────┘
┌──────────────────────────────────▼───────────────────────────────────────┐
│                        协调层 Coordinator                                 │
│  CoordinatorAgent：意图理解 → 任务分解 → 选择专家 → 汇总 → 冲突裁决       │
│  AgentRegistry（能力注册/发现）   Blackboard（共享工作记忆）              │
│  MessageBus（Agent 间消息，内存事件，单实例与现状一致）                    │
└──────────────────────────────────┬───────────────────────────────────────┘
┌──────────────────────────────────▼───────────────────────────────────────┐
│                          专家 Agent 层                                    │
│  RetrievalAgent 检索 · PlanningAgent 策划 · WritingAgent 文书             │
│  SchedulingAgent 排班 · DataAgent 事务 · ReviewerAgent 审查(critic)       │
│  领域包专家：PartyAgent 党务（随 party 包装配，可选）                       │
└──────────────────────────────────┬───────────────────────────────────────┘
┌──────────────────────────────────▼───────────────────────────────────────┐
│                     能力层（复用现有 ToolRegistry）                        │
│  21 个现有工具（读 11 / 写 10） · 现有 Service · PolicyGuard · 知识库检索   │
└──────────────────────────────────┬───────────────────────────────────────┘
┌──────────────────────────────────▼───────────────────────────────────────┐
│                          记忆层                                          │
│  短期：Blackboard（会话内）  长期：agent_message + 知识库  审计：agent_audit_log │
└──────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Agent 清单（含现有 21 个工具的分配）

> 原则沿用现有文档：**聚合优先，工具越多 LLM 选错率越高**。专家 Agent 数量控制在 5–7 个。

| Agent | 职责 | 装配的工具（现有） | 私有提示词重点 |
|---|---|---|---|
| `CoordinatorAgent` | 意图识别、任务分解、调度、汇总、裁决 | 无（只调度，不直调业务工具） | 分解为可并行的子任务；何时委派、何时收口 |
| `RetrievalAgent` | 查数据 + 查知识 | `queryActivityOverview` `queryActivityTasks` `searchMaterials` `searchArticles` `queryNotifications` `queryCommonFreeTime` `queryAttendance` `queryCreditRecords` `queryRosterSigninStats` + 知识库 `KbPipeline` | 只给结论 + 依据 + 引用，不编造 |
| `PlanningAgent` | 活动策划 | `createActivity` `draftPlanSections` `generateAgendaFromPlan` `checkPlanCompleteness`(待实现) | 只落 DRAFT，不提交审核 |
| `WritingAgent` | 文书写作 | `draftArticleFromActivity` `draftNewsFromActivity` `draftAnnouncement` `publishAnnouncement` | 素材白名单，正文过净化 |
| `SchedulingAgent` | 排班与任务分解 | `generateActivitySchedule` `generateActivityTasks` `createSeatLayout` | 冲突检测，复用规则服务 |
| `DataAgent` | 批量事务 | `previewCreditBatch`(双模) `createMember` `sendNotification` | 必须出 skipped 明细 |
| `ReviewerAgent` | **新增**：质量/事实/合规审查 | 无（只读 Blackboard 与产物） | 完整性、事实核对、合规、越权检查，输出 PASS/REVISE + 理由 |
| `PartyAgent`（领域包） | 党务专有问答 | `queryPartyMemberProgress` + 党务知识源 | 复刻脱敏口径，`restricted` 不出网 |

### 3.3 协作模式（论文的方法论主体，给出形式化定义）

定义一次协作 `Run = <Goal, Plan, Blackboard, Steps[]>`，其中 `Step = <Agent, Input, Output, Status>`。

| 模式 | 结构 | 适用 | 对应现有能力 |
|---|---|---|---|
| **P1 串行流水线** | `Retrieval → Planning → Reviewer → 确认` | 单线文书类任务 | 现有编排循环串接 |
| **P2 并行汇聚（map-reduce）** | 协调者广播 → `[Retrieval ‖ PartyAgent]` → 汇总 | 需要多源证据的问答 | 线程池并行 + Blackboard 归并 |
| **P3 反思循环（reflection）** | `Writing ⇄ Reviewer`（最多 N 轮，收敛即止） | 高质量文书 | 新增 critique 回路 |
| **P4 委派-汇报（delegation）** | 专家 Agent 可再调自身工具子集 | 复杂子任务 | 复用现有 function-calling |

**冲突裁决规则**（写进论文，避免「多 Agent 只会更乱」的质疑）：
1. **证据优先**：带 `references[]` 的结论 > 无依据陈述；
2. **置信度投票**：并行结论冲突时按 `confidence` 加权；
3. **审查否决**：`ReviewerAgent` 的 `REVISE` 可回退上游重做；
4. **人工兜底**：任何写操作最终必过 `TaskGate` 人工确认（复用现有铁律，**这是多 Agent 安全性的最后一道闸**）。

### 3.4 黑板书（Blackboard）——协作的单一事实来源

```json
{
  "goal": "为迎新晚会生成报道推文",
  "plan": [{"id": 1, "agent": "RetrievalAgent", "task": "收集活动素材"},
           {"id": 2, "agent": "WritingAgent",  "task": "撰写推文草稿"},
           {"id": 3, "agent": "ReviewerAgent", "task": "事实与合规审查"}],
  "findings": [{"agent": "RetrievalAgent", "summary": "...", "refs": [{"type":"ACTIVITY","id":88}], "confidence": 0.9}],
  "artifacts": [{"agent": "WritingAgent", "type": "ARTICLE_DRAFT", "contentRef": "..."}],
  "openQuestions": [],
  "decisions": [{"by": "ReviewerAgent", "verdict": "REVISE", "reason": "时间与活动详情不符"}]
}
```

- 每个 Agent **读黑板 + 写黑板**；黑板可整体序列化落库 → **支持协作过程回放**（论文演示与答辩利器）。
- 对应新增表 `agent_run.blackboard_json`。

### 3.5 与现有安全机制的衔接（论文强论证点）

**核心命题：「多 Agent 协作不放大权限」。** 这是最容易被质疑、也最有论文价值的一点。

| 现有机制 | 多 Agent 下的处理 | 论证 |
|---|---|---|
| 工具可见性 `ToolRegistry.visibleTo(auth)` | **仍以发起用户身份计算**，专家的工具子集是「用户权限 ∩ Agent 职责」 | Agent 再多也不越权 |
| 派发前断言 `dispatch()` | 每次委派前**重新断言**（角色可能已变） | 委派不绕过 403 |
| `PolicyGuard` 出网闸口 | 所有 Agent 的上下文**统一过闸**；`restricted` 永不出网 | 唯一的出网收口 |
| `TaskGate` 确认门禁 | 只在**最终写操作**触发；Agent 间协作不写库 | 「想得多」≠「做得多」 |
| 审计 `agent_audit_log` | 增加 `run_id / step_id / agent_name / parent` | 全链路可追溯 |
| 会话隔离 | `agent_conversation.user_id` 强隔离，`agent_run` 同口径 | 协作不改隔离边界 |

> 论文可直接写成：「本文提出的多 Agent 协作架构将权限判定收敛到**工具派发点**而非 Agent 层，使得协作图规模的扩张不改变系统的授权边界。」

### 3.6 数据模型（新增 7 张表 + 3 处扩展）

Flyway 新迁移（沿用 `V30+`，`CREATE TABLE IF NOT EXISTS` + utf8mb4 约定）：

**多 Agent 协作（3 张）**

| 表 / 变更 | 关键字段 | 说明 |
|---|---|---|
| **`agent_definition`**（新） | `id, name, display_name, role_prompt, tool_scope(JSON), model, temperature, enabled, sort_order` | Agent 注册表，替代硬编码 |
| **`agent_run`**（新） | `id, conversation_id, user_id, org_id, goal, mode(P1/P2/P3/P4), status, blackboard_json, started_at, finished_at` | 一次协作 |
| **`agent_step`**（新） | `id, run_id, agent_name, seq, parent_step_id, input_json, output_json, status, duration_ms, token_in, token_out` | 协作步骤（可画拓扑） |
| `agent_message` 扩展 | `+ agent_name` | 消息归属哪个 Agent |
| `agent_task` 扩展 | `+ run_id, + agent_name` | 确认门禁关联协作链路 |

**通用化 / 组织注册（4 张，见 §2.3）**

| 表 / 变更 | 关键字段 | 说明 |
|---|---|---|
| **`organization`**（新） | `id, name, type, short_name, logo, theme_json, enabled_packs(JSON), terminology_json, status, owner_user_id, created_at` | 组织（租户）；驱动品牌、领域包、术语 |
| **`org_unit`**（新） | `id, org_id, parent_id, name, unit_type, code, path, level, sort_order, leader_user_id` | **组织架构树**；替代 / 吸收 `sys_department` |
| **`org_position`**（新） | `id, org_id, name, level, role_code` | 职务 → 角色映射 |
| **`org_invitation`**（新） | `id, org_id, code, role_code, org_unit_id, expire_at, used_by` | 邀请入组 |
| `sys_user` / `sys_role` 扩展 | `sys_user + org_id`；`sys_role + org_id`（内置角色 `org_id` 为 NULL 表示全局） | 多租户归属（M2 时） |

### 3.7 事件契约扩展（前端协作视图的数据源）

在现有 `AgentStreamEvent` 上新增类型（**对旧前端向后兼容**——`mergeTimeline` 未知类型默认忽略，见现有实现）：

| 新事件 | payload | 前端表现 |
|---|---|---|
| `AGENT_START` | `{agent, stepId, parentStepId}` | 协作拓扑上点亮一个节点 |
| `AGENT_END` | `{agent, stepId, status, durationMs}` | 节点完成态 |
| `AGENT_DELEGATE` | `{from, to, reason}` | 画委派边 |
| `REVIEW_RESULT` | `{verdict, reason, round}` | 审查卡（PASS/REVISE） |

### 3.8 前端改造（复用灵思全套，新增「协作视图」）

- **复用**：`AgentHome` 三栏骨架、`LingsiConversationList`、`LingsiMessageBody`、`LingsiProcessZone`（时间轴）、`LingsiActionCard`（确认卡）、`LingsiFormCard`、`LingsiArtifactCard`。
- **新增**：`LingsiAgentTeam`（协作拓扑/团队面板，展示各专家状态与委派关系）、`LingsiReviewCard`（审查结论卡）。
- **改造**：`stores/agent.ts` 的 `mergeTimeline` 增加新事件分支；消息气泡显示 `agent_name` 身份标识与头像。
- 时序上「哪个 Agent 在干活」由 `agent_step` 驱动，刷新后可完整回放（复用现有「历史时间轴由 toolCalls 派生」的同款思路）。

---

## 4. 实施路线（分批，去依赖顺序）

| 批次 | 主题 | 交付物 | 出口条件 |
|---|---|---|---|
| **A1 组织与架构** | 注册 + 架构自定义 | `organization`/`org_unit`/`org_position`/`org_invitation` 迁移、注册向导（四步）、架构编辑器（树）、架构→角色/权限自动生成、`sys_department` 吸收进 `org_unit` | 新组织可自助注册并画出自己的架构；党建组织按其原有架构重建后行为等价 |
| **A2 领域解耦** | 领域包装配 | 术语表 + 主题令牌化 + 菜单/权限/知识源/工具按包装配；党建降级为 `party` 包；新增示例包（志愿/社团） | 切换组织/领域包，菜单/主题/术语随之变化；党建功能不回归 |
| **A3 多租户隔离**（可选） | 数据隔离 | 业务表补 `org_id` + 写入；Hibernate `@Filter` 开启自动过滤；跨组织越权回归 | 两个组织数据互不可见 |
| **B 多 Agent 基座** | 协调骨架 | `agent_definition/run/step` 迁移、`AgentRegistry`、`Blackboard`、`CoordinatorAgent`；现有 `AgentOrchestrator` 降级为专家执行内核并保留为单 Agent 兜底 | 单专家链路仍可用；协作 Run 可落库 |
| **C 专家落地** | 6 个专家 | Retrieval/Planning/Writing/Scheduling/Data/Reviewer（+领域包 PartyAgent）；工具按 tag 装配 | 每个专家可独立完成其职责 |
| **D 协作模式** | P1–P4 + 前端 | 流水线/并行/反思 + `AGENT_*` 事件 + `LingsiAgentTeam` 协作视图 | 协作拓扑可视化，过程可回放 |
| **E 评估实验** | 对比实验 | 单 Agent vs 多 Agent 在「任务完成率 / 平均轮次 / 耗时 / 事实一致性」上的对比脚本与数据 | 论文实验章节数据齐备 |
| **F 论文** | 成文 | 章节与图表 | 见 §5 |

**穿透演示故事线（答辩用，复用现有编排能力）**：
> 用户在通用首页提出「为迎新晚会准备报道」→ Coordinator 分解为 [收集素材 ‖ 查历史推文风格] → Planning 出策划要点 → Writing 生成推文草稿 → Reviewer 指出「时间与活动详情不符」→ Writing 修订 → Reviewer PASS → 出确认卡 → 人工确认落库 DRAFT。全程协作拓扑实时可见。

---

## 5. 论文映射（把工程直接转成章节）

| 章节 | 内容 | 由本项目哪个部分支撑 |
|---|---|---|
| 第 1 章 绪论 | 高校学生组织数字化痛点；Agent / 多 Agent 研究现状；本文工作 | 现状 §1 |
| 第 2 章 相关技术 | LLM function calling；多 Agent 协作范式（黑板模型 / 合同网 / 反思）；RAG；Spring Boot / React | 现有技术栈 |
| 第 3 章 需求分析与总体设计 | 通用工作台需求；组织注册与架构自定义；领域包抽象；多 Agent 协作需求 | §2（2.3 组织注册） |
| 第 4 章 关键设计 | 组织架构模型与多租户隔离、多 Agent 协作架构、协作模式与冲突裁决、协作不扩权的权限机制、确认门禁、RAG 与引用回填 | §2.3 + §3.1–3.5 |
| 第 5 章 系统实现 | 注册向导与架构编辑器、各 Agent 实现、工具装配、黑板、事件流、前端协作视图 | §2.3.7 + §3.6–3.8 |
| 第 6 章 测试与评估 | 功能测试、越权矩阵、协作对比实验、性能指标 | 批次 E + 现有 `mvn test` / E2E |
| 第 7 章 总结与展望 | 贡献总结；向量检索、跨组织联邦等展望 | — |

**创新点（建议提炼 4 条）**：
1. **面向高校学生组织的「组织自注册 + 架构自定义 + 可插拔领域包 + 多 Agent 协作」工作台架构**（通用性与智能性结合，一个部署适配多类组织）；
2. **协作不扩权的多 Agent 权限继承机制**（子 Agent 以发起人身份鉴权 + 工具可见性收敛，协作图扩张不改授权边界）；
3. **人机协同的确认门禁范式**（dry-run + 一次性令牌），作为 Agent 写操作的通用安全约束；
4. **协作过程可观测**（黑板 + 事件流 + run/step 拓扑 + 全链路审计），解决多 Agent「黑箱」质疑。

---

## 6. 关键决策点（需拍板）

| # | 决策 | 选项 | 建议 |
|---|---|---|---|
| D1 | 通用化的激进程度 | A 抽象领域包并重构底座 / B 保留党建为默认领域、抽象到「可配置装配」/ C 仅改文案与品牌 | **B**：性价比最高，既满足「通用」论证，又不摧毁现有业务 |
| D2 | 多 Agent 实现路径 | A 自研轻量协调器 + 黑板 / B 引入 LangChain4j / Spring AI 的多 Agent / C 引入 AutoGen 风格外置框架 | **A**：与现有栈一致、可控、可写论文；B/C 会引入不可控的外部抽象、且与现有权限/门禁机制冲突 |
| D3 | 专家 Agent 粒度 | 精细（10+）/ 中等（5–7）/ 动态生成 | **中等**：沿用现有「工具数≤25」的聚合原则 |
| D4 | 是否保留党建为默认演示领域 | 保留 / 移除 | **保留**：现有党务数据是最完整、最有说服力的演示样本，且已实现脱敏等难点 |
| D5 | 是否实现向量检索 | 做 / 不做 | **缓做**：现有 ngram 关键词检索 + 引用回填已足够支撑答辩；向量检索列为「展望」 |
| D6 | 组织注册的隔离模式 | M1 单组织初始化 / M2 逻辑多租户 | **M2 逻辑多租户**，但按「先建 `org_id` 字段、后开 Hibernate 过滤器」渐进落地（见 §2.3.1）——直接对应「高校学生组织」复数语义，通用性最强 |

---

## 7. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 多 Agent 只是「多点几次 LLM」，无实际增益 | 论文核心被质疑 | 用批次 E 的**对比实验**量化增益；确保 Reviewer 能真实纠错（反思回路有效） |
| 协作导致延迟/成本上升 | 体感变差 | 沿用现有 TTFE / 总时长双指标；并行模式用线程池；简单任务直接走单 Agent 兜底 |
| Agent 间消息放大敏感数据暴露面 | 合规风险 | 黑板内容同样过 `PolicyGuard`；`restricted` 不进黑板 |
| 通用化改造打散现有业务 | 党建功能回归 | 批次 A 每步配回归测试；党建降级为包但**行为等价** |
| 协作链路难排障 | 维护成本高 | `agent_run/step` + 审计表全链路留痕，复用现有 `agent_tool_call` 排障范式 |

---

## 8. 本策划对现有代码的改动面（预估）

| 层 | 新增 | 改造 | 不动（复用） |
|---|---|---|---|
| 数据库 | `agent_definition` `agent_run` `agent_step` `organization` `org_unit` `org_position` `org_invitation`（7 表） | `agent_message`/`agent_task`/`sys_user`/`sys_role` 加列、`sys_department` 吸收进 `org_unit` | 现有 5 张 agent 表 + kb_* 表 + 业务表（M2 二期才加 `org_id`） |
| 后端 | `orchestration/`（Coordinator/Registry/Blackboard/MessageBus）、6 个专家 Agent、`org/` 注册与架构装配、多租户过滤器 | `AgentOrchestrator` 降级、`AgentStreamEvent` 扩展、`JwtAuthenticationFilter` 注入 `orgId`、`agency` 包微调 | 工具 21 个、`TaskGate`、`PolicyGuard`、`AuditService`、`PermissionService`、知识库全套、安全/权限 |
| 前端 | `OrgRegister`（注册向导）、`OrgStructure`（架构编辑器）、`LingsiAgentTeam`、`LingsiReviewCard`、协作拓扑 | `stores/agent.ts`、`AgentHome`、主题令牌、菜单装配、登录页（组织选择） | 19 个 `Lingsi*` 组件、知识库页、权限管理页、路由/权限守卫 |

---

## 9. 附录：D6「组织隔离模式」详析

> 本节是对 §6 D6 的展开。结论先行：**建议 M2a（共享库 + 行级 `org_id`），并采用「先建列、后开过滤」的三段式落地**。下面是依据。

### 9.1 问题的三层解耦

D6 表面是「一个部署服务几个组织」，实际牵动三个**可独立决策**的问题：

| 层 | 问题 | M1（单组织） | M2（多租户） |
|---|---|---|---|
| ① 注册语义 | 「注册组织」是什么 | 首次部署的**初始化向导** | 真正的**开租户**（组织持续增加） |
| ② 数据隔离 | 如何保证 A 看不到 B | 不需要 | 必须（且要可测试） |
| ③ 字典归属 | 架构/角色/权限/领域包归谁 | 全局唯一 | **内置（全局）+ 组织自定义** 双层 |

**关键：②③ 才是成本所在，① 两者都便宜。** 所以「先做注册与架构（①②的①）、隔离后补」是可行的解耦。

### 9.2 候选方案对比（M2 并非只有一种）

| 方案 | 隔离粒度 | 改造面 | 运维成本 | 适用 |
|---|---|---|---|---|
| **M1 单组织实例** | 部署级 | 最小 | 最小 | 只演示「架构可配置」 |
| **M2a 行级 discriminator**（推荐） | 行 | 中（加 `org_id` + 自动过滤） | 最小（仍单库单实例） | **高校多组织、数据量小、单机部署** |
| M2b schema-per-tenant | schema | 大（每租户一套 DDL + 连接切换） | 中（DDL 迁移需逐租户） | 中等规模租户、需强隔离 |
| M2c database-per-tenant | 库 | 最大（连接池 × 租户） | 大（备份/迁移 × 租户） | 强合规、租户少而大 |
| M2' 弱隔离（仅逻辑 WHERE） | 无硬保证 | 小 | 小 | ❌ 不安全，不建议 |

**本项目约束**：单机 `docker-compose`、单库 `pams_db`、数据量小（~1660 材料 + 演示数据）。**M2b/M2c 的运维复杂度与「轻量部署」目标冲突**（与现有 D6 文档「不引入新运维组件」的一贯取舍一致），因此定位在 **M2a**。

### 9.3 本代码库的实测改造面（M2a）

**规模盘点（2026-10 实测）**：

| 指标 | 数量 | 说明 |
|---|---|---|
| `@Entity` 实体 | **53**（含 `BaseEntity`） | 其中约 **48 张为租户数据表**需加 `org_id` |
| Repository | **53** | Spring Data 派生查询 → 底层是 JPQL，**可被自动过滤覆盖** |
| 自定义 `@Query` | **27 处 / 15 文件** | 绝大多数为 JPQL → **同样可被自动过滤覆盖** |
| `nativeQuery=true` | **1 处** | `UserRepository`（复活软删账号）→ **需手工加谓词** |
| `JdbcTemplate` 裸 SQL | **2 文件** | 均在知识库（`ChunkSearchRepository`、`KnowledgeIndexInitializer`）→ **需手工加谓词** |

> **这是最关键的结论**：M2a 的最大风险是「绕过 ORM 的裸 SQL」。而这个库恰好只有 **3 处**，且集中在已做「权限下推 SQL」的知识库——**补 3 处谓词即可，不是几十处**。

**租户表 vs 全局表的划分**（例）：

| 类别 | 表 | 处理 |
|---|---|---|
| 租户数据（加 `org_id`） | activity / plan / agenda / task / signin / schedule / attendance / member / material / article / news / announcement / credit / party_* / group_chat / notification / kb_* / agent_* / file_record / seat_layout | 约 48 张 |
| 全局字典（不加） | `permission`（全局权限目录，`code` 全局唯一） | 保持全局 |
| 双层（内置全局 + 组织自定义） | `role`、`role_permission`、`template_asset`（内置模板 `org_id=null`） | 内置 `org_id=null`，自定义带 `org_id` |
| 需重构 | `sys_department` → 吸收进 `org_unit` | 见 §2.3.3 |

**唯一约束需重新界定**（否则跨组织冲突）：

| 现有约束 | 问题 | 改法 |
|---|---|---|
| `uk_member_session_name (name, deleted)`、`MemberSession.name unique` | 两个组织都有「第九届」 → 冲突 | 改 `(org_id, name, deleted)` |
| `uk_member_session_student (session_id, student_no, deleted)` | `session_id` 已组织域内 | 无需改 |
| `uk_no_class_dept_semester (dept_id, semester)` | `dept_id` 已组织域内 | 无需改 |
| `permission.code` unique | 全局目录 | 保持全局 |
| `sys_user.username` | 跨组织可否同名？ | 需拍板：全局唯一（简单）或 `(org_id, username)` |
| `Role.code` | 内置 + 自定义同名？ | 内置全局；自定义 `(org_id, code)` |

**鉴权链路**（改动小但关键）：

| 位置 | 现状 | 改法 |
|---|---|---|
| `JwtAuthenticationFilter` | 每请求 `findByUsername` 查库 + 从 `role_permission` 算权限 | 顺带读 `u.getOrgId()` → 放入 `LoginUser.orgId` 并设为租户上下文 |
| `LoginUser` | 7 字段 | 加 `orgId` |
| `JwtUtil` | claims: `sub/uid/role/iat/exp` | **不建议把 `org` 写进 token**（见 9.5） |
| `SecurityConfig` | — | 无需改（隔离在 ORM 层） |

### 9.4 关键技术机制与陷阱

**机制（Hibernate 6/7，本项目随 Spring Boot 4 引入 Hibernate 7）**：

| 机制 | 说明 | 适用 |
|---|---|---|
| `@TenantId` | Hibernate 6+ 的**一等公民**，标注租户列后自动过滤实体查询；由 `CurrentTenantIdentifierResolver` 解析当前租户 | **推荐**：声明式、改动最小 |
| `@FilterDef` / `@Filter` | 动态参数化过滤（可表达 `org_id = :x` 或 `org_id in (...)`） | 需复合条件（如「本组织 + 下级组织」）时用 |
| `MultiTenancyStrategy.SCHEMA/DATABASE` | 内置多租户策略 + `MultiTenantConnectionProvider` | M2b/M2c 才用 |

**这个库有一个天然的利好**：全库软删已用 `@SQLRestriction("deleted = 0")`——**团队已经熟悉「声明式过滤」这套心智与踩坑经验**（DataSeeder 注释里专门记录了「`count()` 漏软删行」的坑）。租户过滤是同一类机制。

**必须注意的陷阱**：

1. **裸 SQL 不自动过滤**：`nativeQuery` 与 `JdbcTemplate` 必须**手工加 `org_id` 谓词**（本库仅 3 处）。这是 M2a 唯一真正的技术风险点。
2. **`@TenantId` 与 `@Filter` 不可混用于同一实体**——选一种。
3. **租户上下文必须在事务内设置**（Filter 在事务开始时绑定会话），留意异步线程（`agentExecutor` 已用 `DelegatingSecurityContextExecutor`，需同样传播租户）。
4. **`@SQLRestriction` 与租户过滤叠加**：注意两者都改变 `count()` 语义（本库已有前车之鉴）。
5. **批量/统计查询**：仪表盘聚合、`KnowledgeIndexer` 整源重建等**跨表批量操作**需确认过滤是否生效。
6. **启动期建索引**（`KnowledgeIndexInitializer` 的 ngram DDL）不受租户影响，但**检索**受影响。

### 9.5 与鉴权、Agent 的衔接（一个重要的安全修正）

**❌ 不要把 `org` 写进 JWT 并信任它。** 理由与本项目既有安全哲学一致：

- 现有安全设计信奉「**每次请求回源查库**」——`JwtAuthenticationFilter` 每请求都 `findByUsername` 并重新计算权限，正因为不信任 token 里的快照（角色可能已变）。
- 同理，**租户应取 `u.getOrgId()` 而非 claims**：token 泄露或被跨组织重放时，org 无法被伪造/搬运。
- 代价：零（本来就要查这一行）。

**对 Agent / 多 Agent 的影响**：

- 工具**以发起用户身份**执行 → 自动继承其 org 上下文；底层自动过滤生效，工具代码**几乎不用改**（与 §3.5「协作不扩权」同源——租户隔离也收敛在数据访问点，不在 Agent 层）。
- **黑板的组装**已隐含组织边界：跨组织的检索结果与知识不得进入同一 `agent_run`。
- 知识库的裸 SQL 检索需补 org 谓词——**与它已有的「权限下推 SQL」放在同一处**，是自然的扩展点。

### 9.6 推荐路径：M2a + 三段式（降低一次性风险）

```
阶段 1（批次 A1）  建 org 表 + 注册向导 + 架构编辑器；所有实体加 org_id 列，
                   写入时填「当前组织」（默认组织）——【只写，不过滤】
                          ↓  出口：功能可完整演示；数据已带 org 归属
阶段 2（批次 A3）  开启自动过滤（@TenantId/@Filter）+ 3 处裸 SQL 手工补谓词
                   + 唯一键重构 + 跨组织越权回归
                          ↓  出口：两组织数据互不可见
阶段 3（可选）     若将来需要更强隔离，再评估 schema-per-tenant（M2b）
```

**这个三段式的价值**：把「功能可演示」（阶段 1）与「隔离正确性」（阶段 2）解耦。阶段 1 结束后，即使隔离没开，系统也完全可演示、可答辩；阶段 2 是纯粹的加固。

### 9.7 反悔成本（为什么「先建列」几乎无成本）

| 若最终选 | 想改成另一种 | 成本 |
|---|---|---|
| M1 | M2a | 需**回填历史行 `org_id`**（数据迁移）+ 补阶段 2 全部 → 高 |
| **M2a（阶段 1 已建列但未开过滤）** | M1 | 仅停用过滤器、忽略 `org_id` 列 → **近零** |

> **结论：无论你最终选 M1 还是 M2，都应在阶段 1 先把 `org_id` 列建上并写入。** 这是「让 D6 决策可以延后且不可逆风险最低」的关键技巧——列先建、默认填，事后无论往哪边走都便宜。

### 9.8 论文可写的点（D6 的学术价值）

1. **隔离模型选型论证**：discriminator vs schema vs database 在「高校组织规模/数据量/轻量部署」约束下的权衡（§9.2）。
2. **声明式租户隔离**：`@TenantId` 自动过滤 + 裸 SQL 盲区的手工收口——一个「ORM 自动化的边界」案例。
3. **组织架构 × 数据权限的泛化**：`data_scope` 从「固定部门」泛化为「`org_unit` 子树」，映射到论文的「访问控制模型」。
4. **越权测试矩阵的租户维度**：在现有「角色 × 接口」矩阵上增加「组织」维度（A 组织账号访问 B 组织资源必 403/不可见）。

---

> **文档结束**。本策划的「现状」部分已逐项核对至 2026-10 的源码与迁移链（Flyway V1–V29）。评审重点建议放在第 6 节的 6 项决策上。
