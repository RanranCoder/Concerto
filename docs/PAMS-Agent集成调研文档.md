# PAMS Agent 集成调研文档

> **文档版本**：v1.1
> **撰写日期**：2026-08-08（v1.1 现状复核：2026-09-13）
> **文档状态**：调研结论，**已被 [PAMS-Agent业务设计方案.md](PAMS-Agent业务设计方案.md)（2026-09-13）承接与勘误**
> **调研方式**：8 个并行调研 agent 通读后端全部模块源码（entity / service / controller / Flyway 迁移）+ 前端页面，所有机会点均基于真实存在的表结构与接口，不包含臆造能力。
> **v1.1 变更**：项目功能已推进 5 周（Flyway V17 → **V23**），本节及各模块的"前置约束 / 权限缺口 / 关键风险"逐条重核，**已闭合项就地标注为 ✅ 并改写为"当前真实状态"**，未闭合项保留并标注 🔶。机会点的"价值/工作量/就绪"若因新功能落地而变化，同步下调或上调。

> ⚠️ **阅读提示（2026-09-13 复核）**
> 本文档的机会点清单**依然有效**，但其中多处"前置约束 / 权限缺口 / 关键风险"**已在 08-08 之后的 5 周内修复**（Flyway 由 V17 推进到 **V23**）。
> 请以《[PAMS-Agent业务设计方案](PAMS-Agent业务设计方案.md)》第 0 节的"勘误 + 现状对齐"表为准，尤其注意：
> - 第 1 节判断 ③、第 5 节所列的"必须先修的五处权限缺口" —— **均已修复**，不再构成前置；
> - `RosterImportService` 已接线（`POST /api/files/import`）、`/uploads/**` 已改为登录鉴权（V18）、新闻稿已补发布端点、排班生成已由规则算法实现（`schedule:generate`）；
> - 因此"阶段一先修缺口、阶段二才能做 Agent"的**串行前提不再成立**。
>
> **v1.1 复核明细（逐条可追溯）**：
>
> | 位置 | 08-08 原文判断 | 09-13 复核结论 | 证据 |
> |---|---|---|---|
> | 第 1 节判断 ③ | 存在一批必须先修的权限缺口 | ✅ 已闭合 | 各控制器已挂权限码，见第 5 节表 |
> | §3.1 机会 7 | 议程自动生成「AgendaController 无 `@PreAuthorize`，需先补权限」 | ✅ 已闭合 | `hasAuthority('activity:edit')` |
> | §3.2 业务现状 | 新闻稿「无审核流且发布闭环缺失（无 publishDate/发布端点）」 | ✅ 已闭合 | `POST /api/news/{id}/publish` |
> | §3.2 本模块风险 | 「News/Article 创建/编辑/删除无 `@PreAuthorize`」 | ✅ 已闭合（🔶 未收敛到 `content:*`） | News 挂 `LEADER` 宏、Article 挂 `hasRole('MEDIA_LEADER')` |
> | §3.4 业务现状 | 「三张主表 `userId` 基本为空，靠 `personName` 关联；且三控制器无 `@PreAuthorize`」 | ✅ 大部分闭合 | V20 `schedule_user_binding` + `SchedulePerson`；权限码已挂；`FreeSchedule` 表由 V11 删除 |
> | §3.4 机会 1 | 值班排班草稿生成「前置：排班人员绑定 `userId`」，工作量=高 | ⚠️ **已由规则算法实现** | `ActivityScheduleService` + `POST /api/activity-schedules/generate`（`schedule:generate`）；工作量下调为**低** |
> | §3.5 业务现状 | 「`RosterImportService.importFromXlsx` 已实现但未接任何 Controller」 | ✅ 已接线 | `POST /api/files/import`（`party:entry`）；本项从"接线工作"降级为"预检 + 结果解读" |
> | §3.6 业务现状 | 公告「已读只存前端 localStorage、发布不触发通知」 | ✅ 已闭合 | V19 `announcement_read` + `AnnouncementPublishedEvent` |
> | §3.6 本模块风险 | 「Material/Template/Credit/File 四控制器无 `@PreAuthorize`」 | ✅ 已闭合 | `material:*` / `template:manage` / `quality:*` / `party:entry` |
> | §3.6 / §8 | 「`/uploads/**` 静态映射公开可读」 | ✅ 已闭合 | V18 `uploads_auth` → `authenticated()` |
> | §8 风险 1 | 「43 权限码只是管理数据」 | ✅ 已收敛 | `JwtAuthenticationFilter` 双轨注入，约 20 个权限码运行时生效 |
> | §3.4 机会 7 | 课表截图识别导入「无 OCR/文件解析管线」 | 🔶 仍成立 | 本期不引入 OCR |

---

## 1. 调研结论速览（TL;DR）

**一句话**：PAMS 的**所有业务模块都有 Agent 可参与的机会**，但优先级差异极大——"文书生成"与"对话查询"是最快见效的两个富矿，党务台账等敏感模块则必须加严权限与脱敏后才能参与。

**三个关键现状判断**：

1. **系统目前零 AI 集成**。`grep` 全仓无 openai/deepseek/ollama 等任何 LLM 依赖；PRD-F08 无课表的"AI 推荐"实际是纯后端算法（共同空闲统计），不是 LLM。
2. **可复用的"地基"已非常完整**：通知中心事件总线（**13 类业务事件**）、WebSocket 用户定向推送、JWT + RBAC（43 权限码，**其中约 20 个已接入请求级鉴权**）、文件上传/下载通道、仪表盘聚合、**规则算法排班生成**、**考勤审计与自动任务**——Agent 要接入所需的底座能力大半已经存在。
   - ✅ **2026-09-13 更新**：事件总线已由 7 类扩展为 **13 类**（`module/notification/event/`），并改为 **Spring 领域事件**风格——业务侧 `publishEvent(new XxxEvent(...))`，`NotificationEventListener` 以 `AFTER_COMMIT` 落库 + 推 WebSocket。Agent 的过程事件推送可**直接复用 `SimpMessagingTemplate`**（`NotificationEventListener` 已证明该模式在任意线程可用）。
3. **曾经存在的权限缺口已全部堵上（08-08 判为"必须先修"，09-13 复核已闭合）**：`AgendaController`、`NewsController`、`ArticleController`（部分）、`Material/Template/Credit/File` 四个控制器，以及 `Schedule/Attendance/FreeSchedule`，**均已挂 `@PreAuthorize`**；43 个权限码中约 20 个已在控制器生效。
   - ✅ **2026-09-13 更新：口子已堵。**上述控制器均已挂 `@PreAuthorize`（`activity:edit` / `material:upload` / `quality:add` / `template:manage` / `notice:publish` / `schedule:generate` 等），`JwtAuthenticationFilter` 已"双轨注入"角色码与权限码。仅 `content` 模块（News/Article）仍用 `LEADER` 角色宏 / `hasRole('MEDIA_LEADER')`，属 PRD-v5 的 S2 长尾。
   - ⚠️ **但风险并未归零**：`@PreAuthorize` 挂在 **Controller** 上，`Service` 方法**不带**方法级鉴权。Agent 若直调 Service 会绕过全部校验——这是本方案**最高工程风险**，需在工具层做"声明权限码 + 派发前断言 + 可见性过滤"三层防护。

