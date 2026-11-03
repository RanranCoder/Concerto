# 灵思 Agent 工具层 v2：业务能力目录与契约

日期：2026-10-31。状态：设计草案，非已实现能力列表。

关联：[新一期 Agent 与提示词方案](PAMS-Agent新一期设计方案-执行闭环与提示词重构-v1.md)、[下一阶段开发计划](../下一阶段开发计划-2026-10-31.md)。本次仅设计，不修改业务实现、数据库或部署。

## 1. 设计结论

工具层按业务动作建设：查找对象 → 读取可信事实 → 生成或修订预览 → 人工确认/审批 → 后端提交 → 核对业务结果。保留已有工具，重点补“读原文、改已有对象、明确接收范围、审批衔接和执行核对”，不把所有 CRUD 接口直接包装为工具。

全系统目录是能力地图，不是一次给模型的全量 tools 数组。每次暴露的集合为：运行权限 ∩ 模式 ∩ 专家作用域 ∩ 当前任务阶段。首版采用确定性的能力包选择，不让模型任意加载未授权工具。

以下所有新增工具名均为拟定标识，实施时需登记 Schema 版本；“已有服务”表示可以复用业务基础，不表示已经具备 Agent 所需并发、预览与幂等语义。

## 2. 当前业务与基础

抽查模块包含活动、策划书、任务、议程、签到、评分、宣传稿件、新闻、公告、通知、成员、组织、课表、排班、考勤、座位、材料、素拓、党务、知识库、群聊及系统管理。

当前 Agent 工具类共覆盖以下 22 个标识：

| 类别 | 已有工具 |
| --- | --- |
| 查询与回查 | queryMyPendingWork、queryActivityOverview、queryActivityTasks、queryAttendance、queryCommonFreeTime、queryCreditRecords、queryRosterSigninStats、queryNotifications、queryPartyMemberProgress、searchArticles、searchMaterials、recallConversation |
| 预览后写入 | createActivity、draftPlanSections、draftArticleFromActivity、generateActivityTasks、generateActivitySchedule、createSeatLayout、previewCreditBatch、createMember、publishAnnouncement、sendNotification |

历史 10 月 29 日工具矩阵验证 21 个业务工具；会话回查另计，不将历史测试数误写为当前全部工具数。注册表实际启用、运行权限和数据库角色 scope 还会影响可调用集合。

基础契约保留 `AgentTool` / `WriteAgentTool`、`ToolRegistry`、`ToolResult`、`TaskGate`、`PolicyGuard` 与 `WorkItemState`。业务权限不少位于 Controller，工具直接调用 Service 必须主动重做等价权限和对象范围校验；不能因为接口“登录可查”就扩大 Agent 可见数据。

## 3. 分层与动作分类

| 层 | 职责 | 是否作为模型工具 |
| --- | --- | --- |
| 业务工具 | 查询、读取、准备创建/修订/提交申请 | 是，按任务选择 |
| 确定性能力服务 | 文档定位与差异、金额校验、排班规则、受众解析 | 默认由工具/工作流内部调用 |
| 事务与执行控制 | 确认、审批鉴权、commit、重试、恢复、Outbox | 否，用户事件和调度器触发 |

工具语义分 READ、PREPARE_CREATE、PREPARE_UPDATE、PREPARE_TRANSITION。PREPARE 工具模型调用后只产生待确认计划；真正写入仍由确认接口执行 `commit`。计划持久化不等于业务对象已写入。

保持现有“写工具走确认”的边界。未来如要允许只读模式生成不落业务库的内容建议，应另定义能力与 UI 语义，本期不偷偷放开写工具。

确认、批复、发版、发送、删除不合并为通用 `executeAction(action)`。相同权限域内的有界查询视图可合并；不同权限、敏感级或副作用的动作保持独立。

## 4. 业务能力目录

标记：保留＝沿用；增强＝已有工具升级；新增＝新适配；储备＝待验证服务后排期。P0 为本期主闭环，P1 为后续连续业务，P2 为扩展，P3 为暂缓。

### 4.1 活动与策划书

