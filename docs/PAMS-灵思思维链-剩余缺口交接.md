# 灵思思维链展示 · 剩余缺口交接单

> 日期：2026-10-12
> 背景：对「灵思过程区/时间轴的思维链展示」做过一轮只读审计（A 内容完整性 / B 视觉排版 / C 交互折叠 / D 性能与无障碍），
> 其中**后端导出链路 + 前端过程区/时间轴/store/api 契约**已由另一条并行会话在 21:35–21:41 落地并自测通过。
> 本文只记录**尚未完成的三件事**，供接手方直接执行；不含已落地部分的重复描述。
> 范围：`pams-backend/**`、`pams-web/src/{api/agent.ts,stores/agent.ts,components/agent/**,styles/lingsi.css}`。
> ⚠️ 接手前请确认那条并行会话已停手，否则同一批文件会互相覆盖。

## 进度更新（2026-10-12 21:5x，接手前请先读这一段）

> **最新（21:58 复核）：三条缺口已全部落地，前端接线也已完成。** 下表是 21:55 那一版的状态，保留作为演进记录；
> 复核结论见本节末尾的「独立验证」。

那条并行会话在 21:44–21:49 又推进了一批（随后因额度中断，已续跑），本文 §1–§3 的状态变为：

| 缺口 | 现在的状态 |
|---|---|
| §1 工具行下钻 `argsJson` | **后端已完成**：`PolicyGuard.egressArgs(sensitivity, sanitizedArgsJson)`（normal 给遮罩明文、sensitive/restricted 给 null，闸门刻意放在读侧以免动审计口径）+ `AgentOrchestrator` 已把 `argsJson` 放进 toolCallEntry map + 前端 `api/agent.ts` 已声明 `AgentToolCallEntry.argsJson?: string \| null`。**只差前端接线**：`TimelineItem` 带 `argsJson` + 工具行下钻 UI（`stores/agent.ts` / `components/agent/**` 目前对 `argsJson` 零引用）。 |
| §2 编排旁白回放 | **后端已完成**：新增 `thinking_notes_json` 列与 `ThinkingTrace.notesJson()`，`AgentOrchestrator` 与 `KbPipeline` 都收集旁白，VO 上是 `thinkingNotes?: AgentThinkingNote[]`。**只差前端接线**（`stores/agent.ts` / `utils/thinking.ts` / 组件对 `thinkingNotes` 零引用）。 |
| §3 V35 幂等 | **已解决**：新建 `V36__agent_message_thinking_notes.sql`（h2 + mysql 两份），判存 + `PREPARE` 范式，并注明「V35 曾 failed 的库需 `flyway repair` 后 V36 才生效」；未改历史迁移（正确）。 |
| §3 附注（sensitive 口径） | **仍未拍板**，实现方已在 `ConversationStore.save` 的 javadoc 里显式标注「目前整列不落而非 D4 的仅存摘要，口径待产品拍板」——即**故意保留待决**，不是漏做。 |

**完整性已验证**（中断后复核，两边都能编译）：前端 `tsc -p tsconfig.app.json --noEmit` → EXIT=0；后端 `%TEMP%` 副本 `mvn -o -DskipTests compile` → BUILD SUCCESS（418 源文件，0 error，仅 2 条既有 warning）。

### 独立验证（21:58，由另一会话复核，非实现方自述）

