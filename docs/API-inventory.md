# PAMS 接口全量清单

> **源码基线**：2026-11-11 静态扫描。2026-11-12 对当前工作区复核了 HTTP 方法与路径、公开入口和实时通道
>
> **覆盖范围**：基线含 56 个 Controller、18 个业务模块和 351 个 HTTP 端点（含 SSE 与文件流），另列 WebSocket（STOMP）。基线之后的扩展接口见下文。
>
> **与旧文档的关系**：[旧版 API 文档](API.md)保留 2026-08-26 的历史接口。

---

## 1. 总览

| 指标 | 数量 |
| --- | ---: |
| Controller 类 | 56 |
| Controller 源码文件 | 56 |
| 业务模块 | 18 |
| HTTP 端点总数 | **351** |
| ├ GET / POST / PUT / DELETE | 146 / 109 / 55 / 41 |
| 带 `@PreAuthorize`（方法级或类级） | 235 |
| 无 `@PreAuthorize`（含公开端点） | 116 |
| 免登录公开端点 | 8 |
| 返回 `Result<T>` / `PageResult<T>` 包装 | 341 |
| 非标准响应（文件流 / SSE / ResponseEntity 包装） | 10 |
| 说明列：源码注释摘录 / 由动词与路径推断（†） | 118 / 233 |

### 1.1 按模块分布

| 模块 | 中文域 | Controller | 端点 | GET | POST | PUT | DEL | 有注解 | 无注解 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `root` | 认证与基础 | 2 | **4** | 2 | 2 | 0 | 0 | 0 | 4 |
| `user` | 用户 / 部门 / 角色 | 3 | **11** | 5 | 4 | 1 | 1 | 7 | 4 |
| `org` | 组织与租户 | 1 | **11** | 5 | 3 | 2 | 1 | 6 | 5 |
| `permission` | 权限与角色管理 | 2 | **29** | 17 | 6 | 4 | 2 | 3 | 26 |
| `member` | 成员与账号 | 4 | **24** | 9 | 11 | 2 | 2 | 23 | 1 |
| `party` | 党务管理 | 5 | **23** | 7 | 5 | 6 | 5 | 23 | 0 |
| `activity` | 活动 / 计划 / 签到 / 评分 / 任务 | 9 | **67** | 23 | 18 | 14 | 12 | 56 | 11 |
| `seat` | 座位布局 | 1 | **8** | 3 | 3 | 1 | 1 | 5 | 3 |
| `routine` | 日常事务（考勤 / 日程 / 活动排期） | 3 | **27** | 10 | 11 | 3 | 3 | 24 | 3 |
| `schedule` | 课表 | 1 | **9** | 6 | 2 | 1 | 0 | 8 | 1 |
| `archive` | 档案 / 材料 / 文件 / 公告 / 学分 / 模板 | 6 | **28** | 10 | 9 | 4 | 5 | 16 | 12 |
| `content` | 内容（文章 / 新闻） | 2 | **14** | 3 | 3 | 6 | 2 | 11 | 3 |
| `notification` | 通知 / 待办 / 流式推送 | 4 | **18** | 12 | 1 | 5 | 0 | 0 | 18 |
| `chat` | 群组聊天 | 1 | **11** | 3 | 3 | 3 | 2 | 8 | 3 |
| `dashboard` | 首页仪表盘 | 1 | **1** | 1 | 0 | 0 | 0 | 0 | 1 |
| `system` | 系统配置 | 1 | **1** | 1 | 0 | 0 | 0 | 0 | 1 |
| `knowledge` | 知识库问答 | 1 | **16** | 11 | 3 | 1 | 1 | 16 | 0 |
| `agent` | Concerto Agent | 9 | **49** | 18 | 25 | 2 | 4 | 29 | 20 |

> 注解列统计 Controller 层的 `@PreAuthorize`。实际访问还取决于全局认证、组织归属和业务授权。

### 1.2 扩展功能接口

- `PUT /api/orgs/current/packs/{code}` 切换当前组织的扩展功能，需要 `org:manage`。参见 [OrgController](../pams-backend/src/main/java/com/pams/module/org/controller/OrgController.java)。
- `/api/youth/**` 提供团员档案、组织生活、评议和推优。参见 [YouthController](../pams-backend/src/main/java/com/pams/module/youth/YouthController.java)。
- `/api/volunteer/**` 提供志愿活动、报名、服务时长复核与导出。参见 [VolunteerController](../pams-backend/src/main/java/com/pams/module/volunteer/VolunteerController.java)。

## 2. 通用约定

### 2.1 Base URL 与本地代理

| 项 | 值 |
| --- | --- |
| 后端端口 | `http://localhost:8080`（`server.port`） |
| 前端开发端口 | `http://localhost:5173`，Vite 代理 `/api`、`/uploads`、`/ws` → 8080 |
| 接口前缀 | 绝大多数为 `/api/**`；`GET /uploads/{*path}` 为历史图片直出；`/ws` 为 WebSocket 握手 |
| Content-Type | `application/json;charset=UTF-8`；上传接口为 `multipart/form-data` |
| 文件上传上限 | 单文件 100MB、单请求 200MB（`spring.servlet.multipart`） |

### 2.2 认证与免登录端点

JWT Bearer：`Authorization: Bearer <token>`；有效期 24h（`pams.jwt.expire-hours`）。登录时另下发 `pams_token` Cookie，用于 `<img>` / `<a>` 直链访问 `/uploads/**` 的兜底鉴权。未认证统一返回：