| 工具 | 状态/优先级 | 输入与输出重点 | 服务与边界 |
| --- | --- | --- | --- |
| queryActivityOverview | 增强/P0 | 关键词、日期、状态、分页；列表、查询范围、hasMore、统计口径 | Activity 查询；过滤下推，不能前 200 条代替全量 |
| getActivityDetail | 新增/P0 | activityId；活动事实、业务 revision、来源时间 | Activity/Detail；不一次返回所有成员和敏感子模块 |
| createActivity | 保留/P0 | 用户字段→创建预览→确认后 ID | 保持创建与后续生成分离 |
| prepareActivityUpdate | 新增/P1 | activityId、baseline、显式字段变更；前后差异和影响提示 | 时间/地点变更不自动修改已发布通知和排班 |
| listPlanVersions | 新增/P0 | activityId、分页；版本、状态、作者、可用动作 | PlanService/PlanAccessService；“最新”需标明最新草稿或已通过版本 |
| readPlanDocument | 新增/P0 | planId、基线、可选段落范围/续读游标；原文块、版本、完整性 | 原生 DOCX 与旧文档适配；摘要不得冒充全文 |
| draftPlanSections | 增强/P0 | 保留现名；完整正文或受控结构→新草稿预览 | 描述明确“新稿”，不承诺修改已有原文 |
| preparePlanRevision | 新增/P0 | planId、baseline、稳定块定位和变更集；真实差异、待确认版本 | 文档服务定位和校验，确认后创建新草稿，旧稿不覆盖 |
| preparePlanSubmission | 新增/P1 | planId、baseline、reviewerId；提交审核预览 | `plan:submit`；负责人候选由权限服务提供 |
| getPlanReviewStatus | 新增/P1 | planId；当前版本审批状态、可见意见、待办入口 | 与本地 AI 内容审校状态区分 |

审批人的“同意/驳回”首期继续由原生审批界面办理，Agent 可准备意见但不得自作决策。审批通过后发布仍需对应业务权限及动作，不把审批等同于发布。

### 4.2 任务、议程和活动执行

| 工具 | 状态/优先级 | 设计 |
| --- | --- | --- |
| queryActivityTasks | 增强/P1 | 输出任务 ID、revision、负责人、截止时间、进度、依赖；明确分页和逾期口径 |
| generateActivityTasks | 增强/P1 | 从标准清单升级为可传明确任务建议；利用 TaskService.preview/savePlan，不悄悄重复追加 |
| prepareTaskPlanChange | 新增/P1 | activityId、整体/单项基线、显式新增/修改项；返回关联影响；首版不包含删除和 undo |
| prepareTaskProgressUpdate | 新增/P1 | taskId、expectedRevision、progress；本人/管理范围按业务规则判断 |
| queryActivityAgenda | 新增/P2 | 顺序、标题、备注及来源；与文档中的建议议程区分 |
| prepareAgendaChange | 储备/P2 | 议程变更集、基线和顺序校验；先提取共享服务并补租户/对象授权，不能直接复制 Controller Repository 写法 |
| queryRosterSigninStats | 增强/P1 | 统计来源、分母定义、名单版本；明细另受 checkin 权限和敏感策略约束 |
| queryActivityScores | 新增/P2 | 评分规则、已录入统计、授权范围的汇总；不允许模型推断缺失评分 |

签到补录、评分写入和批量删除不在首批；这些会改变考核结果，需要独立审计和更严格的授权范围。

### 4.3 宣传稿件、新闻与公告

| 工具 | 状态/优先级 | 设计 |
| --- | --- | --- |
| searchArticles | 增强/P1 | 明确稿件类型、状态与分页；搜索结果只作定位，不等于正文 |
| readArticleDocument | 新增/P1 | articleId、基线、范围；正文与编辑状态，保留来源与截断标记 |
| draftArticleFromActivity | 增强/P1 | 基于活动事实生成新稿；建议内容标明待核实，不伪造参加人数和效果 |
| prepareArticleRevision | 新增/P1 | 基线+变更集；必须验证现有稿件并发机制，未具备则先补服务 |
| prepareArticleSubmission | 新增/P2 | 明确稿件与版本，复用 `article:submit` 及作者/管理范围规则 |
| prepareArticlePublication | 储备/P2 | 审核状态、版本、发布参数齐全后才可预览；确认后调用发布服务 |
| publishAnnouncement | 增强/P1 | 保持兼容名，description 明确“准备全员公告，确认后发布”；标明受众与是否需阅读确认 |
| queryAnnouncements | 新增/P2 | 可见公告列表与详情、版本；与个人通知收件箱分开 |

Article 与 News 是不同业务模型，不通过一个 type 参数绕过 `article:*` / `news:*` 权限；新闻编辑/发布如有需求另设能力包。

### 4.4 排班、课表、考勤与座位

