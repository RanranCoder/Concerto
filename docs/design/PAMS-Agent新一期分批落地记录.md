# 灵思 Agent 新一期分批落地记录

日期：2026-11-01。以当前工作区为基线；不代表部署或真实模型验收完成。

## 批次与出口

| 批次 | 范围 | 当前状态 |
| --- | --- | --- |
| 1：T0 基础契约 | 实际工具清单、FORM 声明、权限与 scope 诊断、结果完整性兼容标记 | 已完成下述增量，T0 尚未全部完成 |
| 2：A0/A1 | 场景基线用例、Prompt 资源版本与 Resolver/Renderer、运行事实、配置升级诊断 | 已落地基础增量，真实模型质量门槛及完整运行事实待补 |
| 3：T1/A2 读取验证 | 活动详情、策划书版本列表、可信基线及原文读取、DOCX 格式技术验证 | 已接入只读工具与自动化格式探测；完整快照台账/真实文档验收待补 |
| 4：T1/A2 定向修订 | 服务端变更集、真实差异、确认再鉴权/CAS、幂等创建新草稿 | 已实现简单DOCX单处预算总额修订；生产/真实模型及视觉验收待补 |
| 5：T2/A3 连续业务 | 任务与通知回执、跨确认接续、未知结果核对 | 约定支持范围内后端能力已补齐；外部模型/MySQL/浏览器发布验收待做 |
| 6：A4/T3/T4 | 受控规划、宣传审批及扩展能力 | 后端开放范围及本轮隔离MySQL/恢复验收已完成；模型执行收益、浏览器验收及储备能力待做 |

## 批次 1 实现

- 从实际工具组件契约生成 [22 个工具快照](../audits/agent-implementation-20261101/tool-inventory.json)，含 JSON Schema、权限 ALL/ANY、领导约束、敏感级、读/预览语义及 FORM 支持。共 12 个读工具、10 个写预览工具；仅 createActivity 明确支持 FORM。
- AgentTool 增加默认关闭的 supportsForm 和兼容 ToolDescriptor。旧 Schema 固定标识为 v1；写动作先标为 PREPARE，不推断已完成 create/update/transition 的语义审计。
- 原工具列表 API 保留旧字段并附 descriptor；注册表提供权限/模式与显式 scope 取交集的诊断方法，尚未接入专家运行诊断界面。空 scope 返回空，不等于全部工具。
- 注册表按名称稳定排序，重复名称直接报错，避免静默覆盖实现。
- 普通只读模型结果保留 status/summary/data/references/truncated，附 schemaVersion、actionState 和 completeness。完整来源覆盖与 hasMore 未经旧工具声明时返回 null；truncated 仅表示渲染裁剪。写工具和敏感结果继续只发送摘要；会话回查仍使用自身渲染路径。
- 元数据只用于描述，不替代权限、确认或业务幂等校验。旧工具完整性标为 NOT_DECLARED，写工具幂等性标为未核验，防止诊断承诺超过实际能力。

## 验证与边界

运行：`mvn -q -f pams-backend/pom.xml -Dtest=ToolInventoryTest,ToolDescriptorTest,ToolResultTextTest,AgentToolActivityNameSchemaTest test`。

结果：8 项测试通过，失败/错误/跳过均为 0。覆盖实际组件清单与 Schema、FORM 声明、权限/模式/scope 交集、重复注册拒绝、裁剪与敏感摘要边界、既有活动名称参数契约。

本批未启用新 Prompt，未新增文档修订写能力，也未完成真实模型、数据库副作用或前端卡片验收。T0 剩余工作包括每工具完整性/对象策略/动作细分/预算与结果错误契约审计，以及专家诊断接线；不能据本批测试认定完整 T0 或 A1 已验收。工作区此前已有修改保持原状，未自动提交。

工具快照更新：运行 ToolInventoryTest，将 `pams-backend/target/agent-tool-inventory.json` 复制到上述审计目录；该快照来自无数据库组件契约检查，不证明生产环境的注册或数据库角色配置。

## 批次 2：Prompt 版本基础与运行事实