| 验证项 | 命令 / 方法 | 结果 |
|---|---|---|
| 前端类型 | `tsc -p tsconfig.app.json --noEmit --incremental false --composite false` | **EXIT=0** |
| 后端测试 | `%TEMP%` 副本 `mvn -o -Dtest='PolicyGuardEgressTest,ThinkingTraceTest,BenchmarkServiceTest,DeepSeekStreamTest' test` | **Tests run: 30, Failures: 0, Errors: 0 — BUILD SUCCESS** |
| 迁移是否真跑 | 同上日志中的 Flyway 行 | 真跑到 `35 - agent message thinking` 与 `36 - agent message thinking notes`（H2 分支） |
| 前端接线是否真接上 | 在 `stores/agent.ts` / `utils/thinking.ts` / `components/agent/*.tsx` 里检索 `thinkingNotes` / `argsJson` / `hasTeamPanel` / `deltaBuffer` | 命中：store 18 处、thinking 4 处、ProcessZone 3 处、TeamPanel 3 处、TimelineRow 2 处（此前为 0） |
| 别回退清单是否健在 | 检索 §5 的 4 处 lingsi.css 锚点 | 4/4 命中 |

**仍未验证（接手方请补齐）**
1. `vitest` 全套（含新增的 `thinking.test.ts` 8 条用例）——本沙箱 vite 加载配置要 spawn esbuild，`spawn EPERM`，务必在沙箱外跑 `npm test`。
2. **MySQL 版 V36 未在真库执行**（只跑了 H2 分支）。MySQL 分支是 `information_schema` + `PREPARE` 幂等范式；若某库 V35 曾 failed，Flyway 会拒绝继续，需先 `flyway repair`（SQL 注释里已写）。
3. 真实 reasoner 端到端（需 API Key）：验证 `reasoning_content` 真落库并在前端流式展开。
4. 浏览器真实渲染：入参折叠块、旁白行、去重后的时间轴、行高与截断观感。
5. **工具行与推理段的精确交错仍做不到**：落库的工具调用摘要不带时间戳，无法与推理段按时间重排（已在 `utils/thinking.ts` 注释中标注为后续可做）。

**一条与本次改动无关的残余日志**（供知情，不是回归）：测试日志里有 `Table "statistics" not found`，来自 `module/knowledge/service/KnowledgeIndexInitializer.java:67` 的 `information_schema.STATISTICS` —— 那是 MySQL 专有的索引内省写法，H2 下不存在；异常被吞掉，测试仍全绿。

---

## 0. 已落地（勿重复实现、勿回退）

| 项 | 状态 | 落点 |
|---|---|---|
| `THINKING_DELTA` 实时推送（payload `{round,text}`，沿用 `AgentStreamEvent.thinkingDelta()` 工厂） | 已做 | `AgentLlmCaller` 的 `ReasoningBatch`（长度 48 字符 / 80ms 双阈值；重试 `round+1000`、降级 `round+2000`、失败 `discard` + `ThinkingTrace.drop`） |
| 推理按「LLM 调用轮次」分段落库 | 已做 | `ThinkingTrace`（单例、LRU 200 流 / 每流 40 段 / 每段 8000 字）+ `agent_message.thinking_json`（V35，h2 与 mysql 两份） |
| 历史回放字段 | 已做 | 历史 `ConversationStore.toVo` 与 `/chat` 响应均带 `thinking: AgentThinkingStep[]`；前端 `api/agent.ts` 已定义 `AgentThinkingStep{round,text,startedAt?,endedAt?,durationMs?}` |
| 前端实时/历史消费 | 已做 | `stores/agent.ts`、`components/agent/LingsiProcessZone.tsx`、`LingsiTimelineRow.tsx`、`utils/thinking.ts`（含 `thinking.test.ts`）、`styles/lingsi.css` |
| 后端编译 | 通过 | 见 §4 的验证方法（工作区直接 `mvn` 会被沙箱拦，需用 TEMP 副本） |

---

## 1. 【缺口一】工具行下钻：`argsJson` 前后端都没有

**现状证据**
- 写入侧本来是齐的：`AgentOrchestrator.recordToolRow()` → `AgentEventPublisher.recordToolCall()` → `agent_tool_call.args_json`。
- **读侧完全没接**：`AgentToolCallEntry`（前端同名接口）只有 `tool / label / status / durationMs / write / sensitivity / error`；`args_json` 从不回读；`AgentEventPublisher.rebuildFromDb()` 只发 `toolEnd`，不带入参。
- 因此前端「调用 3 项能力」这行点不开，看不到本次入参。