| 工具 | 状态/优先级 | 设计 |
| --- | --- | --- |
| queryCommonFreeTime | 增强/P1 | 明确学期、周次/日期、人员范围和资料缺失；未知课表不等于空闲 |
| queryActivitySchedules | 新增/P1 | 日期、活动、状态；草稿/已发布排班、班次、revision |
| generateActivitySchedule | 增强/P1 | 调确定性规则服务，预览缺口与冲突；生成草稿不等于发布 |
| prepareScheduleChange | 储备/P2 | 班次或换人变更；再次核验课表、时间冲突与基线 |
| prepareSchedulePublication | 新增/P2 | 明确 schedule IDs 与版本；不默认 publish-all；`schedule:publish` |
| queryAttendance | 增强/P1 | 分清日常考勤与活动签到，返回制度口径和时间范围 |
| createSeatLayout | 保留/P2 | 创建预览；模板、容量与已有配置冲突明确反馈 |
| querySeatLayout | 新增/P2 | 指定布局读取，容量和分配信息按权限裁剪 |

课表导入、成绩/考勤改写、排班取消是独立高影响动作，先留在原业务界面。公平性或复杂优化不靠 LLM 猜测，需在排班规则服务实现并可解释。

### 4.5 成员与组织、通知与个人工作台

| 工具 | 状态/优先级 | 设计 |
| --- | --- | --- |
| queryMyPendingWork | 保留/P0 | 汇总本人待办；AI 待确认任务与业务审批待办分别标记 |
| findMembers | 新增/P1 | 姓名、部门、届别等有界筛选；返回最小身份定位，memberId 与 userId 不混用 |
| listOrgUnits | 新增/P1 | 当前租户有效部门/岗位及 ID；不导出账号权限、手机号或完整组织管理数据 |
| createMember | 保留/P2 | 显示届别、身份字段和重复检查；不自动创建账号、邀请或授予角色 |
| queryNotifications | 保留/P1 | 本人收件箱、未读和来源业务链接；不能用来判断他人已收到 |
| resolveNotificationAudience | 新增/P1 | 明确用户/角色/部门选择、组合规则；返回受众快照、数量、排除原因和有效期 |
| sendNotification | 增强/P1 | 内容+audienceRef；确认时校验受众快照和内容版本；成功先表述“已提交投递” |
| getNotificationDeliveryStatus | 新增/P1 | 发送操作或通知批次 ID；提交/投递/失败/阅读状态分开；只允许发送者或授权管理范围 |

受众组合使用显式 `UNION` / `INTERSECTION`，无默认“全部”；接收人必须来自当前授权组织。成员不一定有可接收通知的账号，排除项要可核对。发送权限不自动附带成员详情读取权限；仅有发送权限时可以由受限受众解析器返回数量与受众句柄，不泄露人员名单。若现有业务授权尚不支持这条路径，先返回原生选择入口，不临时放宽权限。

受众快照不冻结权限：确认/投递时剔除失效或越权对象；如接收对象集合变化，确认前重新预览。规模较大时按业务规则固定确认范围，提交后新增成员不自动扩大发送。定向通知与全员公告分别授权。

### 4.6 档案、素拓、党务与知识库

| 工具 | 状态/优先级 | 设计 |
| --- | --- | --- |
| searchMaterials | 增强/P1 | 文件元数据、分类、关联活动、权限允许的摘要；不承诺读到了附件全文 |
| readMaterialDocument | 储备/P2 | 按 file/material 引用读取受支持正文；文件权限、敏感级、解析格式与长度限制独立校验 |
| queryCreditRecords | 保留/P1 | 本人和管理范围分开；合计由数据库计算，附口径与时间 |
| previewCreditBatch | 增强/P1 | 固定签到名单版本、规则、重复加分排除项；确认时再核对，不由模型计算应得分数 |
| queryPartyMemberProgress | 保留/P1 | 阶段与缺项的授权查询；个人明细保持现有敏感限制，不扩大模型出网内容 |
| searchKnowledgeEvidence | 新增/P1 | 将既有检索服务适配为办事模式只读工具；返回授权证据集合与引用映射 |
| readKnowledgeEvidence | 新增/P2 | evidenceRef + 段落范围，扩展当前授权片段；逐次鉴权，不等于允许下载整库 |
| recallConversation | 保留/P0 | 当前会话历史回查；来源为历史陈述，不作为执行回执 |

知识模式继续走 KbPipeline；新增证据工具供办事型查询组织制度，避免两条链路复制检索与脱敏实现。长期记忆的创建、修改和删除继续走显式授权管理，不让模型把第三方文本自动写成用户偏好。

党务转正、发展阶段变更、组织关系转接和材料正式归档首批只辅助查询与准备清单；正式认定与登记保持业务人工流程。系统配置、权限角色、账号密码、邀请令牌、模型密钥和群聊代发不纳入本轮工具目录。

