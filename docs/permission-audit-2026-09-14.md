# PAMS 权限体系审计与智能体问答越权风险分析

> 日期：2026-09-14
> 范围：`com.pams.security` / `com.pams.module.permission` / 各业务模块 Controller / `com.pams.module.agent`
> 方法：静态代码审计 + 覆盖度实测（非运行时渗透测试）
> 关联：`code-audit-2026-08-23.md`（前次审计）、`PAMS-灵思知识库问答方案-v1.md`（D12 与本文结论互相约束）

---

## 0. 结论摘要

**一句话**：系统采用 **单角色 RBAC + Controller 方法级注解**，权限码体系本身是健全的；但 **39.1% 的端点没有任何注解**，且**不存在行级数据域隔离**；智能体问答（灵思）会**复用同一批 Service**，因此它既继承了这些缺口，又额外引入了两条独有路径。

| # | 问题 | 等级 | 当前可利用 |
|---|---|---|---|
| **A1** | `ChatRefsResolver` 无权限校验 → 任意登录用户按 ID 枚举活动名称/状态/起止/地点，并注入 LLM 上下文 | 🟡 **低（已下调，见下方更正）** | 是，但活动对全体登录用户本就可见 |
| **A2** | `PolicyGuard.egress` 返回值被丢弃 → **SENSITIVE 字段级遮罩从未生效**（闸口空转） | 🔴 **高** | 潜伏（当前两个 SENSITIVE 工具恰好无手机号/身份证） |
| **A3** | 88 / 225 端点无 `@PreAuthorize` → 页面上"权限码"不生效，**"页面权限"这个概念在多数业务模块并不存在** | 🔴 **高** | ✅ 是 |
| **A4** | 无行级数据域：`Role.dataScope` 仅在用户列表被使用 | 🟠 **中高** | ✅ 是 |
| **A5** | `GET /api/agent/streams/{streamId}` 无归属绑定 | 🟠 中 | 需知 streamId |
| **A6** | `PolicyGuard.mask()` 不遮姓名；`maskName()` 定义后**从未被调用** | 🟠 中 | ✅ 是 |
| **A7** | `POST /api/signins/scan` permitAll 匿名写考勤，仅靠签到码 | 🟠 中 | ✅ 是 |
| **A8** | `CourseScheduleController./analyze` 无权限且接受任意 `userIds` → 任一人可查任意人空闲时段 | 🟠 **中高** | ✅ 是 |
| **A9** | **`party:view` 口径与前端路由守卫冲突**：干事（STAFF）持有 `party:view`，但 `/party/*` 路由用 `RequireRole(LEADER_ROLES)` 守卫 → **干事看不到党务台账页面，却可通过灵思问出党员姓名与所处阶段** | 🔴 **高** | ✅ **是（本审计最确凿的"越权获取受保护内容"实例）** |

### ⚠️ 定级更正说明（2026-09-14 当日二次核查）

本报告首次发布时把 **A1 定为「🔴 高」**，并在摘要里称其为"教科书式 IDOR"。**二次核查后下调为「🟡 低」，理由如下**：

核查前端路由与菜单后发现，**活动对全体登录用户本就是开放的**：

| 依据 | 内容 |
|---|---|
| `router/index.tsx` L76-78 | `/activities`、`/activities/:id` **没有任何 `RequirePerm` / `RequireRole` 守卫** |
| 同处注释 | "活动管理（**干事可看**，删除/改状态后端已限制部长及以上）" |
| `MainLayout.tsx` L74 | `activity:view` 仅用于**菜单显隐**，各角色的种子权限集**全都包含** `activity:view` |

所以 A1 泄露的并非"受保护内容"——页面同样能看到。**它仍应修**（一个无校验的 ID 入口，一旦活动将来改为受限就会立刻变成越权通道，且当前吐数据不留审计），但它不是一次正在发生的越权。

**同时本次核查发现了真正的越权实例（A9）**，定性比 A1 严重得多，见 §2.3.A9。

> **教训**：判断"是否越权"不能只看"这个端点有没有 `@PreAuthorize`"，还要看**这个数据在产品上到底该给谁看**。本系统里后者的权威定义在**前端路由守卫**（`RequirePerm`/`RequireRole`）与菜单映射里，而不是后端注解 —— 因为后端注解本身缺了 39.1%。我第一版只看了后端，把"注解缺失"直接等同于"内容受保护"，结论偏重了。

**对知识库问答（对话档位）的影响**：A1/A2/A3/A4 会**原样传导**进知识索引层。这意味着 —— **"忠于页面现状地映射权限"这条原则，在页面本无门的地方会变成"一致地漏"**。这是本方案最需要提前处理的结论，详见 §3。

---

## 1. 权限设计现状

### 1.1 访问控制模型定性

| 维度 | 现状 | 评价 |
|---|---|---|
| 模型类型 | **单角色 RBAC** + 手写对象级校验；**无 ABAC、无行级数据域、无数据权限注解** | 够用但粗 |
| 用户 ↔ 角色 | **1:1**（`sys_user.role_id` 外键，`User.role` 单值） | 一人一角色，无多角色叠加、无角色继承 |
| 角色 ↔ 权限 | **M:N**（`role_permission` 表），可在权限管理页运行时改（全量覆盖） | 灵活，且改动**立即生效** |
| 授权载体 | Spring Security `GrantedAuthority` | — |
| 强制点 | **仅 Controller 方法级 `@PreAuthorize`** | ⚠️ Service 层无注解，这是所有问题的根 |

### 1.2 角色（7 个）

来源：`PermissionService.restoreDefault()` 与 `DataSeeder.mkRole(...)`

