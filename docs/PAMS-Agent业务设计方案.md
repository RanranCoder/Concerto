# PAMS Agent 业务设计方案

> **文档版本**：v1.9　|　**撰写日期**：2026-09-13（v1.9 增补 2026-10-12）　|　**文档状态**：实施中——基座与一期能力已落地；多 Agent / 知识库由后续文档接管（**编码前先读 §0.5**）
> **v1.9 变更（2026-10-12，思维链落地 + 现状对齐）**：① **模型推理（思维链）端到端接通**——新增 `THINKING_DELTA` 事件（`{round,text}`，`ReasoningBatch` 48 字符 / 80ms 合并）、`ThinkingTrace` 按 streamId→round 暂存（LRU 200 流 / 每流 40 段 / 每段 8000 字），`agent_message` 新增 **`thinking_json`（V35）/ `thinking_notes_json`（V36）** 支持刷新后回放；② **`agent_tool_call.args_json` 增加读侧闸门** `PolicyGuard.egressArgs`（`normal` 给遮罩明文、`sensitive`/`restricted` 给 `null`）并随 VO 下发给前端做「工具行下钻」——**闸门刻意在读侧，不动审计口径**；③ 迁移链现状推进至 **V36**；④ 补记两条**待产品拍板**的口径（见 D7「v1.9 现状核对」）。**同日补记：核对官方文档后发现模型已换代——思考模式成了请求参数（`thinking`，默认开）且支持工具调用，而 `deepseek-chat` / `deepseek-reasoner` 已不在官方 `model` 取值列表（现为 `deepseek-flash` / `deepseek-v4-pro`）；已改为 `model: deepseek-flash` 并按官方约束回灌 `reasoning_content`，细节与"仍待真机验证"清单见 D7 的 v1.9 补记。**
> **v1.8 变更（2026-10-05，现状对齐 + 自洽修复，不改变任何设计决策）**：① 新增 **§0.5 实施现状与使用须知**——工具数（21 = 读 11 / 写 10）、迁移链（V1–V34）、流式（事件级 + 字符级均已落地）、净化、多 Agent、限流缺口等逐项核对至源码；② **修复决策 6 的自相矛盾**（"写时脱敏"与"90 天留存"二选一，按实现修正并勘误）；③ 以「v1.8 现状核对」补记更新 D5 / D7 / §3.5 / §3.6 / §4.3.1 / §4.4 / §6 的过期状态。**要点速览：`draftPlanSections` 已落地；字符级流式已落地；`providerFor(Sensitivity)` 分级路由未实现；Agent 端点限流未接；人工编辑保存路径未接服务端净化。**
> **v1.7 变更**：新增 **§3.10 对话体验修正与内容类工具**——① 落地工具清单 #15 **`draftPlanSections`**（策划书章节草稿，此前"没有办法生成策划书"）；② **内容类输出先预览后落库**（`AgentActionPlan.preview` + 确认卡「查看全文」抽屉）；③ **多轮「接续」**（出新清单自动作废旧清单 + 待确认清单注入上下文，修"纠正后无法执行"的错位）；④ 用户气泡单一数据源（修"发出的消息不显示"）；⑤ 执行结果落进对话框、去掉 corner 弹窗。工具数 **20 → 21**（读 11 / 写 10）；回归 `f4-plan.mjs` 16 项全绿。
> **v1.7 补记（同日）**：定位并修复 **§4.3.1 缺陷 6**——`agent_audit_log.side_effect` 为 `VARCHAR(10)` 而编排器写入 `"WRITE_PREVIEW"`（13 字符），导致**所有写类工具的 dry-run 审计行静默丢失**（也是缺陷 2"少一条审计"的真正根因）；取值改为 `"PREVIEW"`，回归 `f5-audit.mjs` + SQL 断言通过。
> **v1.6 变更**：新增 **§3.9 活动名称自动解析**——工具不再强制 `activityId`（用户天生只说得出名称），新增共享组件 `ActivityResolver`（ID 直取 / 名称尾缀「（ID n）」/ 精确同名唯一才拍板 / 其余出候选清单，**绝不静默选择**）；6 个工具接入。确定性单测 12 项 + 端到端 `f3-resolve.mjs` 14 项全绿。
> **v1.5 变更**：新增 **§3.8 对话历史持久化与恢复（V26）**——确认卡/表单卡按 `stream_id` 挂回产生它的消息、引用随消息落库（`refs_json`）、历史轮次过程时间轴由 `tool_calls` 摘要派生、用户提问恢复；顺带修复"新会话首轮任务 `conversation_id` 落 NULL"。回归 `f2-history.mjs` 16 项全绿。
> **v1.4 变更**：新增 **§3.7 表单补全（D2.5 Form Elicitation）**——写类工具缺参时弹出结构化表单引导补全（对齐 Claude/MCP elicitation 与 Codex 结构化确认最佳实践），含状态机 `WAITING_INPUT`、`POST /tasks/{id}/input` 端点、`INPUT_NEEDED` 事件契约与两变体回归实测（全绿）。创建活动为首个消费者。
> **v1.3 变更**：在源码核实基础上**把第 6 节的 6 项决策补成"有理由的建议"**——LLM 供应商（D5 补选型详述 + "按数据分级路由 provider"建议）、HTML 净化（D10 重写，含白名单口径与"顺带覆盖人工写路径"）、content 权限码收敛（D3 补论证，建议不收敛）、ngram FULLTEXT（D6 补 DDL 与 `BOOLEAN MODE` 细节）、`agent_message` 留存（拆为分角色差异化）。**另修正 D3 一处实现级错误**（原文用 `LoginUser.permissions`，实际该字段不存在）。
> **文档定位**：在《[PAMS-Agent 集成调研文档](PAMS-Agent集成调研文档.md)》（v1.0，2026-08-08，机会点调研）的基础上，把 40+ 个机会点**收敛为可施工的业务设计**，并**给出技术方案与落地可行性判断**。
> **基线核对**：本稿所有"现状"结论均来自 2026-09-13 对 `pams-backend` / `pams-web` 源码与 Flyway 迁移链的直接核对，与调研文档的差异集中在第 0 节；**v1.8 已补充核对至 2026-10-05（Flyway V1–V34），与 v1.7 的差异集中在 §0.5**。
> **前置文档**：[PAMS-Agent集成调研文档.md](PAMS-Agent集成调研文档.md) ｜ [PRD-v5-开发规划.md](PRD-v5-开发规划.md) ｜ [API.md](API.md) ｜ [PAMS设计方案.md](PAMS设计方案.md)
> **后续文档（v1.8 补登，见 §0.5.3 优先级）**：[PAMS-灵思页面设计方案.md](PAMS-灵思页面设计方案.md) ｜ [PAMS-灵思知识库问答方案-v1.md](PAMS-灵思知识库问答方案-v1.md) ｜ [PAMS-通用化与多Agent协作改造策划.md](PAMS-通用化与多Agent协作改造策划.md) ｜ [PAMS-协作内聚进灵思-修改计划.md](PAMS-协作内聚进灵思-修改计划.md)

---

## 0. 相对调研文档的更新（勘误 + 现状对齐）

调研文档写于 2026-08-08，其后项目推进了 5 周（Flyway 从 V17 → **V23**）。调研文档中**"接入前必须先修的 5 处权限缺口"与多项"关键前置"已经闭合**，这直接改变了 Agent 的可行性与优先级排序。

| # | 调研文档（08-08）结论 | 2026-09-13 实际状态 | 对 Agent 设计的影响 |
|---|---|---|---|
| 0.1 | `AgendaController` / `NewsController` / `ArticleController` / `Material` / `Template` / `Credit` / `File` 无 `@PreAuthorize` | ✅ 已修复。除 content 模块外，上述控制器均已挂 `hasAuthority(...)`（如 `activity:edit`、`material:upload`、`quality:add`、`notice:publish`、`template:manage`） | Agent 工具层可直接复用，**阶段一不再需要"先补权限"作为前置** |
| 0.2 | `Schedule` / `Attendance` / `FreeSchedule` 三控制器无 `@PreAuthorize` | ✅ 已修复。`schedule:manage` / `attendance:view` / `attendance:manage` / `schedule:check` / `schedule:generate` 已接入（`FreeSchedule` 表已由 V11 删除，改为 `course_schedule`） | 排班/考勤类 Agent 能力权限前置解除 |
| 0.3 | 43 个权限码仅"管理数据"、未接入请求级鉴权 | ✅ 大幅收敛。`JwtAuthenticationFilter` 已"双轨注入"（`ROLE_*` + 权限码），约 20 个权限码已在控制器生效 | Agent 能从 **`Authentication.getAuthorities()`** 取到权限码做工具可见性过滤，方案可行（⚠️ 注意：**不在 `LoginUser` 上**，该字段不含权限码） |
| 0.4 | `/uploads/**` 静态映射 `permitAll`（审计 M4） | ✅ 已改为 `authenticated()`（V18 `uploads_auth`），登录时下发 `pams_token` Cookie 兜底 `<img>` 直链 | Agent 回复仍**禁止**给直链，但风险等级下降 |
| 0.5 | 新闻稿（News）无发布端点、"写完即终态" | ✅ 已补 `POST /api/news/{id}/publish`（DRAFT → PUBLISHED，记录发布日期） | 「公告/新闻稿一键生成 → 确认发布」闭环可完整落地 |
| 0.6 | `RosterImportService` 已实现但未接线 | ✅ 已接线：`POST /api/files/import`（`party:entry`） | 名册导入类自动化前置解除，Agent 只需做"上传前预检 + 结果解读" |
| 0.7 | 排班/考勤三张主表 `userId` 基本为空、靠 `personName` 字符串关联 | ✅ 部分解决：V20 `schedule_user_binding` + 新增 `SchedulePerson` 实体 | 「排班草稿生成」不再硬依赖人工补 `userId`；但考勤/素拓的历史姓名关联仍需 Agent 做同名消歧 |
| 0.8 | 公告已读只存前端 localStorage、发布不触发通知 | ✅ 已补：V19 `announcement_read` 表 + `AnnouncementPublishedEvent` 事件埋点 | 「通知触达率」「已读回执」类能力有真实数据源 |
| 0.9 | 排班草稿"自动生成"被列为高工作量、需 LLM | ⚠️ 已被**规则算法**实现：`ActivityScheduleService`（读 `course_schedule` 共同空闲一键生成）+ `POST /api/activity-schedules/generate`（`schedule:generate`） | 该项从"LLM 依赖项"降级为"Agent 薄包装"，是本方案**最大的可行性利好** |
| 0.10 | 系统无任何审计日志 | 🔶 局部有了：`AttendanceAudit` + `AttendanceAuditRepository` + `AttendanceAutoJob` | Agent 审计表可沿用同一设计范式，不是从零发明 |
| 0.11 | AI 集成零基础 | ✅ 仍为零：全仓无 openai/deepseek/ollama 依赖 | LLM 网关需新建（本方案第 3.6 节） |

**仍然存在的缺口（本期 Agent 的前置或并行项）**：

| # | 缺口 | 说明 | 处理建议 |
|---|---|---|---|
| G1 | content 模块（`News`/`Article`）仍用 `LEADER` 角色宏，未收敛到 `content:*` 权限码 | 与 S2「双轨权限收敛」的剩余部分一致 | Agent 侧先用 **LEADER 等价口径**放行，等 S2 扫到 content 时同步切换；不阻塞 |
| G2 | 服务端无 HTML 净化 | 前端有 DOMPurify，但策划书/推文正文是富文本 HTML，**生成类 Agent 会把 LLM 输出写入库** | 引入 Jsoup 白名单（与 wangEditor5 产出的标签集对齐），约 1 人日，**建议本期做**（**v1.8：已落地**——jsoup `HtmlSanitizer` 接入 4 个生成类写工具；人工编辑路径收口仍缺，见 §0.5.2） |
| G3 | 无 SSE 通道（但有 WebSocket） | 现有推送只有 WebSocket 用户队列（通知用），无 HTTP SSE | **一期用 WebSocket 推送离散过程事件**（D7 事件级流式，成本低）——**v1.8 核对：字符级流式最终也走 WebSocket `MESSAGE_DELTA` 落地，未引入 SSE / fetch 流** |
| G4 | `agent_*` 表不存在 | 会话/消息/任务/审计 | 本期批次 0 建表（**v1.8：已建**——V24 起，现共 9 张 agent 表，见 §0.5.2） |
| G5 | 材料库仅元数据检索，无正文索引 | 1660 份材料正文未索引 | 一期用结构化检索；二期用 **MySQL 8 内置 ngram 全文索引**（零新增组件，详见 3.7）（**v1.8：已落地**——知识库检索 V27/V28，见 §0.5.2） |
| G6 | 考勤/素拓历史数据仍靠姓名关联 | V20 只覆盖排班 | Agent 回答时对同名做二次确认；不做批量回填 |

> **结论**：调研文档的"阶段一先修 5 处权限缺口 → 再做基座"的串行前提**已不成立**。Agent 可以**并行启动**，唯一真正的硬前置是 G2（服务端净化，因为生成类能力离不开它）与批次 0 基座本身。

---

## 0.5 实施现状与使用须知（v1.8 新增｜2026-10-05 核对｜**编码前先读本节**）

### 0.5.1 一句话状态

本文的 D1–D11 设计**已进入实施并大面积落地**：基座（B0 / B0.5）与一期能力（B1–B3）已上线运行；检索增强（B4）由《[PAMS-灵思知识库问答方案-v1](PAMS-灵思知识库问答方案-v1.md)》接管并已落地；多 Agent 协作与通用化由《[PAMS-通用化与多Agent协作改造策划](PAMS-通用化与多Agent协作改造策划.md)》接管，批次 B/C 及协作内聚 C1–C3 已落地。**本文继续作为「基座契约」的唯一事实来源**：工具派发与权限（D3）、确认门禁（D2）、出网与脱敏（D4）、审计（D9）、解耦契约（D11）以本文为准；能力扩展以各自文档为准（见 0.5.3）。

### 0.5.2 当前实现一览（逐项核对至 2026-10-05 源码）

| 维度 | 现状 | 证据 / 备注 |
|---|---|---|
| 迁移链 | **V1–V36**（通用 `db/migration` + `migration-mysql` / `migration-h2` 方言目录） | Agent 相关：V24 基座、V25 seat 修复、V26 历史绑定、V32 多 Agent、V33 评估、**V34 租户唯一键、V35 `thinking_json`、V36 `thinking_notes_json`（V35 原为裸 `ALTER TABLE`，由 V36 补成判存 + `PREPARE` 幂等写法）** |
| Agent 工具 | **21 个（读 11 / 写 10）**；与 §3.5 规划（23）的偏差见 §4.3.1「v1.8 更新」表 | `module/agent/tool/`：23 个文件 = 21 工具 + `ActivityResolver` / `ToolArgs` 辅助 |
| Agent 表 | 基座 5 张（V24）+ `agent_definition` / `agent_run` / `agent_step`（V32）+ `agent_benchmark`（V33）；`agent_message` / `agent_task` 另有 V26 / V32 扩展列，**`agent_message` 再有 V35 / V36 两列（思维链推理与旁白）** | Flyway 迁移 |
| LLM 网关 | DeepSeek + Stub 已落地（无 Key / 失败自动降级）；**Ollama 未接入**；`providerFor(Sensitivity)` 分级路由**未实现**（`LlmConfig` 仅以注释保留设计意图） | 见 D5「v1.8 现状核对」 |
| 流式 | **事件级 + 字符级 + 推理流均已落地**：`MESSAGE_DELTA` 由 `AgentLlmCaller.DeltaBatch`（24 字符 / 60ms 双阈值）合并推送、`THINKING_DELTA`（模型推理增量）由 `ReasoningBatch`（48 字符 / 80ms）合并推送；`round` 语义覆盖重试（+1000）/ 降级（+2000） | 见 D7「v1.9 现状核对」 |
| 内容净化 | 已落地（jsoup `HtmlSanitizer`），接入 4 个生成类写工具（`draftPlanSections` / `draftArticleFromActivity` / `publishAnnouncement` / `sendNotification`）；**⚠️ 未收口到 Service 公共入口——人工编辑器保存路径仍只有前端 DOMPurify，D10「顺带覆盖人工写路径」未完成** | 建议见 D10：若要闭合，收口进 `PlanSectionService` / `ArticleService` / `AnnouncementService` 的保存入口 |
| 多 Agent | 已落地（V32/V33，`orchestration/`：Coordinator + Blackboard + P1–P4 专家执行）；协作内聚 C1–C3 已落地——协作成为灵思内联执行策略，原 `/agent/team` 页面与路由已删除（`LingsiTeamPanel` 内联呈现） | 见《协作内聚进灵思-修改计划》 |
| 通用化 / 多租户 | 阶段 1（先建列，V30/V31）+ A3 隔离收尾（V34）已落地；领域包 / 组织注册 / 主题令牌见《通用化与多Agent协作改造策划》 | 迁移链 |
| 前端 | `AgentHome` + **20 个 `Lingsi*` 组件**（含 `LingsiTeamPanel`；`LingsiFormCard.test.tsx` 为单测）+ 知识库页 + 评估页 | `pams-web/src/components/agent/` |
| Agent API | **18 个端点**：AgentController 11（`/api/agent/**`）+ AgentTeamController 4（`/api/agent/team/**`，协作 Run 接口）+ AgentBenchController 3（`/api/agent/bench/**`） | controller 包实测 |
| 限流 / 成本 | **Agent chat 端点未接限流**（平台已有 `LoginRateLimiter` / `OrgRegisterRateLimiter` 范式可复用）——已知缺口，扩容或对外开放前补齐 | 代码 |

