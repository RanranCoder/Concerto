# 协奏 Concerto 工程实践

> 协奏 Concerto（工程代号 PAMS）是一个面向学生组织的协同管理平台，覆盖活动、排班考勤、成员、党务、宣传、档案、通知、权限与 AI 助手「Concerto Agent」等业务。本文沉淀项目的工程约定、架构分层、构建测试体系与踩坑经验，供后续开发与维护参考。

---

## 1. 项目定位与总体架构

协奏采用**前后端分离 + 多租户（多组织）**架构：

```
┌────────────────────────────────────────────────┐
│  pams-web（React 18 / Vite / AntD 5 / Zustand） │
│  端口 5173，经 Vite 代理 /api、/ws、/uploads     │
└──────────────┬─────────────────────────────────┘
               │ REST + STOMP WebSocket
┌──────────────▼─────────────────────────────────┐
│  pams-backend（Spring Boot 4 / Java 21）        │
│  端口 8080，20 个功能模块 / 58 个 Controller     │
│  JPA + Hibernate 多租户 / Flyway / Spring Sec   │
└──────────────┬─────────────────────────────────┘
               │
        MySQL 8（pams_concerto_demo）
        LLM（OpenAI 兼容协议，可降级 StubProvider）
```

- **单 JVM 部署**：流式推送基于内存 STOMP simple broker，注释中明确单实例前提；多实例需先替换 broker。
- **租户隔离**：每个请求经 `OrgContext`（ThreadLocal）携带组织上下文，Hibernate 通过 `OrgTenantIdentifierResolver` + `@TenantId` 做行级多租户，实体实现 `OrgScoped` + `TenantFillListener` 自动填充。

---

## 2. 技术栈与版本基线

### 2.1 后端（pams-backend）

| 项 | 版本/选型 |
|---|---|
| 框架 | Spring Boot **4.0.2**（注意 Boot 4 拆分包，测试用 `spring-boot-webmvc-test`） |
| 语言 | Java **21** |
| ORM | Spring Data JPA / Hibernate（`ddl-auto: none`，结构全靠 Flyway） |
| 数据库 | MySQL 8（驱动 `mysql-connector-j`）；测试用 H2 `MODE=MySQL` |
| 迁移 | Flyway（`flyway-core` + `flyway-mysql`，`baseline-on-migrate: true`） |
| 安全 | Spring Security + **jjwt 0.12.6**（HMAC） |
| 实时通信 | `spring-boot-starter-websocket`（STOMP） |
| HTTP 客户端 | `httpclient5`（LLM 传输层） |
| 文档处理 | `poi-ooxml 5.4.1`（Word/Excel） |
| HTML 净化 | `jsoup 1.18.3` |
| 其他 | Lombok、spring-boot-starter-validation |

### 2.2 前端（pams-web）

| 项 | 版本/选型 |
|---|---|
| 框架 | React **18.3.1** + react-router-dom **6.28**（`createBrowserRouter`） |
| UI | AntD **5.24** + @ant-design/icons |
| 状态 | Zustand **5.0.3** |
| HTTP | axios **1.7.9** |
| 实时 | @stomp/stompjs 7.3 + sockjs-client（Agent 流）；@microsoft/fetch-event-source（通知流 SSE） |
| 构建 | Vite **7** + TypeScript **5.7**（solution-style tsconfig） |
| 文档/表格 | superdoc、docx、docx-preview、xlsx、revogrid 等 |
| 测试 | vitest 3 + jsdom + @testing-library/react；E2E 用 Playwright 1.63（25 个 spec） |

---

## 3. 后端工程结构

### 3.1 包结构

```
com.pams
├── common/      # Result / PageResult / BizException / GlobalExceptionHandler
│                # BaseEntity / OrgContext / OrgScoped / TenantFillListener
├── config/      # SecurityConfig / WebSocketConfig / OrgTenantIdentifierResolver / DataSeeder
├── security/    # JwtUtil / JwtAuthenticationFilter / RoleExprs / LoginRateLimiter
│                # WebSocketAuthInterceptor / UserDetailsServiceImpl
├── controller/  # 平台入口：AuthController、PingController
├── dto/  entity/  repository/
└── module/      # 19 个业务模块，每个模块五层子包
    ├── activity/  agent/  archive/  chat/  content/  dashboard/  knowledge/
    ├── member/  notification/  org/  party/  permission/  routine/
    ├── schedule/  seat/  system/  user/  volunteer/  youth/
```