## 5. 主闭环的工具契约

### 5.1 指定版本读取

`readPlanDocument` 输入示例：

```json
{"planId":125,"expectedBaseline":"opaque-baseline","scope":{"type":"FULL"},"cursor":null}
```

输出：`documentRef`、`planId`、`versionLabel`、`baseline`、`blocks[]`、`completeness`、`nextCursor`、`unsupportedElements[]`。块包含稳定 `blockId`、类型、文本/表格单元格、内容指纹。基线由服务端按真实保存语义生成；仅版本 ID 无法识别同一草稿再次保存时，必须补 revision/hash。

FULL 可分段返回；服务端维护同一不可变文档快照和覆盖清单。分页读取中内容变化则失效并重读。模型无权自行声明“已读完全文”；服务端检查需要的块是否覆盖，支持范围是否允许该修订。扫描件/OCR、复杂嵌入对象和不支持元素明确反馈。

### 5.2 定向修订

`preparePlanRevision` 输入示例：

```json
{
  "planId":125,
  "documentRef":"doc-snapshot-opaque",
  "baseline":"opaque-baseline",
  "instruction":"只把预算总额改为3000元，其余保留",
  "changes":[{"op":"replaceText","blockId":"b17","expectedHash":"opaque-hash","before":"预算总额为2500元","after":"预算总额为3000元"}]
}
```

工具验证基线、旧内容定位与授权范围；金额分项不一致时返回冲突，不擅自修改其他分项。首版只支持验证通过的文字/表格单元格变更，复杂重排和格式变更另排期。

输出待确认 `artifactId/version`、`changeSetId`、真实差异、修改块数量、未支持项和审校状态。确认内容是完整变更集，首版整体采用/放弃；后端从原文应用变更生成 DOCX，不接受模型重建的整篇文档替换原稿。最终确认重新鉴权、CAS 校验基线，使用稳定 operationId 创建一份新草稿。

模型的 instruction 不是修改范围的唯一授权凭证：运行时绑定原始用户目标与已确认澄清，变更范围需校验。超出范围的修订必须重新预览，不通过改写 instruction 自行授权。

### 5.3 其他写工具共用约束

activityId、articleId 等对象引用经后端重新鉴权；缺字段只对明确支持 FORM 的工具允许省略。重名返回可见候选，禁止默选第一个。预览中的 baseline、内容版本、受众和规则版本均绑定计划摘要；过期或已修订计划的 token 失效。

`commit` 不作为模型工具。幂等键由运行时创建、跨重试复用，写工具必须落实到业务唯一约束/事务/Outbox；客户端随机生成新键不能证明幂等。结果未知先查询业务回执，不能从异常直接推断未执行。

## 6. v2 元数据与统一结果

逐步扩展 ToolDescriptor，保留现有接口适配：`name`、`schemaVersion`、`domain`、`operationKind`、`requiredPermissions`（ALL/ANY）、`objectPolicy`、`sensitivity`、`supportsForm`、`requiresBaseline`、`idempotencyPolicy`、`resultSchema`、`budgets`。

租户、当前用户、权限上限、run/workItem、operationId、确认令牌由运行时注入，不接受模型覆盖。权限码沿用业务动作；新增权限仅在业务确无等价能力时设计，不把新工具名变成一套平行权限体系。

统一结果建议：

```json
{
  "schemaVersion":"2",
  "status":"OK",
  "actionState":"READ_COMPLETE",
  "summary":"已读取指定版本",
  "facts":{},
  "references":[],
  "completeness":{"scope":"DOCUMENT","complete":true,"hasMore":false,"truncated":false},
  "source":{"asOf":"2026-10-31T10:00:00+08:00","baseline":"opaque-baseline"},
  "pendingAction":null,
  "error":null
}
```

示例状态不直接取代现有枚举；适配器将当前 `payload/plan/form` 映射到结构化结果，模型视图、UI 视图和内部提交参数分离。`facts` 只能包含 PolicyGuard 放行的有界事实，不能把内部原始 payload 默认发给模型。

错误类别：VALIDATION_ERROR、AMBIGUOUS_TARGET、NOT_FOUND_OR_NOT_VISIBLE、STALE_BASELINE、CONFLICT、INSUFFICIENT_SOURCE、UNSUPPORTED_FORMAT、TRANSIENT_FAILURE、RESULT_UNKNOWN、BUDGET_EXCEEDED。错误附 `retryable` 和允许的下一步；对象不可见与不存在不泄露差别，工具权限不足可如实说明。相同错误不得靠模型无限重试。