**三形态 × 全模块适配总览**（价值 1-5，★=建议优先）：

| 模块 | 最佳机会（形态） | 价值 | 工作量 | 敏感度 |
|------|----------------|:---:|:---:|:---:|
| 内容宣传 | 报道/预热推文草稿生成（automation） | ★5 | 中 | 普通 |
| 活动核心 | 策划书初稿起草 / 章节智能填充（automation/copilot） | ★5 | 高 | 普通 |
| 签到/座位 | 活动批量加素拓分（automation） | ★5 | 中 | **敏感** |
| 档案材料 | 材料库自然语言检索（chat） | ★5 | 低 | **敏感** |
| 排班考勤 | ~~值班排班草稿自动生成（automation）~~ → **已由规则算法实现**，Agent 仅为薄包装 | ★3 | **低**（原高） | 普通 |
| 平台地基 | 办公全景问答"党建小助"（chat） | ★4 | 中 | 普通 |
| 活动核心 | 甘特任务逾期问答/自动分解（chat/automation） | ★4 | 低/中 | 普通 |
| 党务台账 | 党员发展进度问答（chat，严格脱敏） | ★4 | 低 | **极敏感** |
| 群聊+通知 | 我的待办/进度问答、催办提醒（chat/automation） | ★4 | 低/高 | 普通 |

> ⚠️ **2026-09-13 调整说明**：原"排班草稿自动生成"被列为 ★5/高工作量/就绪中，现已被 `ActivityScheduleService`（读 `course_schedule` 共同空闲 + 一键生成）与 `POST /api/activity-schedules/generate` 以**纯规则算法**落地。Agent 侧只剩"自然语言 → 参数映射 → 调生成端点 → 解读结果"的薄包装，**价值由 5 降为 3，工作量由高降为低**，是本轮复核中**变化最大的一行**，也是整个 Agent 方案**最大的可行性利好**。

**推荐落地节奏**：**阶段一先搭 Agent 基座 + 全部"零 LLM"的规则型能力（1-2 周）→ 阶段二接入 LLM API 做对话问答 + 文书生成（2-3 周）→ 阶段三做敏感模块与演示亮点串联（3-4 周）**。AI 后端做成可插拔，按数据分级路由：**`normal` → DeepSeek API（低成本、中文好）；`sensitive` → 本地 Ollama 或"脱敏后再出网"；`restricted` → 不出网**。接口预留切换，**无 API Key 时用 `StubProvider` 离线兜底也不阻塞基座开发**。

---

## 2. Agent 形态定义

"Agent"在本文档拆成三种可落地形态，各自对 LLM 的依赖程度不同：

| 形态 | 是什么 | 典型例子 | LLM 依赖 |
|------|--------|---------|:---:|
| **chat（对话问答）** | 用户自然语言提问，Agent 用系统数据/知识库只读作答 | "这个月有哪些进行中的活动？""XX 的策划书审核到哪一步？" | 需要（意图理解）；纯规则/关键词可降级 |
| **automation（自动执行）** | Agent 代用户执行业务操作，**必须带用户确认门禁** | 起草策划书初稿、生成新闻稿草稿、批量加素拓分、批量归档 | 文书生成必须；数据搬运类可规则化 |
| **copilot（流程内 AI 辅助）** | 嵌入具体流程步骤的智能建议/自动填充/分析 | 审核意见预填、任务逾期预警、座位表区域建议、材料类型建议 | 部分需要；很多可纯规则（预警/缺检/推荐） |

**贯穿所有形态的两条铁律**：
- **以当前登录用户身份调用现有接口**，绝不绕过后端鉴权自建读写通道（尤其扫码公开接口 `/signins/scan` 严禁 Agent 直接调用）。
- **automation 一律先出"拟执行清单"、用户确认后才写库**；生成类只落 DRAFT/草稿，不自动提交审核、不自动发布。

---

## 3. 分模块深度机会清单

> 每模块列出已核实的 Agent 机会，按"价值 / 工作量"排序。标注：形态、价值(1-5)、工作量(低/中/高)、敏感度、数据就绪度、关键前置与风险。

### 3.1 活动核心（活动/策划书/议程/任务甘特）

**业务现状**：活动六态状态机（ASSIGNED→…→ARCHIVED，只能相邻推进）；策划书 wangEditor5 富文本 HTML，12 章模板 + 7 个可编辑章节 + 版本化 + 审核流（DRAFT/PENDING/APPROVED/REJECTED）；议程有序列表；甘特任务含负责部门/负责人/依赖/里程碑/进度。详情聚合接口 `GET /api/activities/{id}/detail` 一次返回全部数据。

| # | 机会 | 形态 | 价值 | 工作量 | 敏感 | 就绪 | 关键前置 / 风险 |
|---|------|:---:|:---:|:---:|:---:|:---:|------|
| 1 | **策划书初稿起草**：按活动信息+12章模板+同类历史策划书生成 7 章节草稿，落 DRAFT 不自动提交 | automation | 5 | 高 | 普通 | 中 | LLM 服务；生成 HTML 必须服务端净化防 XSS；flow/budget 的 JSON 约定需生成器严格遵守 |
| 2 | **策划书章节智能填充**：编辑器对空章节"AI 起草"，预算自动生成表格 | copilot | 5 | 高 | 普通 | 中 | LLM；沿用"审核通过后新建版本"约束 |
| 3 | **甘特任务逾期问答**：逾期任务/部门负载/整体完成度 | chat | 4 | 低 | 普通 | 高 | deptId→deptName 映射；逾期规则 |
| 4 | **甘特任务自动分解**：策划书通过后按 flow 拆成任务清单，勾选确认后批量建 | automation | 4 | 中 | 普通 | 中 | 需 LLM 解析；批量确认 UI；注意 TaskAssignedEvent 会通知部门，批量前先提示 |
| 5 | **策划书审核意见辅助**：按 12 章完整性/预算空/时间冲突生成建议意见预填 | copilot | 4 | 中 | 普通 | 中 | 意见只预填绝不自动提交；12 章常量后端化 |
| 6 | **活动状态推进建议**：详情页提示"还差什么才能推进下一状态" | copilot | 4 | 中 | 普通 | 中 | 推进条件规则库；仅建议不自动推进 |
| 7 | **议程自动生成**：解析 plan.flow（JSON 数组或纯文本）转议程行 | automation | 3 | 低 | 普通 | 高 | ✅ ~~AgendaController 无 @PreAuthorize，需先补权限~~ → 已挂 `hasAuthority('activity:edit')`，**前置解除** |
| 8 | **活动状态与策划书进度问答** | chat | 3 | 低 | 普通 | 高 | — |
| 9 | **任务进度预警简报**：甘特页内嵌临近截止/逾期高亮 + 一段可发群里的进展简报 | copilot | 3 | 低 | 普通 | 高 | 纯规则即可做 |
| 10 | **批量状态推进与归档** | automation | 2 | 低 | 普通 | 高 | 只能相邻推进；归档前建议校验签到/材料齐全 |