| 角色码 | 名称 | level | dataScope | 说明 |
|---|---|---|---|---|
| `TEACHER` | 指导老师 | 最高 | ALL | 默认**全部权限**（`include=null`） |
| `DIRECTOR` | 主任 | — | ALL | 除 `user:permission` |
| `ORG_LEADER` | 组织部长 | — | ALL | 部长级权限集 |
| `SECRETARY_LEADER` | 书记 | — | ALL | 同上 |
| `MEDIA_LEADER` | 宣传部长 | — | ALL | 同上（content 模块的角色宏之一） |
| `TECH_LEADER` | 技术部长 | — | ALL | 同上 |
| `STAFF` | 干事 | 最低 | **DEPT** | 最小权限集 |

**两个必须点出的问题**：

1. **`dataScope` 是"纸面字段"**。全仓只有 `UserService.isDeptScoped()` → `UserController` 用户列表用它。**业务数据（活动、考勤、党员、材料）没有任何部门维度的行级隔离** —— 一个 STAFF 能看到全校所有部门的活动与考勤。
2. **5 个部长角色的权限集完全相同**，区别只在角色码本身（用于 `hasRole` 宏）。这是"角色即权限组"的常见简化，但会导致 `LEADER` 语义被滥用（见 §2.2 P2）。

### 1.3 资源（权限码）：51 个

命名规范 `module:action`，按模块分组，存 `permission` 表 + `module` + `sortOrder`，权限管理页按模块渲染成勾选树。

| 模块 | 权限码 |
|---|---|
| activity | `view` `create` `edit` `delete` `review` `assign` |
| checkin | `view` `manage` `export` |
| material | `view` `upload` `download` `preview` `delete` |
| template | `view` `use` `manage` |
| seat | `view` `edit` `template` |
| quality | `view` `add` `activity_add` `delete` |
| chat | `view` `manage` `category` |
| notice | `view` `publish` `manage` |
| notification | `view` `preference` `send` |
| schedule | `view` `manage` `check` `free_table` `generate` |
| attendance | `view` `manage` |
| party | `view` `manage` `letter` `entry` |
| member | `view` `manage` `export` `import_account` |
| user | `view` `manage` `permission` |

**权限码体系本身是健全的**——粒度合理、命名一致、可运行时配置、改动即时生效（每请求查 DB）。问题不在权限码，在**它被挂在哪些端点上**。

### 1.4 校验层级与时机

```
① 认证层   SecurityConfig: anyRequest().authenticated()
            例外：/api/auth/login|logout、/api/ping、POST /api/signins/scan、
                  GET /api/signins/scan-config  → permitAll（匿名）
            特例：/ws/** 完全跳过 Security 过滤链（走自建握手拦截器）

② 身份注入  JwtAuthenticationFilter（每请求）
            解析 JWT → 查 user → 注入两条 authority：
              ROLE_<roleCode>            ← 供 hasRole/hasAnyRole
              <每个权限码>               ← 供 hasAuthority
            查询频率：每请求一次 DB（permissionsOfRole）

③ 授权层   @PreAuthorize（Controller 方法级，@EnableMethodSecurity）
            形态：hasAuthority('x:y') / hasAnyAuthority(...) / hasRole(...) / hasAnyRole(...)

④ 对象层   手写校验（散落在 Service）
            例：ConversationStore.require()、TaskGate.detail()、ArticleService.update()
            ⚠️ 无统一机制，全靠每个方法自觉
```

**关键时机特征**：权限码每请求从 DB 现取 → 权限调整**即时生效**，无需重新登录。这是一个好性质，但也意味着**一次 DB 查询的开销**，且**没有缓存失效问题**（因为没有缓存）。

### 1.5 覆盖度实测（这是最要紧的一组数字）

对全部 39 个 Controller 逐文件统计映射注解与 `@PreAuthorize` 数量：

| 指标 | 数值 |
|---|---|
| HTTP 端点总数 | **225** |
| 带 `@PreAuthorize` | **137** |
| **未带 `@PreAuthorize`** | **88（39.1%）** |
| 有效覆盖率 | **60.9%** |

**完全没有任何鉴权注解的 Controller（8 个 / 16 个端点）**：

| Controller | 端点数 | 实际门槛 |
|---|---|---|
| `AuthController` | 3 | 登录相关，合理 |
| `PingController` | 1 | 健康检查，合理 |
| `NotificationController` | **7** | ⚠️ 站内通知（我的通知），登录即可 |
| `ActivityDetailController` | 1 | ⚠️ 活动详情 |
| `DashboardController` | 1 | ⚠️ 首页仪表盘聚合数据 |
| `SystemController` | 1 | ⚠️ 系统信息 |
| `DepartmentController` | 1 | ⚠️ 部门列表 |
| `RoleController` | 1 | ⚠️ 角色列表 |

**未注解端点最多的敏感 Controller**：

| Controller | 未注解 / 总数 | 具体未注解端点 |
|---|---|---|
| `NotificationController` | 7 / 7 | 全部 |
| `CourseScheduleController` | **7 / 9** | `/configs` `/mine`(GET/POST) **`/analyze`** `/generated` `/generated/latest` `/import/download` |
| `ArticleController` | **6 / 8** | `GET /`(分页) **`PUT /{id}`** **`PUT /{id}/submit`** **`PUT /{id}/publish`** `PUT /{id}/stats` `DELETE /{id}` |
| `SigninRosterController` | 5 / 17 | — |
| `AnnouncementController` | 3 / 6 | **`GET /`(列表)** `/read` `/read-ids` |
| `PartyMemberController` | 3 / 7 | **`GET /`(分页)** **`GET /{id}`** **`GET /stages`** |
| `PartyTransfer/Roster/Register/Investigation` | 各 3 / 4 | 各含 3 个读端点 |
| `MemberSessionController` | 3 / 5 | — |
| `SigninController` | 3 / 8 | — |
| `SeatLayoutController` | 3 / 8 | — |