### 0.5.3 使用须知（对编码 Agent / 开发者，强制）

1. **区分「规划」与「现状」**：§3.5 是原始**规划基线**；开工前以本节与 §4.3.1 现状表为准——**已落地能力不要重复实现**（清单见 0.5.2），**规划未实现项也不要当作遗漏**（多数在后续文档有明确安排，如 `checkPlanCompleteness` 在《通用化与多Agent协作改造策划》中仍标注「待实现」）。
2. **契约类改动必读**：动权限（D3）、出网（D4）、门禁（D2）、Service 复用方式（D1）之前，先读 §3.2 与 §4.3.1——那里是**已经踩过的坑**（六条真实缺陷 + v1.8 补记两条），不是理论风险。
3. **文档优先级（冲突裁决）**：① 基座契约（D1–D4、D11、§4.3.1）→ 本文；② 知识库 / RAG → 《知识库问答方案》（本文 D6 的检索议题已由其实际接管）；③ 多 Agent 协作 / 通用化 / 多租户 → 《通用化与多Agent协作改造策划》+《协作内聚修改计划》；④ 页面与组件形态 → 《灵思页面设计方案》及 UI v2 / v3 / v4。**较新文档优先；冲突消除后回填本文。**
4. **维护义务**：任何改变「工具数 / 表结构 / 门禁状态机 / 出网口径」的变更，合入时同步更新 §0.5 与 §4.3.1。

---

## 1. 设计目标与范围

### 1.1 目标

把 PAMS 从"数据录入 + 表单流转"的工具，升级为**能理解意图、能检索、能起草、能代办**的党务工作助手，同时**不引入新的运维组件、不破坏现有权限与安全基线**。

三条可度量的成功标准：

1. **提效可感**：文书起草（策划书/推文/新闻稿/公告）从"从零写 40 分钟"到"改 5 分钟"；批量操作（素拓加分/归档/导入）从"多页点选"到"一句话 + 一次确认"。
2. **零越权**：Agent 的全部读写以**当前登录用户身份**执行，越权用例 100% 被拒；受限字段不进入任何外部模型请求。
3. **离线可演示**：在无外网环境下（`StubProvider` / 本地 Ollama）仍能完整跑通"问答 + 一次文书生成 + 一次批量确认"的演示链路。

### 1.2 三条设计铁律

| 铁律 | 含义 | 落地点 |
|---|---|---|
| **① 复用现有接口与 Service，不自建数据通道** | 工具调用现有 Service；鉴权、数据范围、脱敏逻辑全部继承，**不产生第二套权限或第二套业务规则** | 3.2 D1 / D3 |
| **② 写操作先出"拟执行清单"，用户确认后才落库** | 生成类只落 `DRAFT`，不自动提交审核、不自动发布；批量类必须先预览、后执行 | 3.2 D2 / 3.4 |
| **③ 数据分级驱动"是否允许出网"** | 按 `normal / sensitive / restricted` 决定工具结果能否进入 LLM 上下文 | 3.2 D4 / 3.9 |

### 1.3 本期范围

- **做**：Agent 基座（编排/工具/网关/审计/确认门禁）、读类问答、文书起草、批量代办（含敏感模块的"严格脱敏版"）、离线兜底。
- **明确不做**（理由见 4.4）：向量数据库、独立 Agent 服务/微服务拆分、Agent 直接操作微信/企业微信、Agent 自动审批或自动发布、移动端。

---

## 2. 业务设计：从 40+ 机会点收敛到 3 个产品入口

调研文档按"模块 × 机会点"罗列了 40+ 项。**罗列机会 ≠ 可用的产品**。用户不会记住"12 个模块各有哪些自动化"，只会记住"我在哪儿能用到它"。因此本方案把机会点**按交互入口收敛为 3 个**。

| 入口 | 形态 | 交互位置 | 用户心智 | 承载的机会点 |
|---|---|---|---|---|
| **A. 党建小助** | chat | 全局悬浮球 / 右侧抽屉，任意页面可唤起 | "有事问它" | 全部查询问答类（活动/任务/材料/通知/排班/党务脱敏/素拓） |
| **B. 流程内 Copilot** | copilot | 具体表单/编辑器内的就地按钮（不做全局入口） | "这一步帮我填/帮我查" | 策划书章节起草、审核意见辅助、完整性检查、状态推进建议、材料类型建议、签到名单预检 |
| **C. 批量自动化** | automation | 列表页/详情页的动作按钮，或从 A 触发后落成"任务卡" | "帮我批量做，我确认" | 素拓批量加分、名单导入、材料归档补全、议程生成、排班生成、批量状态推进、催办 |

> **v1.8 命名现状**：入口 A 最终以「**灵思**」为产品名落地（页面 `/agent`、侧栏菜单、前端组件前缀 `Lingsi*`）；本节的"党建小助"为历史命名，其余入口名称与形态不变。

### 2.1 入口 A：党建小助（对话问答）

- **目标场景**（一期聚焦 5 类高频，均已有现成接口）：
  1. **我的待办**："最近有哪些策划书待我审核？""有几篇推文待审？"
  2. **活动与任务**："这个月有哪些进行中的活动？""XX 活动还有哪些任务逾期了？"
  3. **材料检索**："找一下第四十期培训班的策划书""这学期所有党日活动的签到表"
  4. **通知与进度**："XX 活动的进度到哪一步了？""上周的公告我看了没？"
  5. **排班与空闲**："下周三下午部门开会有空吗？""本周控烟值班谁主班？"
- **交互约束**：回答必须**给结论 + 给依据 + 给入口**（结构化卡片 + 跳转链接），不做长篇散文；数据超 5 条时给汇总 + "查看全部"深链。
- **口吻**：简洁、公文化，不使用感叹号与营销腔（与党建办公场景匹配）。

### 2.2 入口 B：流程内 Copilot（就地辅助）

| 位置 | 能力 | 关键约束 |
|---|---|---|
| 策划书编辑器 | 空章节 AI 起草、预算表生成、12 章完整性/冲突检查 | 只写 `DRAFT`；生成后**不自动提交审核** |
| 审核弹窗 | 按完整性/预算空/时间冲突**预填**审核意见 | 意见只预填，审核动作仍由人点击 |
| 活动详情页 | 状态推进条件提示（"还差什么才能推进到下一状态"） | 仅提示，不自动推进（六态只能相邻推进） |
| 推文/新闻稿编辑页 | 摘要、标题候选、润色、续写 | 正文 HTML 必须服务端净化后再入库 |
| 材料上传弹窗 | 按文件名/描述建议材料类型、标签、关联活动 | 建议 + 现有 `bizTypeOf` 规则双保险 |
| 签到名单上传 | 按表头建议核验字段与必填项 | 必须与 `scanSignin` 硬编码字段名一致，防止"姓名"缺失导致扫码 400 |

### 2.3 入口 C：批量自动化（"拟执行清单"）

统一交互范式（复用已被 `ActivityCreditModal` 五步弹窗验证过的模式）：

```
① 意图解析 → ② 后端只读匹配（dry-run） → ③ 拟执行清单卡片
   （N 条将执行 / M 条跳过及原因 / 明细可展开）
→ ④ 用户点"确认执行"（携带确认令牌） → ⑤ 异步执行 → WebSocket 推送结果卡
```

**强制要求**：
- 清单必须呈现 **skipped 明细及原因**（如素拓加分里的"同名同学号将被跳过"），不允许只报成功数；
- 确认令牌一次性、防重放（复用 `agent_task` 状态机乐观锁，见 3.2 D2）；
- 可撤销的必须可撤销（素拓加分按 `batchId` 撤回已有能力）；
- **不得调用**扫码公开接口 `POST /api/signins/scan`（免登录，Agent 调用等于绕过鉴权）。

---

## 3. 技术方案

### 3.1 总体架构

**不新建独立服务**，Agent 作为后端的一个新模块 `com.pams.module.agent` 内嵌实现；前端新增悬浮抽屉 + 就地按钮两个入口。

```
┌─────────────────────────────── 前端 pams-web ───────────────────────────────┐
│  AgentDrawer（全局抽屉）      CopilotButton（就地）      ActionConfirmCard（确认卡）│
│  ├ 会话列表/流式消息渲染      ├ 编辑器/弹窗内注入        ├ 拟执行清单（可展开明细）  │
│  └ 结构化结果卡（表格/链接）  └ 结果回填表单             └ 确认令牌提交            │
└──────────────────────────────────┬──────────────────────────────────────────┘
                       POST /api/agent/**（JWT Bearer，与现有鉴权同口径）
┌──────────────────────────────────▼──────────────────────────────────────────┐
│                      后端 com.pams.module.agent（新增）                       │
│  AgentController ── AgentOrchestrator ── ToolRegistry ── PolicyGuard          │
│        │                  │                    │                │            │
│        │             ConversationStore    AgentTool 列表   ToolResultSanitizer │
│        │             （会话/上下文）      （按权限过滤）    （脱敏/出网裁决）    │
│        └────────── LlmProvider（可插拔网关）                                   │
│                     ├ DeepSeekProvider   （OpenAI 兼容，支持 function calling）│
│                     ├ OllamaProvider     （本地兜底）                         │
│                     └ StubProvider       （离线规则，保证无网可演示/可测）      │
├──────────────────────── 能力层（直接调用现有 Service） ─────────────────────────┤
│  ActivityService · PlanService · AgendaService · TaskService · SigninService  │
│  SigninRosterService · CreditService · MaterialService · ArticleService       │
│  NewsService · AnnouncementService · NotificationService · ScheduleService     │
│  ActivityScheduleService · AttendanceService · CourseScheduleService          │
│  PartyMemberService（已内建脱敏） · PartyRecordService · RosterImportService   │
├──────────────────────────── 数据层（新增 5 表） ───────────────────────────────┤
│  agent_conversation · agent_message · agent_task · agent_tool_call · agent_audit_log │
└───────────────────────────────────────────────────────────────────────────────┘
```

**关键：能力层是"现有 Service"，不是现有 HTTP 接口。** Agent 不发起自调用 HTTP（避免二次鉴权、端口依赖、序列化开销），而是注入 Service 直接调用。这带来一个必须处理的问题，见 D1 与 D3。

### 3.2 关键设计决策

#### D1　工具层复用 Service，并显式处理 `SecurityContext` 传递

- **问题**：`SecurityContextHolder` 默认基于 `ThreadLocal`，**不跨线程传递**。Agent 若在异步线程池中执行工具，`LoginUser` 会丢失；而不少 Service 内部会从 `SecurityContextHolder` 取当前用户。
- **方案**：
  - **同步路径优先**：对话问答与 dry-run 全部同步执行（毫秒~秒级），`SecurityContext` 天然可用。
  - **异步路径显式传递**：长任务（文书生成、批量执行）用 `DelegatingSecurityContextExecutor` 包装专属线程池（Spring Security 现成类），或把 `LoginUser` 作为 `ToolContext` 入参显式下传。
- **取舍**：不引入 `@Async` 默认线程池（会丢上下文且难观测），为 Agent 建独立、命名的线程池（`agentExecutor`），便于限流与指标采集。

#### D2　写操作 = dry-run + 结构化确认，状态机落在 `agent_task`

- **为什么不用"LLM 复述一遍让用户说'确认'"**：LLM 无法可靠地"记住"影响范围，且用户在对话里说"好的"语义模糊，无法作为审计依据。
- **方案**：写类工具被拆成**读阶段**与**写阶段**两个方法：

```
POST /api/agent/chat
   └→ 命中写类意图 → 执行工具.read(...)  （只查询与匹配，绝不写库）
        └→ 生成 AgentActionPlan{摘要, 明细[], skipped[], 影响条数}
             └→ 落 agent_task(status = PENDING_CONFIRM)，返回 planId
                  ↓ 前端渲染确认卡（明细可展开）
POST /api/agent/tasks/{id}/confirm   （携带一次性 confirmToken）
   └→ 乐观锁：UPDATE agent_task SET status='RUNNING' WHERE id=? AND status='PENDING_CONFIRM'
        └→ 影响行数 = 0 则判定重复提交，直接返回幂等结果
             └→ 执行工具.write(...)  →  SUCCESS / FAILED / PARTIAL
```

- **dry-run 能力从哪来**：**不需要给每个 Service 加 preview 方法**。工具自己把"查询匹配"（Repository/Service 现有只读方法）与"写库"分离即可。个别场景已有现成预览能力（素拓加分的 `skipped` 明细、签到名单的匹配结果）直接复用。
- **益处**：确认门禁是**后端强制的**，不依赖前端自觉；`agent_task` 天然成为审计与"任务卡"的数据源。

#### D3　权限不做第二套：`@PreAuthorize` + 工具可见性 + 显式断言

这是本方案**最容易踩的坑**，必须说清：

- **坑**：现有权限注解 99% 挂在 **Controller** 上，`Service` 方法本身**不带** `@PreAuthorize`。Agent 直接调 Service ⇒ **绕过全部方法级鉴权**。若此时 Agent 只靠"LLM 自己判断该不该做"，等于给越权开了自动化通道。
- **三层防护**：
  1. **工具声明所需权限码**：每个 `AgentTool` 声明 `requiredPermissions`（如 `quality:add`、`party:entry`、`schedule:manage`）。工具粒度与权限码**一一对应**，不允许"一个工具混用多个权限域"。
  2. **派发前断言**：`ToolRegistry.dispatch(tool, ctx)` 在调用前用 **`Authentication.getAuthorities()`** 校验（**不是** `ctx.loginUser.permissions` —— `LoginUser` 不含该字段，见下方修正说明），不满足直接抛 `AccessDeniedException`（返回 403），**且该工具不会出现在给 LLM 的 tools 列表中**（不可见 ⇒ 不可诱导）。
  3. **测试兜底**：为每个写类工具写"低权限角色调用必失败"的集成测试（对应 PRD-v5 的 Q3 断言思路）。
- **补充**：`content` 模块当前仍用 `LEADER` 角色宏（G1），Agent 侧先以 **LEADER 等价口径**声明权限，待 S2 收敛到 `content:*` 时同步切换，避免二次改动。
- **⚠️ 文档修正**：本节第 2 层防护原文写的是 `ctx.loginUser.permissions`，这是**错的**。已核实 `LoginUser` **不含权限码**（只有 `id/username/realName/roleCode/roleLevel/deptId/deptName`），权限码是 `JwtAuthenticationFilter` 查 `role_permission` 后注入了 `Authentication.getAuthorities()`。因此断言必须基于 **`Authentication.getAuthorities()`**（或按 `roleCode` 查 `PermissionService`），**不能读 `LoginUser.permissions`**——按原文实现会编译不过或恒为空集，导致**所有工具都"不可见"**（表现是"Agent 什么都答不了"，而不是越权，方向相反但同样是故障）。

**关于 content 模块权限码是否本期收敛（G1）——建议"不收敛"，理由如下**：

| 方案 | 说明 | 判断 |
|---|---|---|
| **本期维持 `LEADER` 等价口径**（推荐） | Agent 工具声明 `hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')`，与 `ActivityController.LEADER` 常量同源 | ✅ **采用**。理由见下 |
| 本期收敛到 `content:manage` | 需改 `NewsController`（4 处）+ `ArticleController`（2 处）+ 新增权限码 + V24 种子数据 + 回归测试 | ❌ 本期不做 |

**为什么不收敛**：① **这是 S2（双轨权限收敛）的职责范围**，Agent 顺手改会与 S2 的实际收敛方案撞车——S2 可能定的码不是 `content:manage`（比如拆成 `content:view`/`content:publish`），届时 Agent 侧要改两遍；② Agent 用 `LEADER` 等价口径**功能上完全够用**（`ArticleController` 现有口径就含 `MEDIA_LEADER` + `TEACHER/DIRECTOR`，Agent 照抄即可）；③ 收敛本身**不影响 Agent 的正确性**，只影响"权限码写法"——而权限码写法在 D11 里被归类为**第三类变更（必须登记）**，也就是说届时**本来就有登记机制兜住**，不需要现在预防性改动。