**口径（很重要，别搞错）**
- `args_json` 存的**不是明文**：`recordToolRow` 传的是 `auditService.toJson(call.arguments())`，内部走 `PolicyGuard.sanitizeArgs()`（idCard/phone/mobile 键置 `***`、字符串值过 `mask()` 遮罩证件/手机/学号）。所以它是「遮罩后的明文」。
- 但 `docs/PAMS-Agent业务设计方案.md` §表结构（第 437/998 行）写的是 `args_json(脱敏)`，且要求 `sensitive` / `restricted` 的入参**不落明文**。
- 因此下发时**必须自建读侧闸门**（不要去改写入侧，那是审计口径）：

  | `AgentToolCall.sensitivity` | 下发给前端 |
  |---|---|
  | `normal` | `argsJson` = 原字符串 |
  | `sensitive` | `null` |
  | 其它（restricted 等） | `null` |

**要做的**
1. 后端：历史 VO 与 `/chat` 的 `toolCalls[]` 元素增加 `argsJson`（按上表闸门取值），并在字段上写明这条规则与依据。
2. 前端：`AgentToolCallEntry` 增加 `argsJson?: string | null`；`TimelineItem` 增加 `argsJson?: string`（由 `timelineFromToolCalls` 带入）；工具行提供下钻入口（抽屉/气泡展示本次入参 JSON）。**没有 `argsJson` 就是"不可下钻"**，不要在前端再猜敏感度。
3. 实时态（回合进行中）拿不到入参是正常的：`res.toolCalls` 在回合结束才返回，届时该轮 timeline 由它重建。若要在流式期间就能下钻，需另发 `TOOL_START` 的 args（默认**不做**，避免新增披露面）——要做请先确认口径。

**验收**：历史回合里每个 `normal` 工具行可展开看到入参且值已遮罩；`sensitive` 工具行无下钻入口。

---

## 2. 【缺口二】编排旁白（`THINKING`）无法历史回放

**现状证据**
- 旁白只有实时事件：`AgentOrchestrator`（「正在理解你的问题…」）与 `KbPipeline.emitNote()`。
- **没有任何收集器**，`ThinkingTrace` 只装模型推理分段。所以 `thinking_json` 里永远不会有旁白行，刷新后过程区只剩工具行——与刚才读到的不是同一条时间轴。

**要做的**
1. 后端：本轮每发一条 `THINKING` 时收集 `{note, ts, durationMs}`（`durationMs` = 下一行 ts − 本行 ts，取不到就留空）；收官时与推理分段**一起**落库。
   - 落库形态二选一，**接手方定一个并写进注释**：①新增列 `thinking_notes_json`；②把一个对象 `{notes:[…], reasoning:[…]}` 写进现有 `thinking_json`（改动小，但要同时改历史解析与前端类型）。
   - `KbPipeline` 也要收集（它是知识库问答链路，与办事型链路并列）。
2. 前端：历史回放时把旁白行按 `ts` 排进时间轴（推理段在各轮旁白之后），并沿用同一套 `utils/thinking.ts` 的格式化，不要另写一份。

**验收**：刷新页面后，历史回合的过程区同时有「旁白行 + 推理段 + 工具行」，顺序与刚才实时看到的一致。

---

## 3. 【缺口三】V35 迁移不是幂等写法

**现状证据**
- `db/migration-mysql/V35__agent_message_thinking.sql` 与 `db/migration-h2/V35__agent_message_thinking.sql` 都是裸 `ALTER TABLE agent_message ADD COLUMN thinking_json ...`。
- 仓库范式是 `information_schema` 判存 + `PREPARE` 动态 DDL（对照 `migration-mysql/V25`、`V26`、`V27:119-148`），保证重跑/列已存在不炸。
- 两库列名与类型一致（`thinking_json JSON NULL`，mysql 多内联 COMMENT），Flyway 位置无误（`application.yml` prod = `db/migration,db/migration-mysql`；test 追加 `migration-h2`）。