### 本批交付

- `agent/prompts/v1` 提供 core-policy、executor、knowledge、reviewer、memory-summary 五份独立资源。PromptResolver 以固定枚举解析，规范化换行并计算 SHA-256；缺失或空文件直接失败，不允许模型指定路径。修改资源应同步评审版本与指纹。
- 单 Agent 与专家均通过 PromptRenderer 使用公共执行规则和 executor 契约。规则明确事实与创作建议、FORM 显式支持、目标歧义、预览与实际成功、未知结果核对、现阶段文档修订能力边界。未把尚未实现的修订工具写成可用能力。
- 运行事实只序列化白名单字段：UTC 当前时刻、模式、实际提供的工具及 FORM/READ/PREPARE 语义、run/workItem/revision/attempt、配置的最大工具迭代数。专家使用权限过滤后的实际 scope，不注入其他专家能力。该迭代数不是动态剩余预算。
- ToolContext 尚无用户时区，明确输出 timezone=null 与 USER_TIMEZONE_UNAVAILABLE，不假定用户在服务器时区。用户本地日期的可靠归一化仍需后续用户设置接线。
- KB、审校、摘要分别迁移已有契约；审校与摘要不继承执行规则。KB 改为无需复述恶意指令，办理入口须有证据或系统支持。原引用、结构化审校与 sourceMessageIds 校验路径保留。
- 数据库自定义角色内容与 scope 不覆盖。AgentRegistry.promptConfigurations() 提供只读配置报告，包含角色状态、公共规则/角色指纹与配置 scope；尚无 HTTP/UI 诊断入口。领域角色维持旧配置，其内容不能授予权限；代码鉴权仍为最终门禁。
- 补充 [12 个真实模型种子场景与判定标准](../audits/agent-implementation-20261101/prompt-scenarios.md)。这是待执行清单，不是现状质量基线结果。

### 验证

`mvn -q -f pams-backend/pom.xml -Dtest=PromptRendererTest,PromptConfigurationTest,ContextAssemblerTest,ContextSummaryGeneratorTest,ContextSummaryCallTest,PlanReviewServiceTest,KbPromptBudgetTest,SpecialistLoopTest,SpecialistPipelineTest,AgentOrchestratorTurnSensitivityTest,OpenAiNativeProtocolsTest test`

36 项测试通过，失败/错误/跳过均为 0。含 H2 下单 Agent 和专家集成回归、原生工具调用配对、敏感级留存、上下文权限、摘要隔离、审校契约、资源与配置诊断。增加专家请求组装断言并纠正摘要资源中 Java 字符串转义后，另跑 PromptRendererTest、SpecialistLoopTest、ContextSummaryGeneratorTest（6 项）。

### 尚待完成

本批代码已接入新 Prompt，但未部署、未执行真实模型质量对比，也未验证生产数据库配置。A0 的 40–60 条评测集与现状基线、A1 的完整 RuntimeEnvelope（用户时区、活动约束、明确版本基线、动态预算等）、领域角色资源迁移/版本快照持久化和配置诊断界面仍待实施。当前只能识别资源版本与指纹，不能据此承诺旧任务精确恢复原 Prompt。模型规划器尚未接入，不提前启用 planner 契约。文档读取与 DOCX 技术验证按下一批推进。

## 批次 3：活动与指定版本读取

新增 getActivityDetail、listPlanVersions、readPlanDocument 三个只读工具，注册工具总数由22增至25。检索/策划默认 scope 增加最小读取能力；仅启用且精确匹配旧默认 scope 的数据库配置自动升级，自定义和禁用配置保留。

服务使用 PlanAccessService 的版本访问规则，每页重新鉴权；内容哈希与 revision 组成基线，变更后拒绝沿用旧分页。原生 DOCX 从实际附件读取；旧 HTML 单独标记，派生 documentText 不冒充原件。DOCX 读取支持普通段落与简单表格文本，复杂元素明确反馈；所有结果均不具备修订提交资格。