> **一句话建议：本期维持 `LEADER` 等价口径，把 content 权限收敛留给 S2 统一处理，并在契约登记清单里预留一条"content 模块权限码收敛"的待登记项。**

#### D4　数据分级 + PolicyGuard：决定"什么能出网"

| 级别 | 数据 | 是否允许进入外部 LLM 上下文 | 处理方式 |
|---|---|---|---|
| `normal` | 活动/任务/推文/材料元数据/群聊档案/通知/排班 | ✅ 允许 | 直接进入 |
| `sensitive` | 签到名单（姓名/学号/手机号）、党务 PII（身份证/电话/住址）、素拓记录、缺勤名单 | 🔶 脱敏后允许 | **服务端**字段级遮罩（不依赖 LLM 自觉），仅保留聚合或必要字段 |
| `restricted` | 函调家系信息（父母/社会关系人姓名 + 支部 + 寄送地址） | ❌ **禁止** | 工具直接拒绝或返回"仅可在页面查看"，只给深链；结果不经 LLM，由模板渲染固定话术 |

- **PolicyGuard 位置**：横切在"工具结果 → 组装 prompt"之间，是**唯一**的出网闸口。
- **日志**：`sensitive` / `restricted` 工具的入参与出参**不落明文**到 `agent_message`，仅落 `hash + 条数摘要`。
- **理由**：会话记录本身是新的数据面；如果它把 PII 原样存下来，等于给系统新增了一个未受保护的泄露点。

#### D5　LLM 网关：可插拔 + 三级降级

- **接口**（最小抽象，只暴露必要能力）：

```java
public interface LlmProvider {
    String name();                          // deepseek / ollama / stub
    boolean supportsToolCalling();          // 是否原生支持 function calling
    ChatResponse chat(ChatRequest request); // messages + tools + 采样参数
}
```

- **实现**：`DeepSeekProvider`（OpenAI 兼容 `/chat/completions`，原生 tools）、`OllamaProvider`（`/api/chat`，视模型决定是否支持 tools）、`StubProvider`（离线规则：关键词 → 固定工具链，用于无网演示与单测）。
- **三级降级**：`配置的 provider 失败/超时 → 重试 1 次 → 降级到 StubProvider 的规则回复`，并向前端明示"当前为规则模式"。**离线可演示是硬指标**，这条必须实现，不能只在文档里写。
- **配置项**（与现有"密钥走环境变量"的治理口径一致）：`pams.agent.provider`、`pams.agent.base-url`、`pams.agent.model`、`pams.agent.timeout-ms`、`pams.agent.max-tokens`，密钥 `PAMS_AGENT_API_KEY`。
- **成本控制**：`max-tokens` 上限、上下文只保留 system + 最近 K 轮 + 工具结果摘要、同一会话内工具结果去重。

**供应商选型详述（2026-09-13 补充）**：

已核实 `pams-backend/pom.xml` **当前无任何 AI/LLM SDK 依赖**（无 openai / deepseek / ollama 客户端），因此"选哪家"在代码层面是**零迁移成本**的——三个 provider 都走同一个 `LlmProvider` 接口。

| 方案 | 优势 | 劣势 | 建议 |
|---|---|---|---|
| **DeepSeek API**（推荐） | ① **OpenAI 兼容协议**，一个 `WebClient` 打通，无需官方 SDK；② 中文能力强、工具调用（function calling）支持成熟；③ 价格远低于 GPT 系；④ 国内直连，无网络合规风险 | 需外网 + 需 Key + 数据要出本机构 | **主选**。党务数据虽非绝密，但"数据出网"这件事应事前被知晓而非默认发生 |
| 其他国产 API（通义/文心/豆包等） | 同样国内直连；部分有免费额度 | 协议各异，需各写适配层；工具调用成熟度不一 | 备选。**除非有既定的云厂商合作，否则不建议分散精力** |
| 纯本地 Ollama | **数据完全不出网**（党务场景的最强卖点）；零 API 成本；离线可跑 | 需 GPU 或至少 16GB 内存的机器；7B 级模型的**工具调用可靠性明显弱于云端**，Agent 对工具调用准确性敏感 | **作为"敏感数据走廊"而非主通道**：见下方分级建议 |

**⚠️ 我的核心建议：不要把"选一个供应商"当成互斥题。**

`LlmProvider` 已经是可插拔的，这带来一个原方案没写透的机会——**按数据分级路由到不同 provider**：

```
normal 数据    → DeepSeek API（体验优先，便宜、能力强）
sensitive 数据 → 本地 Ollama（若已部署）或"仅摘要出网"（脱敏后再交给云端）
restricted     → 根本不出网（PolicyGuard 拦截，已是 D4 的既定行为）
```

**为什么这比"选一家"更好**：这个项目的敏感数据（函调家系、纪律信息、党员发展进度）占比不高但是**性质敏感**。如果统一走云端，等于把"党务敏感数据出网"常态化；如果统一走本地，则牺牲了绝大多数普通问答的体验。**分级路由让两条路各得其所**，而实现成本只是 `AgentOrchestrator` 里多一个 `providerFor(Sensitivity)` 的选择（约 20 行）。

**分期落地**：
- **一期**：只接 `DeepSeekProvider` + `StubProvider`。`sensitive` 工具体**先不出网**——按"仅摘要出网"处理（工具结果在服务端摘要/脱敏后再进 LLM 上下文），这与 D4 的既定设计一致，**不需要额外开发**。
- **二期**：若演示环境或实际部署有可用算力，再补 `OllamaProvider` 承接 `sensitive` 走廊。
- **若无 API Key**：`StubProvider` 单跑，体验打折但**流程完整可演示**。这也说明 **B0 的技术选型不依赖 Key**——Key 是配置项，不是阻塞项。

> **v1.8 现状核对**：一期已落地（`LlmConfig`：DeepSeek + 无 Key / 失败降级 Stub）；**`providerFor(Sensitivity)` 分级路由未实现**——代码中仅注释保留设计意图，`sensitive` 实际走 `PolicyGuard` 遮罩后出网（D4），`restricted` 拦截不变；Ollama 未接入，仍为二期可选项。

> **决策建议：DeepSeek API 为主 + Stub 兜底（一期）；`sensitive` 走"仅摘要出网"；`Ollama` 列为二期可选项，不作为一期阻塞。**

#### D6　一期不做向量检索（本方案最重要的取舍）

- **论证**：
  1. 检索类需求**九成是结构化条件**（哪个活动、哪类材料、哪段时间、哪个部门），`MaterialController` 现有 `keyword / bizType / activityId / deptId` 四维过滤已能覆盖；
  2. 材料库 1660 份文件**正文尚未抽取**，向量化的前置是"文档解析管线"（PDF/扫描件无文本层、旧版 .doc/.ppt 需额外库），成本高且收益不确定；
  3. 引入向量库意味着**新增一个运维组件**（pgvector 需换库、Milvus/Qdrant 需部署），与当前"单体 + MySQL + docker-compose"的轻量部署模型冲突。
- **务实路线（三级递进，按需启动）**：
  | 阶段 | 方案 | 新增组件 | 覆盖场景 |
  |---|---|---|---|
  | 一期 | 结构化检索工具（元数据 + `keyword LIKE`） | 无 | 找某活动/某类型/某时段的材料、稿件 |
  | 二期 | **MySQL 8 内置 ngram 全文解析器** + `FULLTEXT` 索引 + `MATCH ... AGAINST` | **无**（内置能力） | 材料标题/描述、稿件标题/摘要的中文关键词检索 |
  | 三期 | 仅当上述不满足时，再评估 embedding | 视方案 | 语义近似检索 |
**二期启动条件与实现细节（2026-09-13 补充）**：

| 维度 | 内容 |
|---|---|
| **何时启动** | **不按时间启动，按触发条件启动**：当出现"用户抱怨按关键词搜不到已有材料"的真实反馈（而非猜测）时启动。**一期先不做，但把接口留好**——`searchMaterials` 工具内部把"检索策略"封成一个方法，二期换实现不改工具签名 |
| **索引 DDL 要点** | `ALTER TABLE material ADD FULLTEXT INDEX ft_material_name_desc (name, description) WITH PARSER ngram;`（MySQL 8 内置 `ngram` 解析器，中文分词无需外部组件）。**注意**：ngram 默认 `ngram_token_size=2`，即按二元组切分，对"素拓""入党"这类双字词效果好，对单字查询效果差——**这是要写在文档里的已知限制** |
| **查询写法** | `MATCH(name, description) AGAINST (? IN BOOLEAN MODE)`。**必须用 `IN BOOLEAN MODE`**：自然语言模式有 50% 阈值规则（词出现在超过一半行里就被忽略），在 1660 份材料的库上会直接导致常见词搜不到 |
| **降级策略** | `FULLTEXT` 查询异常或返回空 → 回退到现有 `LIKE` 逻辑（现有 `MaterialService.page` 的 `cb.like` 实现保留为 fallback）。**不要让新索引成为单点故障** |
| **适用范围** | 仅 `material`（材料库 1660 份）与 `article`（稿件）的**标题+描述/摘要**字段。**不要**对正文做全文索引——正文尚未抽取（见下方论证第 2 点） |
| **工作量** | **约 0.5–1 人日**（DDL + 查询改写 + fallback + 单测）。比预估的更低，因为 MySQL 8 自带 ngram，唯一要做的是**写对 DDL 与 BOOLEAN MODE** |
| **不做什么** | **不做索引同步机制**（材料表无批量写入场景，MySQL 自动维护 FULLTEXT 索引）；**不做搜索排序调优**（相关性排序用默认 BM25-like 权重即可，党务场景不需要） |

> **建议：维持"二期 + 按触发条件启动"，但把上面的 DDL 与 `BOOLEAN MODE` 细节写进文档**——这两点是将来真正动手时最容易踩的坑（`ngram_token_size` 限制、自然语言模式的 50% 阈值），现在记下来比届时空查文档便宜。

- **结论**：二期用 ngram FULLTEXT 是"零新增组件"的中文检索方案，**性价比远高于向量库**，是本期推荐的最终形态。

#### D7　异步与流式：事件级流式本期做，字符级流式二期做；不引入 MQ/Redis

> **2026-09-13 收窄表述**（原为"一期先做非流式"）：原表述把"思考/工具进度可见"与"答复逐字吐出"混为一谈，导致一期会在等待期给用户一个静止的三点动画、无法判断"在动还是卡死"。现拆为两类：
>
> | 类型 | 内容 | 粒度 | 本期 | 理由 |
> |---|---|---|---|---|
> | **事件级流式** | 思考摘要、工具开始/结束/耗时、计划就绪、答复完成 | 离散事件 | **✅ 做** | 天然事件模型，与现有 WebSocket 完全契合；后端本就要产生这些事件，多推一次成本极低；是"过程透明"的核心载体 |
> | **字符级流式** | 答复逐 token 吐出 | 连续 token | **✅ 已落地**（v1.8 核对；原计划二期） | 实际经 WebSocket `MESSAGE_DELTA` 承载（`DeltaBatch` 24 字符 / 60ms 合并推送，`round` 语义覆盖重试 / 降级）——未引入 `fetch + ReadableStream`，"继续延后"的原判断已不适用 |
>
> **代价评估**：事件级流式需新增一个 `/user/queue/agent/stream` 用户定向队列与 `AgentStreamEvent` 契约（`{streamId, seq, type, payload, ts}`，`seq` 单调递增供前端去重排序）。`AgentOrchestrator` 每进入一个阶段即 `simpMessagingTemplate.convertAndSendToUser(...)`。**预估增量 0.5–1 人日**（含断连补拉端点）。若一期连这个也不做，则需要退化为轮询——但轮询会让"实时看到它在动"的效果打折，不建议。
>
> **降级兜底**：若对话请求期间无法维持用户定向订阅，则 `POST /api/agent/chat` 返回 `streamId`，前端轮询 `GET /api/agent/streams/{streamId}?since={lastSeq}`（500ms）。**两种通道共用同一事件契约**，前端 UI 代码不变。
>
> **前端承载**：见 `PAMS-灵思页面设计方案.md` §3.5.3（时间轴）与 §7.1（过程区双形态）。

- **长任务**（文书生成、批量执行）：`agent_task` 表 + 独立线程池；完成后**复用现有 WebSocket 用户定向队列**推送 `AGENT_TASK_DONE` → 前端已有 `useWebSocket` 基建，**加一个订阅即可**。
- **字符级流式输出（二期）**：届时推荐 `fetch + ReadableStream`，而不是 `EventSource`——因为 `EventSource` 无法携带自定义请求头，只能把 token 放 query（会进日志/历史），而现有体系是 `Authorization: Bearer`。**前端事件契约中已预留 `MESSAGE_DELTA` 类型**，二期只需后端多发一类事件、前端加一个 `case`，无需破坏性改动。
- **为什么不上 MQ / Redis**：单实例部署、并发量小（几十人）、任务体量小；引入后只增加运维面与故障点。**明确结论：不是做不到，而是当前规模不必要。**（此结论不受本次收窄影响）

- **⚠️ 单实例是这套推送方案的生效前提（必须记录的边界）**：
  `WebSocketConfig` 用的是 **`enableSimpleBroker("/queue", "/topic")`——内存 broker**，不对接外部 broker。这意味着：
  - 用户订阅关系只存在于**当前 JVM**；
  - 若将来为高可用起**两个实例**，用户在 A 实例建立连接、推送从 B 实例发出，**`/user/queue/**` 会静默失效**（不报错、不抛异常，只是收不到）；
  - **触发条件**：横向扩容、或上负载均衡。
  - **届时的解法**（择一，按需评估）：① 启用 STOMP broker relay（RabbitMQ/ActiveMQ）；② 改用 Redis pub/sub 做跨实例广播；③ Nginx 配置会话粘性（**最省事，但只是缓解，实例重启期间仍会丢**）。
  - **当前动作**：不加任何代码，**只在文档里记下这条边界**——避免将来扩容时"Agent 推送莫名不工作"成为无人能解释的悬案。这一条同时也是 D7"不引 Redis"结论的**适用前提**，两者必须一起读。