**必须读准的一点**：未注解 ≠ 一定越权。`ArticleController` 的写端点虽无 `@PreAuthorize`，但 `ArticleService` 内有对象级校验（`!leader && authorId != currentUserId` → 无权）。**这里的问题是"校验下沉且不统一"，而不是"完全没有校验"** —— 但下沉意味着**任何新增调用方（比如智能体工具）都必须自己记得传 `leader` 与 `currentUserId`，漏传即静默失效**。这正是 D11 第 ③ 类变更的温床。

### 1.6 智能体侧的三层防护（D3）

`ToolRegistry` 实现了与 Controller **平行**的一套校验：

| 层 | 机制 | 位置 |
|---|---|---|
| ① 声明 | `AgentTool.requiredPermissions()` / `requiresLeader()` | 各工具类 |
| ② 断言 | `visibleTo()` 过滤 LLM tools 数组 + `/tools` 面板；`dispatch()` 派发前再断言；`TaskGate.confirm` 确认时第三次断言 | `ToolRegistry` |
| ③ 测试 | `ToolRegistryReadonlyModeTest` 等 | 单测 |

**这套设计本身是对的**，`visibleTo` 一处收敛、四处生效，注释也写得很清楚。问题出在两点：

- **它只做"页面级"校验，不做"记录级"校验**（与 Controller 一致，所以一致地漏）；
- **存在它覆盖不到的旁路**（§2.3 P1、P5）。

---

## 2. 智能体问答场景越权分析

### 2.1 攻击面定位

智能体是**同一批 Service 的第二条入口**，绕过 Controller 的 `@PreAuthorize`：

```
浏览器 ──▶ Controller ──@PreAuthorize──▶ Service ──▶ Repository
                                              ▲
灵思   ──▶ ToolRegistry ──visibleTo/dispatch──┘   ← 平行的第二道，不共享代码
```

因此智能体的越权风险 = **页面自身缺口** ∪ **智能体独有路径**。前者不需要智能体就存在，但**智能体会把它变成"一句话就能拿到"**，降低了利用门槛（不需要理解参数结构、不需要翻接口文档）。

### 2.2 路径总览

| # | 路径 | 性质 | 可拦截? |
|---|---|---|---|
| P1 | `+` 附加上下文（`ChatRefsResolver`）按 ID 直取活动信息 | **智能体独有** | ❌ 当前无拦截 |
| P2 | 工具"忠实映射"页面权限，但页面本无门 | 两处共有 | ⚠️ 需先修页面 |
| P3 | `PolicyGuard.egress` 结果被丢弃 → 脱敏空转 | 智能体独有 | ❌ 闸口失效 |
| P4 | `mask()` 不含姓名 + `maskName()` 从未调用 | 智能体独有 | ❌ 语义未兑现 |
| P5 | `GET /streams/{streamId}` 无归属绑定 | 智能体独有 | ❌ 无拦截 |
| P6 | 无数据域 → 有 `x:view` 即见全部部门数据 | 两处共有 | ❌ 机制不存在 |
| P7 | 写端点无注解但 Service 有对象级校验 | 两处共有 | ⚠️ 依赖调用方自觉 |
| P8 | 知识索引层（新增，设计阶段） | 智能体独有 | 见 §3 |

### 2.3 逐条分析

#### P1 —— `ChatRefsResolver` 的 IDOR【🔴 高，当前可利用】

**代码事实**：

```java
// ChatRefsResolver.resolve() → fetch()
private Activity fetch(Long id) {
    return activityService.getEntity(id);   // 无权限校验、无 scope 校验
}
```

调用链：`AgentController.chat` → `orchestrator.chatSync(msg, convId, ctx, refs)` → `run()` → `refsResolver.resolve(refs)`。**这一步发生在任何工具可见性过滤之前**，且不经过 `ToolRegistry`。

`ActivityController` 的 `GET /api/activities` 与 `GET /{id}` **均无 `@PreAuthorize`**，`ActivityService.getEntity(id)` 也只是 `findById(...).orElseThrow(...)`。

**触发条件**：
1. 任意已登录账号（包括最小权限的 `STAFF`）
2. `POST /api/agent/chat`，body 里带 `"refs":[{"type":"activity","id":<任意ID>}]`
3. 无需知道活动名，**ID 可顺序枚举**

**泄露内容**（两条出口）：
- **HTTP 响应里的 `references`**：`{type:'activity', id, label: 活动名, link:'/activities/<id>'}`
- **注入 LLM 的 system 注记**：`活动「<名称>」（ID n）· 状态 <status> · <startDate> ~ <endDate> · <地点>`
- 更进一步：模型看到名称后，可以继续就此展开回答（"这个活动在哪办"）

**影响评估（已更正）**：活动对**全体登录用户本就开放**（`/activities` 无路由守卫，注释写明"干事可看"，且 7 个角色的权限种子全都含 `activity:view`）。所以 A1 **不是**"越权获取受保护内容"，等级从 🔴 高 下调为 🟡 低。

**为什么仍然要修**（三条实际理由，与"是否正在被利用"无关）：
1. **它是一个无校验的直连入口**。将来若活动改为受限（例如新增"内部活动仅部长及以上可见"），这里会立刻变成越权通道，而且改动者不会意识到这条链路存在
2. **它绕过了一切规则**：不经过 `ToolRegistry`、不产生 `agent_audit_log`、不受 `mode` 影响 —— 一个不受任何既有机制约束的数据出口本身就是技术债
3. **修的成本极低**：加一次可见性判定即可

**修复方向**（保持"当前行为不变、只把门变成真的"）：
1. `ActivityController` 的 `GET /` 与 `GET /{id}` 补 `@PreAuthorize("hasAuthority('activity:view')")`。**这是零行为变更**——7 个角色的种子权限集全都包含该码，补上后没有人会被挡住；
2. `ChatRefsResolver` 注入 `ToolContext`，解析前用同一口径判定；失败时不返回 references、不注入注记，且**话术与"不存在"合并**（避免存在性探测，见 G7）。