### 3.2 模块与 Controller 清单（共 58 个）

| 模块 | Controller（数量） |
|---|---|
| activity 活动管理 | Activity、ActivityDetail、Agenda、Plan、Score、Seat、Signin、SigninRoster、Task（9） |
| `agent`（Concerto Agent） | Agent、AgentArtifact、AgentBench、AgentDiagnostics、AgentMemory、AgentRuntime、AgentTeam + provider 下 AgentProvider、AgentModelStatus（9） |
| archive 档案 | Announcement、Credit、File、LegacyFileAccess、Material、Template（6） |
| party 党务 | PartyInvestigation、PartyMember、PartyRegister、PartyRoster、PartyTransfer（5） |
| member 成员 | AccountBatch、AccountSetup、Member、MemberSession（4） |
| notification 通知 | Notification、NotificationOperations、NotificationStream、PendingWork（4） |
| routine 排班考勤 | ActivitySchedule、Attendance、Schedule（3） |
| user 组织用户 | Department、Role、User（3） |
| content 内容 | Article、News（2） |
| permission 权限 | Permission、RoleManagement（2） |
| 单 Controller 模块 | chat(GroupChat)、dashboard、knowledge、org、schedule(CourseSchedule)、seat(SeatLayout)、system、volunteer、youth（9） |
| 平台入口 | Auth、Ping（2） |

> 模块内部统一五层子包 `controller / dto / entity / repository / service`；个别小模块（dashboard、system、user、volunteer、youth）直接在模块根放 Controller。`module/agent` 最复杂，含 13 个子包（config/controller/core/entity/llm/orchestration/prompt/provider/registry/repository/service/stream/tool）；`module/knowledge` 含 controller/dto/entity/listener/repository/retriever/service/snapshot/source/task。

### 3.3 统一响应与异常

- `Result<T>`：`{code, message, data}`，静态 `ok()` / `ok(T)` / `fail(int, String)`。
- `PageResult<T>`：`{records, total, current, size}`。
- `BizException`：仅携带 `int code`，由 `GlobalExceptionHandler` 统一转换为 `Result`。
- 前端 `http.ts` 按同一契约解包：`code===200` 直接取 `data`，否则抛 `ApiError`。

---

## 4. 安全与认证实践

### 4.1 JWT 认证链路

- 两条 `SecurityFilterChain`：`@Order(0)` 放行 `/ws`、`/ws/**`（WebSocket 握手由 `WebSocketAuthInterceptor` 单独鉴权）；主链 STATELESS，除登录/注册/激活/扫码签到等公开端点外全部认证。
- `JwtAuthenticationFilter`（置于 `UsernamePasswordAuthenticationFilter` 之前）：
  1. 解析 Bearer Token（文件类 GET 可降级读 HttpOnly Cookie `pams_token`）；
  2. 回库校验用户启用状态 + **吊销水位** `tokenInvalidBefore`（`JwtUtil.isRevoked(iat, watermark)`，登出/重置后旧 Token 立即失效）;
  3. 权限**不是**固定 `ROLE_*`，而是每请求调 `PermissionService.permissionsOfUser(uid)` 注入 `SimpleGrantedAuthority` 权限码；
  4. `OrgContext.set(orgId)`，finally 中 clear，防 ThreadLocal 泄漏。

### 4.2 方法级鉴权

- `@EnableMethodSecurity` + `@PreAuthorize("hasAuthority('activity:edit')")`，Controller 层为主。
- 角色谓词收敛在 `security/RoleExprs`：
  - `LEADER = hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')`（部长及以上，不含 STAFF）
  - `ADMIN = hasAnyRole('TEACHER','DIRECTOR')`