**本模块风险**（2026-09-13 复核）：~~AgendaController 无权限~~ ✅ 已修（`activity:edit`）。**仍成立的风险**：富文本 XSS（生成内容必须**服务端**净化——注意 `pom.xml` 目前**无 Jsoup**，前端 DOMPurify 挡不住直接调 API 的写入，建议本期补 Jsoup 白名单）；flow/budget 三格式混存（JSON/HTML表格/纯文本）易解析失败；syncActivity 回写活动基本信息需额外显式确认；批量建任务会通知轰炸（`TaskAssignedEvent` 逐条触发，建议归并单条）。

---

### 3.2 内容宣传（推文 / 新闻稿）

**业务现状**：推文（Article）含完整审核流（DRAFT→PENDING→PUBLISHED/REJECTED）；新闻稿（News）**已补发布端点** `POST /api/news/{id}/publish`（DRAFT → PUBLISHED，记录发布日期），发布闭环已成形（仍无多级审核流）。可关联活动从而引用活动全量数据（含策划书、议程、评分、签到数）作为写作素材。**这是文书生成类 Agent 的最大富矿，且数据就绪度最高。**
- ✅ **2026-09-13 更新**：新闻稿发布端点已补，`NewsController` 4 处已挂 `ActivityController.LEADER` 宏；`ArticleController` 2 处已挂 `hasRole('MEDIA_LEADER') or hasAnyRole('TEACHER','DIRECTOR')`。🔶 **仍未收敛到 `content:*` 权限码**（PRD-v5 S2 长尾），Agent 侧先用 LEADER 等价口径放行。

| # | 机会 | 形态 | 价值 | 工作量 | 敏感 | 就绪 | 关键前置 / 风险 |
|---|------|:---:|:---:|:---:|:---:|:---:|------|
| 1 | **活动报道推文/新闻稿草稿一键生成**：选已完结活动，拉 detail 素材生成 REPORT 稿件，落 DRAFT，确认后才可提交审核 | automation | 5 | 中 | 普通 | 高 | LLM；事实来源白名单（activity/plan/agenda/score，**禁引党务台账**）；防幻觉需事实核查 |
| 2 | **推文编辑页 AI 助手**：一键生成摘要/标题候选/润色/续写 | copilot | 5 | 低 | 普通 | 高 | LLM + 编辑器扩展；summary 上限 500 |
| 3 | **审核意见辅助**：本地规则扫（标题超长/摘要缺失/正文过短/含疑似身份证号）+ 建议意见草稿 | copilot | 4 | 低 | 普通 | 高 | 审核动作仍人工点击；敏感识别可本地正则不需 LLM |
| 4 | **提交前事实核查**：稿件中的时间/地点/人名与活动详情比对，输出差异清单 | copilot | 4 | 中 | 普通 | 高 | 稿件需关联 activityId；比对规则+LLM 混合 |
| 5 | **活动预热推文自动起草**：对未开始活动生成 PREHEAT 草稿 | automation | 4 | 中 | 普通 | 中 | 报名方式/对象需从 description 抽取 |
| 6 | **稿件状态/素材问答**：状态分布、驳回原因、"活动X报道写了吗"、取材问答 | chat | 3 | 低 | 普通 | 高 | reviewComment 不外泄给非审核角色 |
| 7 | **历史稿件风格参考检索** | chat | 3 | 低 | 普通 | 高 | 已发布稿属公开内容 |

**本模块风险**（2026-09-13 复核）：✅ ~~**News/Article 创建/编辑/删除无 @PreAuthorize，干事可绕过前端直接调 API**~~ → 已修（News 挂 `LEADER` 宏、Article 挂 `MEDIA_LEADER`/`TEACHER`/`DIRECTOR`）；✅ ~~News 发布闭环缺失（要自动发布需先补 publish 端点）~~ → 已补 `/publish`。**仍成立的风险**：生成内容幻觉必须配事实核查+确认；正文是纯文本 TextArea 而策划书是 HTML，生成格式需对齐（或先升级文章编辑器）；**服务端无 HTML 净化**（`pom.xml` 无 Jsoup，前端 DOMPurify 只护浏览器侧）；Prompt injection（粘贴外部文本可能操纵生成）。

---

### 3.3 签到 / 评分 / 座位表

**业务现状**：应签名单 Excel 上传/分组/核验字段/扫码签到（公开免登录 `/signins/scan`）/手动补签；评分规则+记录（1224 制名次）；座位表双轨（旧 seat_map + 新 seat_layout 可视化编辑器，4 个种子模板）。素拓批量加分已能从签到分组选人。

| # | 机会 | 形态 | 价值 | 工作量 | 敏感 | 就绪 | 关键前置 / 风险 |
|---|------|:---:|:---:|:---:|:---:|:---:|------|
| 1 | **活动批量加素拓分**：从签到分组提取已签人员（姓名+学号）生成加分清单，部长确认后批量加分，支持按 batchId 撤回 | automation | 5 | 中 | **敏感** | 高 | 必须确认门禁；展示"将加 N 人各 X 分"预览；skipped 明细（同名同号会被跳过）必须呈现 |
| 2 | **活动签到进度核查问答**："应签多少/已签多少/还有谁没签/某组签到率" | chat | 4 | 中 | **敏感** | 高 | 名单明细仅部长级可看，干事只能问总人数；统一解析 fieldsJson 表头漂移 |
| 3 | **签到异常与重复扫码检测**：未命中名单/同号重复签/名单已签但无实到 | chat | 4 | 中 | **敏感** | 中 | 需复刻后端 isInRoster 匹配逻辑（避免口径漂移）；**严禁 Agent 调公开 /signins/scan** |
| 4 | **座位表身份区域自动着色建议**：按应签名单"身份"列统计，建议区域配色 | copilot | 4 | 高 | **敏感** | 中 | 名单身份列可能缺失需降级；label 存区域标签不存人名；仅作用于新 seat_layout |
| 5 | **未签人员催签与补签代办**：导出未签清单，确认后批量补签（backfill 幂等） | automation | 3 | 低 | **敏感** | 高 | 补签生成 MANUAL 记录，口径不变前提下操作 |
| 6 | **应签名单核验字段自动建议**：上传名单后按表头建议核验字段与必填项 | copilot | 3 | 中 | 普通 | 中 | 映射需与 scanSignin 硬编码字段名一致，防"姓名"缺失扫码 400 |
| 7 | **座位模板智能推荐**：按人数/活动类型/场地推荐 4 个种子模板 | copilot | 3 | 低 | 普通 | 高 | 推荐理由可解释；基于应签人数口径 |
| 8 | **评分结果查询与名次核对**：复算总分与 1224 制名次，提示与手填 rankNo 差异 | chat | 3 | 低 | 普通 | 高 | 只读不直接改库 |