**修复方向**：
1. `ChatRefsResolver` 注入 `ToolContext`，解析前做一次可见性判定（活动口径 = 与页面一致，但同时**先给活动模块补上 `activity:view` 注解**，否则判定无依据）
2. 失败时**不返回 references、不注入注记**，并按现有"脏数据跳过 + 留说明"的约定告知"该活动不存在或无权访问"——**注意话术要与"不存在"合并**，否则构成存在性探测（见 G7）

#### P2 —— "忠实映射页面权限"在页面无门时会一致地漏【🔴 高】

`ToolRegistry` 的设计原则是：工具声明的权限码**忠于 Controller 现状**（`SearchArticlesTool` 的注释写得很明确，`requiresLeader()` 就是 content 模块角色宏的忠实映射）。这个原则**本身是正确的**（避免顺手改权限引入 D11 第 ② 类破坏）。

但它的前提假设是"页面上有门"。**在 39.1% 端点没有门的情况下，忠实映射 = 一致地把缺口复制进智能体**：

| 页面 | 页面实际门槛 | 工具声明 | 结果 |
|---|---|---|---|
| `GET /api/announcements` | **登录**（`notice:view` 未挂） | （待定，若按页面 → 登录） | 一致 |
| `GET /api/articles` | **登录**（LEADER 宏只在 create/review） | `requiresLeader()` | **工具比页面更严** ✅ |
| `GET /api/party-members` | **登录**（`party:view` 未挂） | `party:view` | **工具比页面更严** ✅ |
| `GET /api/course-schedules/analyze` | **登录** | 无声明 | 一致，且**任一人可查任意人** ⚠️ |
| `GET /api/activities` | **登录** | `queryActivityOverview` 无声明 | 一致 |

**结论**：智能体在多数业务模块上**并不比页面更宽松**（甚至更严，因为工具是后来加的、声明得更谨慎）。真正的风险不来自智能体，而来自**页面本身**：`notice:view`、`party:view`、`article` 的门都是"装饰性"的 —— 权限管理页上勾不勾选这些码，对实际能否读到数据没有影响。

**这一条对整个知识库方案是决定性的**：如果按"忠于页面"的规则接入知识源，且页面无门，那么知识索引也无门 —— **权限管理页上的开关会给管理员一种"我已经控制住了"的错觉**。

#### P3 —— `PolicyGuard.egress` 算出结果却被丢弃【🔴 高，闸口空转】

**代码事实**（`AgentOrchestrator` L309-322）：

```java
String egress = policyGuard.egress(tool.sensitivity(), result);   // ← 算了
if (egress == null) {
    // restricted：走模板话术（正常）
} else {
    String text = tool.toLlmText(ctx, result);                     // ← 没用 egress！
    messages.add(...toolResult(call.id(), text));
}
```

`AgentTool.toLlmText()` 的默认实现是 `return result.llmText();`，而**全部工具类都没有覆写它**（全仓仅 `AgentTool` 一处出现 `toLlmText` 定义）。

所以：
- **RESTRICTED 拦截有效**（靠 `egress == null` 判断）
- **SENSITIVE 的 `mask()` 结果被直接丢弃**，原始文本进 LLM 上下文

**为什么现在还没炸**：两个 SENSITIVE 读工具恰好不携带可遮罩字段——
- `queryAttendance`：返回 `personName` / `status` / `remark`（无手机号/身份证/学号）
- `queryPartyMemberProgress`：脱敏由 `PartyMemberService.page(..., staff)` 在 **Service 层**完成（`staff=false` 时吐身份证/地址/电话）

**所以这是"潜伏缺陷"而非"当前泄露"** —— 但性质严重：**一个安全闸口被接上了线却没有通电，且不报任何错**。任何后续新增的 SENSITIVE 工具（包括知识库的 `PartySource`）只要在返回文本里带手机号/身份证，就会**直接出网**。

**修复**：`else` 分支改用 `egress`（并把 `toLlmText` 作为可选的"工具自净化"前置步骤），即：

```java
String text = tool.toLlmText(ctx, result);      // 工具自净化（可选）
String egress = policyGuard.egress(tool.sensitivity(), result);  // 闸口（唯一）
...
```
**注意**：更干净的做法是让 `egress` 接受已 `toLlmText` 的文本，避免"两个净化入口"重新分裂。建议统一为「工具只做裁剪，`PolicyGuard` 做唯一净化」。

**测试兜底**：补一个断言"声明 SENSITIVE 的工具，其进 LLM 的文本必须与 `mask()` 输出一致"——否则这个 bug 会以同样方式回归。

#### P4 —— `mask()` 不遮姓名 + `maskName()` 死代码【🟠 中】

`PolicyGuard.mask()` 只处理三类模式：手机号、身份证、学号（`STUDENT_NO`）。
`maskName()`（"张\*\*"式）**在全仓只出现在它自己的定义处，没有任何调用点**。

于是 `Sensitivity.SENSITIVE` 的注释承诺"必须先经服务端字段级遮罩/摘要"**只兑现了三分之一**：姓名（党务场景里最核心的个人标识）不在遮罩范围内。

**影响**：`queryAttendance` 返回的"姓名 + 缺勤状态 + 备注"会**原样进入外部 LLM**。对 STAFF，备注里可能写"因病请假"这类健康信息；对领导，这是设计上允许的。但由于工具对 `anyPermission('schedule:check')` 的判定（"无管理权限 → 强制只看自己"）是**基于权限码的**，一旦某个 STAFF 被授予 `schedule:check`，他就能看到**全部门所有人的姓名+缺勤+备注**——而这在页面上是同一个权限码控制的，所以是"一致"，但**SENSITIVE 本意是给这层加一道遮罩**，这道遮罩现在是空转的。

**修复**：把 `maskName()` 纳入 `mask()`（或至少在 SENSITIVE 分支按字段类型决定遮罩策略），并明确"姓名遮罩后是否仍可用于回答"（"3 人未签到"可就够用，不必列名）。