> **v1.8 现状核对**：**字符级流式已落地**（超出原"二期"安排）——`AgentLlmCaller` 以 `DeltaBatch`（24 字符 / 60ms 双阈值）合并推送 `MESSAGE_DELTA`，`round` 语义覆盖重试（+1000）与降级（+2000）；前端 `stores/agent.ts` / `api/agent.ts` 已消费。本节"字符级流式二期"的表述作废（设计推导保留为历史）。
>
> **v1.9 现状核对（2026-10-12，思维链）**：**模型推理原文也接进来了**，与 v1.8 时相比多了三件事：
> 1. **`THINKING_DELTA` 从"写了常量但没人发"变成真的在发**——`DeepSeekProvider` 早就把 `delta.reasoning_content` 交给 `onReasoning` 回调（三参 `chatStream` 重载），但调用方 `AgentLlmCaller` 之前只传了正文回调，**推理片段被整段丢掉**；现由 `ReasoningBatch`（48 字符 / 80ms，与 `DeltaBatch` 同款双阈值）合并推送，并用同一个 `round` 语义（重试 +1000、降级 +2000，失败即 `discard` + `ThinkingTrace.drop`）。
> 2. **推理与旁白分列落库**：`thinking_json`（`[{round,text,startedAt,endedAt,durationMs}]`，V35）、`thinking_notes_json`（`[{note,ts,durationMs?}]`，V36），**仅在 `normal` 档写入**；`ThinkingTrace` 有界（LRU 200 流 / 每流 40 段 / 每段 8000 字），收官后取快照。
> 3. **工具入参可下发**：`PolicyGuard.egressArgs(sensitivity, sanitizedArgsJson)`——`normal` 给**遮罩后的明文**（`args_json` 写入时已过 `sanitizeArgs`）、`sensitive`/`restricted` 给 `null`。**闸门放读侧是刻意的**：`args_json` 是审计口径（D9），展示层收紧不该反向改审计数据。
>
> **原记的"两条待产品拍板"（v1.9 补记：均已处理，原判断有误）**：
> - ~~① `sensitive` 档目前是"整列不落"，而决策 6 / D4 的口径是"仅存摘要（截断）"——需拍板后统一注释与实现~~ → **不是待拍板项**：`Sensitivity` 的 javadoc 与决策 6 / D4 都已写明"sensitive 会话中仅存摘要"，属**实现没按已定策略走**。已由 `ConversationStore.forThinkingStorage()` 统一为与正文同一套闸门（normal 全量 / sensitive **在 JSON 内部**截断成摘要 / restricted 不落）。**注意"在 JSON 内部截断"不是实现细节**：这两列是 MySQL 的 JSON 列，直接截 JSON 文本会得到非法 JSON 被数据库拒绝且只在日志报错。
> - ~~② `replySensitivity` 恒为 `NORMAL` 导致留存闸门不触发~~ → **已修**。原代码 `pendingPlan != null ? NORMAL : NORMAL` 两个分支同值，等于策略从未生效；现为本轮**已执行**工具里最严的一档（`Sensitivity.max`）。
> - **仍有真实代价（可一行回退）**：仅 3 个工具是敏感档（`CreateMemberTool` / `QueryAttendanceTool` / `QueryPartyMemberProgressTool`），涉及它们的轮次答复在历史里从全量变摘要（`summarize(reply, 100)`）。若更看重"历史看全答案"，把 `AgentOrchestrator.replySensitivity` 改回 `Sensitivity.NORMAL`。
>
> **⚠️ 已修正的前置条件（v1.9 补记，同日）**：原写「`reasoning_content` 是 reasoner 系列独有字段，而 `application.yml` 是 `deepseek-chat`」——**这两句都已过时，且当时的结论低估了严重性**。核对官方文档（`/guides/thinking_mode/`、`/api/create-chat-completion`）后的事实是：
> 1. **思考模式已从"独立模型"变成请求参数**：`thinking:{type:enabled|disabled}`（**默认 enabled**）+ `reasoning_effort:none|low|high|max`（默认 high）。所以"是不是 reasoner 模型"这个问法本身作废；**思考模式支持工具调用**，原先"reasoner 不支持 function calling、得按档位分模型"的顾虑不成立。
> 2. **`deepseek-chat` / `deepseek-reasoner` 已不在官方 `model` 取值列表里**（现只有 `deepseek-flash` / `deepseek-v4-pro`）。**⚠️ 同日真 Key 实测更正**：旧 id 目前**仍返回 200** —— 服务端把它**静默别名**到 `deepseek-flash`（响应里的 `model` 字段被改写）。因此"无效 model → 每次 400 → 重试 → 降级 stub"这条**不成立**：模型名既不是"一直在规则模式"的病因、也不是解药，它只影响日志/计费里显示的模型名是否与实际一致。**"一直在规则模式"的真因已定位**：`PAMS_AGENT_API_KEY` 不在 JVM 环境变量里（Spring Boot 不读 `.env`，`application.yml` 是 `${PAMS_AGENT_API_KEY:}`）→ `LlmConfig` 直接返回 stub。
> 3. **官方契约要求回传 `reasoning_content`**：请求携带 `tools` 时，后续须完整回传历史 assistant 的推理原文，否则 400（思考模式下 `tool_choice` 也不支持 `required` / 指定具体 tool）。工具循环已按此回灌。**✅ 已自行复现（2026-10-12，JVM 直连真实 API）**：第一轮带 `tools` + thinking → 200 且返回推理；第二轮带 `tools`、回灌该轮 assistant 的 `tool_calls` 但**不带** `reasoning_content` → **HTTP 400**：`The reasoning_content in the thinking mode must be passed back to the API.`（把 `thinking` 关掉则不触发）。工具循环第二轮正好命中该组合，**回灌是必需**。**⚠️ 勘误**：此前一次探测得出"该约束未强制"的相反结论、并据此把本条与相关注释改软 —— 原因是那次没有构造出"带 tools + 历史 assistant 带 tool_calls 且产生过推理"这个组合（单轮或不带 tools 不会触发）。**不要据此删掉回灌。**
>
> **已落地的修法**：`AgentProperties`/`application.yml` 改 `model: deepseek-flash` + `thinking: true` + `reasoning-effort: high` + `max-tokens` 2048→**8192**（思考模式下推理与正文**共享** `max_tokens`，给太小会 `finish_reason=length` 把正文截断）；`LlmProvider.ChatMessage/ChatResponse` 增 `reasoningContent`（各带 4 参兼容构造器，既有构造点零改动）；`DeepSeekProvider` 请求体下发 `thinking`/`reasoning_effort` 并在序列化 assistant 时带上 `reasoning_content`；`AgentOrchestrator` 工具循环回灌时带上推理原文。**顺带修掉一个既存缺陷**：非流式路径 `chat()` 之前用 `String` 接收响应，而真实 API 的 `application/json` 不带 charset，Spring 按 ISO-8859-1 解码 → **中文答复全乱码且不报错**；现改为取 `byte[]` 后按 UTF-8 解码（JSON 按 RFC 8259 即 UTF-8）。
>
> **真机验证已完成（同日，JVM 直连真实 API）**：`GET /models` → 200，Key 可见模型只有 `deepseek-flash` 与 `deepseek-v4-pro`；`thinking:{enabled}` + `reasoning_effort:high` → 200 且**返回 `reasoning_content`**（`reasoning_tokens=29`）；后端自身链路（`DeepSeekProvider` 非流式+工具+thinking、工具循环第二轮、流式 52 片 / 204 字推理）**全部通过**。**方法记录**：本机 pwsh 的 .NET/curl 的 TLS 被沙箱拦（`schannel: SEC_E_NO_CREDENTIALS`），但 **JVM 的 TLS 通** —— 后端用的就是它，所以"用 Java 直连真实 API"是本沙箱唯一可行的真机验证路径（此前这里记的"打不了真实 API"是错的）。
>
> **同日再补记：读侧五处静默吞异常 + 测试/生产行为分叉（本次回归测试挖出来的）**。写回归测试时发现 `ConversationStore.toVo()` 读 JSON 列用的是 `mapper.readValue(...)` 且外面套 `catch (Exception ignored) { vo.put(x, List.of()) }`，**五列全一样**（`tool_calls` / `refs_json` / `thinking_json` / `thinking_notes_json` / `citations_json`）。而 H2（test profile）把 JSON 列当**普通字符串**存，读回是 `"[{\"round\":0}]"` 这种带引号转义的字符串 → `readValue` 解析失败 → 被吞掉 → **历史回放静默返回空数组**。生产是 MySQL（解析成 JSON 文档，读回即原文）所以线上看着正常，但后果是**验收点"刷新后仍能看到思维链 / 工具行 / 引用"在测试环境永远测不出来**——测试与生产行为不一致，是典型的"假绿"，而且线上一旦出现同形态数据也会无声丢内容。
> **修法**：新增 `ConversationStore.readJsonList(json, what)` 统一读侧——先按原样解析，**结果是字符串就再解一层**，两次都失败则 `log.warn` 后回落空列表（保留"一行坏数据不炸整个历史列表"的容错，但不再无声）。五处调用点全部收口。
> **顺带记两条环境事实（供后续解读测试红绿）**：① 本机沙箱里 **Mockito 无法初始化**（inline mock maker 不能 self-attach），**仓库既有 Mockito 测试在该环境下同样全红**，与代码无关；② **H2 不校验 JSON 合法性**（当字符串照收），因此"写非法 JSON 会被数据库拒绝"这条 MySQL 性质在 H2 上无法验证，回归测试改用"产物必须能被 Jackson 解析"守"在 JSON 内部截断"那道闸。

#### D8　会话与上下文

- 表：`agent_conversation`（会话）、`agent_message`（消息，含 `role / content / toolCalls(JSON)`）。
- 上下文策略：`system prompt + 最近 K 轮 + 工具结果摘要`；超长时对早期轮次做 LLM 摘要（**摘要前先过 PolicyGuard**，避免把敏感内容"压缩"进摘要）。
- 敏感消息不落明文（见 D4）。
- 会话归属**严格绑定 `userId`**，他人会话不可见（越权读会话是常见漏洞，需在 Controller 层用 `@AuthenticationPrincipal` 强校验）。

#### D9　审计

- 表 `agent_audit_log`：`userId / conversationId / taskId / toolName / argsSanitized / sideEffect(READ|WRITE) / confirmedBy / resultStatus / durationMs / createdAt`。
- 设计范式**沿用现有 `AttendanceAudit`**（不是在项目里另创一套）。
- 附带收益：系统目前**"读操作无审计"**，Agent 恰好是补齐这一缺口的契机（谁、何时、通过什么问法读取了哪些数据）。

#### D10　内容安全

**服务端 HTML 净化（G2）——建议本期做，且这是唯一真正的硬前置**

已核实事实：`pams-backend/pom.xml` **无任何 HTML 净化库**；前端有 `dompurify@^3.4.13` + `@wangeditor/editor@^5.1.23`（富文本编辑器）。

| 维度 | 结论 |
|---|---|
| 是否本期做 | **必须做**。不做则"文书生成"这个能力不能上线——Agent 会把 LLM 输出**写入数据库**（策划书章节 / 推文正文），而写库内容不再经过任何前端防线 |
| 为什么不能只靠前端 DOMPurify | 前端净化只保护"自己的渲染路径"。API 可被直接调用（脚本、其他客户端、将来的移动端），入库的脏 HTML 会在**所有**读取路径上生效。**入库前的服务端净化是不可替代的那一道** |
| 库选择 | **Jsoup**（`org.jsoup:jsoup`，约 450KB，无传递依赖）。理由：`Safelist` 白名单 API 成熟、单 jar、与 Spring Boot 无集成成本。**不建议**自研正则净化（HTML 解析不能用正则做，这是明确的反模式） |
| 白名单口径 | 与 **wangEditor5 产出的标签集对齐**：`p/br/strong/b/em/i/u/s/h1-h5/ul/ol/li/blockquote/pre/code/span/div/table/thead/tbody/tr/td/th/a/img/hr`；属性仅保留 `href/src/alt/title/colspan/rowspan/style`（`style` 需再限定到少量安全属性，如 `text-align/text-indent`）；**协议白名单** `http/https/mailto`，禁止 `javascript:` / `data:` |
| 落地位置 | **Service 层入口**（如 `PlanSectionService.saveContent()` / `ArticleService.save()`），不是 Controller —— 因为 Agent 走 Service 直调，挂 Controller 会被绕过（这正是 D3 的同一个坑） |
| 工作量 | **约 1 人日**（含单测） |
| 验收用例 | 含 `<script>`、`onerror=`、`javascript:` 协议、`<iframe>`、`style="expression(...)"` 五类，断言全部被剥离 |

> **⚠️ 一个容易漏的点**：净化必须放在 **Agent 写路径与人工写路径的公共入口**。如果只在 Agent 的写入工具里调净化，人工通过编辑器提交的脏 HTML 仍然入库——那是**既有漏洞**，Agent 只是让它更明显。既然要引入 Jsoup，**顺带覆盖人工路径**是零额外成本的正解（也是本条建议"本期做"的额外理由）。

- **事实核查**：生成稿中的**时间/地点/人名**与活动详情做规则比对，输出差异清单交用户确认；不依赖 LLM 自检。
- **Prompt Injection**：工具结果与用户输入**角色分离**（system / user / tool），外部粘贴文本明确标注为"资料，非指令"；真正的安全边界靠 **D2 确认门禁 + D3 权限断言**（即便注入成功，也无法越权写库）。

#### D11　与业务模块的解耦契约：谁走"自动同步"，谁必须"显式登记"

> 本节回答一个必然会被问到的问题：**业务模块改动后，Agent 是否也要跟着改？**
> 结论：**分三类。只有第二类需要同步改，而它的成本是恒定的**——这是本方案刻意做出的架构权衡。

| 类别 | 变更类型 | 是否需同步改 Agent | 原因 |
|---|---|---|---|
| **第一类：透明变更** | Service 方法**新增参数并给默认值**、返回体**加字段**、数值口径调整（如签到率算法变化）、Service **内部实现重写**、新增业务校验（如活动状态机收紧） | **不需要**（编译通过即自动跟随） | Agent 工具是对 Service 的**薄包装**：追加参数用默认值、结果按白名单字段读取。业务侧改动自动生效，**且这正是"复用 Service 而非自建数据通道"的首要动机** |
| **第二类：契约破坏变更** | Service 方法**重命名/签名破坏**、**返回值由对象变聚合/换类型**、**枚举字面量改名**（如 `PartyStageType` 中文 label 调整）、**实体拆分/表重建** | **需要**（约 **0.5–1 人日/工具**） | 编译期即暴露，属"预期的、有界的"改动，而非隐性腐化 |
| **第三类：语义 / 越权语义变更** | **数据可见范围变化**（如脱敏规则调整、`dataScope` 调整）、**权限码重命名**、**新增强制前置条件**（如某业务要求"必须先归档才能加分"）、模块内新增需审批的操作 | **必须显式登记 Agent（PRD 的"契约登记项"）或改工具声明** | 这类变更**不破坏编译**，Agent 却会静默地变得不合规，是最危险的一类 |

**第三类的两个真实例子（当前代码已存在）**：

1. **脱敏是调用方传参，不是自动注入**：`PartyMemberController` 用 `current.getRoleCode()` 算出 `staff` 标志后**显式传给** `PartyMemberService.page(..., boolean staff)`。若 Agent 工具直接调 Service 却忘了传对，**脱敏必然失效**。
   → **约定**：Agent 侧统一从 `LoginUser` 推导——`boolean staff = ctx.loginUser == null || "STAFF".equals(ctx.loginUser.getRoleCode())`，**封装为 `ToolContext.isStaff()`，禁止工具自行判断**。同时把「新增/修改脱敏规则」列进契约登记清单。

2. **`SecurityContext` 依赖**：`ActivityService.currentUserId()` 与 `SigninRosterService` 都从 `SecurityContextHolder` 取当前用户（用于记录操作人）。在 **异步任务**（`agent_task` 执行线程）里，若 `SecurityContext` 未传播，`currentUserId()` 返回 `null`，**审计字段会静默变空**，且不报错。
   → **约定**：Agent 的异步线程池必须用 `DelegatingSecurityContextExecutor` 包装（见 D1），并在工具集成测试中断言"操作人非空"。

**登记清单必须保持极薄（这是约束，不是口号）**：
- 只登记"不破坏编译但会改变行为"的变更，预计**每季度 3–5 条**；
- 若某类变更频繁触发登记，说明该契约应**上移**——把它在 Service 层收口（如脱敏改为 Service 内部按 `LoginUser` 判定，而非调用方传参），而不是每次改 Agent。

**定位说明**：Agent 模块是**横切关注点（cross-cutting）**，不是独立业务模块。它的健康度取决于"与业务模块的耦合面有多大"。因此本方案反复选择"薄包装 + 复用 Service"、拒绝"自建数据通道"——**前者把第二类变更压到编译期暴露，后者会把第三类变更变成没人发现的隐患**。

### 3.3 数据模型（新增 5 张表）

Flyway 新迁移（**`V24__agent.sql`**，沿用 `CREATE TABLE IF NOT EXISTS` + utf8mb4 约定）：

> ⚠️ **迁移号已更正**：本文档早期版本写"建议 `V22__agent.sql`"，但 **V22 与 V23 已被占用**（`V22__drop_activity_session.sql`、`V23__signin_fields_json.sql`）。当前迁移链为 **V1–V23**，Agent 迁移必须用 **`V24`**。若沿用旧号，Flyway 会因校验和不匹配或重复版本直接启动失败。（v1.8 注：此为 2026-09-13 的链上状态，现链已至 **V34**，见 §0.5.2。）

| 表 | 关键字段 | 说明 |
|---|---|---|
| `agent_conversation` | `id, user_id, title, created_at, updated_at, deleted` | 会话；按 `user_id` 强隔离 |
| `agent_message` | `id, conversation_id, role(SYSTEM/USER/ASSISTANT/TOOL), content, tool_calls(JSON), created_at`，**v1.9：`thinking_json`(JSON) / `thinking_notes_json`(JSON)** | 消息；敏感结果仅存摘要。**思维链两列仅在 `sensitivity = normal` 时写入**（推理原文与编排旁白都可能含业务数据，与正文同级脱敏，见 D4 / 决策 6） |
| `agent_task` | `id, user_id, conversation_id, tool_name, task_type(SYNC/ASYNC), status(PENDING_CONFIRM/RUNNING/SUCCESS/FAILED/PARTIAL/CANCELLED), plan_json, result_json, confirm_token, confirmed_at, created_at, finished_at` | 确认门禁 + 异步任务状态机（**核心表**） |
| `agent_tool_call` | `id, conversation_id, message_id, tool_name, args_json(脱敏), sensitivity, side_effect, duration_ms, result_status, created_at` | 调用流水，便于排障与统计。**同时是前端过程区（P5）历史回看的数据源**——刷新页面后 `✓ 查询我的待办 184ms` 这些行从本表读回，不是前端临时状态（灵思 §7.1）。**v1.9：`args_json` 经读侧闸门 `PolicyGuard.egressArgs` 下发给前端供「工具行下钻」——`normal` 给遮罩明文、`sensitive`/`restricted` 给 `null`；写入侧不变（那是审计口径，展示层收紧不该改它）。另：随消息下发的工具条目新增 `ts`（= 调用开始时刻 epoch ms，由 `now - durationMs` 推出，不新增列），前端据此把工具行与推理段/旁白行按真实时间交错** |
| `agent_audit_log` | `id, user_id, conversation_id, task_id, tool_name, args_digest, side_effect, confirmed_by, result_status, created_at` | 审计（写操作 100% 覆盖） |

