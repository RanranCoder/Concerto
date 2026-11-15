# 协奏Concerto 治理艺术

> 治理不是一堆禁令，而是一套让系统"默认安全"的设计。协奏作为承载真实学生组织数据、又内置 LLM Agent 的系统，治理的核心命题有两个：**人（与组织）的权限边界**，以及**模型的数据边界**。本文沉淀权限模型、数据分级、AI 治理、审计追溯的设计决策与已知风险，以及它们背后的取舍逻辑。

---

## 1. 治理哲学：权限即上下文

传统系统里，权限决定"你能调哪个接口"；在协奏里，权限还决定**模型能看到什么**：

- 用户无权调用的工具，**根本不进 LLM 的 tools 列表**（ToolRegistry.visibleTo）——不是"调了再拒绝"，而是"从源头不给机会"。这消除了提示注入诱导工具调用的攻击面。
- 用户无权看的数据（敏感字段、受限知识），在出网闸口（PolicyGuard）被遮罩或拦截——模型答不了它没见过的东西。
- 知识库检索结果逐条回源复核权限后，才允许作为"依据"进入回答。

一句话：**让越权在模型的世界里物理不可达，而不是规则上不允许。**

---

## 2. RBAC 模型与口径统一

### 2.1 单角色模型

- 角色 = `sys_role` 表行（无 Java 枚举），一个用户只持有一个角色；种子 7 角色：TEACHER、DIRECTOR（ADMIN 层）+ 四位部长（LEADER 层）+ STAFF（干事，dataScope=DEPT）。
- 双口径并存但各司其职：
  - **权限码**（`hasAuthority('activity:edit')`）：细粒度操作控制，`@PreAuthorize` 主用；
  - **角色谓词**（`RoleExprs.LEADER/ADMIN`，`hasAnyRole(...)`）：身份性质控制（如党务页面用 LEADER 角色）。
- 角色常量收敛于 `module/agent/core/ToolConventions`（`STAFF`、`LEADER_ROLES`），前后端各自维护同名集合（前端 `RequireRole.tsx` 导出 `LEADER_ROLES/ADMIN_ROLES`）。

### 2.2 口径统一原则（治理红线）

1. **同一资源只能统一采用权限码或角色谓词之一**，不得混用。历史上：党务页面用 LEADER 角色，而 Agent/知识库**不得**改用 STAFF 也持有的 `party:view`——否则干事会借 AI 绕过党务身份限制。
2. **产品可见性看三口径**：前端 `RequirePerm/RequireRole` 守卫、菜单过滤、后端 `@PreAuthorize`，三者对同一资源必须一致；**不能仅凭 `@PreAuthorize` 有无判断越权**——它通常只在 Controller 层，**Service 是鉴权盲区**。
3. 服务层补救：`TenantAuthorizationService` 提供租户运行时授权刷新（V37 Java 迁移引入），工具派发与授权版本变更联动（前端订阅 `/user/queue/authorization` 收到推送后刷新本地权限）。

---

## 3. 数据分级体系

系统有三套互相咬合的分级，分别治理不同边界：

| 分级 | 所在 | 取值 | 治理边界 |
|---|---|---|---|
| 工具/数据敏感度 | `agent/core/Sensitivity` | NORMAL / SENSITIVE / RESTRICTED | **LLM 出网**：直进 / 字段遮罩 / 拦截；每个工具必声明 |
| 知识源层级 | `KnowledgeSource.Tier` | T1 全文级 / T2 字段级 | **检索打分**：T1 参与 BM25 正文打分；T2 只做字段级、固定权重 |
| 知识源敏感度+可见性 | `KnowledgeSource` 声明 | sensitivity: normal/sensitive；visibility: ALL/LEADER | **索引期粗筛**：LEADER 可见源不对普通用户进索引召回 |

文件域配套权限码（V37）：`file:org:read` / `file:sensitive:read`、`kb:restricted:read`、`party:sensitive:view`；文件服务按 `role.level >= 3` 判定敏感文件访问。