19项不同相关测试通过。详细范围、限制、命令对应测试类与验证结论见 [第三批文档读取技术验证](../audits/agent-implementation-20261101/document-read-probe.md)。这不是完整 T1 验收：活动综合查询增强、持久化不可变快照/全文覆盖台账、真实 DOCX 视觉验证、定向变更、差异及确认写入仍待完成。下一批须先补快照与支持元素白名单，才能开放 preparePlanRevision；不能直接把分页块和 documentRef 当作授权变更句柄。

## 批次 4：受限预算修订闭环

在第三批读取工具基础上新增持久化快照及连续覆盖记录、简单段落DOCX格式/包部件白名单、preparePlanRevision 单处预算预览与确认提交。旧的 readPlanDocument 原型说明以本批实现为准：小文档现在可以获得用户/会话绑定快照，只有完整覆盖且通过白名单才标 revisionEligible=true。

真实原始用户消息定义授权金额，模型不能自行扩大变更；确认时重新核验当前作者、版本和实际文件哈希。同一变更集使用持久化回执和数据库锁控制并发与重复写入，新建草稿保留原件及原版本冻结元数据。31项不同相关测试通过。V61 需随部署执行；当前仅在测试环境迁移验证，未部署或提交。

详细边界、测试命令及剩余风险见 [第四批预算定向修订验证](../audits/agent-implementation-20261101/plan-revision-batch4.md)。本批刻意只支持“只改预算总额、其余保留”的简单原生DOCX单处数字替换；表格、复杂文档、旧HTML及历史版本修订仍未开放。通用变更集、生产视觉/模型验收和 RESULT_UNKNOWN 自动核对继续排入后续工作，不因首版闭环跑通就认定全部A2完成。

## 批次 5 首个增量：回执核对与接续

日期：2026-11-02。新增任务回执核对 API 与策划书修订适配器，未知结果统一阻塞为 NEEDS_RECONCILE。快照过期后仍可依据真实版本回执核对；查询无回执不重做写操作，核对成功才按现有依赖/权限机制推进下游。47项相关测试通过，末次可见性检查补充后另跑12项集成测试。

接口、验证范围及未完成项见 [第五批回执核对与接续记录](../audits/agent-implementation-20261102/execution-reconciliation-batch5.md)。本次为第五批首个增量，通知投递/受众与任务变更能力尚未补齐，不表示整个第五批已完成；后续继续本批业务适配，不直接跳到第六批。未部署、未提交。

## 批次 5 第二个增量：任务进度闭环

日期：2026-11-02。新增 prepareTaskProgressUpdate，复用现有 TaskService 和确认门禁，支持基线校验、同操作幂等提交与持久化回执核对。任务查询行增加 revision/assigneeUserId；只允许本人负责或当前组织内的管理范围，确认时再次核验负责人及归档状态。不修改负责人、日期、依赖，也不代替通知投递回执。

V62 保存操作与成功 revision，注册工具总数27，51项相关测试通过。详细范围及验证见 [任务进度闭环记录](../audits/agent-implementation-20261102/task-progress-batch5-2.md)。通知受众/投递、任务计划变更和真实模型验收仍是第五批剩余项；未部署、未提交。

## 批次 5 补齐：目录、任务计划与通知闭环

日期：2026-11-02。本条更新前两个增量的“剩余功能”状态：新增 findMembers、listOrgUnits、prepareTaskPlanChange、resolveNotificationAudience、getNotificationDeliveryStatus；增强任务分页/整图基线、generateActivityTasks 的1–10项显式建议及标准模板原子保存，以及 sendNotification 的受众/内容绑定预览、确认、稳定事件键和投递核对。任务计划、建议、通知全部接入结果待核对与真实回执接续。

目录信息区分成员、账号和当前账号部门；重名/多账号不默选。通知快照仅返回数量/句柄，确认时范围变化或过期须重做预览，提交/入箱/信号/阅读分开；只改授权任务，关联日期级联回原生界面。新写入沿用确认门禁、对象权限、事务和持久化操作回执，旧清单保留旧语义。