> 约束：`agent_task(id, status)` 建索引（乐观锁更新需要）；`agent_conversation(user_id, updated_at)` 建索引（列表查询）。

### 3.4 接口设计（Agent API）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | `/api/agent/chat` | 发送消息；返回 `{streamId, reply, toolCalls[], plan?, references[]}`（`streamId` 用于订阅过程事件，见 D7） | 登录即可，工具级再校验 |
| POST | `/api/agent/chat/{streamId}/abort` | **中止本次回答**（停止编排；已执行的工具副作用不撤销） | 本人 |
| GET | `/api/agent/streams/{streamId}` | **拉取过程事件**，支持 `?since={seq}` 断连补拉（D7 降级通道，也可用作重连补齐） | 本人 |
| GET | `/api/agent/conversations` | 我的会话列表 | 本人 |
| GET | `/api/agent/conversations/{id}/messages` | 会话消息（分页） | 本人 |
| DELETE | `/api/agent/conversations/{id}` | 删除会话（软删） | 本人 |
| GET | `/api/agent/tasks/{id}` | 任务/拟执行清单详情（含明细与 skipped） | 本人（或具备对应权限的上级） |
| POST | `/api/agent/tasks/{id}/confirm` | 确认执行（一次性 `confirmToken`，乐观锁幂等） | 本人 + 工具所需权限 |
| POST | `/api/agent/tasks/{id}/cancel` | 取消待确认任务 | 本人 |
| GET | `/api/agent/tools` | 返回**当前用户可见**的工具清单（供前端做能力提示/引导） | 登录即可，按权限过滤 |

> **v1.8 现状**：实际端点已扩至 **18 个**（含 v1.4 的 `POST /api/agent/tasks/{id}/input` 与多 Agent 的 `AgentTeamController` / `AgentBenchController` 两组），完整清单以 controller 代码为准；本表为规划基线。

**WebSocket 订阅（用户定向队列）**：

| 队列 | 事件 | 说明 |
|---|---|---|
| `/user/queue/agent` | `AGENT_TASK_DONE` | 异步任务完成推送（D7 长任务） |
| `/user/queue/agent/stream` | `AgentStreamEvent` | **对话过程事件流**（D7）：`THINKING`（编排旁白）/ **`THINKING_DELTA`（模型推理增量 `{round,text}`，v1.9 落地）** / `TOOL_START` / `TOOL_END` / `PLAN_READY` / `INPUT_NEEDED` / `MESSAGE_DELTA`（字符级流式）/ `MESSAGE_DONE` / `ERROR` / `ABORTED`；多 Agent 协作另扩展 `AGENT_*` / `REVIEW_RESULT` 事件（见《通用化与多Agent协作改造策划》§3.7） |

**`AgentStreamEvent` 契约**：

```json
{ "streamId": "s_8f21", "seq": 3, "type": "TOOL_END",
  "payload": { "tool": "queryActivityOverview", "status": "OK", "durationMs": 267 },
  "ts": 1757750400000 }
```

> `seq` 单调递增，前端据此去重排序（乱序到达不错位）；断连后用 `GET /api/agent/streams/{streamId}?since={lastSeq}` 补齐缺口。

**响应示例（命中写类意图）**：

```json
{
  "code": 200,
  "message": "ok",
  "data": {
    "reply": "已为你匹配到 42 名已签到人员，其中 1 人因同名同学号被跳过。确认后将各加 0.5 分，共 21.0 分。",
    "toolCalls": [{ "tool": "previewCreditBatchFromActivity", "status": "OK", "durationMs": 84 }],
    "plan": {
      "planId": 10231,
      "summary": "第四十期培训班 · 素拓批量加分",
      "total": 42,
      "skipped": [{ "name": "李想", "reason": "同名同学号已存在记录" }],
      "needsConfirm": true,
      "confirmToken": "ct_7f3a...（一次性）"
    },
    "references": [{ "type": "ACTIVITY", "id": 88, "label": "第四十期入党积极分子培训班" }]
  }
}
```

**约定**：
- 所有工具调用结果都带 `references[]`（实体类型 + id + 展示名），前端渲染为可跳转的引用，避免"AI 说了但找不到"。
- 401/403 口径与现有系统完全一致（`{"code":401|403, ...}`），前端无需新增错误处理分支。

### 3.5 工具清单（首批 23 个，按批次交付）

> 原则：**聚合优先**。项目有约 191 个接口，但**绝不能做 191 个工具**——工具越多，LLM 选错率越高、上下文越贵。按"用户会说的那句话"聚合，首批控制在 25 个以内。

> ⚠️ **现状提示（v1.8）**：本表是 2026-09-13 的**规划基线**（23 个）；截至 2026-10-05 实际注册 **21 个（读 11 / 写 10）**——落地偏差、未实现项与实现期新增工具见 §4.3.1「v1.8 更新」表与 §0.5.2。**勿把本表"批次"栏当作当前待办。**

**读类（chat / copilot）— 14 个**

| # | 工具 | 对应能力 | 权限码 | 敏感级 | 批次 |
|---|---|---|---|---|---|
| 1 | `queryMyPendingWork` | 我的待办：待审策划书/稿件 + 未读通知聚合 | 登录即可 | normal | 1 |
| 2 | `queryActivityOverview` | 活动概览（状态/时间/负责人/进度） | 登录即可 | normal | 1 |
| 3 | `queryActivityTasks` | 甘特任务、逾期任务、部门负载 | 登录即可 | normal | 1 |
| 4 | `queryActivityDetailForWriting` | 生成用素材包（策划书+议程+评分+签到数；**排除党务**） | `activity:view` | normal | 2 |
| 5 | `searchMaterials` | 材料检索（keyword/bizType/activityId/deptId） | `material:view` | sensitive（名单类降级） | 1 |
| 6 | `searchArticles` | 推文/新闻稿检索（状态/活动/关键词） | 登录即可（非审核角色不返 `reviewComment`） | normal | 1 |
| 7 | `queryNotifications` | 我的通知（按当前人身份，禁越权读他人） | 登录即可 | normal | 1 |
| 8 | `querySchedule` | 排班查询 | `schedule:manage` 或本人班次 | normal | 2 |
| 9 | `queryAttendance` | 考勤查询 / 缺勤统计 | `attendance:view`,`schedule:check` | sensitive（纪律信息） | 3 |
| 10 | `queryCommonFreeTime` | 共同空闲时段（复用 `course-schedules/analyze`） | 登录即可（限部长以上看全员） | normal | 2 |
| 11 | `queryPartyMemberProgress` | 党员发展进度（**复刻后端脱敏**） | `party:view` | sensitive | 3 |
| 12 | `queryCreditRecords` | 素拓记录（部长全量 / 干事仅自己） | `quality:add` 或本人 | sensitive | 3 |
| 13 | `queryRosterSigninStats` | 签到统计（仅聚合口径：应签/已签/签到率） | `checkin:view` | sensitive | 2 |
| 14 | `checkPlanCompleteness` | 策划书 12 章完整性 / 预算空 / 时间冲突（**纯规则，不调 LLM**） | `activity:review` | normal | 1 |

**写类（automation，全部走 D2 确认门禁）— 9 个**

| # | 工具 | 对应能力 | 权限码 | 敏感级 | 批次 |
|---|---|---|---|---|---|
| 15 | `draftPlanSections` | 策划书章节草稿（落 `DRAFT`，不提交审核） | `activity:edit` | normal | 2 |
| 16 | `draftArticleFromActivity` | 报道推文草稿（素材白名单：活动/策划书/议程/评分） | `LEADER`（→ 后续 `content:manage`） | normal | 2 |
| 17 | `draftNewsFromActivity` | 新闻稿草稿 | `LEADER` | normal | 2 |
| 18 | `draftAnnouncement` | 公告草稿（可选：确认后发布） | `notice:publish` | normal | 3 |
| 19 | `previewCreditBatch` + `commitCreditBatch` | 素拓批量加分（读/写分离，展示 skipped） | `quality:add` | sensitive | 2 |
| 20 | `generateAgendaFromPlan` | 议程自动生成（解析策划书流程） | `activity:edit` | normal | 2 |
| 21 | `generateActivitySchedule` | 活动排班一键生成（**包装已有 `ActivityScheduleService`**） | `schedule:generate`,`schedule:manage` | normal | 3 |
| 22 | `archiveActivityMaterials` | 材料归档完整性检查 + 批量补建档 | `material:upload` | sensitive | 3 |
| 23 | `importPartyRoster` | 入党名单导入（上传前预检 + 结果解读，写入走已有 `/api/files/import`） | `party:entry` | sensitive | 3 |

**特别说明**：
- 第 13 项**只返回聚合口径**，不返回名单明细。原因：调研文档已指出"干事经对话问'名单里都有谁'可绕过前端页面限制"，因此名单明细工具**默认不提供**，需要明细时引导用户去签到页（那里有页面级门控与操作留痕）。
- 第 21 项是本方案**性价比最高的一项**：规则算法与接口都已就绪，Agent 只需做意图解析 + 参数补全 + 结果人话化，预计 0.5–1 人日。
- **严禁**新增 `scanSignin` 相关工具。

**工具实现的统一约定（对应 D11，避免各工具各行其是）**：

| 约定 | 要求 | 原因 |
|---|---|---|
| 脱敏标志 | 一律用 `ctx.isStaff()` 从 `LoginUser` 推导，**禁止**工具自行判断角色 | 当前 `PartyMemberService.page(..., boolean staff)` 的 `staff` 由 **Controller 传入**；工具漏传即脱敏失效且不报错 |
| 结果读取 | 只按**白名单字段**读取返回体，不 `toString()` 整个 VO | 业务侧加字段时自动跟随；避免把敏感字段意外带进 LLM 上下文 |
| 参数 | 新增参数一律走 Service 的默认值或工具显式补全 | 保证"Service 加参数"不波及 Agent |
| 操作人 | 异步工具断言 `currentUserId()` 非空 | `ActivityService` / `SigninRosterService` 从 `SecurityContext` 取操作人，未传播则**静默变 null** |
| 枚举 | 不硬编码中文 label，统一引用模块内枚举 | `PartyStageType.label()` 等中文口径可能调整 |

### 3.6 前端集成方案

> **2026-09-13 更新**：本节已由 `PAMS-灵思页面设计方案.md`（v1.1）展开与部分修正。页面形态从"仅抽屉"扩展为**六面**（P1 主页 / P2 悬浮球+抽屉 / P3 就地 Copilot / P4 确认卡 / P5 过程区 / P6 受限块），组件名有调整。**以灵思页面设计方案为准**，本表保留用于追溯。

> **v1.8 现状**：组件已按灵思方案 + UI v3/v4 落地为 `pams-web/src/components/agent/` 下 **20 个 `Lingsi*` 组件**（含多 Agent 内联面板 `LingsiTeamPanel`；`LingsiFormCard.test.tsx` 为单测）。**新建组件与命名以代码现状为准**，本表旧名（AgentDrawer / AgentMessageBubble / AgentProcessZone / AgentResultCard / AgentActionCard 等）仅作追溯。

| 组件 | 位置 | 说明 |
|---|---|---|
| `AgentDrawer` | `src/components/agent/` | 全局抽屉（P2），接 `MainLayout`；会话列表 + 消息流 + 结果卡渲染 |
| `AgentMessageBubble` | 同上 | 单条消息，**过程区 / 结论区两段式**（灵思 §3.5.1） |
| `AgentProcessZone` | 同上 | **P5 过程区**：流式时间轴 ↔ 折叠条双形态（灵思 §7.1；原设计中的 `AgentToolTrace` 已升级为此） |
| `AgentResultCard` | 同上 | 结构化结果卡（表格/统计/引用列表），**不是纯文本** |
| `AgentActionCard` | 同上 | 拟执行清单（P4）：总量 + 明细（可展开）+ skipped 原因 + 确认/取消（原名 `AgentConfirmCard`，为强调"动作"语义改名） |
| `AgentCopilotButton` | 复用组件 | 就地按钮（P3），`onResult` 回调把生成结果回填父表单字段 |
| `stores/agent.ts` | `src/stores/` | Zustand store：会话、消息、**过程事件流状态**、任务订阅（原名 `useAgent`，统一为 `stores/` 目录下的 store 命名） |
| `agent.ts` | `src/api/` | 类型与接口（内聚，遵循现有 `src/api/*` 约定）；含 `AgentStreamEvent` 类型 |
| 菜单/路由 | — | 有独立页面 `/agent`（P1 主页），侧栏菜单项「灵思」；另在 `MainLayout` 挂悬浮球 |

**复用点**：`useWebSocket`（长任务完成推送 + 过程事件流）、`httpBlob`（下载类结果）、`useHasPerm`（按权限隐藏 Copilot 按钮）、现有 `Result`/`PageResult` 类型。

### 3.7 表单补全（D2.5 Form Elicitation，2026-09-14 增补，已落地）

**问题**：写类工具缺参时只有两条坏路——工具 FAILED 让 LLM 在对话里连环追问（"活动叫什么？时间呢？地点呢？"四五个回合），或 LLM 自行编造默认值（编出来的日期直接进确认清单）。这恰是 Claude/MCP *elicitation* 与 Codex *结构化确认* 要解决的同款问题。

**设计**：参数收集与确认门禁**分离**——表单只收集参数，**不替代 D2 确认**（清单 + 一次性令牌铁律不动）：

```
LLM 调工具（参数可空）──→ 工具 dry-run 发现缺参 ──→ ToolResult.form(schema+预填值)
      │                                              │
      │                                    Orchestrator：落 agent_task(WAITING_INPUT)
      │                                    （plan_json 复用为表单载体，type:'form' 区分）
      │                                              │
      │                                    推 INPUT_NEEDED 事件 + chat 返回 form
      ▼                                              ▼
前端 LingsiFormCard：行内卡片 + 弹窗自动弹出（schema 驱动渲染，预填 LLM 已提取值）
      │
      └─ 用户补全提交 → POST /api/agent/tasks/{id}/input {values}
            └─ TaskGate.submitInput：合并 originalArgs+values → 经 ToolRegistry.dispatch 重跑 dry-run
                  ├─ 参数齐 → 同一任务转 PENDING_CONFIRM（换发 confirmToken）→ 走既有确认门禁
                  ├─ 仍缺 → FORM（保持 WAITING_INPUT，预填更新）
                  ├─ 校验失败 → ERROR（保留 WAITING_INPUT 可改后重提）
                  └─ 业务拦停 → CANCELLED（任务终态化，防悬挂）
```

**关键实现约束**（均有源码注释）：

| 约束 | 理由 |
|---|---|
| 表单 schema 自描述（`{name,label,type,required,options,placeholder}`） | D11 同款：新增表单型工具前端零改动（`LingsiFormCard` 按 schema 渲染 text/textarea/date/select/number） |
| `AgentTask` 复用 `plan_json` 存表单（新增状态 `WAITING_INPUT`，varchar(20) 够用） | **零迁移**：无 V26，新库老库一致 |
| `submitInput` 重跑走 `ToolRegistry.dispatch` | D3 权限断言在表单提交时**依然生效**（任务创建很久后角色可能已变，与 confirm 的二次断言同理） |
| `submitInput` 刻意不开整体事务 | 与 `confirm()` 同款：重跑内部调业务 Service，失败要优雅返回 ERROR 而非 500 |
| `originalArgs` 随表单存档 | 用户提交后合并重跑，LLM 已提取的参数不丢 |
| 前端表单卡与清单卡互斥，提交后原位变清单卡 | 单一视觉焦点；`pendingTaskCount` 在转 PLAN 时 +1，角标不断裂 |
| 系统提示词 + 工具描述双管齐下（"参数可空直接调用，绝不为凑参数反问用户"） | DeepSeek 实测：仅改系统提示词仍会追问一次；把工具 `parametersSchema.required` 清空后稳定直调（required 非空会让 LLM 视为调用硬前提） |

**回归实测（2026-09-14，`.workbuddy/reg/f1-form.mjs`，两变体全绿）**：

| 变体 | 发起消息 | 结果 |
|---|---|---|
| 零信息 | "帮我创建一个活动" | LLM 直调 `createActivity(FORM)` → 任务 12 WAITING_INPUT（9 字段全空）→ 提交全量 → PLAN → confirm SUCCESS → 活动 32 落库 → REST 复核 200 |
| 部分信息 | "下周六在报告厅办一个党团知识竞赛…" | 预填 `name=党团知识竞赛, location=报告厅` → 表单弹出补日期 → PLAN → SUCCESS → 活动 33 落库 |

