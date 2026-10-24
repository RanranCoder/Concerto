# 当前 Agent 能力管线审查

日期：2026-10-24。对象：当前工作区（包含已有未提交修改）。本轮仅检查、运行验证并保存报告，没有修改业务实现。

## 结论

**目前不能认定全部能力可以端到端跑通。** 单 Agent 的工具循环、任务门禁与前端卡片已有可用基础；自动协作写操作存在确定断点，成果跳转存在系统性旧路由问题，部分默认能力只在目录中声明，尚无工具实现。

“测试通过”“工具返回 OK”“专家步骤 SUCCESS”“最终业务成果写入并可打开”是不同层次。现有测试大多验证前几层，不能替代全部能力的真实模型与真实业务数据验收。

## 验证结果与边界

| 验证 | 本轮结果 | 证明范围 |
|---|---|---|
| Agent + 知识库后端测试 | 206 项，205 通过、1 跳过 | 现有单元/集成断言 |
| 后端全量测试 | 571 项，570 通过、1 跳过 | 包含上述 206 项，不相加；test profile 使用 H2、stub 模型 |
| 前端原环境全量测试 | 159 项，145 通过、14 失败 | Node v26.12.0 下的真实基线 |
| 关闭 Node 实验性 Web Storage 后复测 | 159 项，154 通过、5 失败 | 9 项角色/权限守卫测试由环境问题导致；余下为 tokens 样式规范测试 |
| 前端生产构建 | 通过 | TypeScript 与 Vite 构建；仍有现有文档编辑器大包提示 |
| 灵思浏览器回归 | 5/5 通过 | 预览确认、补参转清单、取消、引用和手机界面；API 被测试脚本模拟，不证明后端工具提交成功 |
| 独立 Java 缺陷探针 | 3 项确认 | 专家丢失 plan/form、null 工具作用域为空、P2 有产物仍 REVISE；mock 服务，不写业务数据 |
| 路由匹配探针 | 20 个链接声明位置中 18 个不匹配 | 使用当前 React Router 的 matchRoutes；部分动态链接采用静态前缀构造代表 URL，不覆盖实体内容与页面查询参数 |

跳过项来自 EnvFileImportConfigTest 的条件测试。没有调用付费模型、没有对现有业务数据库执行 Agent 写操作。线上模型工具选择质量、MySQL 提交链路、真实 WebSocket 断连恢复和所有角色/组织组合，仍需独立验收。

## 发现清单

### F01 高优先级：协作分支丢失写清单与补参表单

位置：`SpecialistAgent.java:122–127`、`AgentOrchestrator.java:245–256, 365–392, 693–716`。

单 Agent 分支对 `ToolResult.plan/form` 调用 `TaskGate.createPlan/createForm` 并推送 PLAN_READY/INPUT_NEEDED。协作分支直接调用协调者，专家把 ToolResult 缩减为 name/label/status/summary/durationMs；plan、form、references、payload 均没有传出，也没有调用 TaskGate。

影响：10 个写工具只要从协作路径执行，即使 dry-run 返回 OK，也无法从该返回结果继续确认提交；缺参时也不能生成可提交表单。自动协作默认开启，正式推文、公告等请求容易进入此路径。探针确认：模拟工具返回完整计划，专家结果只剩五个字段。

### F02 高优先级：专家缺少原始目标、历史、附加资料和上游产物

位置：`CoordinatorAgent.java:169`、`SpecialistExecutor.java:215`、`SpecialistAgent.java:77–95`。

多数子任务仅是“撰写推文 / 新闻稿 / 公告草稿”等固定文本，未保留原始活动名称和约束。专家模型输入只有 rolePrompt 与 task；黑板、上一专家结果、审查意见及已解析的附加资料均未加入消息。

专家也只完成一次模型调用与其返回的工具调用，没有将工具结果反馈模型继续处理。因此“先查询再生成”“根据实际工具错误改参数”“按审查意见修订”在专家内部尚无完整闭环。P3 主要是重新执行最后一个产出任务。

### F03 高优先级：有产物与质量通过被混用，P2 审查必然误判

位置：`SpecialistExecutor.java:127, 258–265, 313–323`。

审查没有调用模型或质量规则，只检查黑板 artifacts 非空。任意 OK 工具调用都会被加入 artifacts，contentRef 使用工具 label，缺少真实产物标识，故读查询成功也可能满足“有产物”。P2 审查明确传 null 黑板，必然 REVISE。探针确认：已有一个 artifact 时仍返回 REVISE。

### F04 高优先级：执行状态不能可靠表达业务完成

位置：`SpecialistExecutor.java:207–229`、`CoordinatorAgent.java:159`、`AgentOrchestrator.java:725`。

