# 协奏Concerto Agent 设计原理

> 「灵思」是协奏 Concerto 内置的 AI 助手 Agent，产品名灵思，代码仍为 `com.pams.module.agent`（接口前缀 `/api/agent/**`）。它不是外挂聊天框，而是**长在权限体系里的业务协作者**：复用业务 Service、受 RBAC 与多租户约束、写操作强制人机协同确认。本文沉淀其设计原理与关键不变量。

---

## 1. 设计目标与原则

| 原则 | 含义 |
|---|---|
| 权限即上下文 | 模型能看到什么工具、什么数据，完全由用户权限决定——"不可见即不可诱导" |
| 人机协同 | 一切写操作走 dry-run 清单 → 用户确认 → commit 三步，模型永远不能直接改数据 |
| 数据分级决定 LLM 上下文 | NORMAL 直进 / SENSITIVE 遮罩 / RESTRICTED 拦截，出网有唯一闸口 |
| 可审计 | 每次工具调用、每次确认都有审计记录；审计丢失即是事故 |
| 复用而非旁路 | Agent 调用的是业务 Service，而不是自建 SQL——业务规则、校验、事务天然一致 |

---

## 2. 模块结构（13 个子包）

```
module/agent
├── config/         LlmConfig / AgentProperties / AgentExecutorConfig（线程池 core4/max8/queue64）
├── controller/     AgentController(/api/agent) / AgentArtifact / AgentBench /
│                   AgentDiagnostics / AgentMemory / AgentRuntime / AgentTeam
├── core/           ToolRegistry / AgentTool / PolicyGuard / Sensitivity /
│                   ChatRefsResolver / CitationMapper / ToolConventions
├── llm/            LlmProvider 接口 + OpenAiResponsesProvider / OpenAiChatCompletionsProvider /
│                   StubProvider / OpenAiTransport / ModelEndpoint / LlmCancellation
├── orchestration/  多智能体：CoordinatorAgent / SpecialistAgent / SpecialistExecutor /
│                   CollaborationRouter / DagScheduler / Blackboard / AgentRunService /
│                   ControlledPlanningService 系（Planning/ToolGuard/NodeExecutor/PlanStore/
│                   PlanValidator）/ RuntimeLease / WorkItemState
├── prompt/         提示词组装
├── provider/       模型接入治理：TaskProviderRegistry / ProviderSecrets / ProviderUrlPolicy /
│                   ProviderHttpClient / AgentProviderService
├── registry/       AgentCatalog / AgentRegistry / AgentDefinitionSeeder（多 Agent 目录）
├── repository/ entity/ service/   （TaskGate / AgentOrchestrator / KbPipeline /
│                   AuditService / AgentLlmCaller / ContextAssembler /
│                   ConversationMemory / LongTermMemory 等）
├── stream/         AgentStreamEvent / AgentEventPublisher / AgentRunOutbox / ThinkingTrace
└── tool/           44 个工具实现（CreateActivityTool、FindMembersTool、SendNotificationTool…）
```

---

## 3. 核心四件套

### 3.1 ToolRegistry（工具注册表 + 三层防护）

- 启动期：重名工具直接抛异常（fail-fast）；每个工具实现 `AgentTool` 契约。
- **可见性过滤** `visibleTo(ToolContext)`：按 `requiredPermissions` / `anyPermission` / `requiresLeader` 过滤，**不可见的工具根本不进 LLM 的 tools 列表**——这是第一层防护，模型连"诱导"它的机会都没有。
- **派发断言** `dispatch()`：即使模型幻觉出调用（提示注入、上下文残留），执行前再断言一次权限，不满足抛 403——第二层防护。
- **确认二次断言**：写操作经 TaskGate 确认时再校验一次 confirmToken 归属——第三层防护。
- 三层检查的权限口径必须一致（同一权限码集合），这是评审的硬性要求。

### 3.2 TaskGate（确认门禁，D2）

- 写类工具（`writeOperation()==true`）的 `execute` 实际是 **dry-run**：生成影响清单落库为 `agent_task(PENDING_CONFIRM)`，返回一次性 `confirmToken`。
- 用户确认走 `POST /api/agent/tasks/{id}/confirm`：校验 token + **乐观锁抢占**（防重复确认/并发确认），通过后才调 `WriteAgentTool.commit` 真正写库。
- 缺参场景返回 **FORM / WAITING_INPUT**，前端渲染表单卡收集参数后回到确认流程。
- `cancel` 主动作废；确认类编排**刻意不开整体事务**，审计用 `REQUIRES_NEW` 独立提交（见 §9 踩坑）。