测试产物（活动 32/33）已清理；`INPUT_NEEDED` 事件对旧前端向后兼容（`mergeTimeline` 未知类型默认忽略）。

### 3.8 对话历史持久化与恢复（V26，2026-09-14 增补，已落地）

**问题**：重新进入会话时，聊天记录虽在（`agent_message` 持久化 + `messages()` 已返回 `toolCalls`），但**确认卡/表单卡/引用/历史轮次过程时间轴/用户提问气泡全部丢失**——前端 `selectConversation` 把这些字段硬置 `null/[]`，后端也无恢复数据源。

**缺口定位（勘察结论）**：

| 数据 | 持久化 | 恢复缺口 |
|---|---|---|
| 消息正文 + toolCalls 摘要 | `agent_message` ✓ | 前端只取 content/toolCalls，其余丢弃 |
| 引用 references | ✗ 只随 /chat 同步返回 | 无数据源 |
| 任务卡 plan/form | `agent_task` ✓ 但无 stream_id | 无法绑定到消息；**新会话首轮 `conversation_id` 落 NULL**（ctx 在会话创建前构造，未回填） |
| 过程时间轴 | `agent_tool_call` ✓（`/streams/{id}` 缓冲丢失时可重建） | 前端历史轮次不渲染 |
| 用户提问 | `agent_message(role=USER)` ✓ | 前端用本地 `askedTexts` ref，重进即空 |

**方案（V26，零新表）**：

```
ToolContext.derive(convId, streamId)   ← 编排器在会话落定后回填 ctx（修首轮 NULL + 绑流）
TaskGate.createPlan/createForm         ← t.setStreamId(ctx.getStreamId())
V26（守卫式 ALTER）                    ← agent_task.stream_id + 索引；agent_message.refs_json
ConversationStore.save(+references)    ← assistant 消息落 refs_json；messages() 反查任务挂卡
前端 selectConversation                ← plan/form/references 从消息 VO 水合；时间轴由 toolCalls 派生
```

关键取舍：

| 决策 | 理由 |
|---|---|
| 任务↔消息用 `stream_id` 关联（agent_message 已有该列，V24） | 消息与任务在编排器内同流产生，天然键；无需改 `agent_message` |
| 历史时间轴 | **v1.5：由 toolCalls 摘要本地派生**；**v1.9 修正**：**工具行**仍由 toolCalls 摘要派生（同源、零请求、对旧消息也生效），但**编排旁白与模型推理段改由消息上的 `thinking_notes_json` / `thinking_json` 回放**——原句"THINKING 行是一次性过渡态，不参与恢复"已不成立（那会让刷新后的时间轴缺行，与"过程区是真实历史"自相矛盾） | 两者都必须能回放，且**顺序与实时看到的一致**（旁白按 `ts`、推理按轮次分段） |
| 只恢复**仍可交互**的任务卡（PENDING_CONFIRM → plan / WAITING_INPUT → form），终态不出卡 | 已执行/已取消的操作再挂确认按钮是误导；`pendingTaskCount` 与可见卡面一致 |
| `confirmToken` 随卡传入 `confirmPlan(planId, token)`，不再读全局 `pendingPlan` | 历史恢复的确认卡同样可确认（原实现全局 plan 为 null 时静默失效） |
| 恢复出的 plan 携带后端签发的令牌 | 24h 过期校验仍由后端把守（前端只做视觉提示） |

**回归实测（2026-09-14，`.workbuddy/reg/f2-history.mjs`，16 项全绿）**：新会话首轮回填 `conversation_id`（修复前 NULL）→ 表单卡 `taskId` 挂回消息 → 提交转 PLAN 后确认卡（带令牌）挂回同一条消息且与表单互斥 → 第二轮读类工具 `toolCalls` 随消息持久化 → USER 消息在历史中 → 取消后终态不出卡 → 会话清理。F1（D2.5 表单链路）重跑全绿，无回归。测试残留（活动 34、会话 36/37）已清理。

**边界**：V26 之前的旧任务无 `stream_id`，其历史卡片不可恢复（数据在修复前未写，无法追溯）；旧消息的时间轴/引用恢复不受影响（分别来自 `tool_calls` 摘要与 `refs_json` 缺省空数组）。

### 3.9 活动名称自动解析（2026-09-14 增补，已落地）

**问题**：用户与 LLM 在对话里天生只说得出活动**名称**（"给新生党员欢迎会加分"），不可能报出一串活动 ID。但 6 个工具曾强制要求 `activityId`，于是 LLM 只能先调"列活动"→ 从返回里翻 ID → 再二段调用：**多一轮往返、极易翻错**，且在"还没有活动"或活动很多时直接卡死。

**方案**：新增共享解析组件 `ActivityResolver`（`module/agent/tool/`，`@Component`），工具 schema 增加 `activityName`、`required` 移除 `activityId`，`execute` 首部统一接入解析。解析顺序：

```
① ID 直取              activityId 给了 → getEntity（最精确）
② 名称尾缀（ID n）直取   "迎新晚会（ID 34）" → 直接取 34（候选回传的闭环载体）
③ 精确同名唯一才拍板     equalsIgnoreCase 且“仅命中一个”才用
④ 其余 → 候选清单       LIKE(name/theme) 列表 + 教 LLM 回传「名称（ID n）」，绝不静默选择
```

**接入的 6 个工具**：

| 工具 | 类型 / 权限 | `required` 变化 |
|---|---|---|
| `queryRosterSigninStats` | 读 · `checkin:view` | 移除 `activityId`（余空） |
| `previewCreditBatch` | 写 · `quality:activity_add` | `[activityId, credit]` → `[credit]` |
| `generateActivityTasks` | 写 · `activity:edit` | 移除 `activityId` |
| `draftArticleFromActivity` | 写 · LEADER 口径 | 移除 `activityId` |
| `createSeatLayout` | 写 · `seat:edit` | 移除 `activityId` |
| `generateActivitySchedule` | 写 · `schedule:generate` | 移除 `activityId`（其余 5 项 required 保留） |

> 另有 4 个读类工具（`QueryActivityTasks` / `QueryAttendance` / `QueryCreditRecords` / `SearchMaterials`）的 `activityId` 本就可选，本期不动。

**关键取舍**：

| 决策 | 理由 |
|---|---|
| **精确同名唯一才自动拍板**，模糊/重名一律出候选 | 写类工具静默挑错活动 = 分数加到错的人、座位建到错的场，后果不可逆；宁可多一轮往返 |
| 候选清单固定写成「名称（ID n）」，与解析正则对称 | LLM 照抄即命中，消歧闭环不依赖额外会话状态 |
| 名称匹配复用 `ActivityService.page(keyword)`（LIKE name/theme） | 零新查询通道；LIKE 只用于**构造候选**、不用于拍板，模糊性无害 |
| `getEntity` 返回 null 也归一为失败 | 防"解析成功但活动为空"；不依赖 `getEntity` 抛异常这一实现细节 |

**回归实测（2026-09-14）**：

- **确定性（JUnit）**：`ActivityResolverTest` 11 项（ID 直取 / 尾缀 / 精确唯一 / 同名重复 / 模糊多命中 / 零命中 / 参数缺失 / null 实体加固）+ `AgentToolActivityNameSchemaTest` 1 项（**锁死 6 工具 schema 含 `activityName` 且 `required` 不含 `activityId`，防契约回退**）→ `Tests run: 12, Failures: 0, Errors: 0`。
- **端到端（`.workbuddy/reg/f3-resolve.mjs`，14 项全绿）**：① 只给名称查签到统计 → `queryRosterSigninStats:OK`，引用 link = `/activity/35/signin`（**反证名称被解析到正确 id**）；② 只给名称生成筹备任务 → 产出确认清单（`planId=22`，摘要「灵思名称解析回归活动 · 筹备任务清单（10 项）」）并取消；③ 名称不存在 → 给可读提示、不泄漏异常。全程**未出现**"需要指定活动 ID"。测试活动（id 35）与两轮任务（21/22）已清理。

**边界**：同名活动 ≥2 个时不自动选择（用「名称（ID n）」消歧）；名称匹配依赖 `page` 的 LIKE 模糊命中，零命中时按可读提示兜底（不抛异常）。

### 3.10 对话体验修正与内容类工具（2026-09-14 增补，已落地）

**反馈与处置**（1 项能力缺口 + 4 条体验）：

| # | 反馈 | 根因 | 处置 |
|---|---|---|---|
| 0 | **现在没有办法生成策划书** | 工具清单 #15 `draftPlanSections` 一直未实现——灵思没有这个能力 | 新增 `draftPlanSections` 工具（§3.10.1），工具数 20 → **21**（读 11 / 写 10） |
| 1 | 发出的消息在对话框不显示，重载后正常 | 用户文案存在页面本地 state（`askedTexts`）、占位消息存在 store，两处更新互相竞态 | 用户文案下沉 store 的 `userTexts`，与占位消息在**同一次 `set`** 写入（单一数据源、下标恒等） |
| 2 | 策划书/推文这类内容应在对话框先给预览 | 清单卡原设计只显示"标题 + 正文前 60 字"，无法通读 | `AgentActionPlan.preview`（长文正文）+ 确认卡「查看全文」抽屉；`draftPlanSections` 与 `draftArticleFromActivity` 均已接入 |
| 3 | 多轮纠正后首轮确认按钮"无法执行" | **后端实测纠正后仍可确认**（探针：confirm 200 + 任务 SUCCESS），问题在交互预期——旧卡与已经走远的上下文并置，用户以为它失效了 | **多轮「接续」**（§3.10.2）：出新清单时自动作废本会话旧清单 + 前端旧卡原地转「已被新清单取代」+ 把当前待确认清单注入 LLM 上下文 |
| 4 | 任务完成后不要弹窗通知 | `useWebSocket` 在 `AGENT_TASK_DONE` 上弹 corner 通知 | 去掉弹窗；执行结果落进对话框（卡片原地变结果块 / 追加结果消息），WS 只做状态收敛 |

#### 3.10.1 内容类工具：先预览、后落库

`draftPlanSections`（工具清单 #15 落地；权限 `activity:edit`；数据级 normal；可撤回）：

- **章节正文由 LLM 撰写后作为工具参数传入**（工具描述里明确要求），工具只负责"校验 + 净化 + 出预览 + 确认后落库"。**不引入"工具内部再调一次 LLM"的反模式**——那会让权限/审计边界变模糊。
- 只写"模型真的写了"的章节：空章节不落库（否则会把用户已编辑过的内容清空）；未生成的章节以 `skipped` 必现（§4.3 口径）。
- 章节编号对齐前端 12 章模板（三/四/九/十/十一/十二 + 附：活动流程）；一/二/五/六/七/八 是"活动基本信息"只读章节，由前端从活动带出，模型不需要写。
- 落 `activity_plan(DRAFT)`，**不提交审核**（起草是"给出初稿"，提交是"我认可这稿"）；`syncActivity=false`，只写策划书、不回写活动基本信息。

`AgentActionPlan.preview`（内容预览）：

- 内容类工具把正文随清单一起下发；前端确认卡显示「查看全文（约 N 字）」→ antd `Drawer` + Markdown 渲染。
- 与 `detailLines` 的分工：`detailLines` 是"清单的补充说明"（逐行、短），`preview` 是"**即将写入数据库的正文本身**"（长文、需要通读后才能确认）。
- 兼容性：`AgentActionPlan` 保留 8 参构造器 → 非内容类工具与门禁调用点**零改动**；`toJson()` 由 `Map.of` 改为 `LinkedHashMap`（前者最多 10 对键值、且 null 值抛 NPE）。

#### 3.10.2 多轮「接续」（修"上下文偏移"）

- **后端作废**：`TaskGate.createPlan` 在落新清单前，把**本会话**内旧的 `PENDING_CONFIRM` 任务置 `CANCELLED`。范围刻意只覆盖 PENDING_CONFIRM——表单卡（`WAITING_INPUT`）是"模型在向用户要信息"，误作废会让 D2.5 表单链路半途失效。
- **上下文注入**：`buildContext` 追加一句"当前待确认清单"摘要（工具 / 摘要 / 项数 / 任务 ID），模型在用户纠正时才会**按新说法重出清单去替换**，而不是只做口头说明。这是"上下文偏移"的根修——不注入时模型把纠正当成全新任务。
- **前端退役**：本轮返回新清单时，旧卡原地转为 info 结果块「已被新的清单「X」取代，未执行」，使会话里始终只有一张可确认卡。

#### 3.10.3 结果落进对话框（替代弹窗）

- `AGENT_TASK_DONE` 不再产生任何 corner 通知；store 的 `applyTaskDone` 按 `taskId` 处理：
  清单卡还在 → **原地变结果块**（P4「已执行态原地变结果卡」，不留可点的僵尸按钮）；卡片不在（异步完成 / 历史轮次）→ **追加一条结果消息**。
  确认/取消也走同一路径（`confirmPlan` / `cancelPlan` 用 REST 返回原地写结果块），并按 `taskId` 去重，避免 REST 与 WS 各写一条。
- 结果块分级配色：success / failed / partial / info（`--color` 用 8 位 hex 字面量，不做 `var(--x)55` 这类 alpha 拼接）。

**回归实测（`.workbuddy/reg/f4-plan.mjs`，16 项全绿）**：
`帮我给"X"写一份策划书` → `draftPlanSections:OK` + 清单带 **1371 字**全文预览 → `PENDING_CONFIRM`；
纠正"内容太简单，改成三个环节并补预算" → 重出清单 29，**旧清单 28 自动 CANCELLED**；
确认 → `activity_plan` id=20 version=2、状态 `DRAFT`、章节正文非空。
测试数据（活动 36/37、会话 47/48/49、遗留任务 25–29）已清理。

**边界**：
① 作废只发生在**同一会话内**——不同会话仍可各有一张待确认卡；
② "接续"依赖模型在新一轮选择重出清单；若模型只做口头说明（不调工具），旧卡保持可确认（**不静默失效**，用户仍可确认或取消）；
③ `preview` 每章最多 600 字（抽屉通读用），落库内容不受截断影响。

---

## 4. 落地可行性评估（本方案核心）

### 4.1 前置依赖就绪度核查

| 依赖项 | 状态 | 证据 | 是否阻塞 Agent |
|---|---|---|---|
| 控制器级权限（5 处缺口） | ✅ 已解决 | 上述控制器均已挂 `hasAuthority` | 否 |
| 权限码可被运行时读取 | ✅ 已解决 | `JwtAuthenticationFilter` 双轨注入 | 否 |
| `/uploads` 匿名直出 | ✅ 已解决 | `SecurityConfig` → `authenticated()`（V18） | 否 |
| 排班人员 `userId` 绑定 | ✅ 已解决 | V20 + `SchedulePerson` | 否 |
| 名册导入接线 | ✅ 已解决 | `POST /api/files/import`（`party:entry`） | 否 |
| 新闻稿发布闭环 | ✅ 已解决 | `POST /api/news/{id}/publish` | 否 |
| 公告已读后端化 | ✅ 已解决 | V19 `announcement_read` | 否 |
| 排班生成算法 | ✅ 已解决 | `ActivityScheduleService` + `schedule:generate` | 否（反而降本） |
| 通知事件总线 + WebSocket 推送 | ✅ 已具备 | `NotificationEventListener` + `/user/queue/*` | 否（直接复用） |
| 文件上传/下载通道 | ✅ 已具备 | `FileStorageService` + `FileController`（含 `BLOCKED_EXTENSIONS`） | 否 |
| **服务端 HTML 净化** | ⬜ **缺** | 仅前端 DOMPurify | **是**（生成类硬前置，约 1 人日） |
| `agent_*` 表 | ⬜ 缺 | — | 是（批次 0） |
| LLM 供应商可用性/预算 | ⬜ 待定 | 无依赖、无 Key | 否（有 Stub 兜底），但影响效果 |
| content 模块权限码收敛 | ⬜ 缺（G1） | 仍 `LEADER` 宏 | 否（用等价口径） |
| 流式通道 | ✅ 部分就绪 | 现有 STOMP WebSocket 可直接承载过程事件 | 否（事件级一期做，字符级二期） |
| 材料正文全文索引 | ⬜ 缺（G5） | 仅元数据检索 | 否（二期） |
| 考勤/素拓历史姓名关联（G6） | 🔶 部分 | V20 仅覆盖排班 | 否（同名二次确认） |

**结论：无硬阻塞。**唯一的真前置是"服务端净化"与"批次 0 基座"，二者都在本期范围内。

### 4.2 工作量估算（人日，估算区间 ±30%）