工具返回 FAILED/DENIED/FORM 时，专家仍可能正常返回 Result，外层 Step 保持 SUCCESS；协调者结束时固定将 Run 标为 SUCCESS，回复模板固定以“协作完成”开头。达到反思上限且仍 REVISE，也没有聚合为未完成或待处理状态。

### F05 高优先级：多数成果与资料链接使用旧路由

位置：`tool/*.java`、`TaskGate.java:624`、`LingsiEntityPanel.tsx:26`、`router/index.tsx:120`。

工具生成 `/activity/...`、`/member?id=...`、`/content/article?id=...`、`/archive/material?id=...`、`/archive/credit...`、`/archive/announcement?id=...`、`/notification`、`/party/member?id=...` 等路径。当前路由为 `/activities/:id`、`/members/:id`、`/content/articles`、`/archive/materials`、`/archive/credits`、`/archive/announcements`、`/notifications`、`/party/members/:id` 等。

TaskGate 仅把 link 映射为 path，前端原样 navigate，没有兼容转换。未匹配路径命中通配重定向至首页。20 个链接声明位置的探针中仅策划书与活动排班 2 处匹配，18 处不匹配（涉及 17 个工具）。即使修正为列表路由，也仍需检查列表页是否处理实体 ID 与对应 Tab；不能仅以不回首页为验收标准。

### F06 中优先级：能力声明与实现不一致

当前实际发现 **21 个工具实现，11 读、10 写**。默认专家目录还引用了不存在的 `draftNewsFromActivity`、`draftAnnouncement`、`generateAgendaFromPlan`。这些工具不会进入实际工具池；独立新闻稿、公告草稿、策划转议程尚无同名实现。

`queryPartyMemberProgress` 已实现，但不在默认专家作用域中；单 Agent 可见时仍能调用，协作检索专家无法使用。数据库可覆盖默认定义，以上目录结论针对代码默认值，不代替运行数据库配置检查。

### F07 中优先级：null 工具作用域意外变成空工具集

位置：`SpecialistAgent.java:153–160`。

契约规定 null 作用域为全部可见工具，但 `Optional.map(registry::toolScopeOf)` 在映射结果为 null 时变成 empty，后续 `orElse(List.of())` 得到空集，后面的 scope==null 分支不可达。默认 generalist 使用 null 作用域，因此单 Agent 对比基线不能按声明获得全部工具。独立探针已确认。

### F08 中优先级：检索加写作的规则分解会遗漏检索

位置：`CoordinatorAgent.java:50–57`。

检索条件是 asksQuery && !asksWriting。“先查历史资料，再写一篇推文”同时命中两者，检索专家被排除。实际协调者结果可能与 CollaborationRouterTest 中人为 mock 的跨域计划不同，路由测试通过不足以证明真实语句端到端正确。

### F09 中优先级：流中止、断连补拉与协作字符流未闭环

AgentLlmCaller 没有传输级取消；协作执行器没有统一检查 abort，KB 分支也没有标准工具循环中的中止检查。前端停止显示不代表后台请求已停止。

前端声明了 agentStreamSince API，但全项目只有定义、没有调用；useWebSocket 重连后重新订阅，未按最后 seq 补拉。普通工具行可以从历史摘要恢复，协作步骤也没有统一恢复到聊天历史的完整协议。

专家调用共用 streamId，callLlm 的 round 固定从 0 开始，增量没有 taskId/agentId 维度。P2 并行时多个专家的正文/推理可能混入同一拼接通道。此项为代码风险，未用真实并行模型复现。

### F10 中优先级：KB 实际输入片段与引用校验集合不同

位置：`KbPrompts.java:78`、`KbPipeline.java:134,143`。

Prompt 按单片 600 字、总 4000 字、最多 8 片截断，但引用映射使用完整 safe 集合。模型本轮未收到的后部编号仍可能被认作合法引用。本轮没有真实模型证据表明一定产生错误引用，因此按可信度边界记录。

### F11 中优先级：规则降级的能力范围明显小于目录

StubProvider 的部分写调用固定 activityId=1，排班缺日期/部门，加分缺分值，且不覆盖所有写能力。协作又常把任务替换为通用描述。无 Key 或主模型失败时，界面仍可对话，但不能据此宣称所有能力可离线完成。协作回复还把 provider 固定为 multi-agent，未把专家真实降级状态完整聚合回前端。

### F12 低优先级：前端全量基线仍不全绿

Node 26 原生 Web Storage 与测试环境存在兼容问题，9 项权限守卫测试在关闭该实验特性后通过。仍失败的 5 项为 CSS 变量、旧变量、字号、内联色及圆角约束，其中动态设置的 `--lingsi-turn-min-height` 也需要区分测试误报与真正缺失定义。未在本轮改样式或测试。