---

## 4. AI 治理：给模型划边界

### 4.1 人机协同（写操作）

- 写类工具 `execute` 只是 **dry-run**：产出影响清单 → `agent_task(PENDING_CONFIRM)` + 一次性 confirmToken → 用户确认（乐观锁防并发抢占）→ `commit` 真写。
- 不可逆操作在确认卡上强制警示；确认卡展示 skipped 项，透明化跳过原因。
- 缺参不猜：走 FORM / WAITING_INPUT 表单收集，收集后回到确认流程——**模型永不替用户做业务决定**。

### 4.2 出网闸口（PolicyGuard.egressText）

- 全系统**唯一**脱敏闸口：sensitive 数据按正则遮罩（手机号/身份证/学号），**遮罩顺序固定 ID_CARD → PHONE → STUDENT_NO**（先长模式后短模式，防部分遮蔽）；restricted 直接拦截。
- 两条纪律：① 必须以返回值替换原文（历史上曾丢弃返回值导致脱敏失效，已修 + 纳入回归）；② 改动脱敏逻辑必跑遮罩顺序回归。
- 写库脱敏复用同一闸口，保证"进模型"与"进数据库"口径一致。

### 4.3 知识库治理（双期权限）

```
索引期：粗筛（visibility/permission）→ 决定"什么能进索引、谁能召回"
检索期：PermissionResolver 逐条回源 canAccess 复核（TopK≤12）→ 决定"这次回答能用什么"
```

- **citations 只能由复核后集合构造**；模型只输出 `[n]` 编号，服务端回填、越界丢弃——编号与出处永远不信任模型。
- **零引用拒答**：复核后无依据就拒绝回答，宁可说"不知道"，不编造。
- 历史引用回看也要**实时复核**：权限被收回的引用标记 revoked（前端只提示不显内容）——权限撤销追溯生效，而非"索引里留个副本就永久可读"。
- 唯一真相在业务表，索引只是可检索视图（整源先删后插重建）；AFTER_COMMIT 领域事件增量索引 + 每日兜底全量。
- **永不进索引**：运行痕迹、通知、聊天、审计、`agent_*` 表。AI 不应该"记得"治理数据本身。
- MemberSource（T2）只读姓名/职务/部门/届别/状态，**不读**学号/手机号/政治面貌/备注；快照只做带 `asOf` 的聚合事实（服务端非空校验 + 前端"数据截至"展示），不含个人明细。

### 4.4 上下文边界

- `ChatRefsResolver`（用户点选的附加上下文）只注入**身份信息**，不能绕过工具与脱敏；references 上限 5 且校验资源可见性、走审计。
- `ToolContext.derive()` 必须透传 `mode`——任何上下文派生都不得让权限模式静默降级。
- 未识别的 mode 一律 fail-fast，绝不降级为可写。

---

## 5. 审计与追溯

- `agent_audit_log`（V24）：userId / conversationId / taskId / toolName / argsDigest / **sideEffect(READ/PREVIEW/WRITE)** / confirmedBy / resultStatus / affectedCount / createdAt / orgId，随租户隔离（OrgScoped）。
- 口径纪律：sideEffect 仅三个枚举值（≤10 字符）。曾写入 `WRITE_PREVIEW` 导致事务 rollback-only、**审计静默丢失**——审计是合规底线，丢失即是事故。
- 事务纪律：确认类编排**刻意不开整体事务**，审计用 `REQUIRES_NEW` 独立提交，防 rollback-only 污染。
- 追溯链：taskId 串起"对话 → 工具调用 → 确认人 → 结果状态 → 影响行数"，事后可完整还原一次 AI 操作的全过程。

---

## 6. 多租户治理