### 3.3 AgentOrchestrator（编排器，B0）

- 工具循环主流程：组装上下文 → LLM 推理 → 解析 tool_calls → 逐个 dispatch → 回填结果 → 循环，直至产出最终回复或等待确认。
- 约束参数：`context-rounds: 6`（携带的历史轮数）、`max-tool-iterations: 5`（单次请求工具循环上限）、`temperature: 0.3`、`max-tokens: 8192`、`timeout-ms: 120000`。

### 3.4 PolicyGuard + Sensitivity（出网闸口，D4）

- `Sensitivity` 枚举：`NORMAL / SENSITIVE / RESTRICTED`，提供 `canEgress()`、`max()`。每个工具**必须**声明 `sensitivity()`。
- `PolicyGuard.egressText()` 是**全系统唯一出网脱敏闸口**：
  - normal：直进；
  - sensitive：字段级遮罩（正则遮手机号/身份证/学号，**遮罩顺序固定 ID_CARD → PHONE → STUDENT_NO**，先长后短防部分遮蔽）；
  - restricted：直接拦截。
- 同时用于写库脱敏与回复出网脱敏。**调用纪律**：必须以返回值替换原文，历史上曾发生"调用了但丢弃返回值"导致脱敏失效，已修并纳入回归。

---

## 4. 工具契约（AgentTool）

44 个工具实现于 `tool/` 包，代表性工具覆盖活动（CreateActivityTool / QueryActivityOverviewTool / GenerateActivityScheduleTool / GenerateActivityTasksTool）、成员（FindMembersTool / CreateMemberTool）、宣传（DraftArticleFromActivityTool / SearchArticlesTool）、档案（SearchMaterialsTool / ReadMaterialDocumentTool）、通知（SendNotificationTool / QueryNotificationsTool）、协作（QueryCommonFreeTimeTool / QueryMyPendingWorkTool）等。

契约字段（模型侧 + 安全侧一体声明）：

```java
public interface AgentTool {
    String name();            String label();
    String runningLabel();    String description();
    Map<String,Object> parametersSchema();  List<String> requiredParameters(); // ToolArgs 校验
    List<String> requiredPermissions();     // 空 = 登录即可
    boolean anyPermission();                // 任一满足即可
    boolean requiresLeader();               // 与 requiredPermissions 取"与"
    Sensitivity sensitivity();              // 数据分级
    boolean writeOperation();               // true ⇒ 强制走 TaskGate
    ToolResult execute(ToolArgs args, ToolContext ctx);   // 写类工具此处是 dry-run
}
```

- `ToolContext` 携带 userId / orgId / mode / 权限集合；**`derive()` 派生时必须透传 `mode`**，否则权限模式静默降级（曾出过问题，已修）。
- 活动类工具共用 `ActivityResolver` 做"活动引用 → 实体"解析，**禁止静默选择同名活动**（歧义时必须让用户澄清）。

---

## 5. 运行模式（mode）

| mode | 行为 |
|---|---|
| `standard` | 完整工具循环（读 + 确认式写） |
| `readonly` | ToolRegistry 只返回读工具（`ToolRegistryReadonlyModeTest` 锁定），写工具不可见亦不可调 |
| `kb` | **知识库问答独立流水线**（KbPipeline），不进工具循环，见 §8 |

不变量：**未识别的 mode 必须 fail-fast**，绝不允许静默降级成 standard（可写）。

---

## 6. 流式协议与实时推送

### 6.1 事件模型

```java
record AgentStreamEvent(String streamId, long seq, String type, Object payload, long ts)
```

事件类型（与前端一一对应，共 15 种）：`THINKING / THINKING_DELTA / TOOL_START / TOOL_END / PLAN_READY / INPUT_NEEDED / MESSAGE_DELTA / MESSAGE_DONE / ERROR / ABORTED / COLLAB_DECISION / AGENT_START / AGENT_END / AGENT_DELEGATE / REVIEW_RESULT`。