## 21 项能力逐项清点

下表“接线”仅表示静态实现与入口存在，不表示已经逐项执行真实提交。所有协作写能力统一受 F01/F02/F04 影响；除创建活动外，其他写工具缺参主要返回 FAILED 或对话提示，没有统一 FORM 分支。精确声明、链接和测试文件文本命中见 inventory.json；文本命中不能充当覆盖率。

| 工具 | 类型 | 当前管线结论与特有边界 |
|---|---|---|
| queryMyPendingWork 待我处理 | 读 | 单 Agent 接线存在；任务链接旧路由 |
| queryActivityOverview 活动概览 | 读 | 单 Agent 接线存在；活动链接旧路由 |
| queryActivityTasks 任务进度 | 读 | 接线存在，需可解析活动；链接旧路由 |
| searchMaterials 材料检索 | 读 | 接线存在；材料链接旧路由 |
| searchArticles 稿件检索 | 读 | 接线存在；稿件链接旧路由 |
| queryNotifications 通知查询 | 读 | 接线存在；通知链接旧路由 |
| queryCommonFreeTime 共同空闲 | 读 | 接线存在；依赖学期、人员与课表数据；尚无逐项 Agent 成功验收 |
| queryAttendance 考勤查询 | 读 | 接线存在；依赖实际考勤数据；尚无逐项 Agent 成功验收 |
| queryCreditRecords 素拓台账 | 读 | 接线存在；台账链接旧路由 |
| queryPartyMemberProgress 党员发展进度 | 读 | 单 Agent 接线存在；默认协作作用域缺失；链接旧路由 |
| queryRosterSigninStats 签到统计 | 读 | 接线存在，依赖活动名单；签到链接旧路由 |
| createActivity 创建活动 | 写 | 单 Agent FORM→PLAN→confirm 接线存在；协作丢 FORM/PLAN；成果链接旧路由 |
| createMember 新增成员 | 写 | 单 Agent dry-run/commit 接线存在；依赖届别、身份与部门；成果链接旧路由 |
| createSeatLayout 创建座位表 | 写 | 单 Agent dry-run/commit 接线存在；依赖活动及座位模板；成果链接旧路由 |
| draftArticleFromActivity 起草推文 | 写 | 单 Agent dry-run/commit 接线存在；正文主要为活动事实规则模板；自动协作易触发 F01；成果链接旧路由 |
| draftPlanSections 策划书草稿 | 写 | 单 Agent dry-run/commit 接线存在；需要实际章节正文；通用专家任务缺原始上下文；成果路由匹配 |
| generateActivitySchedule 生成排班 | 写 | 有预览回归，需日期时段部门与课表；尚未逐项验收提交；成果路由匹配 |
| generateActivityTasks 生成任务清单 | 写 | 单 Agent dry-run/commit 接线存在；幂等/已有任务会影响生成；成果链接旧路由 |
| previewCreditBatch 批量加分 | 写 | 单 Agent dry-run/commit 接线存在；依赖签到名单、人员匹配与分值；成果链接旧路由 |
| publishAnnouncement 发布公告 | 写 | 单 Agent dry-run/commit 接线存在；这是发布工具，不能当作独立公告草稿能力；自动协作受 F01 影响；成果链接旧路由 |
| sendNotification 发送通知 | 写 | 单 Agent dry-run/commit 接线存在；依赖接收对象；成果链接旧路由 |

## 留待统一策划的验收缺口

尚缺“每个写工具有效参数→预览→确认→真实业务结果→可打开成果”的完整覆盖；真实模型、实际 MySQL 环境与前端模拟接口回归之间也未做贯通验证。现有多 Agent 测试主要断言专家/步骤存在、状态与黑板落库，P2 的测试没有断言审查 PASS，无法发现本轮已确认的闭环问题。

下一轮统一策划可以直接引用 F01–F12，不应把本轮测试通过数当作全部能力完成率。

## 证据与重现

- backend-tests.log：Agent 与知识库专项测试。
- backend-all-tests.log：后端全量测试。
- frontend-tests.log / frontend-tests-compatible.log：原环境与关闭实验性 Web Storage 后的结果。
- frontend-build.log：生产构建。
- browser-tests.log：5 项模拟接口浏览器回归。
- inventory.py / inventory.json：工具静态清单，使用可用 Python 运行脚本生成。
- routes.mjs / routes.json / routes.log：`node docs/audits/agent-pipeline-2026-10-24/routes.mjs`。
- PipelineProbe.java / probe-results.log：独立缺陷探针；inventory.py 从现有 surefire XML 提取本机测试 classpath，再以 Java source-file mode 运行。不纳入业务测试或修改业务源码。