- 行级隔离：`OrgContext`(ThreadLocal) + Hibernate `@TenantId`（`OrgTenantIdentifierResolver`），实体 `OrgScoped` + `TenantFillListener` 自动填充；过滤器 finally clear 防 ThreadLocal 跨请求泄漏。
- 唯一键治理（V34）：租户内唯一（如同名活动）与全局唯一分开设计。
- 运行时租约（V71 RuntimeLease）：防同一任务并发重复执行。
- UI 层：组织主色、术语表（terminology）、领域包开关（enabledPacks）按组织下发——多租户不只是数据隔离，还是产品形态的可配置。

---

## 7. 已知风险与治理路线（如实记录）

| # | 风险/不足 | 现状 | 路线 |
|---|---|---|---|
| 1 | 部分业务端点无鉴权、无行级数据域 | 历史遗留，Service 层盲区 | 按模块补 `@PreAuthorize` + 数据域过滤，优先涉人涉钱模块 |
| 2 | `PermissionGuardIntegrationTest` 长期失效 | H2 不支持 MySQL 专有语法（V25 长文本索引） | 迁移 Testcontainers 真实 MySQL |
| 3 | `AgentEventPublisher` 两个内存 Map 无淘汰策略 | 复审确认中 | 加上限/TTL；多实例化时一并换分布式 broker |
| 4 | 扫码签到无防代签机制 | 现仅免登录扫码 | 引入位置/时效/设备指纹校验 |
| 5 | 单 JVM 部署，未验证多实例切换 | 内存 simple broker、内存缓冲 | 多实例前替换外部 MQ/Redis broker |
| 6 | 知识库无向量检索 | BM25 关键词路线，依赖 QueryTokenizer | 语义检索按权限裁剪后可叠加，但**不得替代双期复核** |
| 7 | 无多角色叠加、无审批流 | 单角色模型简化了治理 | 若引入需重审"权限码/角色谓词混用"红线 |
| 8 | 会话/长期记忆的留存策略 | 未系统化 | 明确保留期与用户删除权 |

---

## 8. 治理决策记录（ADR 摘要）

| 决策 | 选择 | 放弃的方案 | 理由 |
|---|---|---|---|
| 写操作安全 | dry-run + 确认门禁（D2） | 直接执行 + 事后审计 | 组织场景错误成本高（误发通知/误改活动），事前拦截 > 事后补救 |
| 防提示注入 | 工具不可见即不可调（D3） | 仅靠 dispatch 拒绝 | 拒绝信息本身会进上下文成为新的注入素材；源头不可达更彻底 |
| 出网脱敏 | 唯一闸口（D4） | 各工具自行脱敏 | 分散实现必然遗漏；闸口可集中回归验证 |
| 知识库权限 | 双期复核 | 仅索引期过滤 | 索引滞后/权限即时变更会泄漏；回源复核以业务表为准 |
| 引用机制 | 服务端回填 `[n]` | 模型自述出处 | 模型会编造编号；服务端映射保证 citations 可信 |
| 流式通道 | STOMP + REST 补拉 | SSE / 纯轮询 | 需要双向（abort）+ 用户级队列 + 断线补拉；轮询成本高 |
| LLM 接入 | OpenAI 兼容协议 + Stub 降级 | 绑定单一厂商 | 协议通用，换网关只改配置；无 Key 环境可全流程开发测试 |
| 渲染净化 | 前端 DOMPurify + 后端 jsoup（D10） | 单端净化 | 双端设防，LLM 输出的 HTML/Markdown 不被信任 |

---

## 9. 结语：治理艺术的三层境界

1. **规则层**——权限码、@PreAuthorize、菜单过滤：防君子，也能防大多数失误，但有盲区（Service 层）。
2. **架构层**——三层工具防护、双期知识复核、唯一出网闸口、审计 REQUIRES_NEW：让错误**结构性地难以发生**，即使发生也留痕。
3. **文化层**——口径统一红线（同一资源一种口径）、fail-fast 纪律（未识别 mode 不降级）、回归闭环（r/f/kb 三系列 238 用例）：治理最终落在每个人的评审习惯里。

协奏的治理艺术，本质是把"信任"做成了**可验证的机制**：用户信任系统，因为每次写操作都要确认；系统信任模型，因为模型只能看见被授权的世界。