V63新增操作与受众快照表；注册工具32个。后端全量864项中854项通过、10项环境型专项跳过、0失败/错误，末次目录调整再做2项集成复测。详细交付矩阵、限制、测试证据和隔离MySQL复跑方式见 [第五批补齐记录](../audits/agent-implementation-20261102/batch5-completion.md)。第五批后端实现已补齐，仍未宣称真实模型、MySQL故障恢复或浏览器发布验收完成；未部署、未提交。

## 批次 6 首个增量：受控规划预览

日期：2026-11-02。增加独立planner/v1、默认关闭且按组织启用的规划预览端点、服务端结构/依赖/来源校验、一次修复和明确规则基线回退。规划器无业务工具，预览不创建Run或写业务数据；未将模型候选交给现有调度器执行。

本增量及规则/DAG/记忆兼容回归共78项测试全部通过，0失败、0错误、0跳过；未完成真实模型对照验收。

详见 [第六批受控规划预览记录](../audits/agent-implementation-20261102/batch6-controlled-planning-preview.md)。下一增量先完成节点能力/输入绑定的持久化与执行约束，再进行真实模型对照，随后推进宣传审批能力。第六批尚未整体完成，前五批的待验收项仍保留；未部署、未提交。

## 批次 6 后端补齐：受控执行、送审和扩展

日期：2026-11-02。本条更新前一增量的待实现状态：新增服务端规划句柄、节点能力/Schema/输入绑定/完成策略及尝试证据持久化、确定性参数求解和精确工具约束、固定图局部重规划及旧确认失效。读取成功与业务确认回执分开；默认规则入口不自动消费模型计划，规划/执行开关均默认关闭。

宣传链新增稿件完整原文读取、唯一纯文本片段修订、稿件/策划书送审准备和两类组织审批状态；原生稿件编辑与Agent共享并发版本。扩展包括知识证据检索/续读、议程/评分汇总/排班查询、指定班次发布、授权材料正文、组织公告和指定座位布局；新增写入均复用原门禁、原生服务及同事务幂等回执。审批决定仍由有权人员在原生业务界面办理。

V64增加业务并发版本、节点契约/结果、规划句柄及短期证据引用；工具总数47（30 READ、17 PREPARE）。完整交付矩阵、测试和真实模型证据见 [第六批补齐记录](../audits/agent-implementation-20261102/batch6-completion.md)。后端实现不等于A4真实业务收益达标；隔离MySQL、浏览器及前五批遗留验收继续保留，设计标为储备的写操作继续关闭。未部署、未提交。

最终全量919项中908项通过、11项环境/显式网络专项跳过，0失败/错误；真实模型专项另外显式运行，用户指定的deepseek-flash最终6/6合成目标能力/澄清检查通过，全部单次调用。早期稿件送审规划出现Responses未完成响应，失败与回退、诊断及同预算收紧输出后的成功记录均保留；不据小样本认定生产执行收益或常态性能。

## 批次 6 验收第一步：隔离 MySQL 与进程恢复

日期：2026-11-02。在新建、权限受限且执行后清理的本机 MySQL 测试库中完成 V1–V64迁移、稿件/任务/通知及受控执行回归、同操作和同确认并发、原生编辑乐观锁冲突、事务回滚以及真实强杀JVM后的恢复。最终43项全部通过，失败/错误/跳过均为0；完整后端回归926项中909项通过、17项专项默认跳过、0失败/错误。

验收发现并修复 Connector/J 9.5 对评分汇总SQL别名的误判，保持原返回字段；普通旧进程中断任务在核对时以版本CAS显式转RESULT_UNKNOWN，无回执不重做写入。真实未提交事务在进程强杀后由MySQL回滚，已提交业务则按持久化回执核实成功，待确认动作和排队中的受控节点均可跨JVM接续。

证据、失败历史、复跑方式及清理回查见 [MySQL验收记录](../audits/agent-acceptance-20261102/README.md)。本轮数据库/账号剩余数量均为0，原业务库及默认生产开关保持原状。下一项为浏览器端到端验收及A4真实业务收益对照；未部署、未提交。