#### P5 —— `GET /streams/{streamId}` 无归属绑定【🟠 中】

`AgentController.stream()` 仅 `@PreAuthorize("isAuthenticated()")`，随后 `publisher.since(streamId, since)` **不校验该流属于当前用户**。

`streamId` 生成方式：
```java
String id = prefix + "_" + Long.toHexString(System.nanoTime());
```
`nanoTime()` 不是秘密（单调时钟，可从进程启动时间推断量级），**不是 UUID**。缓冲区未命中时还会走 `rebuildFromDb(streamId, since)` 从 `agent_tool_call` 表重建 —— 同样不带用户条件。

**触发条件**：需要知道或猜到 `streamId`。主要来源：自己会话的 `streamId` 是可见的（每次 `/chat` 返回），但跨用户猜测需要精度。**降级因素是"难以猜"而非"被拦住"**——这仍是 IDOR。

**修复**：`AgentEventPublisher` 内部维护 `streamId → userId` 映射，`since()` 增加 userId 参数并断言；`rebuildFromDb` 的查询加用户条件。

#### P6 —— 无行级数据域【🟠 中高】

`Role.dataScope`（`ALL` / `DEPT`）**仅在 `UserController` 的用户列表生效**。

因此：一个有 `activity:view` 的干事，在**页面上**和**通过智能体**都能查到全校所有部门的活动、考勤、材料。这不是智能体引入的问题，但智能体让它变得更容易触发（自然语言问一句就行）。

**这一条对知识库是关键**：`kb_chunk.owner_dept_id` 这个字段在 v1.2 设计里存在，但**它过滤所依据的"用户所属部门"在业务侧根本没有被用于隔离** —— 也就是说，知识库如果做部门过滤，它会**比页面更严**；如果不做，它与页面一致。**必须明确选择哪一个，并写进评审**：这属于"知识库要不要顺便补齐页面的缺口"的决策，而不是技术选择。

#### P7 —— 写端点无注解但 Service 有对象级校验【🟠 中】

`ArticleController` 的 `PUT /{id}`、`/{id}/submit`、`/{id}/publish`、`/{id}/stats`、`DELETE /{id}` 均无 `@PreAuthorize`，但 `ArticleService` 内校验 `leader` 与 `authorId`。

`leader` 由 `ArticleService.isLeader(current.getRoleCode())` 从**角色码硬编码推导**，而不是从权限码。这意味着：**content 模块的角色宏（`MEDIA_LEADER`/`TEACHER`/`DIRECTOR`）与权限码体系是两套并行的判定**，权限管理页里把 `template:manage` 勾给某个角色，不会影响 content 模块的任何判定。

**对智能体的影响**：`draftArticleFromActivity` 与 `searchArticles` 用 `requiresLeader()` 表达同一口径 —— 目前是**正确且节制**的做法。但它意味着 content 模块**永远无法通过权限管理页调整**，必须改代码。

#### P8 —— 知识库索引层（设计阶段，见 §3）

### 2.3.A9 —— 【🔴 高】真正的越权实例：干事可以通过灵思问出党务台账内容

**这是本审计中唯一确凿的"没有页面权限的用户获取到该页面受保护内容"实例。**

#### 权威权限口径在哪里

本系统的**产品级权限口径由前端路由守卫定义**（后端注解缺 39.1%，不可作为口径来源）：

| 路由 | 守卫 | 位置 | 语义 |
|---|---|---|---|
| `/agent` | **无** | `router/index.tsx` L70-71 | 对所有登录用户开放；注释明示"权限控制在工具层" |
| `/activities` | **无** | L76-78 | 注释："活动管理（**干事可看**…）" |
| `/content/articles` | **无** | L112 | 注释："推文**干事可看**" |
| `/content/news` | **无** | L113 | 仅菜单层面对部长及以上显示 |
| `/archive/*` | **无** | L116-120 | 菜单按权限码显隐 |
| `/members` | `RequirePerm(['member:view'])` | L93 | 真门槛 |
| **`/party/*`** | **`RequireRole(LEADER_ROLES)`** | **L102-103** | 注释："党务台账（**敏感，部长及以上**；干事读已脱敏，仍隐藏入口）" |
| `/admin/users` | `RequirePerm(['user:view'])` | L126 | 真门槛 |
| `/admin/permissions` | `RequirePerm(['user:permission'])` | L128 | 真门槛 |
| `/admin/settings` | `RequireRole(ADMIN_ROLES)` | L130 | 真门槛 |

`LEADER_ROLES`（前端 `RequireRole.tsx` L8-15）与后端 `ToolConventions.LEADER_ROLES` **完全一致**（6 个角色，不含 `STAFF`）。

#### 漏洞链路

```
① 前端：STAFF 角色 → /party/* 被 RequireRole(LEADER_ROLES) 拦在 /403，菜单也隐藏
② 后端：PartyMemberController 的 GET /  (page)、GET /{id}、GET /stages 三个端点
        —— 无 @PreAuthorize（实测 3/7 未注解）
        → 干事直连 API 即可拿到党务名册
③ 灵思：QueryPartyMemberProgressTool.requiredPermissions() = ["party:view"]
        —— 而 STAFF 的权限种子里**就有** "party:view"（PermissionService.restoreDefault L124）
        → 工具对干事可见
        → 干事问一句"有哪些预备党员"，得到 姓名 + 阶段 + 所属部门 + 申请时间
```

**根因是口径冲突**：产品口径用「**角色级别**（部长及以上）」定义党务台账的可见性，而工具用「**权限码** `party:view`」表达，且这个权限码被授予了 STAFF。**两套口径不一致，且不一致的方向恰好是后端更松。**

#### 为什么 `party:view` 是错误的口径（而不是"权限配错了"）