**本模块风险**：名单含姓名/学号/手机号属可识别个人信息，**干事经对话问"名单里都有谁"可绕过前端页面限制，必须按角色收敛返回**；扫码公开接口禁调；TEXT 无 schema 的 JSON（fieldsJson/seatData）解析要健壮；批量加分/补签是数据变更，误操作靠确认门禁兜底；旧 seat_map 与新 seat_layout 双轨并存需明确以新布局为准。

---

### 3.4 排班 / 考勤 / 无课表

**业务现状**（2026-09-13 重核）：六类值班排班（控烟/办公室值班/摆摊/档案整理/盖章/教学楼检查），按人登记考勤（出勤/请假/缺勤），无课表两条线（~~FreeSchedule 手填空闲周次~~ **该表已由 V11 删除** + CourseSchedule 个人课程表 + 共同空闲热力图分析）。
- ✅ **userId 绑定已部分解决**：V20 `schedule_user_binding` 迁移 + 新增 `SchedulePerson` 实体，排班主表不再只靠 `personName` 字符串关联。
- ✅ **权限已补齐**：`schedule:manage` / `schedule:generate` / `attendance:view` / `attendance:manage` / `schedule:check` 均已接入控制器。
- ✅ **排班草稿生成已由规则算法实现**：`module/routine/ActivityScheduleService` 读 `course_schedule` 共同空闲，一键生成活动排班草稿，端点 `POST /api/activity-schedules/generate`（`schedule:generate`）。**这是本模块最大的变化——原被列为"高工作量 + 需 LLM"的能力，现在是个现成的规则服务。**
- ➕ **新增审计能力**：`AttendanceAudit` + `AttendanceAuditRepository` + `AttendanceAutoJob`（考勤审计与自动任务），Agent 的审计表可沿用同一设计范式。

| # | 机会 | 形态 | 价值 | 工作量 | 敏感 | 就绪 | 关键前置 / 风险 |
|---|------|:---:|:---:|:---:|:---:|:---:|------|
| 1 | ~~**值班排班草稿自动生成**：一句话描述排班需求，结合课表空闲+历史均衡生成整月排班草稿~~ → **修正为：排班生成的「自然语言入口 + 结果解读」薄包装** | automation | 3（原 5） | **低**（原高） | 普通 | **高**（原中） | ✅ ~~前置：排班人员绑定 userId~~ 已由 V20 解决；✅ 生成算法已存在（`ActivityScheduleService` + `POST /api/activity-schedules/generate`），Agent 只需做参数映射与结果解读；仍无节假日/校历数据源 |
| 2 | **排班/考勤数据问答助手**："本周控烟值班谁主班""上周谁缺勤最多" | chat | 4 | 低 | 普通 | 高 | ✅ ~~建议后端补按 userId 查询的只读端点~~ → V20 后 `SchedulePerson` 已有 userId 关联，同名校验成本降低；历史考勤仍需同名消歧 |
| 3 | **排班表单冲突检测与空闲建议**：选人实时检测同时段冲突 + 推荐共同空闲时段 | copilot | 4 | 中 | 普通 | **高**（原中） | ✅ 依赖的 userId 绑定已就位；`ActivityScheduleService` 的共同空闲计算可直接复用 |
| 4 | **周考勤汇总与缺勤通报自动生成**：每周汇总缺勤/请假名单生成通报，推送通知中心 | automation | 3 | 低 | **敏感** | 高 | 接收人范围限定，防缺勤名单扩散；发布前确认；可参考 `AttendanceAutoJob` 的定时任务范式 |
| 5 | **共同空闲时间自然语言查询**："下周三下午部门开会有空吗" | chat | 3 | 低 | 普通 | 高 | 只传目标 userIds；未录课表者被当全天空闲需提示 |
| 6 | **无课表热力图最优时段智能解读**：对 optimal 结果做人话解读"为什么推荐这个时段、差在谁" | copilot | 3 | 低 | 普通 | 高 | 仅展示聚合结论 |
| 7 | **个人课表截图识别导入**：上传课表截图自动识别格子填进课程表 | automation | 3 | 高 | 普通 | 🔶 **低** | **仍成立**：无 OCR/文件解析管线；覆盖式保存前必须确认 |

**本模块风险**（2026-09-13 复核）：✅ ~~**Schedule/Attendance/FreeSchedule 无 @PreAuthorize**~~ → 已修（`schedule:manage` / `attendance:view|manage` / `schedule:check|generate`）。**仍成立的风险**：`analyze` 端点接受任意 userIds，可被用来探测他人出勤规律，**需限部长以上**（这条与权限码无关，是业务规则层面的收敛）；缺勤名单属个人纪律信息，推送范围必限；**历史考勤/素拓数据仍靠 `personName` 字符串关联**（V20 只覆盖排班），Agent 答"某人的考勤"时必须做同名二次确认。

---

### 3.5 党务台账（敏感度最高）

**业务现状**：党员发展五阶段流转（入党申请人→积极分子→重点发展对象→预备党员→正式党员）、函调（父母/社会关系人姓名+支部+地址）、转入转出、名册、党务登记。**干事（STAFF）只有 party:view 且成员接口后端自动脱敏；函调/登记/转移/名册仅限 6 个领导角色。**
- ✅ **2026-09-13 更新**：`RosterImportService.importFromXlsx`（表头识别/去重/返回新增+跳过条数）**已接线**——端点 `POST /api/files/import`（`party:entry`），前端已有入口。原"现成未接线的高价值自动化"**已落地**，Agent 侧只需做"上传前预检 + 导入结果解读"。