### 4.3 角色模型

无 Java 角色枚举，角色是 `sys_role` 表行，**单角色**（一个用户持有一个 Role）。种子角色 7 个：

| 角色 | 说明 |
|---|---|
| TEACHER / DIRECTOR | 指导老师 / 主任（ADMIN 层） |
| ORG_LEADER / SECRETARY_LEADER / MEDIA_LEADER / TECH_LEADER | 四位部长（LEADER 层） |
| STAFF | 干事，dataScope=DEPT（部门数据域） |

角色码常量与集合收敛在 `module/agent/core/ToolConventions`（`STAFF`、`LEADER_ROLES`），避免魔法字符串散落。

---

## 5. 数据库演进（Flyway）

- `db/migration` 常规脚本 **62 个**，版本号 V1–V72（不连续）。
- **双方言拆分**：MySQL/H2 有语法差异的脚本放专有目录 `db/migration-mysql` 与 `db/migration-h2`（各 6 个同名脚本：V25 座位色标修复、V26 会话历史绑定、**V27 知识库**、V34 租户唯一键、V35/V36 思维链字段）；application.yml 按 profile 选择 locations，test 用 H2 目录。
- **Java 迁移**：复杂逻辑用 Java 版脚本（如 `V37__tenant_authorization.java` 租户授权、`V47__business_permission_catalog_v2.java` 权限目录 v2）。
- 近期脚本示例：V66 模型上下文窗口、V67 请求级引导、V69 计划修订页审阅、V70 请求级写操作、V71 运行时租约、V72 青年/志愿领域包。
- 约定：**结构变更必须走 Flyway，禁止 `ddl-auto` 建表**；新脚本版本号不回填空洞版本。

---

## 6. 构建与本地开发约定

| 项 | 约定 |
|---|---|
| 后端端口 | 8080（`spring-boot:run` 有子 JVM，停服须按 8080 实际 PID 杀进程） |
| 前端端口 | 5173（WorkBuddy 环境下勿占用 7661） |
| 前端类型检查 | 必须用 `tsc -b`：根 tsconfig 是 solution-style（`files: []` + references），直接 `tsc --noEmit` 会空转零输出 |
| Vite 直启 | 可 `node node_modules/vite/bin/vite.js` 绕过 npm 脚本 |
| Vite 代理 | `/api`、`/uploads`（changeOrigin + 同源重写，可被 `PAMS_E2E_API_TARGET` 覆盖且强制 localhost）、`/ws`（ws:true） |
| LLM 密钥 | 只经环境变量注入（`PAMS_AGENT_API_KEY` / `PAMS_JWT_SECRET` / `PAMS_AI_ENCRYPTION_KEY`），yml 默认值为空；无 Key 时自动降级 StubProvider |
| 测试 profile | H2 `MODE=MySQL` + 强制 `provider: stub`，不依赖真实 LLM |

---

## 7. 测试与回归体系

### 7.1 分层测试

- **单元/集成**：JUnit（Spring Boot Test + H2）。重点用例：`ToolRegistryReadonlyModeTest`（只读模式工具可见性）、`ChatRefsResolverTest`（附加上下文权限）、`TaskGateArtifactsTest`（确认门禁产物）、`MemberSourceSafetyTest`（T2 知识源数据边界）、`ChunkSearchRepositoryTest`（检索仓储）。
- **接口回归脚本**：覆盖 r1–r8 主干回归、f1-form（缺参表单）、f2-history（历史会话）、f3-resolve（活动解析）、f4-plan（策划书）、f5-audit（审计）、kb1/kb2/kb3（知识库问答）。历史成绩：f4 16/16、kb 70/70；本期四批回归 **238/238 全通过**（81 + 48 + 91 + 18，其中 91 为组织治理与 AI 服务专项、18 为浏览器界面专项）。
- **E2E**：Playwright 25 个 spec，`pams-web/e2e/`；vitest 排除 `e2e/**` 防混跑。

### 7.2 已知测试坑