即便把 `party:view` 从 STAFF 摘掉，也仍是错的口径 —— 因为它表达的是"能看党务"，而产品要表达的是"**部长及以上**能看党务"。
用权限码表达角色谓词，会在权限管理页上产生一个新的失败模式：**管理员为了让某干事用上某个功能，勾选了 `party:view`，于是顺带把他放进了党务台账** —— 而前端路由是角色守卫，不受权限码影响，所以**页面依然打不开**，他只会通过 API 和灵思看到数据。

> 正确修法：**把口径收成一处**。要么让前端 `/party/*` 也改用 `RequirePerm('party:view')`（则权限码成为唯一口径），要么把工具的 `requiredPermissions` 改成 `requiresLeader()`（则角色成为唯一口径）。**不能一边角色、一边权限码。**

#### 修复清单

| # | 动作 | 位置 |
|---|---|---|
| 1 | `page`/`detail`/`stages` 三个端点补 `@PreAuthorize`，口径与前端路由一致 | `PartyMemberController` |
| 2 | `QueryPartyMemberProgressTool` 增加 `requiresLeader()`，使工具口径与路由一致（`party:view` 保留为"能看党务"的粗粒度码，不再单独决定可见性） | 工具类 |
| 3 | 统一口径后，把 `party:view` 从 STAFF 种子中移除或明确其语义（当前它是个会误导人的码） | `PermissionService.restoreDefault` / 迁移 |
| 4 | 集成测试：STAFF 调 `/api/party-members` 必须 403；STAFF 调 `/agent/tools?mode=standard` 的清单中必须无 `queryPartyMemberProgress` | 新增 |

**对知识库方案的直接影响**：`PartyMemberSource.canAccess` 必须用 **`LEADER_ROLES`（部长及以上）**，**绝不能用 `party:view`**。这是一次具体的"不能照抄权限码"的实证 —— 与 §3.2 的 G1 是同一条道理，但这次有确切的代码证据。

---

## 3. 知识库问答（对话档位）专项：现有机制能否拦截

### 3.1 能拦住的

| 机制 | 拦住什么 |
|---|---|
| `@PreAuthorize("isAuthenticated()")` on `/api/agent/*` | 未登录访问 |
| `ToolContext` + `visibleTo` | 对话档位不会调用任何**写工具**（且 KB 档位走独立流水线，不进工具循环） |
| `PolicyGuard.egress` 的 **RESTRICTED 分支** | `restricted` 级内容不经 LLM（这一条是有效的） |
| `Sensitivity.RESTRICTED.canEgress()` | 同上 |
| `ConversationStore.require()` / `TaskGate.detail()` | 跨用户读会话、跨用户读任务（**归属校验是到位的**） |
| `PolicyGuard.forStorage()` | SENSITIVE 内容落库只存摘要（**有效**） |

**重要不对称**：`forStorage` 有效、`egress` 空转（P3）。也就是说目前"存得住、防不住"——用户自己看不到原文明文（存的是摘要），**但原文已经出网给了外部 LLM**。这个不对称本身就是 P3 的证据。

### 3.2 拦不住的 —— 知识库设计缺口

| # | 缺口 | 为什么现有机制拦不住 | 等级 |
|---|---|---|---|
| **G1** | **"忠于页面"会一致地漏**（P2 传导） | 39.1% 端点无门 → 知识源接入时若按页面口径，索引也无门。`canAccess` 实现得再正确，判定的依然是"一个不存在的门" | 🔴 高 |
| **G2** | **引用片段可能从未过滤集合构造** | 若 `citations[].snippet` 从**粗筛结果**而非**回源复核后结果**构造，越权正文会经"出处"泄露——答案正文干净，出处带毒。**这是本设计里最容易写错的一行** | 🔴 高 |
| **G3** | **索引期粗筛字段默认值风险** | 若某源适配器把 `visibility` 默认成 `ALL`，粗筛形同虚设，全部压力落在回源复核；只要复用出一个"忘记调 `canAccess`"的分支就漏 | 🟠 中 |
| **G4** | **聚合数据的推断泄露** | B 形态快照本身是"合法的聚合"，但小组织内**聚合 ≈ 个体**："本支部今年处理违纪 1 起" + 支部 8 人 = 可推断。`PolicyGuard` 不处理这个问题（它只认字段模式） | 🟠 中 |
| **G5** | **`kb_snapshot` 作用域未与部门绑定** | 若快照生成时 `scope_id` 为空、`visibility='ALL'`，则 A 部门用户可读到 B 部门的聚合快照 | 🟠 中 |
| **G6** | **`DataDigest` 若交给 LLM 自由选择** | 会变成绕过 `visibleTo` 的新通道；§2.2.2 的"不由 LLM 选"是硬约束，必须代码层保证（不是靠 prompt） | 🔴 高 |
| **G7** | **存在性泄露** | 三种拒答话术（无权限 / 无内容 / 不相关）若可区分 → 攻击者可枚举"哪些文档存在"。对制度类文档危害小，对人事类文档危害大 | 🟠 中 |
| **G8** | **权限收紧无法撤回历史** | 某文档曾对用户可见并被引用进历史回答，之后权限收紧 → **历史 `agent_message` 里的 `citations[].snippet` 仍然可见**。v1.2 只处理了"源删除"（chunk 软删 → 显示"已删除"），**没处理"权限收紧"** | 🔴 高 |

### 3.3 G8 需要单独说明（这是我最不希望被漏掉的一条）

v1.2 的删除语义是：源软删 → chunk 软删**保留行** → 历史引用显示"该文档已删除"。
这个处理**对"删除"是对的**（历史事实不该抹掉）。但对**"权限收紧"完全不适用**：

> 用户在 3 月问过"某党员的发展情况"，回答里带 `snippet`（含姓名与阶段），落库进 `agent_message`。
> 6 月 该用户被调离组织部门。
> 用户再次打开 3 月的那条对话 —— **snippet 依然原样显示**。

现有机制里**没有任何东西**会拦截这个：`ConversationStore.messages()` 只校验"会话属于你"，不校验"消息里的引用你现在还能不能看"。