```json
{"code":401,"message":"未登录或登录已过期","data":null}
```

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/login` | 登录 |
| POST | `/api/auth/logout` | 登出（免登录：无有效 token 时仅清 Cookie） |
| GET | `/api/ping` | 健康检查 |
| POST | `/api/orgs/register` | 组织注册（新组织无账号，限流在 OrgService） |
| POST | `/api/auth/account-setup` | 批量开户后的账号激活 |
| GET | `/api/orgs/public` | 组织公开列表（仅非敏感字段，登录页用） |
| POST | `/api/signins/scan` | 扫码签到提交 |
| GET | `/api/signins/scan-config` | 扫码落地页配置 |
| GET/WS | `/ws`、`/ws/**` | 独立 `@Order(0)` 过滤链完全放行，STOMP 建连由 `WebSocketAuthInterceptor` 校验 Bearer token |

`GET /uploads/**` 要求登录，文件读取继续检查对象访问权限。SSE 的初次请求需要认证，后续 ASYNC dispatch 由安全配置放行。

### 2.3 统一响应与分页

```json
{ "code": 200, "message": "ok", "data": { } }
```

| 结构 | 字段 | 说明 |
| --- | --- | --- |
| `Result<T>` | `code` / `message` / `data` | 成功 `code=200`、`message="ok"`；失败 `Result.fail(code,message)` |
| `PageResult<T>` | `records` / `total` / `current` / `size` | 分页包装，通用查询参数 `page`（默认 1）、`size`（默认 10） |

### 2.4 错误码（`GlobalExceptionHandler`）

| code | HTTP | 触发条件 |
| --- | --- | --- |
| 400 | 400 | `BizException` 非白名单码、`@Valid` 校验失败、非法枚举/参数、请求体 JSON 解析失败、参数类型不匹配 |
| 401 | 401 | 未登录或登录已过期（含 `BizException(401)` 与认证入口） |
| 403 | 403 | `AccessDeniedException` / `@PreAuthorize` 拒绝 |
| 404 | 404 | 资源不存在 / `BizException(404)` |
| 405 | 405 | 请求方法不支持 |
| 409 | 409 | `BizException(409)` 冲突 |
| 500 | 500 | 未处理异常 |

### 2.5 明细表字段口径

| 列 | 含义 |
| --- | --- |
| 方法 / 路径 | 类级 `@RequestMapping` + 方法级映射拼接后的最终路径；`{id}` 为路径变量 |
| 说明 | 优先摘录源码 Javadoc / 行注释首句；标 **†** 表示源码无注释，由 HTTP 动词 + 路径 + 方法名**推断**，仅供参考 |
| 入参 | 业务入参摘要：`@Body`=请求体、`@Path`=路径变量、`@Query`=查询/表单参数、`@Part`=文件；框架注入参数（`@AuthenticationPrincipal`、`HttpServletRequest` 等）已省略 |
| 返回 | Controller 方法声明返回类型（已去包名）；`Result<?>` 表示源码使用通配符 |
| 鉴权 | 权限码 / 角色谓词简写；`登录`=`isAuthenticated()`；`公开`=SecurityConfig permitAll；`—`=**无 `@PreAuthorize`**。业务权限及数据范围需查看对应服务。注解统计见附录 B |

### 2.6 特殊响应端点（非 `Result` 直接返回）

| 方法 | 路径 | 响应 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/files/{id}/download` | `ResponseEntity<Resource>` | 附件下载（二进制流） |
| GET | `/api/plans/{id}/document` | `ResponseEntity<Resource>` | 活动策划 Word 文档下载（docx） |
| GET | `/api/members/export` | `ResponseEntity<Resource>` | 成员名单 xlsx 导出 |
| GET | `/api/members/import/template` | `ResponseEntity<Resource>` | 成员导入模板 xlsx |
| GET | `/api/course-schedules/import/download` | `ResponseEntity<Resource>` | 课表导入结果 / 模板下载 |
| GET | `/api/schedules/export` | `ResponseEntity<byte[]>` | 日程 xlsx 导出 |
| GET | `/api/notifications/stream` | `ResponseEntity<SseEmitter>` | SSE 长连接，`text/event-stream` |
| GET | `/uploads/{*path}` | `ResponseEntity<Void>` | 历史图片 URL 兼容直出（302 重定向 / 对象级鉴权） |
| POST | `/api/auth/account-setup` | `ResponseEntity<Result<Void>>` | 额外写 Cookie / 响应头 |
| POST | `/api/members/account-batches/{id}/credentials` | `ResponseEntity<Result<...>>` | 凭证明文下载，附加响应头 |

另有 **6** 个端点通过 `@RequestParam MultipartFile` 接收文件（其中 2 个显式声明 `consumes = MULTIPART_FORM_DATA_VALUE`）：

| 方法 | 路径 | 文件参数 |
| --- | --- | --- |
| POST | `/api/course-schedules/import` | @Part files: MultipartFile[], @Query deptId: Long, @Query semester: String, @Query maxWeek: Integer |
| POST | `/api/files/import` | @Part file: MultipartFile, @Query type: String |
| POST | `/api/files/upload` | @Part file: MultipartFile, @Query bizType: String |
| POST | `/api/members/import` | @Query sessionId: Long, @Part file: MultipartFile |
| POST | `/api/signins/groups/upload` | @Query activityId: Long, @Part file: MultipartFile, @Query groupId: Long |
| POST | `/api/signins/roster/upload` | @Query activityId: Long, @Part file: MultipartFile |

## 3. WebSocket（STOMP）与长连接

| 项 | 值 |
| --- | --- |
| 握手端点 | `/ws`（SockJS，`setAllowedOriginPatterns("*")`），独立 Security 过滤链 `@Order(0)` 放行 |
| STOMP 鉴权 | `CONNECT` 携带 `Authorization: Bearer <token>`。建连、订阅、发送和出站消息会校验会话有效期、吊销水位及用户和组织状态 |
| 客户端目的地 | 订阅限 `/user/queue/**`，发送限 `/app/**` |
| Broker 前缀 | `/queue`、`/topic`（`enableSimpleBroker`，**内存 broker**） |
| 用户前缀 | `/user`（`setUserDestinationPrefix`） |

| 客户端订阅目的地 | 推送方 | 载荷 / 用途 |
| --- | --- | --- |
| `/user/queue/agent/stream` | `AgentEventPublisher.DEST_STREAM` | `AgentStreamEvent`：Concerto Agent 流式过程事件（MESSAGE_DELTA / TOOL_* / RUN_DONE 等） |
| `/user/queue/agent` | `AgentEventPublisher.DEST_TASK` | Concerto Agent 持久化任务 / 工作项状态变更 |
| `/user/queue/authorization` | `AuthorizationChangeListener` | 角色权限变更后强制刷新客户端权限缓存 |


通知中心使用 `GET /api/notifications/stream`，请求携带 `Authorization` 头。STOMP 的内存 broker 在当前 JVM 内管理订阅，数据库运行租约与消息跨实例投递是独立机制。

## 4. 接口明细（按模块）

共 351 个端点，按模块 → Controller → 路径排序。Controller 源码索引见附录 A。

### 4.1 认证与基础 · `root`（4 个）

Controller：`AuthController`、`PingController`

| 方法 | 路径 | 说明 | 入参 | 返回 | 鉴权 |
| --- | --- | --- | --- | --- | --- |
| **POST** | `/api/auth/login` | auth/login · 登录（login） **†** | @Body req: LoginRequest | `Result<LoginResponse>` | **公开** |
| **POST** | `/api/auth/logout` | 登出：服务端吊销当前 token（审计 #3：推进用户级失效水位）+ 清理文件直链兜底 Cookie | — | `Result<Void>` | **公开** |
| GET | `/api/auth/me` | 当前登录用户信息（含最新权限码），供前端启动时刷新、权限管理页改完刷新即生效 | — | `Result<Map<String, Object>>` | — |
| GET | `/api/ping` | ping · 健康检查（ping） **†** | — | `Result<String>` | **公开** |

### 4.2 用户 / 部门 / 角色 · `user`（11 个）

Controller：`DepartmentController`、`RoleController`、`UserController`

| 方法 | 路径 | 说明 | 入参 | 返回 | 鉴权 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/depts` | depts · 列表查询（list） **†** | — | `Result<List<Department>>` | — |
| GET | `/api/roles` | roles · 列表查询（list） **†** | — | `Result<?>` | — |
| GET | `/api/users` | 用户列表：由当前有效治理身份的 user:view 控制 | @Query keyword: String, @Query deptId: Long, @Query accountStatus: String, @Query roleId: Long, @Query managementSource: String, @Query pag… | `Result<PageResult<Map<String, Object>>>` | `user:view` |
| **POST** | `/api/users` | 创建用户：user:manage（V7 种子：仅 TEACHER/DIRECTOR | @Body req: UserSaveRequest | `Result<Long>` | `user:manage` |
| **POST** | `/api/users/change-password` | 当前登录用户自行修改密码（头像下拉入口） | @Body req: ChangePasswordRequest | `Result<Void>` | — |
| GET | `/api/users/options` | 用户选项（选人组件数据源，登录即可）：仅 id/姓名/部门/角色最小字段（PRD-v5 F5） | @Query deptId: Long | `Result<List<Map<String, Object>>>` | — |
| **POST** | `/api/users/status-changes` | users/status-changes · 状态查询（statusChanges） **†** | @Body request: UserStatusRequest | `Result<List<Map<String, Object>>>` | `user:manage` |
| GET | `/api/users/summary` | users/summary · 汇总统计（summary） **†** | — | `Result<Map<String, Object>>` | `user:view` |
| **DELETE** | `/api/users/{id}` | users/{id} · 删除（delete） **†** | @Path id: Long | `Result<Void>` | `user:manage` |
| **PUT** | `/api/users/{id}` | users/{id} · 更新（update） **†** | @Path id: Long, @Body req: UserSaveRequest | `Result<Void>` | `user:manage` |
| **POST** | `/api/users/{id}/reset-password` | users/{id}/reset-password · 重置（resetPassword） **†** | @Path id: Long | `Result<Void>` | `user:manage` |

### 4.3 组织与租户 · `org`（11 个）

Controller：`OrgController`

| 方法 | 路径 | 说明 | 入参 | 返回 | 鉴权 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/orgs/current` | 当前组织信息（品牌 / 术语 / 领域包） | — | `Result<Map<String, Object>>` | — |
| **PUT** | `/api/orgs/current` | 修改当前组织信息 | @Body req: OrgUpdateRequest | `Result<Void>` | `org:manage` |
| **POST** | `/api/orgs/current/invitations` | orgs/current/invitations · 新建（createInvitation） **†** | @Body body: Map<String, Object> | `Result<OrgInvitation>` | `org:invite` |
| GET | `/api/orgs/current/positions` | 岗位列表（架构 → 角色映射） | — | `Result<List<OrgPosition>>` | — |
| GET | `/api/orgs/current/structure` | 架构树 | — | `Result<OrgStructureVO>` | `org:manage` + `org:structure:manage` |
| GET | `/api/orgs/current/units` | orgs/current/units · 查询（units） **†** | — | `Result<List<OrgUnitVO>>` | — |
| **POST** | `/api/orgs/current/units` | orgs/current/units · 新建（createUnit） **†** | @Body req: UnitSaveRequest | `Result<Long>` | `org:structure:manage` |
| **DELETE** | `/api/orgs/current/units/{id}` | orgs/current/units/{id} · 删除（deleteUnit） **†** | @Path id: Long | `Result<Void>` | `org:structure:manage` |
| **PUT** | `/api/orgs/current/units/{id}` | orgs/current/units/{id} · 更新（updateUnit） **†** | @Path id: Long, @Body req: UnitSaveRequest | `Result<Void>` | `org:structure:manage` |
| GET | `/api/orgs/public` | 组织公开列表（免登录）：登录页组织选择与品牌 | — | `Result<List<Map<String, Object>>>` | **公开** |
| **POST** | `/api/orgs/register` | 注册组织（含架构树） | @Body req: OrgRegisterRequest | `Result<Map<String, Object>>` | **公开** |

### 4.4 权限与角色管理 · `permission`（29 个）

Controller：`PermissionController`、`RoleManagementController`

| 方法 | 路径 | 说明 | 入参 | 返回 | 鉴权 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/permissions` | 权限树 + 各角色映射（权限管理页） | — | `Result<Map<String, Object>>` | `role:grant` |
| **POST** | `/api/permissions/restore-default` | 恢复默认权限 | — | `Result<Void>` | `role:grant` |
| **PUT** | `/api/permissions/roles/{role}` | 保存某角色的权限配置 | @Path role: String, @Body permissionCodes: List<String> | `Result<Void>` | `role:grant` |
| GET | `/api/orgs/current/audit-log-page` | orgs/current/audit-log-page · 分页查询（auditPage） **†** | @Query page: int, @Query size: int, @Query keyword: String, @Query action: String, @Query from: String, @Query to: String | `Result<?>` | — |
| GET | `/api/orgs/current/audit-logs` | orgs/current/audit-logs · 查询（audit） **†** | — | `Result<?>` | — |
| GET | `/api/orgs/current/authorization-changes/{id}` | orgs/current/authorization-changes/{id} · 查询（change） **†** | @Path id: String | `Result<?>` | — |
| **POST** | `/api/orgs/current/authorization-changes/{id}/apply` | orgs/current/authorization-changes/{id}/apply · 应用变更（apply） **†** | @Path id: String | `Result<?>` | — |
| GET | `/api/orgs/current/management-summary` | orgs/current/management-summary · 汇总统计（managementSummary） **†** | — | `Result<?>` | — |
| GET | `/api/orgs/current/role-delegations/{userId}` | orgs/current/role-delegations/{userId} · 查询（delegation） **†** | @Path userId: long | `Result<?>` | — |
| **PUT** | `/api/orgs/current/role-delegations/{userId}` | orgs/current/role-delegations/{userId} · 更新（delegate） **†** | @Path userId: long, @Body req: Delegate | `Result<?>` | — |
| **POST** | `/api/orgs/current/role-delegations/{userId}/change-previews` | orgs/current/role-delegations/{userId}/change-previews · 变更预览（previewDelegation） **†** | @Path userId: long, @Body req: Delegate | `Result<?>` | — |
| GET | `/api/permissions/catalog` | permissions/catalog · 权限目录（catalog） **†** | — | `Result<?>` | — |
| GET | `/api/permissions/migration-reviews` | permissions/migration-reviews · 查询（migrationReviews） **†** | — | `Result<?>` | — |
| **POST** | `/api/roles` | roles · 新建（create） **†** | @Body req: Create | `Result<?>` | — |
| GET | `/api/roles/assignable` | roles/assignable · 分配（assignable） **†** | — | `Result<?>` | — |
| GET | `/api/roles/page` | roles/page · 分页查询（page） **†** | @Query page: int, @Query size: int, @Query keyword: String, @Query status: String, @Query drafts: boolean | `Result<?>` | — |
| GET | `/api/roles/templates` | roles/templates · 查询（templates） **†** | — | `Result<?>` | — |
| **DELETE** | `/api/roles/{id}` | roles/{id} · 删除（delete） **†** | @Path id: long, @Query version: long, @Query draftVersion: long | `Result<?>` | — |
| GET | `/api/roles/{id}` | roles/{id} · 详情查询（detail） **†** | @Path id: long | `Result<?>` | — |
| **PUT** | `/api/roles/{id}` | roles/{id} · 当前登录信息（metadata） **†** | @Path id: long, @Body req: Metadata | `Result<?>` | — |
| **POST** | `/api/roles/{id}/change-previews` | roles/{id}/change-previews · 变更预览（preview） **†** | @Path id: long, @Body req: Action | `Result<?>` | — |
| **DELETE** | `/api/roles/{id}/draft` | roles/{id}/draft · 删除（discard） **†** | @Path id: long, @Query version: long, @Query draftVersion: long | `Result<?>` | — |
| **PUT** | `/api/roles/{id}/draft` | roles/{id}/draft · 更新（draft） **†** | @Path id: long, @Body req: Draft | `Result<?>` | — |
| GET | `/api/roles/{id}/history` | roles/{id}/history · 历史查询（history） **†** | @Path id: long, @Query page: int, @Query size: int | `Result<?>` | — |
| GET | `/api/roles/{id}/members` | roles/{id}/members · 当前登录信息（members） **†** | @Path id: long, @Query page: int, @Query size: int | `Result<?>` | — |
| GET | `/api/roles/{id}/references` | roles/{id}/references · 查询（references） **†** | @Path id: long, @Query page: int, @Query size: int | `Result<?>` | — |
| **POST** | `/api/users/role-change-previews` | users/role-change-previews · 分配（assignment） **†** | @Body req: Assign | `Result<?>` | — |
| GET | `/api/users/{id}/effective-permissions` | users/{id}/effective-permissions · 查询（effective） **†** | @Path id: long | `Result<?>` | — |
| GET | `/api/users/{userId}/permission-explanation` | users/{userId}/permission-explanation · 权限解释（explain） **†** | @Path userId: long, @Query operation: String, @Query resourceId: long | `Result<?>` | — |

### 4.5 成员与账号 · `member`（24 个）

Controller：`AccountBatchController`、`AccountSetupController`、`MemberController`、`MemberSessionController`

| 方法 | 路径 | 说明 | 入参 | 返回 | 鉴权 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/members/account-batches` | members/account-batches · 查询（recent） **†** | — | `Result<List<AccountBatchVO.Result>>` | `member:import_account` + `user:manage` + `role:assign` |
| **POST** | `/api/members/account-batches/previews` | members/account-batches/previews · 变更预览（preview） **†** | @Body request: AccountImportRequest | `Result<AccountBatchVO.Preview>` | `member:import_account` + `user:manage` + `role:assign` |
| GET | `/api/members/account-batches/sessions` | members/account-batches/sessions · 查询（sessions） **†** | — | `Result<List<MemberSessionVO>>` | `member:import_account` + `user:manage` + `role:assign` |
| **POST** | `/api/members/account-batches/{id}/apply` | members/account-batches/{id}/apply · 应用变更（apply） **†** | @Path id: String | `Result<AccountBatchVO.Result>` | `member:import_account` + `user:manage` + `role:assign` |
| **POST** | `/api/members/account-batches/{id}/credentials` | members/account-batches/{id}/credentials · 提交（credentials） **†** | @Path id: String | `ResponseEntity<Result<List<AccountBatchVO.Credential>>>` | `member:import_account` + `user:manage` + `role:assign` |
| **POST** | `/api/auth/account-setup` | auth/account-setup · 提交（setup） **†** | @Body request: Setup | `ResponseEntity<Result<Void>>` | **公开** |
| GET | `/api/members` | members · 分页查询（page） **†** | @Query sessionId: Long, @Query deptId: Long, @Query position: String, @Query status: String, @Query keyword: String, @Query page: int, @Que… | `Result<PageResult<MemberVO>>` | `member:view` |
| **POST** | `/api/members` | members · 新建（create） **†** | @Body req: MemberRequest | `Result<Long>` | `member:manage` |
| **POST** | `/api/members/batch-delete` | members/batch-delete · 删除（batchDelete） **†** | @Body body: Map<String, List<Long>> | `Result<Void>` | `member:manage` |
| GET | `/api/members/export` | members/export · 导出（export） **†** | @Query sessionId: Long, @Query deptId: Long, @Query position: String, @Query status: String, @Query keyword: String | `ResponseEntity<Resource>` | `member:export` |
| **POST** | `/api/members/import` | members/import · 导入（importMembers） **†** | @Query sessionId: Long, @Part file: MultipartFile | `Result<MemberImportResultVO>` | `member:manage` |
| **POST** | `/api/members/import-accounts` | members/import-accounts · 导入（importAccounts） **†** | @Body req: AccountImportRequest | `Result<AccountImportResultVO>` | `member:import_account` |
| GET | `/api/members/import/template` | members/import/template · 查询（template） **†** | — | `ResponseEntity<Resource>` | `member:export` |
| GET | `/api/members/stats` | members/stats · 统计（stats） **†** | @Query sessionId: Long | `Result<MemberStatsVO>` | `member:view` |
| GET | `/api/members/unregistered` | members/unregistered · 查询（unregistered） **†** | @Query sessionId: Long | `Result<List<UnregisteredMemberVO>>` | `member:import_account` + `user:manage` + `role:assign` |
| **DELETE** | `/api/members/{id}` | members/{id} · 删除（delete） **†** | @Path id: Long | `Result<Void>` | `member:manage` |
| GET | `/api/members/{id}` | members/{id} · 详情查询（detail） **†** | @Path id: Long | `Result<MemberDetailVO>` | `member:view` |
| **PUT** | `/api/members/{id}` | members/{id} · 更新（update） **†** | @Path id: Long, @Body req: MemberRequest | `Result<Void>` | `member:manage` |
| **POST** | `/api/members/{sessionId}/archive` | members/{sessionId}/archive · 提交（archive） **†** | @Path sessionId: Long | `Result<Map<String, Integer>>` | `member:manage` |
| GET | `/api/member-sessions` | member-sessions · 列表查询（list） **†** | — | `Result<List<MemberSessionVO>>` | `member:view` |
| **POST** | `/api/member-sessions` | member-sessions · 新建（create） **†** | @Body req: MemberSessionRequest | `Result<Long>` | `member:manage` |
| **DELETE** | `/api/member-sessions/{id}` | member-sessions/{id} · 删除（delete） **†** | @Path id: Long | `Result<Void>` | `member:manage` |
| **PUT** | `/api/member-sessions/{id}` | member-sessions/{id} · 更新（update） **†** | @Path id: Long, @Body req: MemberSessionRequest | `Result<Void>` | `member:manage` |
| **POST** | `/api/member-sessions/{id}/set-current` | member-sessions/{id}/set-current · 提交（setCurrent） **†** | @Path id: Long | `Result<Void>` | `member:manage` |

### 4.6 党务管理 · `party`（23 个）

Controller：`PartyInvestigationController`、`PartyMemberController`、`PartyRegisterController`、`PartyRosterController`、`PartyTransferController`

| 方法 | 路径 | 说明 | 入参 | 返回 | 鉴权 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/party/investigations` | party/investigations · 列表查询（list） **†** | @Query memberId: Long | `Result<List<PartyInvestigation>>` | `party:letter` |
| **POST** | `/api/party/investigations` | party/investigations · 新建（create） **†** | @Body req: PartyInvestigationRequest | `Result<Long>` | `party:letter` |
| **DELETE** | `/api/party/investigations/{id}` | party/investigations/{id} · 删除（delete） **†** | @Path id: Long | `Result<Void>` | `party:letter` |
| **PUT** | `/api/party/investigations/{id}` | party/investigations/{id} · 更新（update） **†** | @Path id: Long, @Body req: PartyInvestigationRequest | `Result<Void>` | `party:letter` |
| GET | `/api/party/members` | 成员分页 | @Query keyword: String, @Query stage: String, @Query page: int, @Query size: int | `Result<PageResult<Map<String, Object>>>` | `party:view` |
| **POST** | `/api/party/members` | 写操作：party:manage（V7 种子：部长级 | @Body req: PartyMemberRequest | `Result<Long>` | `party:manage` |
| GET | `/api/party/members/stages` | 流转历史 | @Query memberId: Long | `Result<List<PartyStage>>` | `party:view` |
| **DELETE** | `/api/party/members/{id}` | party/members/{id} · 删除（delete） **†** | @Path id: Long | `Result<Void>` | `party:manage` |
| GET | `/api/party/members/{id}` | 成员详情 | @Path id: Long | `Result<Map<String, Object>>` | `party:view` |
| **PUT** | `/api/party/members/{id}` | party/members/{id} · 更新（update） **†** | @Path id: Long, @Body req: PartyMemberRequest | `Result<Void>` | `party:manage` |
| **PUT** | `/api/party/members/{id}/stage` | 追加流转阶段并更新政治面貌 | @Path id: Long, @Body req: PartyStageRequest | `Result<Void>` | `party:manage` |
| GET | `/api/party/registers` | party/registers · 列表查询（list） **†** | @Query memberId: Long | `Result<List<PartyRegister>>` | `party:entry` |
| **POST** | `/api/party/registers` | party/registers · 新建（create） **†** | @Body req: PartyRegisterRequest | `Result<Long>` | `party:entry` |
| **DELETE** | `/api/party/registers/{id}` | party/registers/{id} · 删除（delete） **†** | @Path id: Long | `Result<Void>` | `party:entry` |
| **PUT** | `/api/party/registers/{id}` | party/registers/{id} · 更新（update） **†** | @Path id: Long, @Body req: PartyRegisterRequest | `Result<Void>` | `party:entry` |
| GET | `/api/party/rosters` | party/rosters · 列表查询（list） **†** | @Query type: String, @Query issueNo: String | `Result<List<PartyRoster>>` | `party:entry` |
| **POST** | `/api/party/rosters` | party/rosters · 新建（create） **†** | @Body req: PartyRosterRequest | `Result<Long>` | `party:entry` |
| **DELETE** | `/api/party/rosters/{id}` | party/rosters/{id} · 删除（delete） **†** | @Path id: Long | `Result<Void>` | `party:entry` |
| **PUT** | `/api/party/rosters/{id}` | party/rosters/{id} · 更新（update） **†** | @Path id: Long, @Body req: PartyRosterRequest | `Result<Void>` | `party:entry` |
| GET | `/api/party/transfers` | party/transfers · 列表查询（list） **†** | @Query memberId: Long | `Result<List<PartyTransfer>>` | `party:entry` |
| **POST** | `/api/party/transfers` | party/transfers · 新建（create） **†** | @Body req: PartyTransferRequest | `Result<Long>` | `party:entry` |
| **DELETE** | `/api/party/transfers/{id}` | party/transfers/{id} · 删除（delete） **†** | @Path id: Long | `Result<Void>` | `party:entry` |
| **PUT** | `/api/party/transfers/{id}` | party/transfers/{id} · 更新（update） **†** | @Path id: Long, @Body req: PartyTransferRequest | `Result<Void>` | `party:entry` |

### 4.7 活动 / 计划 / 签到 / 评分 / 任务 · `activity`（67 个）

Controller：`ActivityController`、`ActivityDetailController`、`AgendaController`、`PlanController`、`ScoreController`、`SeatController`、`SigninController`、`SigninRosterController`、`TaskController`

| 方法 | 路径 | 说明 | 入参 | 返回 | 鉴权 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/activities` | 活动列表 | @Query keyword: String, @Query status: String, @Query type: String, @Query organizer: String, @Query dateOrder: String, @Query page: int, @… | `Result<PageResult<Map<String, Object>>>` | `activity:view` |
| **POST** | `/api/activities` | activities · 新建（create） **†** | @Body req: ActivityRequest | `Result<Long>` | `activity:create` |
| GET | `/api/activities/organizers` | activities/organizers · 查询（organizers） **†** | — | `Result<List<String>>` | `activity:view` |
| **DELETE** | `/api/activities/{id}` | 删除为高破坏性操作：按 V7 设计仅 TEACHER/DIRECTOR（部长级种子不含 activity:delete） | @Path id: Long | `Result<Void>` | `activity:delete` |
| GET | `/api/activities/{id}` | 活动详情 | @Path id: Long | `Result<Map<String, Object>>` | `activity:view` |
| **PUT** | `/api/activities/{id}` | activities/{id} · 更新（update） **†** | @Path id: Long, @Body req: ActivityRequest | `Result<Void>` | `activity:edit` |
| **PUT** | `/api/activities/{id}/status` | activities/{id}/status · 状态查询（changeStatus） **†** | @Path id: Long, @Body req: StatusChangeRequest | `Result<Void>` | `activity:transition` |
| GET | `/api/activities/{id}/detail` | 活动详情聚合：策划书/议程/座位表/评分/签到/任务 | @Path id: Long | `Result<Map<String, Object>>` | — |
| GET | `/api/agendas` | agendas · 列表查询（list） **†** | @Query activityId: Long | `Result<List<ActivityAgenda>>` | — |
| **POST** | `/api/agendas` | 议程的增删改限干部（activity:edit），登录即可查询 | @Body req: AgendaRequest | `Result<ActivityAgenda>` | `agenda:manage` |
| **DELETE** | `/api/agendas/{id}` | agendas/{id} · 删除（delete） **†** | @Path id: Long | `Result<Void>` | `agenda:manage` |
| **PUT** | `/api/agendas/{id}` | agendas/{id} · 更新（update） **†** | @Path id: Long, @Body req: AgendaRequest | `Result<Void>` | `agenda:manage` |
| GET | `/api/plans` | plans · 连通性测试（latest） **†** | @Query activityId: Long | `Result<ActivityPlan>` | — |
| **POST** | `/api/plans` | plans · 新建（create） **†** | @Body req: PlanRequest | `Result<ActivityPlan>` | `plan:edit` |
| GET | `/api/plans/reviewers` | plans/reviewers · 查询（reviewers） **†** | — | `Result<?>` | `plan:submit` |
| GET | `/api/plans/workspace` | plans/workspace · 查询（workspace） **†** | @Query activityId: Long | `Result<?>` | — |
| GET | `/api/plans/{id}` | plans/{id} · 详情查询（detail） **†** | @Path id: Long | `Result<ActivityPlan>` | — |
| **PUT** | `/api/plans/{id}` | plans/{id} · 更新（update） **†** | @Path id: Long, @Body req: PlanRequest | `Result<Void>` | `plan:edit` |
| GET | `/api/plans/{id}/document` | plans/{id}/document · 查询（document） **†** | @Path id: Long | `ResponseEntity<Resource>` | — |
| **PUT** | `/api/plans/{id}/review` | plans/{id}/review · 更新（review） **†** | @Path id: Long, @Body req: PlanReviewRequest | `Result<Void>` | `plan:review` |
| **POST** | `/api/plans/{id}/revision` | plans/{id}/revision · 提交（revision） **†** | @Path id: Long | `Result<ActivityPlan>` | `plan:edit` |
| **PUT** | `/api/plans/{id}/submit` | plans/{id}/submit · 提交（submit） **†** | @Path id: Long, @Body req: PlanSubmitRequest | `Result<Void>` | `plan:submit` |
| GET | `/api/scores` | 评分：返回 {rules, records} | @Query activityId: Long | `Result<Map<String, Object>>` | — |
| **POST** | `/api/scores/records` | scores/records · 新建（createRecord） **†** | @Body req: ScoreRecordRequest | `Result<Long>` | `score:manage` |
| **DELETE** | `/api/scores/records/{id}` | scores/records/{id} · 删除（deleteRecord） **†** | @Path id: Long | `Result<Void>` | `score:manage` |
| **PUT** | `/api/scores/records/{id}` | scores/records/{id} · 更新（updateRecord） **†** | @Path id: Long, @Body req: ScoreRecordRequest | `Result<Void>` | `score:manage` |
| **POST** | `/api/scores/rules` | 评分规则与记录的增删改：activity:edit（V7 种子：部长级及以上） | @Body req: ScoreRuleRequest | `Result<Long>` | `score:manage` |
| **DELETE** | `/api/scores/rules/{id}` | scores/rules/{id} · 删除（deleteRule） **†** | @Path id: Long | `Result<Void>` | `score:manage` |
| **PUT** | `/api/scores/rules/{id}` | scores/rules/{id} · 更新（updateRule） **†** | @Path id: Long, @Body req: ScoreRuleRequest | `Result<Void>` | `score:manage` |
| GET | `/api/seats` | 座位表：按 zone 分组返回 {"zone": [座位...]} | @Query activityId: Long | `Result<Map<String, List<SeatMap>>>` | — |
| **POST** | `/api/seats` | seats · 新建（create） **†** | @Body req: SeatRequest | `Result<SeatMap>` | `seat:edit` |
| **DELETE** | `/api/seats/{id}` | seats/{id} · 删除（delete） **†** | @Path id: Long | `Result<Void>` | `seat:edit` |
| **PUT** | `/api/seats/{id}` | seats/{id} · 更新（update） **†** | @Path id: Long, @Body req: SeatRequest | `Result<Void>` | `seat:edit` |
| GET | `/api/signins` | 签到记录含姓名/学号等个人信息：查看需 checkin:view（V7 种子：全体角色含干事） | @Query activityId: Long, @Query keyword: String | `Result<List<Signin>>` | `checkin:view` |
| **POST** | `/api/signins` | 手动补签/登记：checkin:manage | @Body req: SigninRequest | `Result<Signin>` | `checkin:manage` |
| GET | `/api/signins/export` | signins/export · 导出（export） **†** | @Query activityId: Long, @Query keyword: String | `Result<List<Signin>>` | `checkin:export` |
| **POST** | `/api/signins/scan` | 扫码签到（公开接口，免登录） | @Body body: Map<String, Object> | `Result<Signin>` | **公开** |
| GET | `/api/signins/scan-config` | 扫码落地页配置（公开、免登录）：按 token 查活动 + 返回该活动核验字段配置 | @Query token: String | `Result<Map<String, Object>>` | **公开** |
| **POST** | `/api/signins/token` | 生成活动唯一扫码签到码：checkin:manage（刷新即作新旧码） | @Body body: Map<String, Long> | `Result<Map<String, Object>>` | `checkin:manage` |
| **POST** | `/api/signins/{activityId}/close` | 关闭签到：使该活动当前码作废（停止扫码） | @Path activityId: Long | `Result<Void>` | `checkin:manage` |
| GET | `/api/signins/{activityId}/count` | signins/{activityId}/count · 查询（count） **†** | @Path activityId: Long | `Result<Long>` | `checkin:view` |
| **DELETE** | `/api/signins/{id}` | signins/{id} · 删除（delete） **†** | @Path id: Long | `Result<Void>` | `checkin:manage` |
| **POST** | `/api/signins/backfill` | 手动补签，body {activityId, rosterIds:[]}，operatorId 由当前登录用户取 | @Body body: Map<String, Object> | `Result<Integer>` | `checkin:manage` |
| GET | `/api/signins/fields` | 核验字段配置列表 | @Query activityId: Long | `Result<List<SigninFieldConfig>>` | `checkin:view` |
| **PUT** | `/api/signins/fields` | 保存核验字段配置，body {activityId, fields:[{fieldName,fieldKey,required,fieldType,sortOrder}]} | @Query activityId: Long, @Body fields: List<SigninFieldConfigRequest> | `Result<Void>` | `checkin:manage` |
| GET | `/api/signins/groups` | 分组列表，keyword 跨分组按姓名/学号过滤人员 | @Query activityId: Long, @Query keyword: String | `Result<List<SignInGroupVO>>` | `checkin:view` |
| **DELETE** | `/api/signins/groups/batch` | 批量删除分组，body {ids:[]} | @Body body: Map<String, Object> | `Result<Integer>` | `checkin:manage` |
| **DELETE** | `/api/signins/groups/persons/batch` | 批量删除名单行，body {ids:[]} | @Body body: Map<String, Object> | `Result<Integer>` | `checkin:manage` |
| **DELETE** | `/api/signins/groups/persons/{rosterId}` | 删除单个名单行（人员） | @Path rosterId: Long | `Result<Void>` | `checkin:manage` |
| **PUT** | `/api/signins/groups/sort` | 分组排序，body {ids:[]} | @Body body: Map<String, Object> | `Result<Void>` | `checkin:manage` |
| GET | `/api/signins/groups/summary` | 分组汇总：total/signed/unsigned/groupCount | @Query activityId: Long | `Result<SignInGroupSummaryVO>` | `checkin:view` |
| **POST** | `/api/signins/groups/upload` | 上传名单（新建分组或并入已有分组）：multipart activityId + file + 可选 groupId | @Query activityId: Long, @Part file: MultipartFile, @Query groupId: Long | `Result<GroupUploadResultVO>` | `checkin:manage` |
| **DELETE** | `/api/signins/groups/{id}` | 删除分组及其名单行 | @Path id: Long | `Result<Void>` | `checkin:manage` |
| **PUT** | `/api/signins/groups/{id}/rename` | 分组重命名 | @Path id: Long, @Body body: Map<String, String> | `Result<Void>` | `checkin:manage` |
| GET | `/api/signins/roster` | 应签名单列表，status=ALL/SIGNED/UNSIGNED，每行含 signed 状态 | @Query activityId: Long, @Query status: String | `Result<List<SigninRosterVO>>` | `checkin:view` |
| GET | `/api/signins/roster/headers` | 获取名单表头字段列表（从已上传的名单中提取） | @Query activityId: Long | `Result<List<String>>` | `checkin:view` |
| GET | `/api/signins/roster/summary` | 应签汇总：expected/signed/unsigned | @Query activityId: Long | `Result<SigninSummaryVO>` | `checkin:view` |
| **POST** | `/api/signins/roster/upload` | Excel 上传应签名单：multipart，activityId + file | @Query activityId: Long, @Part file: MultipartFile | `Result<Map<String, Integer>>` | `checkin:manage` |
| **DELETE** | `/api/signins/roster/{id}` | 删除名单行 | @Path id: Long | `Result<Void>` | `checkin:manage` |
| GET | `/api/tasks` | tasks · 列表查询（list） **†** | @Query activityId: Long | `Result<List<Task>>` | — |
| **POST** | `/api/tasks` | tasks · 新建（create） **†** | @Body req: TaskRequest | `Result<Task>` | `task:manage` |
| **POST** | `/api/tasks/plan` | tasks/plan · 任务规划（savePlan） **†** | @Body request: TaskPlan.Request | `Result<TaskPlan.Result>` | `task:manage` |
| **POST** | `/api/tasks/plan/preview` | tasks/plan/preview · 变更预览（preview） **†** | @Body request: TaskPlan.Request | `Result<TaskPlan.Preview>` | `task:manage` |
| **POST** | `/api/tasks/plan/undo` | tasks/plan/undo · 提交（undo） **†** | @Body request: TaskPlan.Undo | `Result<TaskPlan.Result>` | `task:manage` |
| **DELETE** | `/api/tasks/{id}` | tasks/{id} · 删除（delete） **†** | @Path id: Long, @Query expectedRevision: Long | `Result<TaskPlan.Result>` | `task:manage` |
| **PUT** | `/api/tasks/{id}` | tasks/{id} · 更新（update） **†** | @Path id: Long, @Body req: TaskRequest | `Result<Void>` | `task:manage` |
| **PUT** | `/api/tasks/{id}/progress` | tasks/{id}/progress · 更新（updateProgress） **†** | @Path id: Long, @Body req: ProgressRequest | `Result<Task>` | `hasAnyAuthority('task:progress', 'task:manage')` |

### 4.8 座位布局 · `seat`（8 个）

Controller：`SeatLayoutController`

| 方法 | 路径 | 说明 | 入参 | 返回 | 鉴权 |
| --- | --- | --- | --- | --- | --- |
| **POST** | `/api/seat-layouts` | seat-layouts · 新建（create） **†** | @Body req: SeatLayoutRequest | `Result<Long>` | `seat:edit` |
| GET | `/api/seat-layouts/activity` | seat-layouts/activity · 详情查询（getByActivity） **†** | @Query activityId: Long | `Result<Map<String, Object>>` | — |
| GET | `/api/seat-layouts/activity/all` | seat-layouts/activity/all · 列表查询（listByActivity） **†** | @Query activityId: Long | `Result<List<Map<String, Object>>>` | — |
| **POST** | `/api/seat-layouts/from-template` | 从模板新建活动布局 | @Query templateId: Long, @Query activityId: Long, @Query name: String | `Result<Long>` | `seat:template` |
| GET | `/api/seat-layouts/templates` | seat-layouts/templates · 列表查询（listTemplates） **†** | — | `Result<List<Map<String, Object>>>` | — |
| **DELETE** | `/api/seat-layouts/templates/{id}` | 删除模板 | @Path id: Long | `Result<Void>` | `seat:template` |
| **PUT** | `/api/seat-layouts/{id}` | seat-layouts/{id} · 更新（update） **†** | @Path id: Long, @Body req: SeatLayoutRequest | `Result<Void>` | `seat:edit` |
| **POST** | `/api/seat-layouts/{id}/save-as-template` | 保存当前布局为模板 | @Path id: Long, @Query category: String | `Result<Long>` | `seat:template` |

### 4.9 日常事务（考勤 / 日程 / 活动排期） · `routine`（27 个）

Controller：`ActivityScheduleController`、`AttendanceController`、`ScheduleController`

| 方法 | 路径 | 说明 | 入参 | 返回 | 鉴权 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/activity-schedules` | activity-schedules · 列表查询（list） **†** | @Query activityId: Long, @Query status: String, @Query date: LocalDate | `Result<List<Schedule>>` | — |
| **POST** | `/api/activity-schedules` | activity-schedules · 新建（createManual） **†** | @Body req: ActivityScheduleSaveRequest | `Result<Long>` | `schedule:manage` |
| **POST** | `/api/activity-schedules/generate` | 一键生成：需 schedule:manage 或 schedule:generate | @Body req: ActivityScheduleGenerateRequest | `Result<List<Map<String, Object>>>` | `schedule:generate` |
| **POST** | `/api/activity-schedules/publish-all` | 一键发布：发布当前范围内全部草稿排班（可按活动过滤） | @Query activityId: Long | `Result<Integer>` | `schedule:publish` |
| GET | `/api/activity-schedules/semesters` | 已上传无课表的学期列表（供生成弹窗下拉选择） | — | `Result<List<String>>` | — |
| **DELETE** | `/api/activity-schedules/{id}` | activity-schedules/{id} · 删除（delete） **†** | @Path id: Long | `Result<Void>` | `schedule:manage` |
| **PUT** | `/api/activity-schedules/{id}` | activity-schedules/{id} · 更新（updateManual） **†** | @Path id: Long, @Body req: ActivityScheduleSaveRequest | `Result<Void>` | `schedule:manage` |
| **POST** | `/api/activity-schedules/{id}/cancel` | activity-schedules/{id}/cancel · 取消（cancel） **†** | @Path id: Long | `Result<Void>` | `schedule:publish` |
| **POST** | `/api/activity-schedules/{id}/publish` | activity-schedules/{id}/publish · 发布（publish） **†** | @Path id: Long | `Result<Void>` | `schedule:publish` |
| **POST** | `/api/activity-schedules/{id}/re-generate` | activity-schedules/{id}/re-generate · 提交（regenerate） **†** | @Path id: Long, @Query deptId: Long, @Query semester: String, @Query semesterStartDate: LocalDate | `Result<Integer>` | `schedule:generate` |
| GET | `/api/attendances` | 考勤中心列表（含活动/场次展开） | @Query activityId: Long, @Query date: LocalDate, @Query dateFrom: LocalDate, @Query dateTo: LocalDate, @Query type: String, @Query keyword:… | `Result<List<AttendanceVO>>` | `attendance:view` |
| **POST** | `/api/attendances` | 考勤登记（兼容既有值班打卡场景）：schedule:check | @Body req: AttendanceRequest | `Result<Attendance>` | `schedule:check` |
| GET | `/api/attendances/my` | 我的考勤 | — | `Result<List<AttendanceVO>>` | `attendance:self:view` |
| GET | `/api/attendances/stats` | 考勤统计卡：todayActivities/shouldAttend/signed/normal/late/absent/leave/makeup/pending | @Query activityId: Long, @Query date: LocalDate, @Query dateFrom: LocalDate, @Query dateTo: LocalDate, @Query type: String, @Query keyword:… | `Result<Map<String, Object>>` | `attendance:view` |
| GET | `/api/attendances/summary` | 按人汇总：应到/实到/请假/缺勤（兼容保留，改走新考勤列表） | @Query weekNo: Integer, @Query type: String | `Result<List<Map<String, Object>>>` | `attendance:view` |
| **DELETE** | `/api/attendances/{id}` | attendances/{id} · 删除（delete） **†** | @Path id: Long | `Result<Void>` | `attendance:manage` |
| GET | `/api/attendances/{id}/audits` | 考勤修改审计记录 | @Path id: Long | `Result<List<AttendanceAudit>>` | `attendance:view` |
| **POST** | `/api/attendances/{id}/makeup` | 人工补签（置 MAKEUP） | @Path id: Long, @Query remark: String | `Result<Attendance>` | `attendance:manage` |
| **PUT** | `/api/attendances/{id}/status` | 人工修改考勤状态（写审计，置 MANUAL） | @Path id: Long, @Body req: AttendanceStatusRequest | `Result<Attendance>` | `attendance:manage` |
| GET | `/api/schedules` | schedules · 列表查询（list） **†** | @Query type: String, @Query weekNo: Integer, @Query weekday: Integer, @Query activityId: Long | `Result<List<Schedule>>` | — |
| **POST** | `/api/schedules` | 值班表的增删改与导出需 schedule:manage（V7 种子：部长级 | @Body req: ScheduleRequest | `Result<Long>` | `schedule:manage` |
| GET | `/api/schedules/export` | 导出值班表 xlsx（POI 生成，Content-Disposition 下载） | @Query type: String, @Query weekNo: Integer, @Query weekday: Integer, @Query activityId: Long | `ResponseEntity<byte[]>` | `schedule:export` |
| GET | `/api/schedules/my` | 我的排班：当前用户被排到的班次 | — | `Result<List<Schedule>>` | `schedule:self:view` |
| **DELETE** | `/api/schedules/{id}` | schedules/{id} · 删除（delete） **†** | @Path id: Long | `Result<Void>` | `schedule:manage` |
| **PUT** | `/api/schedules/{id}` | schedules/{id} · 更新（update） **†** | @Path id: Long, @Body req: ScheduleRequest | `Result<Void>` | `schedule:manage` |
| **POST** | `/api/schedules/{id}/cancel` | schedules/{id}/cancel · 取消（cancel） **†** | @Path id: Long | `Result<Void>` | `schedule:publish` |
| **POST** | `/api/schedules/{id}/publish` | schedules/{id}/publish · 发布（publish） **†** | @Path id: Long | `Result<Void>` | `schedule:publish` |

### 4.10 课表 · `schedule`（9 个）

Controller：`CourseScheduleController`

| 方法 | 路径 | 说明 | 入参 | 返回 | 鉴权 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/course-schedules/analyze` | 生成共同空闲热力图：?semester=xxx&userIds=1,2,3（不传则全员） | @Query semester: String, @Query userIds: List<Long> | `Result<Map<String, Object>>` | `schedule:free_table` |
| GET | `/api/course-schedules/configs` | ===== 时间格配置 ===== | — | `Result<List<ScheduleConfig>>` | — |
| **PUT** | `/api/course-schedules/configs` | course-schedules/configs · 保存（saveConfigs） **†** | @Body configs: List<Map<String, Object>> | `Result<Void>` | `course:manage` |
| GET | `/api/course-schedules/generated` | 读取某部门+学期最新生成的无课表（无记录 data=null） | @Query deptId: Long, @Query semester: String | `Result<NoClassScheduleGeneratedVO>` | `schedule:free_table` |
| GET | `/api/course-schedules/generated/latest` | 最近一次导入无课表的部门+学期（供前端默认选中 | — | `Result<Map<String, Object>>` | `schedule:free_table` |
| **POST** | `/api/course-schedules/import` | 批量上传班级课表 xlsx，生成无课表（含下载） | @Part files: MultipartFile[], @Query deptId: Long, @Query semester: String, @Query maxWeek: Integer | `Result<NoClassScheduleImportVO>` | `course:manage` |
| GET | `/api/course-schedules/import/download` | 下载生成的 xlsx 无课表 | @Query path: String | `ResponseEntity<Resource>` | `schedule:free_table` |
| GET | `/api/course-schedules/mine` | 查看自己的课程表 | @Query semester: String | `Result<Map<String, Object>>` | `course:self:manage` |
| **POST** | `/api/course-schedules/mine` | 保存自己的课程表（覆盖式）：body {semester, cells:[{dayOfWeek, period, courseName}]} | @Body body: Map<String, Object> | `Result<Void>` | `course:self:manage` |

### 4.11 档案 / 材料 / 文件 / 公告 / 学分 / 模板 · `archive`（28 个）

Controller：`AnnouncementController`、`CreditController`、`FileController`、`LegacyFileAccessController`、`MaterialController`、`TemplateController`

| 方法 | 路径 | 说明 | 入参 | 返回 | 鉴权 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/announcements` | announcements · 列表查询（list） **†** | — | `Result<List<Announcement>>` | — |
| **POST** | `/api/announcements` | 发布/修改公告：notice:publish（V7 种子：部长级及以上） | @Body req: AnnouncementRequest | `Result<Announcement>` | `notice:publish` |
| GET | `/api/announcements/read-ids` | 当前用户已读公告 id 列表 | — | `Result<List<Long>>` | — |
| **DELETE** | `/api/announcements/{id}` | 删除公告：notice:manage（V7 种子：仅 TEACHER/DIRECTOR） | @Path id: Long | `Result<Void>` | `notice:manage` |
| **PUT** | `/api/announcements/{id}` | announcements/{id} · 更新（update） **†** | @Path id: Long, @Body req: AnnouncementRequest | `Result<Void>` | `notice:publish` |
| **POST** | `/api/announcements/{id}/confirm` | announcements/{id}/confirm · 确认（confirm） **†** | @Path id: long, @Body request: Confirmation | `Result<Void>` | — |
| GET | `/api/announcements/{id}/confirmation` | announcements/{id}/confirmation · 确认（confirmation） **†** | @Path id: long | `Result<List<Map<String,Object>>>` | — |
| **POST** | `/api/announcements/{id}/read` | 标记公告已读（幂等） | @Path id: Long | `Result<Void>` | — |
| GET | `/api/credits` | credits · 分页查询（page） **†** | @Query keyword: String, @Query userId: Long, @Query activityId: Long, @Query page: int, @Query size: int | `Result<PageResult<Map<String, Object>>>` | — |
| **POST** | `/api/credits` | 手动加分（含修改），需 quality:add 权限码 | @Body req: CreditRequest | `Result<CreditRecord>` | `quality:add` |
| **POST** | `/api/credits/activity-batch` | 活动批量加分，body {sourceActivityId, project, credit, remark, people:[{personName,studentNo}]} | @Body body: Map<String, Object> | `Result<Map<String, Integer>>` | `quality:add` |
| **DELETE** | `/api/credits/batch/{batchId}` | 按批次撤回活动批量加分 | @Path batchId: String | `Result<Integer>` | `quality:delete` |
| **DELETE** | `/api/credits/{id}` | credits/{id} · 删除（delete） **†** | @Path id: Long | `Result<Void>` | `quality:delete` |
| **PUT** | `/api/credits/{id}` | credits/{id} · 更新（update） **†** | @Path id: Long, @Body req: CreditRequest | `Result<Void>` | `quality:add` |
| **POST** | `/api/files/import` | 导入名单（xlsx），按 rosterType 逐行写入 party_roster，并按 rosterType+name+studentNo 去重 | @Part file: MultipartFile, @Query type: String | `Result<Map<String, Integer>>` | `party:entry` |
| **POST** | `/api/files/upload` | files/upload · 上传（upload） **†** | @Part file: MultipartFile, @Query bizType: String | `Result<FileRecord>` | — |
| GET | `/api/files/{id}/download` | 下载需材料或党务任一查看权限（名单类文件可能被两侧引用），上传保持登录即可 | @Path id: Long | `ResponseEntity<Resource>` | `material:view` + `party:view` |
| GET | `/uploads/{*path}` | uploads/{*path} · 下载（download） **†** | @Path path: String | `ResponseEntity<Void>` | — |
| GET | `/api/materials` | materials · 分页查询（page） **†** | @Query keyword: String, @Query bizType: String, @Query activityId: Long, @Query deptId: Long, @Query page: int, @Query size: int | `Result<PageResult<Map<String, Object>>>` | — |
| **POST** | `/api/materials` | 材料上传需 material:upload（V7 种子：干事亦可上传材料） | @Body req: MaterialRequest | `Result<Material>` | `material:upload` |
| GET | `/api/materials/tree` | materials/tree · 查询（tree） **†** | @Query activityId: Long | `Result<List<Map<String, Object>>>` | — |
| **DELETE** | `/api/materials/{id}` | 删除材料为高破坏性操作：material:delete（V7 种子：仅 TEACHER/DIRECTOR） | @Path id: Long | `Result<Void>` | `material:delete` |
| GET | `/api/materials/{id}` | materials/{id} · 详情查询（detail） **†** | @Path id: Long | `Result<Map<String,Object>>` | — |
| **PUT** | `/api/materials/{id}` | 材料元数据改动：material:upload（与上传同权） | @Path id: Long, @Body req: MaterialRequest | `Result<Void>` | `material:upload` |
| GET | `/api/templates` | templates · 列表查询（list） **†** | @Query category: String | `Result<List<Map<String, Object>>>` | — |
| **POST** | `/api/templates` | 模板资产的增删改：template:manage（V7 种子：仅 TEACHER/DIRECTOR | @Body req: TemplateRequest | `Result<TemplateAsset>` | `template:manage` |
| **DELETE** | `/api/templates/{id}` | templates/{id} · 删除（delete） **†** | @Path id: Long | `Result<Void>` | `template:manage` |
| **PUT** | `/api/templates/{id}` | templates/{id} · 更新（update） **†** | @Path id: Long, @Body req: TemplateRequest | `Result<Void>` | `template:manage` |

### 4.12 内容（文章 / 新闻） · `content`（14 个）

Controller：`ArticleController`、`NewsController`

| 方法 | 路径 | 说明 | 入参 | 返回 | 鉴权 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/articles` | articles · 分页查询（page） **†** | @Query status: String, @Query type: String, @Query keyword: String, @Query activityId: Long, @Query page: int, @Query size: int | `Result<PageResult<Map<String, Object>>>` | — |
| **POST** | `/api/articles` | articles · 新建（create） **†** | @Body req: ArticleRequest | `Result<Article>` | `article:create` |
| **DELETE** | `/api/articles/{id}` | articles/{id} · 删除（delete） **†** | @Path id: Long | `Result<Void>` | `article:delete` |
| GET | `/api/articles/{id}` | articles/{id} · 详情查询（detail） **†** | @Path id: Long | `Result<Map<String,Object>>` | — |
| **PUT** | `/api/articles/{id}` | articles/{id} · 更新（update） **†** | @Path id: Long, @Body req: ArticleRequest | `Result<Void>` | `article:edit` |
| **PUT** | `/api/articles/{id}/publish` | articles/{id}/publish · 发布（publish） **†** | @Path id: Long, @Body req: PublishRequest | `Result<Void>` | `article:publish` |
| **PUT** | `/api/articles/{id}/review` | articles/{id}/review · 更新（review） **†** | @Path id: Long, @Body req: ReviewRequest | `Result<Void>` | `article:review` |
| **PUT** | `/api/articles/{id}/stats` | articles/{id}/stats · 更新（updateStats） **†** | @Path id: Long, @Body req: StatsRequest | `Result<Void>` | `article:stats` |
| **PUT** | `/api/articles/{id}/submit` | articles/{id}/submit · 提交（submit） **†** | @Path id: Long | `Result<Void>` | `article:submit` |
| GET | `/api/news` | news · 分页查询（page） **†** | @Query keyword: String, @Query page: int, @Query size: int | `Result<PageResult<Map<String, Object>>>` | — |
| **POST** | `/api/news` | 新闻的增删改限干部，登录即可查询 | @Body req: NewsRequest | `Result<News>` | `news:manage` |
| **DELETE** | `/api/news/{id}` | news/{id} · 删除（delete） **†** | @Path id: Long | `Result<Void>` | `news:manage` |
| **PUT** | `/api/news/{id}` | news/{id} · 更新（update） **†** | @Path id: Long, @Body req: NewsRequest | `Result<Void>` | `news:manage` |
| **POST** | `/api/news/{id}/publish` | 发布新闻稿（DRAFT → PUBLISHED，记录发布日期） | @Path id: Long | `Result<Void>` | `news:publish` |

### 4.13 通知 / 待办 / 流式推送 · `notification`（18 个）

Controller：`NotificationController`、`NotificationOperationsController`、`NotificationStreamController`、`PendingWorkController`

| 方法 | 路径 | 说明 | 入参 | 返回 | 鉴权 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/notifications` | notifications · 列表查询（list） **†** | — | `Result<List<NotificationVO>>` | — |
| GET | `/api/notifications/page` | notifications/page · 分页查询（page） **†** | @Query type: String, @Query page: int, @Query size: int | `Result<PageResult<NotificationVO>>` | — |
| GET | `/api/notifications/preferences` | notifications/preferences · 详情查询（getPreferences） **†** | — | `Result<List<NotificationPreferenceVO>>` | — |
| **PUT** | `/api/notifications/preferences` | notifications/preferences · 保存（savePreferences） **†** | @Body prefs: List<NotificationPreferenceVO> | `Result<Void>` | — |
| **PUT** | `/api/notifications/read-all` | notifications/read-all · 标记已读（markAllAsRead） **†** | — | `Result<Void>` | — |
| GET | `/api/notifications/unread-count` | notifications/unread-count · 查询（unreadCount） **†** | — | `Result<Long>` | — |
| **PUT** | `/api/notifications/{id}/read` | notifications/{id}/read · 标记已读（markAsRead） **†** | @Path id: Long | `Result<Void>` | — |
| GET | `/api/notifications/operations` | notifications/operations · 汇总统计（summary） **†** | — | `Result<Map<String,Object>>` | — |
| GET | `/api/notifications/operations/audit` | notifications/operations/audit · 查询（audit） **†** | — | `Result<List<Map<String,Object>>>` | — |
| GET | `/api/notifications/operations/incidents` | notifications/operations/incidents · 查询（incidents） **†** | — | `Result<List<Map<String,Object>>>` | — |
| GET | `/api/notifications/operations/metrics` | notifications/operations/metrics · 当前登录信息（metrics） **†** | — | `Result<Map<String,Object>>` | — |
| GET | `/api/notifications/operations/policy` | notifications/operations/policy · 查询（policy） **†** | — | `Result<NotificationPolicyService.Policy>` | — |
| **PUT** | `/api/notifications/operations/policy` | notifications/operations/policy · 保存（savePolicy） **†** | @Body request: NotificationPolicyService.Policy | `Result<NotificationPolicyService.Policy>` | — |
| GET | `/api/notifications/operations/subscription` | notifications/operations/subscription · 查询（subscription） **†** | — | `Result<MaintenanceNoticeService.Subscription>` | — |
| **PUT** | `/api/notifications/operations/subscription` | notifications/operations/subscription · 更新（subscribe） **†** | @Body request: SubscriptionRequest | `Result<MaintenanceNoticeService.Subscription>` | — |
| **POST** | `/api/notifications/operations/{kind}/{id}/retry` | notifications/operations/{kind}/{id}/retry · 重试（retry） **†** | @Path kind: String, @Path id: long | `Result<Void>` | — |
| GET | `/api/notifications/stream` | notifications/stream · 流式订阅（stream） **†** | @Header Authorization: String | `ResponseEntity<SseEmitter>` | — |
| GET | `/api/notifications/pending` | notifications/pending · 待办查询（pending） **†** | — | `Result<PendingWorkService.Summary>` | — |

### 4.14 群组聊天 · `chat`（11 个）

Controller：`GroupChatController`

| 方法 | 路径 | 说明 | 入参 | 返回 | 鉴权 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/chat` | ===== 群聊 ===== | @Query keyword: String, @Query categoryId: Long, @Query status: String, @Query department: String | `Result<List<Map<String, Object>>>` | — |
| **POST** | `/api/chat` | chat · 新建（create） **†** | @Body req: GroupChatRequest | `Result<Long>` | `chat:manage` |
| GET | `/api/chat/categories` | ===== 分类 ===== | — | `Result<List<Map<String, Object>>>` | — |
| **POST** | `/api/chat/categories` | chat/categories · 新建（createCategory） **†** | @Body body: Map<String, String> | `Result<Long>` | `chat:category` |
| **PUT** | `/api/chat/categories/sort` | chat/categories/sort · 更新（sortCategories） **†** | @Body body: Map<String, List<Long>> | `Result<Void>` | `chat:category` |
| **DELETE** | `/api/chat/categories/{id}` | chat/categories/{id} · 删除（deleteCategory） **†** | @Path id: Long | `Result<Void>` | `chat:category` |
| **PUT** | `/api/chat/categories/{id}` | chat/categories/{id} · 更新（renameCategory） **†** | @Path id: Long, @Body body: Map<String, String> | `Result<Void>` | `chat:category` |
| **DELETE** | `/api/chat/{id}` | chat/{id} · 删除（delete） **†** | @Path id: Long | `Result<Void>` | `chat:manage` |
| GET | `/api/chat/{id}` | chat/{id} · 详情查询（get） **†** | @Path id: Long | `Result<Map<String, Object>>` | — |
| **PUT** | `/api/chat/{id}` | chat/{id} · 更新（update） **†** | @Path id: Long, @Body req: GroupChatRequest | `Result<Void>` | `chat:manage` |
| **POST** | `/api/chat/{id}/archive` | chat/{id}/archive · 提交（archive） **†** | @Path id: Long | `Result<Void>` | `chat:manage` |

### 4.15 首页仪表盘 · `dashboard`（1 个）

Controller：`DashboardController`

| 方法 | 路径 | 说明 | 入参 | 返回 | 鉴权 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/dashboard` | dashboard · 查询（dashboard） **†** | — | `Result<Map<String, Object>>` | — |

### 4.16 系统配置 · `system`（1 个）

Controller：`SystemController`

| 方法 | 路径 | 说明 | 入参 | 返回 | 鉴权 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/system/info` | system/info · 查询（info） **†** | — | `Result<SystemInfoVO>` | — |

### 4.17 知识库问答 · `knowledge`（16 个）

Controller：`KnowledgeController`

| 方法 | 路径 | 说明 | 入参 | 返回 | 鉴权 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/kb/chunks` | 分块台账（第一个 Tab）：全部分块，可按来源类型与关键词筛选 | @Query sourceType: String, @Query keyword: String, @Query page: int, @Query size: int | `Result<PageResult<Map<String, Object>>>` | `kb:read` |
| GET | `/api/kb/chunks/{sourceType}/{sourceId}` | 某个源记录的完整分块（台账行展开时用 | @Path sourceType: String, @Path sourceId: Long | `Result<List<Map<String, Object>>>` | `kb:read` |
| GET | `/api/kb/documents` | kb/documents · 查询（documents） **†** | @Query status: String, @Query keyword: String, @Query page: int, @Query size: int | `Result<PageResult<Map<String, Object>>>` | `kb:read` |
| **POST** | `/api/kb/documents` | kb/documents · 新建（createDocument） **†** | @Body req: KbDocumentRequest | `Result<Long>` | `kb:document:manage` |
| **DELETE** | `/api/kb/documents/{id}` | kb/documents/{id} · 删除（deleteDocument） **†** | @Path id: Long | `Result<Void>` | `kb:document:manage` |
| GET | `/api/kb/documents/{id}` | kb/documents/{id} · 查询（document） **†** | @Path id: Long | `Result<Map<String, Object>>` | `kb:read` |
| **PUT** | `/api/kb/documents/{id}` | kb/documents/{id} · 更新（updateDocument） **†** | @Path id: Long, @Body req: KbDocumentRequest | `Result<Void>` | `kb:document:manage` |
| GET | `/api/kb/entries` | kb/entries · 查询（entries） **†** | @Query sourceType: String, @Query keyword: String, @Query tier: String, @Query sort: String, @Query page: int, @Query size: int | `Result<PageResult<Map<String, Object>>>` | `kb:read` |
| GET | `/api/kb/health` | 检索模式健康检查：全文索引是否可用、是否已降级为 LIKE（方案 R14） | — | `Result<Map<String, Object>>` | 登录 |
| **POST** | `/api/kb/reindex` | 手动全量重建索引（运维入口 | — | `Result<Map<String, Object>>` | `kb:index:manage` |
| GET | `/api/kb/retrievals` | 检索记录（第二个 Tab）：可读检索流水 | @Query skipReason: String, @Query keyword: String, @Query page: int, @Query size: int | `Result<PageResult<Map<String, Object>>>` | `kb:read` |
| GET | `/api/kb/snapshot-specs` | 快照模板清单（D12 的 B 形态） | — | `Result<List<Map<String, Object>>>` | `kb:read` |
| GET | `/api/kb/snapshots` | 已生成的事实快照（带数据截止时间 | @Query specCode: String, @Query keyword: String, @Query page: int, @Query size: int | `Result<PageResult<Map<String, Object>>>` | `kb:read` |
| **POST** | `/api/kb/snapshots/refresh` | 手工重算全部快照（kb:document:manage） | — | `Result<Map<String, Object>>` | `kb:index:manage` |
| GET | `/api/kb/sources` | 源清单：每个源收了什么、什么分级、权重、当前覆盖 | — | `Result<List<Map<String, Object>>>` | `kb:read` |
| GET | `/api/kb/stats` | 覆盖统计 + 检索健康度（含"知识库更新于…"，取最近一次**成功**索引时间） | — | `Result<Map<String, Object>>` | `kb:read` |

### 4.18 Concerto Agent · `agent`（49 个）

Controller：`AgentArtifactController`、`AgentBenchController`、`AgentController`、`AgentDiagnosticsController`、`AgentMemoryController`、`AgentModelStatusController`、`AgentProviderController`、`AgentRuntimeController`、`AgentTeamController`

| 方法 | 路径 | 说明 | 入参 | 返回 | 鉴权 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/agent/artifacts` | agent/artifacts · 列表查询（list） **†** | @Query conversationId: Long | `Result<List<Map<String,Object>>>` | — |
| GET | `/api/agent/artifacts/{id}` | agent/artifacts/{id} · 详情查询（detail） **†** | @Path id: Long, @Query version: Integer | `Result<Map<String,Object>>` | — |
| **POST** | `/api/agent/artifacts/{id}/versions` | agent/artifacts/{id}/versions · 修改（edit） **†** | @Path id: Long, @Body request: EditRequest | `Result<Map<String,Object>>` | — |
| **POST** | `/api/agent/bench/planning/compare` | agent/bench/planning/compare · 提交（comparePlanning） **†** | @Body req: PlanningRequest | `Result<Map<String,Object>>` | `org:audit:view` |
| GET | `/api/agent/bench/results` | 最近实验结果 | — | `Result<List<AgentBenchmark>>` | `org:audit:view` |
| **POST** | `/api/agent/bench/run` | 运行一次批量对比实验（单 Agent vs 多 Agent） | @Body req: BenchRequest | `Result<Map<String, Object>>` | `org:audit:view` |
| GET | `/api/agent/bench/tasks` | 内置任务集（前端展示用） | — | `Result<List<Map<String, String>>>` | `org:audit:view` |
| **POST** | `/api/agent/chat` | 提问（同步返回最终答复 | @Body body: Map<String, Object> | `Result<Map<String, Object>>` | 登录 |
| **POST** | `/api/agent/chat/{streamId}/abort` | 中止一次进行中的回答（编排器在下一个阶段边界退出） | @Path streamId: String | `Result<Map<String, Object>>` | 登录 |
| GET | `/api/agent/conversations` | 会话列表 | @Query page: int, @Query size: int | `Result<Map<String, Object>>` | 登录 |
| **DELETE** | `/api/agent/conversations/{id}` | agent/conversations/{id} · 删除（deleteConversation） **†** | @Path id: Long | `Result<Void>` | 登录 |
| GET | `/api/agent/conversations/{id}/messages` | 历史消息 | @Path id: Long, @Query page: int, @Query size: int | `Result<PageResult<Map<String, Object>>>` | 登录 |
| GET | `/api/agent/streams/{streamId}` | 补拉过程事件（断连补齐 / 历史回看） | @Path streamId: String, @Query since: int | `Result<Map<String, Object>>` | 登录 |
| GET | `/api/agent/tasks/{id}` | agent/tasks/{id} · 详情查询（taskDetail） **†** | @Path id: Long | `Result<Map<String, Object>>` | 登录 |
| **POST** | `/api/agent/tasks/{id}/cancel` | agent/tasks/{id}/cancel · 提问（cancelTask） **†** | @Path id: Long | `Result<Void>` | 登录 |
| **POST** | `/api/agent/tasks/{id}/confirm` | 确认并执行写操作（D2 的核心关卡） | @Path id: Long, @Body body: Map<String, Object> | `Result<Map<String, Object>>` | 登录 |
| **POST** | `/api/agent/tasks/{id}/input` | 提交表单补全（D2.5 Form Elicitation） | @Path id: Long, @Body body: Map<String, Object> | `Result<Map<String, Object>>` | 登录 |
| GET | `/api/agent/tools` | 当前用户可见的工具清单（供前端提示卡过滤与"我能做什么"面板） | @Query mode: String | `Result<List<Map<String, Object>>>` | 登录 |
| GET | `/api/agent/diagnostics` | agent/diagnostics · 查询（effective） **†** | @Query mode: String | `Result<Map<String,Object>>` | `org:audit:view` |
| GET | `/api/agent/memories` | agent/memories · 列表查询（list） **†** | — | `Result<Map<String,Object>>` | 登录 |
| **POST** | `/api/agent/memories` | agent/memories · 保存（save） **†** | @Body body: Save | `Result<LongTermMemory.Memory>` | 登录 |
| GET | `/api/agent/memories/metrics` | agent/memories/metrics · 当前登录信息（metrics） **†** | — | `Result<Map<String,Object>>` | 登录 |
| **PUT** | `/api/agent/memories/settings` | agent/memories/settings · 设置更新（settings） **†** | @Body body: Setting | `Result<Void>` | 登录 |
| **DELETE** | `/api/agent/memories/{id}` | agent/memories/{id} · 删除（delete） **†** | @Path id: String, @Query revision: long | `Result<Void>` | 登录 |
| GET | `/api/agent/model-status` | agent/model-status · 状态查询（status） **†** | — | `Result<AgentProviderService.ModelStatus>` | 登录 |
| GET | `/api/agent/providers` | agent/providers · 列表查询（list） **†** | — | `Result<AgentProviderService.Listing>` | `org:manage` |
| **POST** | `/api/agent/providers` | agent/providers · 新建（create） **†** | @Body d: AgentProviderService.Draft | `Result<AgentProviderService.View>` | `org:manage` |
| **DELETE** | `/api/agent/providers/default` | agent/providers/default · 重置（reset） **†** | @Query defaultVersion: long | `Result<Void>` | `org:manage` |
| **POST** | `/api/agent/providers/test` | agent/providers/test · 连通性测试（test） **†** | @Body d: AgentProviderService.Draft | `Result<AgentProviderService.TestResult>` | `org:manage` |
| **DELETE** | `/api/agent/providers/{id}` | agent/providers/{id} · 删除（delete） **†** | @Path id: long, @Query version: long | `Result<Void>` | `org:manage` |
| **PUT** | `/api/agent/providers/{id}` | agent/providers/{id} · 更新（update） **†** | @Path id: long, @Body d: AgentProviderService.Draft | `Result<AgentProviderService.View>` | `org:manage` |
| **POST** | `/api/agent/providers/{id}/activate` | agent/providers/{id}/activate · 启用（activate） **†** | @Path id: long, @Body d: Activation | `Result<Void>` | `org:manage` |
| GET | `/api/agent/requests/{id}` | agent/requests/{id} · 查询（request） **†** | @Path id: String | `Result<Map<String,Object>>` | — |
| **POST** | `/api/agent/requests/{id}/abort` | agent/requests/{id}/abort · 中止（abortRequest） **†** | @Path id: String | `Result<Map<String,Object>>` | — |
| **POST** | `/api/agent/requests/{id}/instructions` | agent/requests/{id}/instructions · 提交（instruct） **†** | @Path id: String, @Body body: Map<String,String> | `Result<Map<String,Object>>` | — |
| **POST** | `/api/agent/tasks/{id}/reconcile` | agent/tasks/{id}/reconcile · 对账（reconcile） **†** | @Path id: Long, @Body body: Map<String,String> | `Result<Map<String,Object>>` | — |
| **POST** | `/api/agent/team/runs/{id}/cancel` | agent/team/runs/{id}/cancel · 执行（cancelRun） **†** | @Path id: Long | `Result<Map<String,Object>>` | — |
| GET | `/api/agent/team/runs/{id}/events` | agent/team/runs/{id}/events · 事件补拉（events） **†** | @Path id: Long, @Query since: long | `Result<Map<String,Object>>` | — |
| GET | `/api/agent/team/agents` | 已注册的 Agent 定义（供前端展示专家团队） | — | `Result<List<AgentDefinition>>` | — |
| **POST** | `/api/agent/team/plan` | 只做任务分解，不落库（预览协作计划） | @Body req: TeamRequest | `Result<Blackboard>` | — |
| **POST** | `/api/agent/team/plan/controlled` | agent/team/plan/controlled · 任务规划（controlledPlan） **†** | @Body req: TeamRequest | `Result<ControlledPlanningService.Preview>` | — |
| **POST** | `/api/agent/team/plan/controlled/prepare` | agent/team/plan/controlled/prepare · 提交（prepareControlled） **†** | @Body req: TeamRequest | `Result<ControlledPlanStore.Proposal>` | — |
| **POST** | `/api/agent/team/run` | 发起一次协作 Run（D 批次： 异步 ） | @Body req: TeamRequest | `Result<Map<String, Object>>` | — |
| **POST** | `/api/agent/team/run/controlled` | agent/team/run/controlled · 执行（runControlled） **†** | @Body req: ControlledRequest | `Result<Map<String,Object>>` | — |
| GET | `/api/agent/team/runs/{id}` | Run 详情（含步骤与黑板），按归属校验 | @Path id: Long | `Result<Map<String, Object>>` | — |
| **POST** | `/api/agent/team/runs/{id}/nodes/{nodeId}/replan` | agent/team/runs/{id}/nodes/{nodeId}/replan · 重新规划（replan） **†** | @Path id: Long, @Path nodeId: Long, @Body req: ControlledRevision | `Result<Void>` | — |
| **POST** | `/api/agent/team/runs/{id}/nodes/{nodeId}/retry` | agent/team/runs/{id}/nodes/{nodeId}/retry · 重试（retry） **†** | @Path id: Long, @Path nodeId: Long | `Result<Void>` | — |
| **POST** | `/api/agent/team/runs/{id}/nodes/{nodeId}/revise` | agent/team/runs/{id}/nodes/{nodeId}/revise · 修订（revise） **†** | @Path id: Long, @Path nodeId: Long, @Body req: Revision | `Result<Void>` | — |
| **POST** | `/api/agent/team/runs/{id}/resume` | agent/team/runs/{id}/resume · 当前登录信息（resume） **†** | @Path id: Long | `Result<Void>` | — |

## 附录 A · Controller 源码索引

| 模块 | Controller | 源码文件 | 端点数 |
| --- | --- | --- | ---: |
| `root` | `AuthController` | `…/controller/AuthController.java` | 3 |
| `root` | `PingController` | `…/controller/PingController.java` | 1 |
| `user` | `DepartmentController` | `…/module/user/DepartmentController.java` | 1 |
| `user` | `RoleController` | `…/module/user/RoleController.java` | 1 |
| `user` | `UserController` | `…/module/user/UserController.java` | 9 |
| `org` | `OrgController` | `…/module/org/controller/OrgController.java` | 11 |
| `permission` | `PermissionController` | `…/module/permission/controller/PermissionController.java` | 3 |
| `permission` | `RoleManagementController` | `…/module/permission/controller/RoleManagementController.java` | 26 |
| `member` | `AccountBatchController` | `…/module/member/controller/AccountBatchController.java` | 5 |
| `member` | `AccountSetupController` | `…/module/member/controller/AccountSetupController.java` | 1 |
| `member` | `MemberController` | `…/module/member/controller/MemberController.java` | 13 |
| `member` | `MemberSessionController` | `…/module/member/controller/MemberSessionController.java` | 5 |
| `party` | `PartyInvestigationController` | `…/module/party/controller/PartyInvestigationController.java` | 4 |
| `party` | `PartyMemberController` | `…/module/party/controller/PartyMemberController.java` | 7 |
| `party` | `PartyRegisterController` | `…/module/party/controller/PartyRegisterController.java` | 4 |
| `party` | `PartyRosterController` | `…/module/party/controller/PartyRosterController.java` | 4 |
| `party` | `PartyTransferController` | `…/module/party/controller/PartyTransferController.java` | 4 |
| `activity` | `ActivityController` | `…/module/activity/controller/ActivityController.java` | 7 |
| `activity` | `ActivityDetailController` | `…/module/activity/controller/ActivityDetailController.java` | 1 |
| `activity` | `AgendaController` | `…/module/activity/controller/AgendaController.java` | 4 |
| `activity` | `PlanController` | `…/module/activity/controller/PlanController.java` | 10 |
| `activity` | `ScoreController` | `…/module/activity/controller/ScoreController.java` | 7 |
| `activity` | `SeatController` | `…/module/activity/controller/SeatController.java` | 4 |
| `activity` | `SigninController` | `…/module/activity/controller/SigninController.java` | 9 |
| `activity` | `SigninRosterController` | `…/module/activity/controller/SigninRosterController.java` | 17 |
| `activity` | `TaskController` | `…/module/activity/controller/TaskController.java` | 8 |
| `seat` | `SeatLayoutController` | `…/module/seat/controller/SeatLayoutController.java` | 8 |
| `routine` | `ActivityScheduleController` | `…/module/routine/controller/ActivityScheduleController.java` | 10 |
| `routine` | `AttendanceController` | `…/module/routine/controller/AttendanceController.java` | 9 |
| `routine` | `ScheduleController` | `…/module/routine/controller/ScheduleController.java` | 8 |
| `schedule` | `CourseScheduleController` | `…/module/schedule/controller/CourseScheduleController.java` | 9 |
| `archive` | `AnnouncementController` | `…/module/archive/controller/AnnouncementController.java` | 8 |
| `archive` | `CreditController` | `…/module/archive/controller/CreditController.java` | 6 |
| `archive` | `FileController` | `…/module/archive/controller/FileController.java` | 3 |
| `archive` | `LegacyFileAccessController` | `…/module/archive/controller/LegacyFileAccessController.java` | 1 |
| `archive` | `MaterialController` | `…/module/archive/controller/MaterialController.java` | 6 |
| `archive` | `TemplateController` | `…/module/archive/controller/TemplateController.java` | 4 |
| `content` | `ArticleController` | `…/module/content/controller/ArticleController.java` | 9 |
| `content` | `NewsController` | `…/module/content/controller/NewsController.java` | 5 |
| `notification` | `NotificationController` | `…/module/notification/controller/NotificationController.java` | 7 |
| `notification` | `NotificationOperationsController` | `…/module/notification/controller/NotificationOperationsController.java` | 9 |
| `notification` | `NotificationStreamController` | `…/module/notification/controller/NotificationStreamController.java` | 1 |
| `notification` | `PendingWorkController` | `…/module/notification/controller/PendingWorkController.java` | 1 |
| `chat` | `GroupChatController` | `…/module/chat/controller/GroupChatController.java` | 11 |
| `dashboard` | `DashboardController` | `…/module/dashboard/DashboardController.java` | 1 |
| `system` | `SystemController` | `…/module/system/SystemController.java` | 1 |
| `knowledge` | `KnowledgeController` | `…/module/knowledge/controller/KnowledgeController.java` | 16 |
| `agent` | `AgentArtifactController` | `…/module/agent/controller/AgentArtifactController.java` | 3 |
| `agent` | `AgentBenchController` | `…/module/agent/controller/AgentBenchController.java` | 4 |
| `agent` | `AgentController` | `…/module/agent/controller/AgentController.java` | 11 |
| `agent` | `AgentDiagnosticsController` | `…/module/agent/controller/AgentDiagnosticsController.java` | 1 |
| `agent` | `AgentMemoryController` | `…/module/agent/controller/AgentMemoryController.java` | 5 |
| `agent` | `AgentModelStatusController` | `…/module/agent/provider/AgentModelStatusController.java` | 1 |
| `agent` | `AgentProviderController` | `…/module/agent/provider/AgentProviderController.java` | 7 |
| `agent` | `AgentRuntimeController` | `…/module/agent/controller/AgentRuntimeController.java` | 6 |
| `agent` | `AgentTeamController` | `…/module/agent/controller/AgentTeamController.java` | 11 |

## 附录 B · Controller 鉴权注解索引

### B.1 权限码 / 角色谓词一览

共 66 个权限码、0 个角色谓词。计数为使用到的端点数（一个端点可要求多个权限）。

| 权限码 | 端点数 |
| --- | ---: |
| `checkin:manage` | 15 |
| `party:entry` | 13 |
| `user:manage` | 11 |
| `kb:read` | 10 |
| `member:manage` | 10 |
| `org:manage` | 9 |
| `checkin:view` | 8 |
| `member:import_account` | 7 |
| `score:manage` | 6 |
| `task:manage` | 6 |
| `role:assign` | 6 |
| `schedule:manage` | 6 |
| `seat:edit` | 5 |
| `org:audit:view` | 5 |
| `schedule:publish` | 5 |
| `party:view` | 4 |
| `chat:manage` | 4 |
| `chat:category` | 4 |
| `member:view` | 4 |
| `org:structure:manage` | 4 |
| `party:letter` | 4 |
| `party:manage` | 4 |
| `attendance:view` | 4 |
| `schedule:free_table` | 4 |
| `activity:view` | 3 |
| `agenda:manage` | 3 |
| `plan:edit` | 3 |
| `quality:add` | 3 |
| `template:manage` | 3 |
| `news:manage` | 3 |
| `kb:document:manage` | 3 |
| `role:grant` | 3 |
| `attendance:manage` | 3 |
| `seat:template` | 3 |
| `plan:submit` | 2 |
| `notice:publish` | 2 |
| `quality:delete` | 2 |
| `material:upload` | 2 |
| `kb:index:manage` | 2 |
| `member:export` | 2 |
| `schedule:generate` | 2 |
| `course:manage` | 2 |
| `course:self:manage` | 2 |
| `user:view` | 2 |
| `activity:create` | 1 |
| `activity:delete` | 1 |
| `activity:edit` | 1 |
| `activity:transition` | 1 |
| `plan:review` | 1 |
| `checkin:export` | 1 |
| `notice:manage` | 1 |
| `material:view` | 1 |
| `material:delete` | 1 |
| `article:create` | 1 |
| `article:delete` | 1 |
| `article:edit` | 1 |
| `article:publish` | 1 |
| `article:review` | 1 |
| `article:stats` | 1 |
| `article:submit` | 1 |
| `news:publish` | 1 |
| `org:invite` | 1 |
| `schedule:check` | 1 |
| `attendance:self:view` | 1 |
| `schedule:export` | 1 |
| `schedule:self:view` | 1 |

### B.2 复合鉴权表达式

| 完整表达式 | 端点数 |
| --- | ---: |
| `hasAuthority('member:import_account') and hasAuthority('user:manage') and hasAuthority('role:assign')` | 6 |
| `hasAuthority('material:view') or hasAuthority('party:view')` | 1 |
| `hasAuthority('org:manage') or hasAuthority('org:structure:manage')` | 1 |

### B.3 无显式 `@PreAuthorize` 的写操作

共 **116** 个端点无方法级/类级 `@PreAuthorize`（其中 GET 77 个、写操作 39 个）。下表列出需要登录且未声明 `@PreAuthorize` 的 34 个写操作。权限码、对象归属和治理资格也可能在 Controller 方法体及 Service 中检查。

| 模块 | 方法 | 路径 |
| --- | --- | --- |
| `user` | POST | `/api/users/change-password` |
| `permission` | POST | `/api/orgs/current/authorization-changes/{id}/apply` |
| `permission` | PUT | `/api/orgs/current/role-delegations/{userId}` |
| `permission` | POST | `/api/orgs/current/role-delegations/{userId}/change-previews` |
| `permission` | POST | `/api/roles` |
| `permission` | DELETE | `/api/roles/{id}` |
| `permission` | PUT | `/api/roles/{id}` |
| `permission` | POST | `/api/roles/{id}/change-previews` |
| `permission` | DELETE | `/api/roles/{id}/draft` |
| `permission` | PUT | `/api/roles/{id}/draft` |
| `permission` | POST | `/api/users/role-change-previews` |
| `archive` | POST | `/api/announcements/{id}/confirm` |
| `archive` | POST | `/api/announcements/{id}/read` |
| `archive` | POST | `/api/files/upload` |
| `notification` | PUT | `/api/notifications/operations/policy` |
| `notification` | PUT | `/api/notifications/operations/subscription` |
| `notification` | POST | `/api/notifications/operations/{kind}/{id}/retry` |
| `notification` | PUT | `/api/notifications/preferences` |
| `notification` | PUT | `/api/notifications/read-all` |
| `notification` | PUT | `/api/notifications/{id}/read` |
| `agent` | POST | `/api/agent/artifacts/{id}/versions` |
| `agent` | POST | `/api/agent/requests/{id}/abort` |
| `agent` | POST | `/api/agent/requests/{id}/instructions` |
| `agent` | POST | `/api/agent/tasks/{id}/reconcile` |
| `agent` | POST | `/api/agent/team/plan` |
| `agent` | POST | `/api/agent/team/plan/controlled` |
| `agent` | POST | `/api/agent/team/plan/controlled/prepare` |
| `agent` | POST | `/api/agent/team/run` |
| `agent` | POST | `/api/agent/team/run/controlled` |
| `agent` | POST | `/api/agent/team/runs/{id}/cancel` |
| `agent` | POST | `/api/agent/team/runs/{id}/nodes/{nodeId}/replan` |
| `agent` | POST | `/api/agent/team/runs/{id}/nodes/{nodeId}/retry` |
| `agent` | POST | `/api/agent/team/runs/{id}/nodes/{nodeId}/revise` |
| `agent` | POST | `/api/agent/team/runs/{id}/resume` |

例如，通知运维入口在方法体内检查授权。Concerto Agent 接口同时使用 Controller 注解、任务归属校验与工具层策略。服务端校验决定实际授权。

## 附录 C · 与旧版 `docs/API.md`（2026-08-26）的差异

| 项 | 旧文档 | 当前源码 |
| --- | --- | --- |
| Controller / 接口数 | 39 / 约 191 | 56 / 351 |
| 模块 | 14 | 18（新增 `knowledge`、`org`、`permission` 拆分细化、Concerto Agent 系列 Controller 扩充等） |
| `GET /uploads/**` | 列为免登录公开 | `authenticated()`，需登录（Cookie 兜底） |
| 认证 | 仅列 login / ping / scan 等 | 增加 `logout`、`orgs/register`、`orgs/public`、`account-setup`；WS 独立过滤链 |
| 长连接 | 简述 `/ws` | 列出 STOMP 用户队列和通知 SSE 入口 |
| 错误码 | 400/401/403/404/405/500 | 增加 409；细化各异常映射 |

## 附录 D · 生成口径与局限

- 数据来源：静态扫描 `pams-backend/src/main/java` 下所有含 `@RestController` / `@Controller` 的类，逐方法解析类级 `@RequestMapping`、方法级 `@*Mapping`、`@PreAuthorize`、方法签名与注释；已与源码中 **351 处方法级映射注解**逐文件核对，无遗漏、无重复。
- 未纳入：运行时动态注册路由、`@RestControllerAdvice` 异常处理、Filter/Interceptor 内部逻辑、数据库存储过程；前端 `pams-web/src/api` 的调用封装不作为接口定义来源。
- 入参列是**摘要**，未展开 DTO 内部字段、默认值、`required` 约束；精确契约请以对应 DTO / 源码为准。
- 说明列的 Javadoc 摘录可能取自方法上方的分区注释，语义不一定等于接口功能；标 **†** 者为推断。
- 鉴权列仅反映 Controller 层注解；行级数据域（组织隔离 `@TenantId`、`OrgContext`）与 Service 层校验不在本次静态清单范围内。