列表一律分页、稳定排序；总数由同口径数据库查询得到，无法提供则显式 unknown。不能把当前页条数作为总数。日期按运行时区归一化，区间边界明确；敏感列表先鉴权过滤再分页，不把被过滤数量暴露给用户。

## 7. Prompt 与专家作用域配套

| 能力包 | 默认工具范围 | 额外约束 |
| --- | --- | --- |
| retrieval | 授权查询、原文读取、知识证据、会话回查 | 不拥有写工具；工具可见不代表可输出全部敏感内容 |
| planning | 活动详情、版本列表、原文、新稿、定向修订 | 提交审核为单独阶段按权限加载 |
| writing | 活动事实、稿件读取、新稿、修订 | 公告发布不能因为“文书专家”身份默认附带 |
| scheduling | 排班读取、共同空闲、排班/任务预览 | 草稿生成与发布分阶段 |
| data | 授权成员定位、受众解析、通知、素拓预览 | 按场景细分，不每次提供全部成员与通知能力 |
| reviewer | 无业务写工具；受控证据快照 | 语义审校与业务审批分开 |

角色需要自己的最小读能力，避免所有事实都绕检索专家中转；复杂业务仍可先检索再传递结构化事实。scope 来自工具元数据与角色版本，数据库自定义配置做差异迁移，禁用项不恢复。

每个工具描述统一回答：何时使用、何时不用、必需定位、缺参/歧义处理、返回内容、完整性、是否仅预览。系统提示词不再写“任何缺参都立即调用”；由 `supportsForm` 和工具 Schema 决定。模型可以撰写建议，实际事实与成功状态只取业务回执。

## 8. 分期交付与验收

| 批次 | 交付范围 | 出口标准 |
| --- | --- | --- |
| T0 基线 | 22 个工具清单、权限/Schema/敏感级/FORM/结果完整性矩阵；统一结果适配和有效 scope 诊断 | 清单与运行注册一致，旧工具调用和 UI 卡片兼容 |
| T1 本期核心 | 增强活动查询；getActivityDetail、listPlanVersions、readPlanDocument、preparePlanRevision；原生差异/提交内部能力 | 原稿不变、真实差异、基线冲突拦截、重复确认只新增一版、支持范围内格式保留 |
| T2 连续业务 | findMembers/listOrgUnits、受众解析、通知与投递核对、任务查询/计划变更/进度更新 | 选人准确、只改授权任务、发送回执与收件箱吻合，确认后下游续跑 |
| T3 宣传和审批 | 稿件读取/修订、策划书与稿件提交、审批状态、知识证据 | 审批对象和版本明确；AI 审校不冒充人工审批 |
| T4 扩展 | 议程、排班调整与发布、材料正文、评分只读等 | 各自完成权限、并发、幂等和业务规则验证后开放 |

T1 与 Agent 方案 A1/A2 同步，T2 对应连续执行 A3。T0 的新增结果字段先兼容消费，不同时重命名全部旧工具。迁移前输出有效工具差异，保留旧 name/schema 适配直到旧任务完成；新运行固定新版本，旧待确认任务不静默换语义。

每个新增写能力必须有：低权限与跨组织拒绝、预览不写业务表、确认再鉴权、过期基线、并发提交、重复确认、取消/恢复、真实副作用核对。文档查实际 DOCX、通知查投递和收件箱、任务查实际 revision。真实模型专项测选工具、实体定位、缺参、约束遵循与未执行不报成功；不能以固定参数服务测试代替模型链路验证。

本轮不承诺全目录工期。先完成 T0 和 T1 的文档格式技术验证，再评估实现量；其他能力按真实使用频率与业务依赖排期，不以工具总数作为交付指标。

## 9. 实施前服务缺口核对

- Plan：草稿再次保存的版本基线、文档稳定块定位、原生格式变更、全文覆盖检查。
- Task：优先复用已有 preview/savePlan/revision；工具不另造计划写入逻辑。
- Notification：受众快照、批次/操作回执、投递/阅读不同状态、合法发送者查询范围。
- Article/Schedule：现有更新接口不自动证明有可靠 revision，必须验证并发令牌与幂等。
- Agenda：目前 Controller 直接操作 Repository，工具适配前需抽共享服务并补对象范围检查。
- 综合查询：每个子域单独鉴权，受限模块不返回内容，也不泄露隐藏数量；部分返回明确可见口径。

以上是实施前核对项，不代表本次已经判定这些服务存在漏洞或完成修复。