**要做的**：把两份 V35 改成判存 + `PREPARE` 范式（**不要改版本号**，V35 可能已在别人机器上执行过）。若已执行过，则新建 V36 做修正而不是改历史迁移。

**附带（口径待拍板）**：`ConversationStore` 现在的档位是「只有 `NORMAL` 落推理，`SENSITIVE` 与 `RESTRICTED` 都直接不落」，而文档 D4 / 决策 6 要求 `sensitive` **仅存摘要（截断 + 省略号）**。二选一，并保证注释与实现一致。
> 另注：`AgentOrchestrator` 里 `replySensitivity` 恒为 `NORMAL`（HEAD 即如此，非本次引入），所以办事型链路上这道闸门实际不触发——如果思维链要按敏感度分流，这个恒值本身也得一并核。

---

## 4. 验证方法（本会话踩过的坑，接手可直接用）

- `pams-backend` 在本会话是**只读**的（沙箱写授权只覆盖 `D:\MyApp\PAMS` 根目录，子树未继承）：直接跑 `mvn -o -DskipTests compile` 会在 `maven-resources-plugin` 写 `target/classes/application.yml` 时 `FileNotFoundException: 拒绝访问`，`EXIT=1`，**连 javac 都没到**——这不是代码错误。
  - 可用绕法：把 `pams-backend\{src,pom.xml}` 复制到 `%TEMP%` 下编译（先逐文件 MD5 比对确认与工作区一致）。实测：`Compiling 418 source files` → **BUILD SUCCESS，0 error**（仅两条既有 warning：DeepSeekProvider 过时 API、ConversationStore unchecked）。
- `pams-web`：`node .\node_modules\typescript\lib\tsc.js -p tsconfig.app.json --noEmit --incremental false --composite false` → **0 error**（注意 `tsc -b` 会因写 `node_modules/.tmp/*.tsbuildinfo` 报 `EPERM/TS5033`，是沙箱拦的，不是类型错）。
- `vitest` 在本会话**跑不起来**：vite 加载配置时 esbuild 要 spawn 子进程，沙箱返回 `spawn EPERM`（已知边界，未换法重试）。单测请在沙箱外跑 `npm test`。

---

## 5. 附：本轮已改的共享层（别回退）

同一次会话里另做过一轮「界面 UI 视觉排版对齐」，落点与灵思无关或只在共享层，改动已验证健在：

- `styles/theme.ts`：antd `Table` 补 `cellPaddingBlockMD: 10 / cellPaddingInlineMD: 12`（middle 档原本实际取 `paddingSM/paddingXS` = 12/8，导致**每个表格首列文字比同页卡片左移 4px**）。
- `styles/glass.css`：表格分页器补 12px 水平外边距；登录页标题/副标题改左对齐（原本右对齐，与左对齐的标签、输入框参差）。
- `styles/global.css`：内容区补回 20px 顶部内边距；新增 `.app-header-inner`（顶栏操作组与 1440px 内容列同轴）；仪表盘两张卡的行样式统一（行 + 底分隔线）；补上只在 JSX 里出现、样式表里没有的 `.dash-side-link/.dash-side-title`。
- `styles/lingsi.css`：整页高度 `calc(100vh - 52px - --sp-4 - --sp-5)`（原写死 `100vh - 88px` 是 64px 顶栏时代的值）；消息流内容盒与输入框左边缘对齐（水平 28px 移到外层）；输入提示行内边距与输入框正文同值；左栏子项盒子统一 10px、文字统一 22px。
- 页面层：卡片内边距统一 16（纯状态卡 40）、卡片间距统一 16；33 个表格列（22 个「操作」列 + 11 个数值/日期列 `align:'right'` + `tabular-nums`）。