**修复方向**（两个选项，需要拍板）：
- **方案 A（推荐）**：历史消息渲染时，对 `citations` 做**实时回源复核**，不通过的降级为"（该引用已不可见）"。代价：一次历史查询 N 条消息 × M 条引用 = 需要批量优化（一次 `canAccess` 批查）。
- **方案 B**：落库时记录 `asOf` 的权限快照，渲染时用"当时的权限"判定。代价：用户被降权后仍能看到旧内容 —— **不推荐**，等于权限收紧永不生效。

**方案 A 有一个副作用要接受**：历史对话的观感会"随着权限变化而变化"。这是正确行为，但需要在文档里明说，否则会被当成 bug 报上来。

---

## 4. 缺口清单汇总

| # | 缺口 | 等级 | 触及层 | 修复成本 |
|---|---|---|---|---|
| A1 | `ChatRefsResolver` IDOR | 🔴 高 | Agent 独有 | 低（加一次可见性判定） |
| A2 | `egress` 返回值被丢弃 | 🔴 高 | Agent 独有 | 极低（改一行 + 补测试） |
| A3 | 88/225 端点无注解 | 🔴 高 | 业务层 | **高**（需按模块逐个定夺"该挂哪个码"） |
| A4 | 无行级数据域 | 🟠 中高 | 业务层 | **很高**（需引入统一数据权限机制） |
| A5 | `streams/{id}` 无归属 | 🟠 中 | Agent 独有 | 低 |
| A6 | 姓名不遮罩 + `maskName` 死代码 | 🟠 中 | Agent 独有 | 低 |
| A7 | `/api/signins/scan` permitAll 匿名写 | 🟠 中 | 业务层 | 低（签到码时效 + 限流） |
| A8 | `/analyze` 接受任意 userIds | 🟠 中高 | 业务层 | 中 |
| G1–G8 | 知识库设计缺口 | 见 §3.2 | 新增层 | 中 |

---

## 5. 修复优先级建议

**P0（先修，且必须在知识库 P0 之前）**

1. **A2** —— 一行修复，但它是"安全闸口空转"，且是知识库所有脱敏承诺的地基
2. **A1** —— 当前唯一可利用的智能体独有越权
3. **A3 中与知识源直接相关的四个模块** —— `announcement` / `article` / `partyMember` / `activity`：这是知识库 P0 的四个源，**先给页面补门，知识库才有可忠于的对象**

**P1**

4. **G2 / G6 / G8** —— 知识库设计的三条硬约束，写进实现清单 + 单测断言
5. **A5 / A6** —— 低成本
6. **A8 / A7**

**P2（独立立项）**

7. **A4** —— 行级数据域机制（`dataScope` 要真正落地，需要统一的数据权限注解或 QueryDSL 层拦截，影响面大）
8. **A3 剩余端点** —— 需按模块与业务方确认"该挂哪个码"，不能机械补注解

---

## 6. 本审计对知识库方案的强制约束

以下三条从本文结论直接导出，应写入 `PAMS-灵思知识库问答方案-v1.md` 并作为验收条件：

1. **`canAccess` 必须实现"知识库自己的可见性判定"，不能实现成"查页面有没有挂权限码"**。
   因为页面本无门（A3）。正确做法是逐个源回答："**这个数据，谁应该能看到？**"，然后把它写成 `canAccess`。若答案与页面现状不一致，**必须先修页面**（否则知识库要么比页面严、要么一致地漏，二者都要显式声明）。

2. **`citations` 只允许从"回源复核通过后"的集合构造**（G2）。
   建议实现为：检索 → 粗筛 → **回源复核** → 得到 `allowSet` → **仅用 `allowSet` 拼 prompt 与构造 citations**。并把 `allowSet` 作为唯一入口，禁止任何代码路径从粗筛结果直接读 `snippet`。

3. **历史消息的 `citations` 必须实时回源复核**（G8）。
   v1.2 只处理了源删除，未处理权限收紧。需在 `ConversationStore.messages()` 渲染路径上补批量复核，并接受"历史引用会随权限变化"这一正确但反直觉的行为。

---

## 附：本次审计中确认**不成立**的假设（避免误传）

审计过程中有若干猜测被代码证伪，记录在此以免后续重复讨论：

| 假设 | 实际 |
|---|---|
| `notification:send` 权限码未种子 → `sendNotification` 工具永久不可见 | ❌ **不成立**，V24 已补录该权限码并授予角色 |
| `schedule:generate` 权限码未种子 → `generateActivitySchedule` 死工具 | ❌ **不成立**，V21 已补录 |
| 所有未注解端点都无任何校验 | ❌ **不成立**，`ArticleController` 写端点由 `ArticleService` 做对象级校验 |
| 智能体普遍比页面更宽松 | ❌ **不成立**，`searchArticles`/`queryPartyMemberProgress` 反而**比页面更严** |
| `agent_message` 会落库敏感明文 | ❌ **不成立**，`PolicyGuard.forStorage` 对 SENSITIVE 只存摘要（有效） |
| A1 是正在发生的越权 | ❌ **不成立**（当日二次核查已下调定级），活动对全体登录用户本就可见 |

---

## 7. 本次已实施（2026-09-14）

审计结论中不依赖产品决策的部分已当场修复并验证（**51 项单测全绿**）。