| # | 机会 | 形态 | 价值 | 工作量 | 敏感 | 就绪 | 关键前置 / 风险 |
|---|------|:---:|:---:|:---:|:---:|:---:|------|
| 1 | **党员发展进度自然语言问答**："张同学的发展进度"（当前身份/期数/阶段时间线） | chat | 4 | 低 | **敏感** | 高 | 必须复刻后端脱敏：干事看不到身份证/电话/家庭地址/函调内容；同名须带学号二次确认 |
| 2 | ~~**入党名单 xlsx 批量导入去重**：复用已实现的 RosterImportService，接一个上传端点~~ → **修正为：导入前预检 + 结果解读** | automation | 2（原 4） | **低**（原中） | **敏感** | **高**（原中） | ✅ ~~接线：新增 Controller + 前端导入入口~~ → 已完成（`POST /api/files/import`，`party:entry`）；Agent 只做表头预检提示与"新增 N / 跳过 M"的人话解读 |
| 3 | **党务录入草稿自动回填**：登记/转移/函调表单从成员详情自动带出重复字段，只预填不落库 | automation | 4 | 低 | **敏感** | 高 | 写接口限部长以上，回填同样只对部长开放 |
| 4 | **党员台账统计问答**：阶段分布/期数分布/在册党员数 | chat | 4 | 中 | 普通 | 中 | 建议新增只读聚合端点；固定口径（political_status 中文 vs stage 枚举两套值） |
| 5 | **发展流程流转预检**：标出"考察期/预备期将满该转下一阶段"的候选清单 | copilot | 4 | 中 | **敏感** | 中 | 需新增按当前阶段聚合端点；流转写操作逐条确认（changeStage 不校验上一阶段） |
| 6 | **函调联系函草稿生成**：按家系信息生成正式函调信文本 | automation | 3 | 中 | **restricted** | 中 | 仅部长以上 party:letter；家系信息不写会话日志/不外发 |
| 7 | **录入/导入去重与同名消歧提示**：学号重复/同名多人的只读提示 | copilot | 3 | 低 | **敏感** | 中 | 只提示不自动合并；同名不同人是真实场景 |

**本模块风险**：PII（身份证/电话/住址/家庭关系）泄露面最大，Agent 层必须按调用者 roleCode 复刻脱敏，函调家系字段一律遮罩；权限是角色级而非支部/班级数据范围级；阶段流转可破坏身份状态（只追加不校验）；软删成员的子表孤儿引用需过滤；无读取审计与导出审批——建议对受限操作加操作人+时间日志。

---

### 3.6 档案材料（材料库 / 模板库 / 素拓 / 公告）

**业务现状**（2026-09-13 重核）：材料库按"活动→类型→材料"树归档，约 1660 份真实 Office 材料（**仅元数据搜索，未做全文索引** —— 检索实现为 `MaterialService.page` 的 `Specification` + `name/description/tag` 三字段 `LIKE`）；模板资产库 8 分类；素拓加分流水（含活动批量加分+批次撤回）；公告（LEADER 校验，**已读已后端化**、**发布已触发通知**）。底层 `FileStorageService` + `/api/files` 上传下载通道、Apache POI 5.4.1（可扩展写 docx/xlsx）。
- ✅ **2026-09-13 更新（三处已闭合）**：① 公告已读由前端 localStorage 改为后端表（V19 `announcement_read`）+ `AnnouncementPublishedEvent` 事件埋点 → **"触达率/已读回执"类能力有真实数据源**；② `Material/Template/Credit/File` 四个控制器均已挂 `@PreAuthorize`（`material:view|upload|delete`、`template:manage`、`quality:add|delete`、`party:entry`）；③ `/uploads/**` 由 `permitAll` 改为 `authenticated()`（V18），`JwtAuthenticationFilter` 支持 `pams_token` Cookie 兜底。

| # | 机会 | 形态 | 价值 | 工作量 | 敏感 | 就绪 | 关键前置 / 风险 |
|---|------|:---:|:---:|:---:|:---:|:---:|------|
| 1 | **材料库自然语言检索与归档概览**："找 XX 活动策划书""这学期所有党日签到表" | chat | 5 | 低 | **敏感** | 高 | 按角色过滤 ROSTER/SIGNIN 敏感类型材料；**仍不给 `/uploads/**` 直链**（虽已鉴权，直链仍是弱边界）；检索仍是三字段 `LIKE`，中文单字/多词命中率有限 |
| 2 | **Agent 生成文档落库通道**：按模板生成 docx/xlsx → /api/files/upload → 建 material/template | automation | 4 | 高 | **敏感** | 中 | 走既有 sanitize/BLOCKED_EXTENSIONS 白名单；生成名单类文档需部长以上确认 |
| 3 | **素拓活动批量加分助手（自然语言触发）** | automation | 4 | 中 | **敏感** | 中 | 复用 ActivityCreditModal 5 步确认模式；skipped 明细必展示；限 quality:activity_add |
| 4 | **公告草稿生成与发布**：以活动字段为上下文生成公告，确认后以当前用户身份发布 | automation | 4 | 中 | 普通 | 高 | ✅ 后端 LEADER 校验已兜底；✅ ~~可选增强：补公告事件推送到通知中心~~ → **已实现** |
| 5 | **素拓台账问答**："李想这学期加了多少分" | chat | 3 | 低 | **敏感** | 中 | 部长以上看全量，干事仅自己；建议补汇总端点 |
| 6 | **活动材料归档完整性检查与一键归档包**："缺哪些材料"→批量补建档 | automation | 3 | 中 | 普通 | 中 | 需"每类活动应归档清单"规则库；识别孤立 file_record 需后端只读接口 |
| 7 | **材料归档智能补全**：上传时按文件名+描述建议类型/标签/关联活动 | copilot | 3 | 低 | 普通 | 高 | 建议+FileStorageService.bizTypeOf 规则兜底双保险 |
| 8 | **素拓加分异常与重复检测** | copilot | 3 | 低 | **敏感** | 高 | 校验放 service 层，不把全表暴露给前端 |
| 9 | **公告发布草稿助手** | copilot | 3 | 低 | 普通 | 高 | — |
| 10 | **模板/公告问答与快速定位** | chat | 2 | 低 | 普通 | 高 | 公告正文 TEXT 可能很大，先给标题摘要 |