- `PermissionGuardIntegrationTest` 因 H2 不支持 MySQL 专有语法（V25 长文本索引等）长期失效，根治方案是 Testcontainers 真实 MySQL。
- 检索相关测试要小心**参数顺序**：全文检索 SQL 中 `SELECT` 子句的 `MATCH` 参数最先出现，参数顺序错会静默零命中而不报错。

---

## 8. 工程经验与教训（踩坑录）

以下均为真实发生过、已定位根因的坑，回归时需重点核验：

| # | 坑 | 教训 |
|---|---|---|
| 1 | `agent_audit_log.side_effect` 曾写入 `WRITE_PREVIEW`（>10 字符）导致事务 rollback-only、**审计静默丢失** | 字段口径锁死为 `READ/PREVIEW/WRITE`（≤10 字符），改口径前先查列宽 |
| 2 | 确认类编排刻意**不开整体事务**，审计 `REQUIRES_NEW` 若嵌进外层事务会被 rollback-only 污染 | 长流程编排与审计写入要物理隔离事务边界 |
| 3 | 全文检索参数顺序错 → **静默零命中**；T2 分级用 LIKE 时整句匹配标题也零命中 | 检索 SQL 写完必配 QueryTokenizer 分词断言，任何检索改动都要带"能查到"的用例 |
| 4 | `ToolContext.derive()` 漏传 `mode` → 权限模式静默降级 | 派生上下文必须透传全部安全字段，代码评审把 mode 当必查项 |
| 5 | `PolicyGuard.egressText` 返回值曾被丢弃（脱敏等于没做） | 脱敏函数必须以"返回值替换原文"的方式调用，写回归用例验证遮罩生效 |
| 6 | `AgentEventPublisher` 两个内存 Map 无淘汰策略 | 内存结构要有上限/过期设计，复审项 |
| 7 | 前端 `userRefs` 与 `userTexts` 下标必须同步 | 关联数组用同一来源生成，禁止两处手工维护 |
| 8 | 未识别的 Agent mode 曾可能被静默放行为可写 | 未识别 mode 必须 fail-fast，不得降级 |

### 8.1 工作流程约定

项目遵循「**审计 → 规划 → 执行 → 复审 → 变更后回归**」闭环：

1. 任何修改前先审计现状（相关 Controller/Service/测试覆盖）；
2. 规划明确改动文件清单与回归方案；
3. 执行后自查（`tsc -b` / Maven 编译 / 单测）；
4. 变更后跑对应回归脚本（主干 r 系列、Agent f 系列、知识库 kb 系列）。
5. 交付文档统一放 `docs/`，文件名优先 ASCII；文档内图文表齐全。

---

## 9. 部署形态

- `docker-compose.yml` + `deploy.sh` 提供容器化与脚本化部署；`start.bat` 用于 Windows 一键起前后端。
- 前端构建产物 `tsc -b && vite build`，配 `nginx.conf` / `Dockerfile`；后端单 JVM + MySQL。
- 上传目录 `./uploads`（multipart 上限 100MB/单文件、200MB/请求），经 `GET /uploads/**` 认证后访问。
- 环境变量清单：`PAMS_AGENT_API_KEY`、`PAMS_AGENT_BASE_URL`、`PAMS_AGENT_MODEL`、`PAMS_AGENT_PROTOCOL`、`PAMS_JWT_SECRET`、`PAMS_AI_ENCRYPTION_KEY`、`PAMS_E2E_API_TARGET`。

---

## 10. 小结

协奏的工程实践可归纳为四条主线：

1. **契约先行**：统一 `Result/PageResult/BizException` 三件套贯穿前后端；前端按契约解包，错误处理一致。
2. **结构收敛**：模块五层结构、权限码收敛、角色常量收敛、双方言迁移目录收敛——一切"约定"都有唯一权威出处。
3. **安全内建**：JWT 吊销水位、每请求权限注入、ThreadLocal 租户上下文、方法级鉴权是基础设施而非业务可选。
4. **回归驱动**：主干 r 系列 + Agent f 系列 + 知识库 kb 系列构成三层回归网，任何变更以回归全绿为收口标准。