| 批次 | 内容 | 人日 | 依赖 |
|---|---|---|---|
| **B0 基座** | **`V24`** 建表 + `LlmProvider`（DeepSeek/Stub）+ `AgentOrchestrator` + `ToolRegistry`（含权限过滤与断言）+ `PolicyGuard` + 审计 + `AgentController` + **`AgentStreamEvent` 事件通道（D7）** + 前端 `AgentDrawer` 骨架 | **10.5–13** | 无 |
| **B0.5 安全补口** | Jsoup 服务端净化（G2，**覆盖 Agent 写路径 + 人工写路径的公共入口**）+ 净化单测 + **契约登记清单（D11）建立** | **1.5–2** | 无 |
| **B1 读类能力** | 工具 1/2/3/5/6/7/14 共 7 个 + 结构化结果卡 + **前端过程区（时间轴/折叠条，含 4 个新组件）** + "党建小助"5 类问答调优 | **8–10.5** | B0 |
| **B2 写类能力** | 工具 4/13/15/16/17/19/20 + `AgentActionCard`（拟执行清单）+ Copilot 按钮接入策划书/推文编辑页 | **8–10** | B0、B0.5 |
| **B3 敏感模块** | 工具 8/9/11/12/18/21/22/23（脱敏强化 + 同名消歧 + 权限回归测试） | **6–8** | B2 |
| **B4 检索增强 + 演示** | ngram FULLTEXT 索引（G5）+ 检索工具增强 + 演示故事线串联 + 离线 `StubProvider` 打磨 + **（可选）字符级流式** | **4–6** | B1、B3 |
| **合计** | — | **39–50.5 人日**（较 v1.0 的 36.5–46.5 增加约 2.5–4 人日） | 单人约 8–10 周；双人约 4.5–5.5 周 |

> 说明：B1 的 7 个工具之所以只用 7–9 人日，是因为**它们都是"薄包装"**——业务规则、查询逻辑、权限都在现有 Service/Repository 里，工具只负责参数归一化、结果裁剪与话术组织。真正的成本集中在 B0（框架）与 B2（确认门禁 + 编辑器联动）。
>
> **本次增量来源（+2.5–4 人日，相对 v1.0）**：
> - B0 的事件通道与 `AgentStreamEvent` 契约：+0.5–1（v1.1 引入）
> - B1 的前端过程区四组件与双形态切换：+1–1.5（v1.1 引入）
> - **B0.5 净化范围扩展至人工写路径**：+0.5（v1.2 引入，见 D10——顺带补既有漏洞，零设计成本但需回归人工编辑路径）
>
> **未增加 B2/B3 的成本**——过程区是与业务无关的通用渲染层，不随工具数增长。这也是选择"过程区只依赖 `AgentStreamEvent`"这一设计的经济动机（§3.5.3 兜底设计）。

### 4.3 技术风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| **Service 方法无 `@PreAuthorize`，Agent 直调绕过鉴权** | 越权读写（最高风险） | D3 三层防护：工具声明权限码 + 派发前断言 + 低权限必失败的单测；写操作另有确认门禁 |
| `SecurityContext` 跨线程丢失 | 异步工具取不到当前用户，可能报错或按"匿名"处理 | 独立 `agentExecutor` + `DelegatingSecurityContextExecutor`；`LoginUser` 显式入参 |
| LLM 工具调用不稳定（幻觉工具名 / 参数不合规） | 用户看到"AI 出错" | 严格 JSON Schema + 服务端参数校验 + 失败重试 1 次 + `StubProvider` 降级话术；工具数量控制在 25 内 |
| 流式 + 多轮工具调用复杂度叠加 | 一期做不完 | **一期只做事件级流式（思考/工具进度），字符级流式放二期**（D7）；把"复杂度"从"渲染 token"移到"聚合事件"，可控 |
| **首事件延迟过高（TTFE 超标）** | 流式"做了等于没做"：总时长虽达标，用户仍觉"点了没反应" | **TTFE 单列为硬指标（P95 < 800ms，验收标准 9）**；`AgentOrchestrator` 必须在**进入编排的第一时间**（意图分类之前）就推 `THINKING`，不得等首个工具出结果才推 |
| **将来横向扩容后 `/user/queue/**` 静默失效** | 推送不报错但收不到，排障困难 | **记录边界即可**（D7）：当前单实例前提成立；扩容时按 D7 给出的三个解法择一（broker relay / Redis pub-sub / 会话粘性） |
| 中文 token 成本 / 上下文超限 | 费用与超时 | `max-tokens` + 上下文裁剪 + 结果摘要 + 会话内工具结果去重 |
| 演示环境无外网 | 演示失败（最致命的"落地"风险） | `StubProvider` 必须可用且覆盖 5 类问答 + 1 次文书生成 + 1 次批量确认；CI 中断网跑一遍 |
| LLM 生成 HTML 注入 | XSS | B0.5 服务端 Jsoup 白名单；入库前净化 |
| 敏感数据经对话泄漏 | 合规风险 | D4 分级 + `restricted` 不出网 + 敏感结果不入会话明文 + 会话按 `user_id` 隔离 |
| 批量操作误执行 | 数据污染 | D2 确认门禁 + skipped 明细必现 + 素拓按 `batchId` 可撤回 + 审计留痕 |
| 材料 1660 份格式异构解析失败 | 检索/归档能力打折 | 一期只用元数据；解析失败降级为"按文件名检索并给出下载入口" |
| **业务改动导致 Agent 静默不合规**（详见 D11 第三类） | 最隐蔽的风险：不破坏编译，但权限/脱敏/前置条件失效 | ① `ToolContext.isStaff()` 统一脱敏推导 ② 异步线程池传播 `SecurityContext` ③ 维护"契约登记清单"（每季度 3–5 条）④ 关键工具有"低权限必失败"集成测试 |

### 4.3.1 首轮落地回归实测发现（2026-09-13，含第二轮确认门禁回归）

> 本节记录**真实跑通**（非纸面推演）后暴露的问题与修复。回归方式：`mvn spring-boot:run --server.port=8081`
> 起干净实例 → Flyway 迁移 → 三角色登录 → 工具可见性矩阵 → 对话/多轮/写操作门禁全链路。
> 第二轮回归基于 Node 直连 API 的自动化脚本（登录/越权/确认/取消/会话/流补拉），覆盖 D2 门禁全部状态分支。

**环境实测结论**：

| 项 | 结果 |
|---|---|
| Flyway | `23 → 24 - agent` 成功应用；第二轮补 `25 - fix seat layout color labels`（见缺陷 5）；重启后 `up to date`，5 张 agent 表全部建出 |
| 工具注册 | 实注册 **20** 个（读 11 / 写 9），与 §3.5 规划的 23 个有差异，见下方"工具清单偏差"（**v1.8：当前为 21 个**——新增 `draftPlanSections` 落地，见更新后的偏差表） |
| 模型 | `provider=deepseek`、`degraded=false`，主链路真实调通 |
| 耗时 | 单轮问答 1.2–2.8s，含 1–2 次工具调用，**满足总 P95 < 3s** |

**D3 三层防护实测矩阵**（这是本次回归最有价值的验证）：

| 角色 | 可见工具 | 写类 | 被过滤的能力 |
|---|---|---|---|
| `TEACHER` | 20 | 9 | —（全量） |
| `ORG_LEADER` | 19 | 8 | `createMember`（需 `member:manage`） |
| `STAFF`（干事） | **11** | **1** | `previewCreditBatch` / `createActivity` / `sendNotification` / `publishAnnouncement` / `generateActivitySchedule` / `generateActivityTasks` / `draftArticleFromActivity` 等 8 项写能力<b>全部不可见</b> |

干事侧仅剩 11 个只读工具 + `createSeatLayout`（其权限码 `seat:edit` 干事恰有）。
**"不可见 ⇒ 不可诱导"从设计假设变为实测事实**——LLM 拿不到这些工具的定义，无从诱导。

**修复的 6 个真实缺陷**：

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | 每轮对话都降级为规则模式（`degraded=true`） | `buildContext` 把历史 `role="tool"` 消息当普通消息回灌；OpenAI 兼容协议要求 `tool` 消息<b>必须带 `tool_call_id`</b> 且前一条必须是带 `tool_calls` 的 assistant。DeepSeek 严格校验 → 恒 400 `missing field tool_call_id` | ① `ChatMessage` 增加 `toolCallId` / `toolCalls` 字段与命名工厂；② `DeepSeekProvider` 正确序列化 `tool_call_id` 与 `tool_calls`（`arguments` 输出为 JSON <b>字符串</b>）；③ **历史不回灌 tool 消息**（工具结果是"那轮的证据"而非"对话内容"） |
| 2 | 写操作清单生成时抛 `UnexpectedRollbackException`，整轮失败 | `AuditService` 用 `REQUIRES_NEW`，异常在<b>代理 commit 阶段</b>抛出，方法体内 `try/catch` 捕不到 → 冒泡打断主链路 | ① `saveAndFlush` 把异常提前到方法体内；② `noRollbackFor = Exception.class`；③ 编排层加 `safeAudit()` 兜底壳 |
| 3 | 清单自相矛盾：同一人既在"将加分"又在"将跳过（未签到）" | 名册同一人多行且字段集不同（简版 2 字段 / 详版 7 字段）；`SigninRosterService.matches()` 要求"<b>所有非空字段</b>相等"，详版行带签到记录里没有的字段 → 判为未签到 | `PreviewCreditBatchTool` 按姓名聚合去重，取"任一行已签到即已签到"的最宽口径，并把重复行数如实写入明细 |
| 4 | **确认接口全员 500**（`TransactionRequiredException: No active transaction for update or delete query`） | `confirm()` 里的 `claimForRun` / `finish` 是 `@Modifying` 更新语句，JPA 要求必须在事务内执行；`createPlan` 自带 `@Transactional` 所以生成清单正常，掩盖了 `confirm` 缺事务的问题 | **事务下沉到仓储方法**：`claimForRun` / `finish` 各自声明 `@Transactional`（抢占与落终态独立提交），`confirm()` **刻意不开整体事务**——若整体开事务，`writeTool.commit` 内部业务 Service（REQUIRED 加入同一共享事务）抛异常会把共享事务标 rollback-only，catch 后仍抛 `UnexpectedRollbackException`（与缺陷 2 同款陷阱）。附带修复两个次生问题：确认响应此前返回过期状态（持久化上下文陈旧实体），下沉后 `findById` 天然读到终态；`reversible` 改为结构化解析（JSON 列回读序列化可能带空格，字符串 `contains` 误判） |
| 5 | **座位表模块全链路瘫痪**（`Unknown column 'sl1_0.color_labels'`），Agent 的 `createSeatLayout` 确认执行 FAILED | `pams_db` 先于 Flyway 由早期脚本建表；V8 的 `CREATE TABLE IF NOT EXISTS` 对已存在的旧结构 `seat_layout` 是空操作，实体映射的 `color_labels` 列从未建出。列表接口走投影幸免，<b>一切实体级查询（编辑器详情/模板列表/模板复制）全炸</b> | `V25__fix_seat_layout_color_labels.sql`：information_schema 判存 + `PREPARE` 动态执行——本库真实加列，全新库（V8 已含该列）自动跳过，双向幂等。**这是 Agent 回归踩出来的既有缺陷，不是 Agent 代码问题** |
| 6 | **所有写类工具的 dry-run 都没有审计行**（`agent_audit_log` 里 `generateActivityTasks` / `draftPlanSections` 0 行，而读类工具正常） | `side_effect` 列是 `VARCHAR(10)`（V24 契约 `READ/WRITE`），而编排器 `safeAudit` 传的是 **`"WRITE_PREVIEW"`（13 字符）** → 插入报 `Data truncation` → 因保存走 `saveAndFlush`（缺陷 2 的修复使得异常在方法体内抛出并被 `AuditService.log` catch），**参与同一物理事务的仓储方法已把事务标为 rollback-only** → 代理 commit 抛 `UnexpectedRollbackException` → 被 `safeAudit()` 兜成一条 WARN。**后果是"审计静默丢失"，日志只说"旁路异常已忽略"，极易漏诊** | 取值改为 **`"PREVIEW"`**（7 字符，仍在 10 字符契约内）：`AgentOrchestrator.safeAudit`。另在方法上加注释固化"取值必须 ≤10 字符"这一约束——这是**缺陷 2 的真正根因**，也是它"残留少一条审计"的闭合 |

> **2026-09-14 补记**：缺陷 **2** 的"三重兜底"只是把硬失败降级为"审计少一条 + 一条 WARN"，**真正根因在缺陷 6**（`side_effect` 超长导致插入失败、事务被标 rollback-only）。缺陷 6 修复后，"写类工具 dry-run 无审计行"这一残留已闭合——回归 `f5-audit.mjs` + SQL 断言 `side_effect='PREVIEW'` 落库、后端 0 条审计告警。
>
> **v1.8 补记（2026-10-05）：出网链路另有两处"接线未通电"式缺陷（已修复，改动 D4 前必读）**——① `PolicyGuard.egress` 曾"算了但没用"：编排器拿到返回值只用于判断 `restricted`，真正进入上下文的文本却取自工具原文 ⇒ **`SENSITIVE` 的遮罩从未生效**（审计 A2，已修复）；② 学号遮罩正则曾"定义却从未被调用"（同批修复），遮罩顺序固化 `ID_CARD → PHONE → STUDENT_NO`（长模式优先，否则身份证会被手机号规则先咬掉）。**教训：安全函数单测通过 ≠ 闸口生效——任何改动 `PolicyGuard` 或上下文组装点的变更，必须端到端断言"进入 LLM 请求的文本确实已遮罩 / 被拦截"。**
>
> **运维提醒**：`spring-boot:run` 会 **fork 子 JVM**；`TaskStop`/杀 Maven 包装进程**不会连带杀掉它**，旧子进程会继续占着 8080 跑**旧代码**，而新启动只会报 "Port 8080 was already in use"。表现为"改了代码但行为没变"。排查：`netstat -ano | findstr :8080` 取 PID → `taskkill /F /T /PID <pid>`。

**确认门禁（D2）全链路实测**（第二轮回归，2026-09-13）：

| 步骤 | 结果 |
|---|---|
| teacher 批量加分 dry-run → 清单 | ✅ 20 项 · skipped 0 · 明细含"名册 40 行重复已合并"提示 |
| 确认执行（正确一次性令牌） | ✅ 响应终态 `SUCCESS`；`credit_record` 1→21 行；审计 `confirmed_by` / `affected_count=20` |
| 幂等重放（同令牌再确认） | ✅ 返回既有结果不报错；`credit_record` 仍 21 行，无二次写入 |
| 业务级重复预判（再次发起同批加分） | ✅ 全部判"本活动已加过分"，0 项可执行 → **不生成空任务** |
| 越权：STAFF 持 teacher 任务确认/取消/看详情 | ✅ 一律 `4404 任务不存在或无权访问` |
| 伪造令牌 | ✅ `4403 确认令牌无效或已过期` |
| 取消后确认 | ✅ 返回 `CANCELLED` 快照，绝不执行；再取消 → `4400` |
| STAFF 唯一写工具 `createSeatLayout` 全链路 | ✅（V25 修复后）清单含模板名"报告厅" → 确认 → `SUCCESS` + 引用链接 `/activity/1/seat` |
| 执行失败路径（V25 修复前的同一链路） | ✅ 恰好实证：任务落 `FAILED` + `result.message` 带根因，接口 200 优雅返回，重放幂等 |
| 会话强隔离 / 软删后访问 | ✅ 跨用户读写一律 `4404` |
| 过程事件补拉 `GET /streams/{id}?since=` | ✅ 事件序列 `THINKING→TOOL_START→TOOL_END→MESSAGE_DONE` 可完整回放（seq 不保证连续，按 `since` 语义过滤） |

> 任务状态机在实测中跑满了全部主要分支：`PENDING_CONFIRM → CANCELLED ×6`、`→ SUCCESS ×2`、`→ FAILED ×1`（9 个任务）。
> 写操作审计 3/3 全覆盖（成功 20+1 条、失败 0 条，`confirmed_by` 均有值）。

**遗留的既有缺陷（不在 Agent 范围，但已记录）**：

- `SigninRosterService.matches()` 的"全字段相等"匹配口径，对**字段集不一致的同一人多行**会给出矛盾判定。
  根因在签到模块，建议后续在名册导入时按身份做幂等去重，或把匹配口径改为"姓名 + 学号"命中即算签到。
- 实测活动 1 名册 61 行仅 41 个唯一身份，存在 20 组完全重复的 `fields_json`。
- **schema 漂移风险（V25 只修了 seat_layout）**：凡先于 Flyway 存在的表，其迁移文件里"事后补进 CREATE TABLE 的列"都可能同样缺失。
  V25 的 information_schema 判存范式可直接复用；建议后续做一次"实体字段 ↔ 表结构"全量比对脚本化体检。

**工具清单偏差（v1.8 更新至 2026-10-05：当前 21 实现 = 读 11 / 写 10，vs 规划 23）**：