**本模块风险**（2026-09-13 复核）：✅ ~~**Material/Template/Credit/File 四个控制器无 @PreAuthorize**~~ → 已修；✅ ~~/uploads/** 静态映射公开可读~~ → 已改 `authenticated()`（V18）。**仍成立的风险**：Agent 回复**照旧不给直链**（鉴权只保证"要登录"，不保证"这个人该看"）；**服务端无 HTML 净化**（`pom.xml` 无 Jsoup，公告正文若由 Agent 生成必须补）；POI 只覆盖 xlsx（docx 写未用、pdf/.doc/.ppt 需额外库），1660 份真实材料格式多样解析需降级；素拓记录物理删除无审计，批量误删不可恢复；**材料库无全文索引**（`LIKE` 三字段），二期建议上 MySQL 8 ngram FULLTEXT（详见业务设计方案 D6，注意需 `IN BOOLEAN MODE` 且 `ngram_token_size` 默认 2）。

---

### 3.7 群聊档案 + 通知中心

**业务现状**：群聊是"外部微信群登记簿"（只存元数据，不存聊天内容）；通知中心是纯事件驱动的消息总线——**13 类业务事件**经 `AFTER_COMMIT` 落库 + WebSocket 用户定向推送。**通知是 Agent 自动化的天然载体，但目前只能由事件产生通知（无写接口）。**
- ✅ **2026-09-13 更新**：事件总线已由 7 类扩展为 **13 类**，并重构为 **Spring 领域事件**风格（`module/notification/event/` 下 13 个 Event + `NotificationEventListener` 统一处理）。**对 Agent 的直接价值**：`SimpMessagingTemplate` 的推送模式已被证明在任意线程可用（`NotificationEventListener` 无任何线程特判），Agent 编排层可**直接复用同一条通道**推送过程事件。
- ⚠️ **但有一条差异必须注意**：现有通知推送的 payload **故意只有 `type` + `unreadCount`**（前端收到后回 REST 拉详情）。**灵思的过程事件流不能照抄这个薄载荷**——它必须携带真实内容（工具名、参数摘要、耗时、状态），否则过程区无从渲染。

| # | 机会 | 形态 | 价值 | 工作量 | 敏感 | 就绪 | 关键前置 / 风险 |
|---|------|:---:|:---:|:---:|:---:|:---:|------|
| 1 | **我的待办与工作流进度问答**："最近有哪些策划书待我审核""XX 活动进度时间线" | chat | 4 | 低 | 普通 | 高 | 按当前登录人身份调通知接口，禁越权读他人 |
| 2 | **基于事件总线的催办与提醒**：策划书提交 48h 未审核、活动前 3 天未建群等自动催办 | automation | 4 | 高 | 普通 | 中 | **仍需新增受 @PreAuthorize 保护的通知发送接口**（当前无写通道）+ 定时任务；催办去重；定时任务可复用 `AttendanceAutoJob` 范式 |
| 3 | **通知中心"今日工作简报"**：按实体聚合通知，生成"今日待审 N 件/待办 M 件" | copilot | 4 | 低 | 普通 | 高 | 以当前登录人可见通知为限 |
| 4 | **审核工作台聚合导航**："3 篇待审稿件"→ 一键跳转对应审核页 | copilot | 4 | 低 | 普通 | 高 | 映射 entity_type→前端审核路由 |
| 5 | **活动结束后群聊批量归档** | automation | 4 | 中 | 普通 | 中 | 二次确认 + chat:manage；建议补批量归档接口 |
| 6 | **群聊档案问答检索**："新媒体中心有哪些活跃群" | chat | 3 | 低 | 普通 | 高 | — |
| 7 | **群聊档案批量建档与清洗** | automation | 3 | 中 | 普通 | 中 | 批量创建前确认清单 |
| 8 | **群聊档案创建智能预填** | copilot | 3 | 中 | 普通 | 高 | 仅预填不自动提交 |
| 9 | **通知偏好智能建议** | copilot | 2 | 中 | 普通 | 中 | 偏好内存态不落库，先解决持久化 |

**本模块风险**（2026-09-13 复核）：**通知偏好仍存内存**（重启即清）——Agent 依赖偏好判断会失准；前端类型标签仍含后端枚举不存在的值，统计须以后端枚举为准；`priority` 字段后端恒为 `NORMAL`，Agent 不能依赖它分级；`AFTER_COMMIT` 事件内**不要**嵌入长耗时副作用（LLM 调用绝不能放事件监听器里，必须走独立线程池 + 落库中间态）；群聊档案不存聊天内容，Agent 不应承诺"群内消息"能力；➕ **新增风险：`/user/queue/**` 依赖内存 broker，横向扩容后静默失效**（一期单实例可忽略，但需记录为已知边界）。

---

## 4. 三形态横向评估

| 形态 | 总体判断 | 最适合先做的场景 | 最大风险 |
|------|---------|----------------|---------|
| **chat** | 门槛最低、见效最快；大部分查询已有现成接口 | 活动/任务/材料/通知/排班 5 类问答 | 敏感数据经对话泄漏；意图→接口映射要按角色过滤 |
| **automation** | 价值最高（省掉重复文书与批量操作），但必须带确认门禁 | 文书草稿生成、批量加分、批量归档、**排班生成（已有规则算法，只需薄包装）** | 幻觉、误操作；~~越权（无 @PreAuthorize 的控制器）~~ ✅ 已修，但**直调 Service 仍绕过方法级鉴权** |
| **copilot** | 体验最好、单点侵入小；很多可纯规则先做 | 任务预警、审核意见辅助、状态推进建议、材料类型建议 | 建议被盲目采纳导致不合格内容放行 |

**关键结论**：先做 chat + 规则型 copilot（零 LLM 也能交付价值）；文书生成类 automation 等 LLM 接入后集中上；敏感模块（党务台账/签到名单/函调）只做"严格脱敏版"。

> ✅ **2026-09-13 复核补充**：~~"先修 5 处权限缺口"是 automation 的前置~~——**该前置已解除**，08-08 版的串行路线不再必要。Agent 可以**并行启动**，唯一真正的硬前置是**服务端 HTML 净化**（生成类能力离不开它）与**批次 0 基座本身**。

---

## 5. 敏感数据边界与权限（接入 Agent 前的硬性要求）

系统内数据的敏感分级与对应处理：

| 级别 | 数据 | 处理规则 |
|------|------|---------|
| **restricted（绝密）** | 函调家系信息（父母/社会关系人姓名+支部+寄送地址） | 仅部长以上 + party:letter；Agent 一律遮罩、不写会话日志、不外发 |
| **sensitive（敏感）** | 签到名单（姓名/学号/手机号）、党务台账 PII（身份证/电话/住址）、素拓记录、缺勤名单 | 部长以上看全量，干事仅脱敏/仅自己；Agent 回答必须复刻后端脱敏 |
| **normal（普通）** | 活动/任务/推文/材料元数据/群聊档案/通知 | 按现有权限码收敛 |

> ✅ **2026-09-13 复核结论：下表中的五处权限缺口已全部闭合（仅第 2 项部分闭合），不再是 Agent 的前置。**保留本表是为了**留痕与追溯**——它记录了 08-08 与 09-13 之间的真实变化。

**原「接入 Agent 必须先修的五处权限缺口」复核表**：

| # | 缺口（原判断） | 复核状态 | 证据 |
|---|---|---|---|
| 1 | `AgendaController` 写操作无 `@PreAuthorize` | ✅ 已修 | 已挂 `hasAuthority('activity:edit')` |
| 2 | `NewsController` / `ArticleController` 创建/编辑/删除无 `@PreAuthorize` | 🔶 部分已修 | News 已挂 `LEADER` 宏并补 `/publish` 端点；Article 已挂 `hasRole('MEDIA_LEADER') or hasAnyRole('TEACHER','DIRECTOR')`；**仍未收敛到 `content:*` 权限码**（PRD-v5 S2 长尾） |
| 3 | `Material/Template/Credit/File` 四个控制器无 `@PreAuthorize` | ✅ 已修 | `material:upload/delete`、`template:manage`、`quality:add/delete`、`material:view or party:view`、`party:entry` |
| 4 | `Schedule/Attendance/FreeSchedule` 三个控制器无 `@PreAuthorize` | ✅ 已修 | `schedule:manage`、`schedule:generate`、`attendance:view/manage`、`schedule:check`（`FreeSchedule` 表已由 V11 删除，改为 `course_schedule`） |
| 5 | 权限码仅"管理数据"、未接入请求级拦截 | ✅ 已收敛 | `JwtAuthenticationFilter` 双轨注入；约 20 个权限码在控制器生效 |

> **但仍有一条铁律必须保留**：`@PreAuthorize` 挂在 **Controller** 上，`Service` 方法**不带**方法级鉴权。Agent 若直接调用 Service，会绕过全部方法级校验。因此 Agent 工具层必须①声明所需权限码 ②派发前用 **`Authentication.getAuthorities()`** 显式断言（⚠️ **不是 `LoginUser`** —— 该对象不含权限码字段，权限码由 `JwtAuthenticationFilter` 注入到 `Authentication` 上）③对无权限工具做**可见性过滤**。详见《[PAMS-Agent业务设计方案](PAMS-Agent业务设计方案.md)》D3。

**其他红线**（2026-09-13 复核后仍全部有效）：扫码公开接口 `/signins/scan` 严禁 Agent 调用；`/uploads/**` 直链**仍严禁**出现在 Agent 回复（V18 后已需登录，但"能登录"≠"该看"）；生成 HTML 必须**服务端**净化防 XSS（`pom.xml` 目前无 Jsoup，需补）；automation 一律先确认后写；**LLM 调用不得放在 `AFTER_COMMIT` 事件监听器内**（会拉长事务后处理，且没有失败重试语义），必须走独立线程池并落中间态。

---

## 6. AI 后端方案评估

**现状**：系统零 AI 依赖（2026-09-13 复核：`pams-backend/pom.xml` 中确无 openai/deepseek/ollama/langchain 等任何 AI SDK，也**无 Jsoup**）。接入需新增 LLM 网关，做成**可插拔 provider 接口**（本地上线先留 stub，后续切真实模型零改动）。

| 方案 | 说明 | 优点 | 缺点 | 成本 | 适合阶段 |
|------|------|------|------|------|---------|
| **A. 无外部依赖（先做）** | 只用规则/算法 + 预留 LLM 接口 | 零成本、无隐私外发、可先交付"任务预警/审核辅助规则版/材料归档检查"等 | 自然语言问答和文书生成无法真正启用 | ¥0 | 阶段一 |
| **B. 外部 LLM API（推荐默认）** | DeepSeek 首选（中文好、便宜），Qwen/GLM/Kimi 备选 | 效果最好、接入快、按量计费 | 需 API Key 管理；用户数据（不含 restricted 字段）出网 | 日常几十元/月内 | 阶段二起 |
| **C. 本地模型（Ollama）** | 部署 Qwen/GLM 系列量化版 | 免费、数据不出内网、可作演示兜底 | 需显卡/内存，效果弱于 API，部署复杂度高 | 硬件成本 | 阶段三/离线兜底 |

**推荐组合（2026-09-13 细化为"按数据分级路由"）**：架构上做成 provider 可插拔 → 阶段一零依赖跑规则能力 → 阶段二接 **DeepSeek API**（默认）启用对话问答与文书生成 → 需要时切 Ollama 本地兜底。**关键细化：不选"一个供应商"，而是按数据分级路由 provider**——
```
normal 数据    → DeepSeek API（体验优先，便宜、能力强）
sensitive 数据 → 本地 Ollama（若已部署）或"仅摘要出网"（脱敏后再交给云端）
restricted     → 根本不出网（PolicyGuard 拦截）
```
落地成本约 20 行代码（`providerFor(Sensitivity)`），却把"敏感模块怎么做"从设计难题降为一个映射函数。**无 API Key 时用 `StubProvider` 离线兜底，不阻塞批次 0 基座开发。**

**接入架构建议**（前后端分工，2026-09-13 按实际源码校正）：
- 后端新增 `AgentController` + `AgentOrchestrator`（编排层）：持有"意图→工具→权限"映射表，所有工具调用都以**当前登录用户身份**执行现有 Service；内置脱敏与确认门禁。
  - ⚠️ **若直调 Service 须补鉴权**：`@PreAuthorize` 挂在 Controller 上、Service 无方法级鉴权，因此工具层必须"① 声明所需权限码 ② 派发前用 **`Authentication.getAuthorities()`** 断言（**不是 `LoginUser`**——该类不含权限码字段）③ 无权限工具做可见性过滤"三层防护。
- 新增 Agent 表（**建议 Flyway `V24__agent.sql`**：`agent_conversation` / `agent_message` / `agent_task` / `agent_tool_call` / `agent_audit_log`）。
- **推送通道直接复用现有 STOMP WebSocket**，无需新组件：
  - 现有：`/user/queue/notifications`（薄载荷 `{type, unreadCount}`，前端回拉 REST）
  - Agent 新增：`/user/queue/agent/stream`（**必须是厚载荷**：`{streamId, seq, type, payload, ts}`，工具名/参数摘要/耗时/状态）
  - `WebSocketConfig` 已 `enableSimpleBroker("/queue","/topic")` + `/user` 前缀 + `/ws` SockJS 端点，`SimpMessagingTemplate` 从任意线程可发（`NotificationEventListener` 已实证）。
  - ⚠️ **内存 broker ⇒ 一期必须单实例**；横向扩容后 `/user/queue/**` 会静默失效（需 broker relay / Redis pub-sub / 会话粘滞之一）。
- LLM provider 接口：`LlmProvider`（`name()` / `supportsToolCalling()` / `chat(ChatRequest)`），未来可换模型。
- **不引入**：MQ、Redis、EventSource/SSE、向量数据库（详见业务设计方案 D6/D7）。

---

## 7. 推荐路线图

### 阶段一：Agent 基座 + 零 LLM 依赖能力（约 1-2 周）

**目标**：跑通 Agent 的壳 + 交付一批不依赖 LLM 的真实价值。
- [ ] 后端：Agent 会话端点 + 工具调用层（以登录用户身份调现有接口）+ 权限/脱敏门禁 + **服务端 HTML 净化（Jsoup，生成类能力的硬前置）**
- [ ] `agent_task` 表 + WebSocket 用户定向投递通道（全系统共用地基；**事件级流式，复用现有 STOMP**）
- [x] ~~**先修 5 处权限缺口**（见第 5 节）~~ —— ✅ 2026-09-13 复核：已完成（仅 content 模块仍用 `LEADER` 宏）
- [x] ~~**RosterImportService 接线**（入党名单导入上传端点 + 前端入口）~~ —— ✅ 已接线：`POST /api/files/import`（`party:entry`）
- [ ] 规则型能力上线：任务进度预警简报、活动状态推进建议、策划书审核意见辅助·规则版（缺章检测）、材料归档完整性检查、材料类型/标签智能补全（规则兜底版）
- [ ] **明确不做**：向量数据库、MQ、Redis、SSE/EventSource、独立 Agent 服务、字符级流式（二期）

### 阶段二：LLM 接入 + 对话问答 + 文书生成（约 2-3 周）

**目标**：让 Agent 真正"会说话、会写稿"。
- [ ] LLM 网关（`LlmProvider` 可插拔；**按数据分级路由**：normal→DeepSeek API / sensitive→Ollama 或摘要出网 / restricted→不出网）
- [ ] **对话问答"党建小助"**：先覆盖 活动状态/任务逾期/材料检索/通知待办/排班考勤 5 类高频查询
- [ ] **文书生成**：策划书初稿起草、报道/预热推文草稿、公告草稿、议程自动生成
- [ ] 素拓活动批量加分助手（自然语言触发 + 确认门禁）
- [ ] 推文编辑页 AI 助手（摘要/标题/润色）
- [ ] 批量操作"拟执行清单"确认 UI（通用组件）

### 阶段三：敏感模块 + 演示亮点串联（约 3-4 周）

**目标**：覆盖全部形态 + 形成一条完整的演示故事线。
- [ ] 党务台账问答（严格脱敏版）、函调函草稿、发展流程流转预检
- [ ] ~~排班草稿自动生成（**前置：排班表单绑定 userId**）~~ → ✅ **修正：生成算法已就绪**（`ActivityScheduleService` + `POST /api/activity-schedules/generate`），本阶段只做"自然语言参数映射 + 结果解读"的薄包装；配合考勤缺勤通报
- [ ] 座位表身份区域智能着色、课表截图识别导入
- [ ] 仪表盘"今日焦点"摘要、通知中心"今日工作简报"
- [ ] 演示故事线：**创建活动 → AI 起草策划书 → 审核 → AI 生成报道推文 → 按签到批量加素拓分 → 材料自动归档 → 通知串联**——一条龙走完，既是日常提效也是比赛/汇报的完整亮点。

---

## 8. 总体风险清单（跨模块汇总）

| 风险 | 说明 | 缓解 |
|------|------|------|
| ~~权限码与运行时鉴权脱节~~ | ✅ **2026-09-13 已收敛**：43 权限码中约 20 个已接入请求级鉴权，`JwtAuthenticationFilter` 双轨注入 | 保留原有约束：Agent 工具层一律"以用户身份调现有接口"，不自行判权 |
| **Agent 直调 Service 绕过方法级鉴权** | ⚠️ **新增（本次复核发现的最重要风险）**：`@PreAuthorize` 挂在 Controller 上，Service 无方法级鉴权 | 工具层三层防护：声明权限码 + 派发前用 `Authentication.getAuthorities()` 断言 + 无权限工具可见性过滤 |
| 敏感数据经对话泄漏 | 名单/党务 PII/函调家系 | 按角色脱敏 + restricted 字段遮罩 + 不写日志 |
| 生成内容幻觉 | LLM 编造时间/地点/人名 | 事实来源白名单 + 提交前事实核查 + 强制用户确认 |
| Prompt injection | 粘贴外部文本操纵 Agent | 输入清洗/隔离；输出仅结构化草稿 |
| **服务端无 HTML 净化** | ⚠️ **新增**：`pom.xml` 无 Jsoup；前端 DOMPurify 只护浏览器，挡不住直接调 API 的写入 | 引入 Jsoup 白名单（对齐 wangEditor5 标签集），Service 层入口，**顺带覆盖人工写路径** |
| 写操作污染 | 批量加分/补签/归档误操作 | 一律"拟执行清单"确认；素拓批量加分手动撤回；补审计日志 |
| ~~WebSocket/静态文件越权~~ | ✅ `/uploads/**` 已由 V18 改为 `authenticated()`；`/ws` 有拦截器 | Agent 回复仍不给直链；投递按用户定向校验 |
| **`/user/queue/**` 扩容后静默失效** | ⚠️ **新增**：`enableSimpleBroker` 为内存 broker，多实例下用户定向投递失效且无报错 | 一期**明确单实例**；扩容时三选一（broker relay / Redis pub-sub / 会话粘滞） |
| 事件/通知风暴 | Agent 自动化触发连锁通知 | 批量操作归并单条；催办去重；**LLM 调用不得放在 `AFTER_COMMIT` 监听器内** |
| 身份关联弱 | ~~排班/考勤/素拓靠 personName 字符串~~ → 排班已由 V20 解决；**考勤/素拓历史仍需处理** | 排班可直接用 `SchedulePerson.userId`；考勤/素拓做同名二次确认；不做批量回填 |
| **首事件延迟过高（TTFE 超标）** | ⚠️ **新增**：流式界面下用户感知的时钟是"发送→首个事件"，不是"发送→答案" | 后端进编排第一时间推 `THINKING`；前端点发送后立即插本地占位气泡；硬指标 TTFE P95 < 800ms |

---

## 9. 下一步建议

1. **评审本文档**：重点确认 ① 阶段一的三类规则型能力优先级 ② LLM provider 的选择（DeepSeek API 是否可行，或按数据分级路由） ③ 敏感模块的处理口径。
2. 确认后，把阶段一拆成实施计划（沿用分批复审流程），先做 Agent 基座 + **服务端 HTML 净化** + 规则型能力。
   - ⚠️ **注意（2026-09-13 修正）**：原文此处为"先做 Agent 基座 + **权限补口** + 规则型能力"。经复核，**权限补口已完成**（见第 5 节），该项替换为**服务端 HTML 净化**（生成类能力的唯一硬前置）。
3. 阶段一的 Agent 基座做好后，阶段二接 LLM 就是"填 provider 实现 + 加工具"的增量工作，风险小、见效快。
4. **同步阅读**《[PAMS-Agent业务设计方案](PAMS-Agent业务设计方案.md)》：本文档给"做什么"，业务设计方案给"怎么做"（含 `V24__agent.sql` 表结构、23 个首批工具清单、3 层鉴权防护、确认门禁实现、工作量 39–50.5 人日拆解、6→8 项决策待办）。

---

> **文档结束** — 所有机会点均来自 8 个并行调研 agent 对实际代码的通读；v1.1 的现状复核基于 2026-09-13 对 `pams-backend` / `pams-web` 源码与 Flyway 迁移链（V1–V23）的直接核对。