### 6.2 推送链路（WebSocket STOMP，非 SSE）

- `AgentEventPublisher` 经 `SimpMessagingTemplate` 推 STOMP：`/user/queue/agent/stream`（过程事件）与 `/user/queue/agent`（任务完成/运行状态）。
- **streamId 必须绑定 userId**：订阅时校验归属，用户只能收到自己的流（曾出过归属漏洞，已修）。
- 发布器带**断连补拉缓冲**（stream-buffer-size: 500）与授权版本校验；`AgentRunOutbox` 事件外存兜底。
- REST 补拉：`GET /api/agent/streams/{streamId}?since={seq}`（按 seq 增量）+ `GET /api/agent/requests/{id}` 快照对账。
- 注意：全仓**无 SSE**（无 SseEmitter）——Agent 过程事件是 STOMP，前端定时（2–3s）对账重试，不是轮询降级。

### 6.3 前端重组规则（与后端的协议约定）

- `MESSAGE_DELTA` **按 round 重组**：round 变化（工具轮/重试/降级）即清空旧拼装再追加；同 round 内按 `seq` 排序、去重、乱序容忍。
- `THINKING_DELTA` 归入思维链（ThinkingTrace）独立展示；tool_calls 的 `arguments` 按 index 分片拼接。
- 收官事件（MESSAGE_DONE/ERROR/ABORTED）先同步 flush 再落地。

---

## 7. 上下文工程

| 组件 | 职责 |
|---|---|
| ContextAssembler | 组装系统提示、用户提示、历史轮（context-rounds 上限） |
| ConversationMemory | 会话内记忆（含摘要 summary-enabled） |
| LongTermMemory | 跨会话长期记忆（AgentMemoryController 管理入口） |
| ChatRefsResolver | 用户在输入框点选的"附加上下文"（活动/成员/文档引用）→ 注入身份信息；**只注入身份，不能绕过工具与脱敏**；references 上限 5，且必须校验资源可见性 + 走审计（2026-09-14 修复后口径） |
| RecallConversationTool | 历史会话检索工具 |
| LoadBusinessWorkflowTool | 按需加载业务流程知识，减少常驻提示词体积 |

---

## 8. 知识库问答（kb 流水线，V27/V28）

### 8.1 架构组件（module/knowledge）

```
KnowledgeSource (SPI)
   ├─ Tier: T1 全文级 / T2 字段级
   ├─ extractAll()/extractOne()/canAccess(ToolContext, Long)
   └─ visibility: ALL/LEADER；sensitivity: normal/sensitive
六源实现：manual 制度文档 / announcement 公告 / article 推文 /
          activity_plan 策划书 / member 成员名册(T2,sensitive) / snapshot 事实快照(T2,sensitive)

KnowledgeIndexer     整源先删后插重建 kb_chunk（唯一真相在业务表，索引只是可检索视图）
KnowledgeIndexListener  AFTER_COMMIT 领域事件触发增量索引（fallbackExecution=true + 每日兜底全量）
KeywordRetriever     BM25 检索（非向量）：T1 打分 = BM25×源权重；T2 = 固定 0.4×源权重
PermissionResolver  检索期回源复核：对 TopK≤12 逐条调 canAccess，返回 FilteredChunks
KbPipeline          五步流水线（见下）
CitationMapper      引用回填：模型只写 [n]，服务端映射出处
```

### 8.2 KbPipeline 五步

1. **THINKING**：声明检索意图；
2. **检索 + 复核**：KeywordRetriever 召回 → PermissionResolver 逐条回源 `canAccess` 复核（**索引期粗筛 + 检索期复核双期权限**）；
3. **无依据拒答**：复核后集合为空 → 拒绝回答（零引用拒答），绝不编造；
4. **PolicyGuard**：片段出网过脱敏闸口；
5. **回填引用**：模型输出 `[n]` 编号，服务端按复核后集合映射 `citations`，越界编号丢弃；citations 随 MESSAGE_DONE 事件下发。

### 8.3 安全不变量