| 规划 # | 规划中的工具 | 落地情况（核对源码） |
|---|---|---|
| 4 | `queryActivityDetailForWriting` | 未单独实现——生成类工具自带素材白名单，能力已覆盖 |
| 8 | `querySchedule` | 未单独实现——排班 / 空闲由 `queryCommonFreeTime` 覆盖 |
| 14 | `checkPlanCompleteness` | 未实现（《通用化与多Agent协作改造策划》仍标注「待实现」） |
| 15 | `draftPlanSections` | ✅ **已落地**（v1.7 §3.10.1；本表旧版"未实现"已作废） |
| 16 | `draftArticleFromActivity` | ✅ 已落地 |
| 17 | `draftNewsFromActivity` | 未实现（二期） |
| 18 | `draftAnnouncement` | **演进落地为 `publishAnnouncement`**：草稿预览 + 确认后发布一体化，`notice:publish` / normal / 不可撤回（发布走 D2 门禁） |
| 19 | `previewCreditBatch` + `commitCreditBatch` | **合并为单工具双模**（dry-run / commit）——一个工具对应一个待确认任务，更契合 D2 门禁 |
| 20 | `generateAgendaFromPlan` | 未实现（二期） |
| 21 | `generateActivitySchedule` | ✅ 已落地 |
| 22 | `archiveActivityMaterials` | 未实现（二期） |
| 23 | `importPartyRoster` | 未实现（二期） |
| — | 实现期新增 | `createActivity`、`generateActivityTasks`、`sendNotification`、`createSeatLayout`、`createMember`（高频写操作，均走确认门禁；名称类参数与 §3.9 解析联动） |

> 合计：规划已落地 15（读 11 / 写 4）+ 规划演进 1（#18）+ 实现期新增 5 = **21 个（读 11 / 写 10）**。

> `sendNotification` 依赖的 `notification:send` 权限码原库不存在，已在 `V24__agent.sql` 中补种并授予部长及以上角色。
> **注意**：写错权限码的后果不是报错，而是工具**永久不可见**（静默失效），排查成本很高。

### 4.4 明确不做 / 缓做（含判断依据）

| 项 | 决定 | 依据 |
|---|---|---|
| 向量数据库 / RAG 框架（LangChain 等） | **不做** | 结构化检索已覆盖九成需求；引入新组件与当前的轻量部署模型冲突（D6）。二期用 MySQL 内置 ngram 全文索引替代 |
| 独立 Agent 微服务 / 网关进程 | **不做** | 单体足够；拆分会带来跨进程鉴权、上下文传递、部署复杂度，收益极低 |
| Redis / MQ | **不做** | 单实例、低并发；Spring 事件 + 线程池 + WebSocket 已足够（D7） |
| Agent 自动审批 / 自动发布 | **不做** | 违背铁律②；党务与内容发布必须人工确认 |
| Agent 调用 `/api/signins/scan` | **永久禁止** | 免登录公开接口，调用即绕过鉴权 |
| 在 Agent 回复中给出 `/uploads/**` 直链 | **禁止** | 统一走 `/api/files/{id}/download`（带权限门控） |
| 课表截图 OCR 识别导入 | **缓做** | 无 OCR/解析管线，投入产出比低（调研文档已标"就绪度低"） |
| 移动端适配 | **不做** | 与 PRD-v5 保持一致（当前 1280px+ PC 口径） |

> **v1.8 现状核对**：向量库 / RAG 框架"不做"**维持**——知识库已按"关键词检索型 RAG"落地（V27/V28），一期无任何向量组件；独立 Agent 服务"不做"**维持**——多 Agent 为进程内自研实现（V32/V33），LangChain4j / Spring AI / AutoGen 路径的否决结论见《通用化与多Agent协作改造策划》§6 D2；Redis / MQ"不做"**维持**（单实例前提未变，D7 边界记录继续有效）。

### 4.5 验收标准

**功能**
1. 5 类高频问答（待办/活动任务/材料/通知/排班空闲）在**离线模式**下全部可用且答案正确。
2. 一条完整文书链路：从活动详情一键生成报道推文草稿 → 落 `DRAFT` → 用户可在编辑页修改并走原有审核流。
3. 一条完整批量链路：素拓批量加分 → 拟执行清单（含 skipped）→ 确认 → 执行 → 通知推送 → 可按 `batchId` 撤回。
4. `restricted` 数据（函调家系）：Agent 明确拒绝并给深链，**不出现在任何 LLM 请求日志中**。

**安全**
5. 越权矩阵：干事账号调用 9 个写类工具，**全部 403 或被工具不可见过滤**。
6. 写操作 100% 生成 `agent_audit_log` 记录（含确认人、确认时间）。
7. 生成入库的 HTML 通过净化单测（含 `<script>`、`onerror=`、`javascript:` 用例）。

**工程**
8. `mvn test` 与 `npx tsc --noEmit` 全绿；新增 Agent 相关测试类 ≥ 8 个（含权限断言与净化用例）。
9. **响应体感双指标**（2026-09-13 拆分，原因见下）：
   - **首事件延迟（TTFE）P95 < 800ms** —— 用户发送 → 收到第一个 `THINKING` 事件。**这是"有没有反应"的开关，是流式生效与否的判定标准。**
   - **总时长 P95 < 3s**（不含文书生成）—— 用户发送 → `MESSAGE_DONE`。
10. 长任务经 WebSocket 推送，前端可无刷新收到结果；轮询兜底通道（`GET /api/agent/streams/{id}?since={seq}`）可补齐断连缺口。

> **为什么要拆成两个指标**：原口径只有一个"P95 < 3s"，那是**按"一次性返回"设计的**。改为事件级流式后，用户的体感时钟从"发出去 → 答复出现"变成了"发出去 → **第一个事件**出现"——两者是完全不同的量。一个指标盖两件事会导致**真实体验退化被掩盖**：假如首事件要 1.5s 才出现（如编排器先做完意图分类才推 `THINKING`），即便总时长 2.8s、P95 达标，用户依然会觉得"点了没反应"；反之若首事件 200ms 就到，总时长 8s 用户也能接受。**TTFE 是新增的硬指标，不是补充说明。**

**可维护性（对应 D11）**
11. **契约登记清单**建立：列出所有"不破坏编译但会改变 Agent 行为"的依赖点（脱敏标志、`SecurityContext` 操作人、枚举中文 label、权限码），并确认清单条目 ≤ 10 项。
12. 异步工具执行时 `SecurityContext` 传播正确：断言 `currentUserId()` 非空（防审计字段静默为空）。
13. 任取一个业务模块做一次"Service 加参数 + 加返回字段"的改动，**Agent 侧零改动**且编译通过——证明"透明变更自动跟随"成立。

---

## 5. 实施路线图

| 批次 | 主题 | 交付物 | 出口条件 |
|---|---|---|---|
| **B0** | Agent 基座 | **`V24`** 迁移、`LlmProvider`(DeepSeek+Stub)、`AgentOrchestrator`、`ToolRegistry`、`PolicyGuard`、审计、`AgentController`、前端抽屉骨架 | 离线可跑通"你好 → 规则回复"；工具权限断言单测通过 |
| **B0.5** | 安全补口 | Jsoup 净化 + 单测 | 净化用例全绿 |
| **B1** | 读类问答 | 7 个读工具 + 结果卡 + 5 类问答调优 | 4.5 标准 1 达成 |
| **B2** | 写类与 Copilot | 确认卡 + 7 个写工具 + 编辑器联动 | 4.5 标准 2、3 达成 |
| **B3** | 敏感模块 | 脱敏强化 + 同名消歧 + 权限回归矩阵 | 4.5 标准 4、5、6 达成 |
| **B4** | 检索增强 + 演示串联 | ngram 全文索引 + 演示脚本 + 离线打磨 | 4.5 标准 7、8、9 达成 |

**演示故事线（B4 产出）**：
> 创建活动 → AI 起草策划书（就地 Copilot）→ 人工审核 → AI 生成报道推文 → 一键生成活动排班 → 按签到批量加素拓分（确认门禁）→ 材料归档完整性检查 → 通知串联提醒 → 小助回答"这个活动现在什么状态"

这条链路把**三种形态全部覆盖**，且每一步都落到真实表结构与真实接口上。

---

## 6. 决策待办（需拍板）

| # | 决策项 | 选项 | 建议 |
|---|---|---|---|
| 1 | LLM 供应商 | DeepSeek API / 其他国产 API / 纯本地 Ollama | **DeepSeek API 为主 + Stub 兜底**；`sensitive` 走"仅摘要出网"；Ollama 列为二期可选项。**详见 D5 的"供应商选型详述"**（含"按数据分级路由 provider"的建议） |
| 2 | 服务端 HTML 净化是否本期做 | 做 / 不做 | **必须做**（约 1 人日）——不做则文书生成不能上线。**且应顺带覆盖人工写路径**（零额外成本，见 D10） |
| 3 | content 模块权限码是否本期收敛（G1） | 收敛到 `content:manage` / 维持 `LEADER` 宏 | **本期不收敛，维持 `LEADER` 等价口径**——收敛是 S2 的职责，Agent 顺手改会撞车且要改两遍。**详见 D3 末尾的论证** |
| 4 | 流式输出是否本期做（G3） | 事件级一期+字符级二期 / 全二期 / 全一期 | **事件级一期做、字符级二期**（2026-09-13 修订，原建议"全二期"）。事件级流式成本 0.5–1 人日、收益是"等待可感知 + 事后可追溯"，不做会让一期在等待期只有一个静止动画 |
| 5 | 检索是否上 ngram FULLTEXT（G5） | 一期 / 二期 | **二期，且按触发条件启动**（出现真实"搜不到"反馈时）。**DDL 与 `BOOLEAN MODE` 细节已写入 D6**，含 `ngram_token_size` 限制 |
| 6 | `agent_message` 敏感内容的留存策略 | 只存摘要 / 加密存储 / 定期清理 | **只存摘要 + 分角色差异化留存**（见下方详述） |
| 7 | 过程事件的传输通道（原灵思 §11 第 8 项） | STOMP 用户定向队列 / 轮询 | **✅ 已确认可行，无需拍板**（2026-09-13 源码核实）：`NotificationEventListener` 以普通 `@Component` 注入 `SimpMessagingTemplate` 并调 `convertAndSendToUser(username, dest, payload)`，**无任何线程特判**，证明 `AgentOrchestrator` 在自己的线程池里推事件完全可行。**采用 STOMP `/user/queue/agent/stream` 为主通道，轮询仅作断连补拉兜底** |
| 8 | 首事件延迟（TTFE）阈值 | 800ms / 1.5s / 不设指标 | **P95 < 800ms**。理由见验收标准 9 的说明：TTFE 是"流式是否真的生效"的判定标准，不设指标等于放任"做了流式但体感没变" |

> **v1.8 现状核对（2026-10-05，逐项闭环）**：#1 已执行——DeepSeek + Stub 落地，Ollama 未接、分级路由未实现（见 D5）；#2 ✅ 已落地（`HtmlSanitizer` + jsoup；人工路径收口仍缺，见 §0.5.2）；#3 按原建议执行——content 仍维持 `LEADER` 等价口径，未收敛（现势复核）；#4 事件级 ✅ 且**字符级也已落地**（见 D7）；#5 ✅ 已落地并被《知识库问答方案》扩展（V27/V28）；#6 已落地（写时脱敏），原表"90 天"勘误见决策 6 详述；#7 ✅ 已落地；#8 维持阈值不变。**8 项决策全部闭环，无未决项；后续变更走 §0.5.3 第 4 条维护义务。**

#### 决策 6 详述：`agent_message` 留存策略（建议：只存摘要 + 分角色差异化）

原建议"只存摘要 + 90 天清理"方向正确，但**"一刀切 90 天"有问题**——它会让"我上个月问过的那份材料在哪"这类正常回溯失效。建议改为**按数据分级差异化**（v1.8 进一步修正：因敏感内容在写入时既已脱敏为摘要，"留存期"维度整体取消——见下方勘误注）：

| 消息类型 | 存储内容 | 留存期 | 理由 |
|---|---|---|---|
| `normal` 问答（活动、材料、通知） | **完整文本** | **永久**（随会话） | 这是用户的"工作记忆"，清掉会让功能变难用。且这些内容本身就是用户有权看的业务数据，存着不增加暴露面 |
| `sensitive` 工具结果 | **仅摘要**（如"查到 42 人签到"而非名单） | 随会话保留（不设独立清理任务） | 与 D4 一致：敏感明细不入会话明文；摘要足够支撑"我当时问过什么"的记忆 |
| `restricted` 工具结果 | **不落内容**，仅存"已拒绝 + 深链"的占位 | 随会话保留 | 内容根本没进 LLM 上下文（D4），无内容可存 |
| `agent_tool_call`（调用流水） | **不含结果内容**，只有 `tool_name / args_digest / duration / status` | **永久** | 审计用途（D9），与 `agent_audit_log` 同性质，属于"必须留" |

**实现方式**：不引入定时任务（项目已有 `AttendanceAutoJob`，但这条不需要那么重）——**在写入时判定 `sensitivity`，直接把不该存的内容替换成摘要**（写时就脱敏，而非事后清理）。这样连"90 天后清理任务"都可以省掉，因为**敏感内容从未被写入**。

> **v1.8 勘误与现状**：原表 sensitive / restricted 两行写"90 天"留存，与"写时脱敏、不引入清理任务"自相矛盾——明细既从未落库，"90 天清理"无对象可清；已按实现修正为"随会话保留"。现状核对：`PolicyGuard.forStorage` 已按写时脱敏落地（normal 全文 / sensitive 摘要 / restricted 占位），**代码中不存在任何会话清理任务**；若未来要求摘要行也不长期保留，再单独设计（当前非需求）。

> **⚠️ 一个必须说清的取舍**：如果 `sensitive` 消息只存摘要，那么**用户回看历史会话时会发现"当时的细节不见了"**。这不是 bug，是设计——需要在 UI 上说明（灵思 §7.2 的受限数据块已承担此职责）。**如果不说明，用户会以为系统丢了数据。**

---

## 附：与调研文档的机会点映射（避免能力遗漏）

| 调研文档机会点 | 本方案落位 |
|---|---|
| 3.1 策划书初稿起草 / 章节填充 / 完整性检查 / 状态推进建议 / 议程生成 / 逾期问答 / 任务分解 | 工具 15 / 14 / 4 / 3、入口 B；任务分解**暂不做**（批量建任务会触发部门通知风暴，价值/风险比不划算） |
| 3.2 推文与新闻稿草稿 / 编辑页助手 / 审核意见辅助 / 事实核查 / 预热稿 | 工具 16、17；编辑页助手归入口 B（二期）；事实核查在 D10；预热稿**并入工具 16**（同一素材源，没必要拆分） |
| 3.3 素拓批量加分 / 签到进度问答 / 异常检测 / 座位着色 / 催签补签 / 名单预检 | 工具 19 / 13、入口 C；座位着色与补签**缓做**（敏感且收益低）；名单预检归入口 B |
| 3.4 排班生成 / 排班问答 / 冲突检测 / 考勤通报 / 空闲查询 / 热力图解读 / 截图识别 | 工具 21（**已降本**）、8 / 9 / 10；冲突检测并入工具 21；热力图解读归入口 B；截图识别**不做** |
| 3.5 党员进度问答 / 名册导入 / 草稿回填 / 台账统计 / 流转预检 / 函调函草稿 | 工具 11 / 23；草稿回填归入口 B；台账统计并入工具 11；流转预检**缓做**；函调函草稿**不做**（`restricted`，收益低风险高） |
| 3.6 材料检索 / 生成文档落库 / 素拓助手 / 公告草稿 / 素拓台账 / 归档检查 / 类型建议 | 工具 5 / 22 / 18 / 12；生成文档落库走 `archiveActivityMaterials` 的落库分支 |
| 3.7 待办问答 / 催办提醒 / 今日简报 / 审核台导航 / 群聊归档 / 群聊检索 / 建群预填 | 工具 1 / 7；**催办提醒缓做**（需新增受保护的通知发送通道，当前 `NotificationController` 无写接口）；今日简报并入工具 1；群聊归档/预填归入口 C（低优先） |

**未纳入首期的机会点不是被否定**，而是按"价值 / 风险 / 依赖"排序后暂缓，待 B4 复盘时重排。

---

> **文档结束**。本稿的"现状"部分已逐项核对至 2026-09-13 的源码与迁移链（当时 **V1–V23**）；**v1.8 已补充核对至 2026-10-05（Flyway V1–V34），当前状态与使用须知见 §0.5**。技术方案部分均给出取舍理由与落地路径。第 6 节 8 项决策已全部闭环（见 §6 的 v1.8 现状核对）。