| # | 修复 | 关键改动 | 验证 |
|---|---|---|---|
| **A2** | 出网闸口接线 | `PolicyGuard` 抽出 `egressText(Sensitivity, String)` 作为**唯一闸口**；`AgentOrchestrator` 改为「工具只裁剪（`toLlmText`）+ 闸口做净化」，不再丢弃 `egress` 返回值 | `PolicyGuardEgressTest`（11 项），含**源码级接线契约锁** |
| **A2 附** | 遮罩规则顺序 | 原顺序（先手机号后身份证）会让身份证号里的 `19900307721` 被手机号规则咬走，导致身份证规则**再也匹配不上**、只遮住中段。改为 **ID_CARD → PHONE → STUDENT_NO**（长模式优先） | `idCardIsMaskedBeforePhoneSoItIsNotPartlyEaten` |
| **A6** | 学号规则与姓名 | `STUDENT_NO` pattern 此前**定义了却从未被应用**（"以为有防护其实没有"）；加 `(?<!\d)/(?!\d)` 边界后接上。姓名不放进闸口（中文姓名无法用正则可靠识别，硬做会改坏文本），改由 `QueryPartyMemberProgressTool` 在 `staff` 情形下过 `maskName` —— 顺带修掉那句**假话**"敏感字段已脱敏"（此前摘要仍原样列姓名） | `studentNoAndPhoneDoNotInterfere` / `gateDoesNotGuessNames` |
| **A1** | 引用解析可见性 | `ChatRefsResolver.resolve(refs, ctx)` 接收上下文；**先判可见性再取数据**（拿不到就不取，避免"取了再判断"把内容带进内存）；`ActivityController` 的 `GET /` 与 `/{id}` 补 `activity:view`（**零行为变更**——7 个角色的种子都含该码） | `ChatRefsResolverTest`（9 项），含 **"无权与不存在话术必须一字不差"**（防存在性枚举） |
| **A9** | 党务台账口径统一 | `PartyMemberController` 的 `page`/`detail`/`stages` 补 `@PreAuthorize(RoleExprs.LEADER)`；`QueryPartyMemberProgressTool` 由 `party:view` 改为 `requiresLeader()`；新增 `RoleExprs` 常量类收口角色谓词（`ActivityController.LEADER` 改为引用它，消除字面量重复） | `PartyMemberServiceTest` 等回归通过 |
| **A5** | 过程事件补拉的归属绑定 | `AgentEventPublisher` 增加 `streamId → userId` 归属映射；`newStream(prefix, owner)` / `since(streamId, since, requester)`；**两级判定**：内存映射优先，缺失时回查数据库（`agent_tool_call.conversationId` → 会话 `userId`）—— 只靠内存会把"重启后补拉历史"这个接口的主要用途一起拒掉。不匹配时**返回空列表而非抛错**，避免存在性泄露 | 新增 `AgentEventPublisherTest`（6 项：归属者可读 / 非归属者得空 / null 拒绝 / 回查放行 / 回查拒绝 / 未知流保守拒绝） |

### 顺带修掉的一个知识层缺陷（非权限问题，但属同一类静默失效）

> **T2（字段级）知识源与 LIKE 降级路径实际上永远查不到东西。**

原实现是 `title LIKE '%' || 用户整句 || '%'`：
```
用户问「组织部长是谁」 → LIKE '%组织部长是谁%' → 命中不了标题「张三 · 组织部 · 部长」
```
没有报错、没有日志，只是"问什么都答没有依据"。这条缺陷在接入 T2 源之前**不会暴露** ——
当时唯一的 T2 候选还没接进来，而 T1 走 MySQL 全文索引（`MATCH ... AGAINST` 自带 ngram 分词）。

修法：新增 `QueryTokenizer`（中文 bigram + 西文整词，限流 12 项），把"整句 LIKE"改成"命中任意一词即可"；
并给 `KnowledgeSource.Section` 增加可选 `chunkTitle`，让 T2 源能把「姓名 · 部门 · 职务」这类
**唯一检索入口**放进标题列（T2 只匹配标题，标题放错等于这个源白接）。

验证：`QueryTokenizerTest`（5 项）+ `ChunkSearchRepositoryTest`（占位符与参数配对的结构不变量）+ 端到端实测可问出职务。

### 顺带发现的一个独立问题（未修，建议单独立项）

> **`PermissionGuardIntegrationTest` 的 26 个测试自 V25 引入起就是死的。**

test profile（`application.yml` L61-72）用 **H2 + 全量 Flyway**，而 `V25__fix_seat_layout_color_labels.sql` 用了 MySQL 专有的 `information_schema` + `PREPARE ... EXECUTE` 动态 DDL，在 H2 上必然失败 → Spring 上下文启动失败 → 该测试类的**全部 26 个用例以 Error 结束**。

后果比"少跑几个测试"严重：**这个类恰好是权限守卫的集成测试**（`staff_*_forbidden` 系列）。它长期报错，会被当成"环境问题"忽略，于是**权限回归实际上没有任何集成层覆盖** —— 与 A9 长期存在互为因果。

修法三选一（需拍板）：
1. 给 V25 加 H2 兼容分支（`MODE=MySQL` 下仍不支持 `PREPARE`，难度高）
2. test profile 关闭 Flyway，改用 `schema.sql`/`data.sql` 或用 Testcontainers 起真 MySQL
3. **推荐**：test profile 改走 Testcontainers MySQL —— 迁移脚本是 MySQL 语法，用 H2 测本身就是"测了个不像的环境"

### 尚未修复（依赖产品决策或独立立项）

| # | 项 | 阻塞原因 |
|---|---|---|
| A3 剩余 | 88 个无注解端点中除去已补的 8 个 | 需按模块与业务方确认"该挂哪个码"，机械补注解会误伤 |
| A4 | 行级数据域机制 | 影响面大，需引入统一数据权限机制，独立立项 |
| A7 | `/api/signins/scan` 匿名写 | 需先确认签到码的时效与限流策略是否已足 |
| A8 | `/course-schedules/analyze` 接受任意 `userIds` | 需确认"无课表互查"是否业务上本就允许 |
| 新 | `AgentEventPublisher` 的 `buffers` / `owners` 两个 Map 无淘汰策略 | 按 stream 累积、JVM 生命周期内不释放（每 stream 内的条目受 `streamBufferSize` 约束，但 stream 数量不受约束）。需要 LRU + TTL，属独立小项 |