- citations **只能**由复核通过的集合构造，历史引用回看时也要**实时复核**，权限被收回的引用标记 `revoked`（前端只提示不显内容）；
- MemberSource 是 T2：只索引姓名/职务/部门/届别/状态，**不索引**学号/手机号/政治面貌/备注；运行痕迹、通知、聊天、审计、`agent_*` 表**永不进索引**；
- snapshot 是带 `asOf` 的聚合事实（不含个人明细），`asOf` 必须服务端非空并随引用展示"数据截至"；
- 检索依赖 QueryTokenizer 分词（LIKE/全文都不可整句匹配标题），sourceId 粒度必须与领域事件粒度一致，否则增量索引失准。

---

## 9. 审计（agent_audit_log，V24）

字段：`userId / conversationId / taskId / toolName / argsDigest / sideEffect / confirmedBy / resultStatus / affectedCount / createdAt / orgId`（实现 `OrgScoped`，随租户隔离）。

- `sideEffect` 口径锁死 **READ / PREVIEW / WRITE**（≤10 字符）。曾写入 `WRITE_PREVIEW` 导致列超长、事务 rollback-only、**审计静默丢失**——审计字段改口径前必须先查列宽与存量数据。
- 审计写入 `REQUIRES_NEW`：确认类编排刻意不开整体事务，避免外层回滚拖死审计（防 rollback-only 污染）。
- 入口：`f5-audit` 回归脚本覆盖审计完整性。

---

## 10. 多智能体与模型接入治理

### 10.1 orchestration（多智能体）

- `CoordinatorAgent` + `SpecialistAgent` + `CollaborationRouter` + `DagScheduler` + `Blackboard`：协调者拆解任务 → 路由给专家 Agent → DAG 调度执行 → 黑板共享状态；`AgentRunService` 管运行实例，`WorkItemState` 跟踪工作项。
- 受控规划系（`ControlledPlanningService / ControlledToolGuard / ControlledNodeExecutor / ControlledPlanStore / ControlledPlanValidator`）：规划式执行的安全壳——计划先校验再执行，工具调用仍过 ToolGuard。
- `RuntimeLease`：运行时租约（V71），防并发重复执行。
- 前端对应事件：`AGENT_START / AGENT_DELEGATE / AGENT_END / COLLAB_DECISION`。

### 10.2 provider（模型接入治理）

- `TaskProviderRegistry`：按任务选择 Provider；`ProviderSecrets`：密钥加密存储（`PAMS_AI_ENCRYPTION_KEY`，V66 支持上下文窗口配置）；`ProviderUrlPolicy + provider-internal-hosts`：出网 URL 白名单校验，防 SSRF/内网探测。
- 管理入口：`AgentProviderController / AgentModelStatusController`，前端"AI 服务"页可视化管理。

### 10.3 llm（协议层）

- `LlmProvider` 接口 + 两种 OpenAI 兼容协议实现：`openai_responses`（默认）与 `openai_chat_completions`，按 `pams.agent.protocol` 选择；HTTP 传输统一走 `OpenAiTransport`（httpclient5），支持取消（`LlmCancellation`）。
- **无密钥自动降级 StubProvider**（规则模式）：本地开发与测试不依赖真实 LLM，test profile 强制 `provider: stub`。
- 说明：代码中无 DeepSeek 专属实现，任何 OpenAI 兼容网关（含 DeepSeek）只需配 base-url/model 即可接入。

---

## 11. 设计原理小结（不变量清单）

1. 工具三层防护（可见性 → dispatch 断言 → confirm 二次断言）口径必须一致。
2. 写操作必经 dry-run 清单 → confirmToken → 乐观锁 commit。
3. `PolicyGuard.egressText` 是唯一出网闸口；遮罩顺序 ID_CARD → PHONE → STUDENT_NO。
4. mode 未识别即 fail-fast；kb 是独立流水线，不进工具循环。
5. streamId 绑定 userId；MESSAGE_DELTA 按 round 重组；seq 去重容忍乱序。
6. citations 只能来自 PermissionResolver 复核后的集合；零引用拒答。
7. side_effect 只有 READ/PREVIEW/WRITE；审计 REQUIRES_NEW 独立事务。
8. ChatRefsResolver 只注入身份信息；references ≤5 且校验可见性。
9. 任何派生 ToolContext 必须透传 mode；ActivityResolver 禁止静默选同名活动。
10. 复用业务 Service 是数据一致性的根——Agent 永远不直连 SQL。
