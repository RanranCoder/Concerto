# PAMS 党务管理系统 · 接口文档

> 后端版本：1.0.0（Spring Boot 4.0.2 / Java 21）
> 文档基线：2026-08-26，基于 `pams-backend` 源码逐 Controller 整理，共 14 个模块、39 个 Controller、约 191 个接口。

---

## 1. 通用约定

### 1.1 Base URL 与请求格式

| 项 | 说明 |
| --- | --- |
| Base URL | `http://<host>:8080`（开发环境前端经 Vite 代理 `/api` → 8080） |
| Content-Type | `application/json`（文件上传接口为 `multipart/form-data`） |
| 时间格式 | JSON Body：`yyyy-MM-dd HH:mm:ss`（LocalDateTime）/ `yyyy-MM-dd`（LocalDate）；查询参数同 |
| 字符编码 | UTF-8 |
| 文件上传限制 | 单文件 ≤ 100MB，单请求 ≤ 200MB |

### 1.2 认证方式（JWT Bearer）

登录成功后获取 token，后续请求携带请求头：

```
Authorization: Bearer <token>
```

- Token 有效期 24 小时（`pams.jwt.expire-hours`）。
- **免登录公开接口**（无需携带 token）：
  - `POST /api/auth/login`
  - `GET /api/ping`
  - `POST /api/signins/scan`（扫码签到提交）
  - `GET /api/signins/scan-config`（扫码落地页配置）
  - `GET /uploads/**`（上传文件静态直出）
  - `/ws`（WebSocket，握手阶段自行鉴权）
- 其余接口未认证时返回 HTTP 401：`{"code":401,"message":"未登录或登录已过期","data":null}`。
- 登录限流：同一用户名连续失败 5 次锁定 5 分钟。

### 1.3 统一响应结构 `Result<T>`

```json
{
  "code": 200,
  "message": "ok",
  "data": { }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| code | int | 200 成功；其余为错误码（见 1.5） |
| message | String | 成功固定 `"ok"`；失败为错误描述 |
| data | T | 业务数据，可为 null |

> 例外：文件下载类接口（`/api/files/{id}/download`、`/api/schedules/export`、`/api/members/export`、`/api/members/import/template`、`/api/course-schedules/import/download`）直接返回二进制流（xlsx / 文件流），不走 Result 包装。

### 1.4 分页结构 `PageResult<T>`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| records | List&lt;T&gt; | 当前页数据 |
| total | long | 总记录数 |
| current | long | 当前页码（从 1 开始） |
| size | long | 每页条数 |

分页请求通用参数：`page`（默认 1）、`size`（默认 10）。

### 1.5 错误码

**HTTP 层**（GlobalExceptionHandler 统一处理）：

| code | HTTP | 说明 |
| --- | --- | --- |
| 400 | 400 | 参数校验失败 / 请求体格式错误 / 参数类型错误 |
| 401 | 401 | 未登录或登录已过期 |
| 403 | 403 | 无权限访问（@PreAuthorize 拒绝） |
| 404 | 404 | 资源不存在 |
| 405 | 405 | 请求方法不支持 |
| 500 | 500 | 服务器内部错误 |

**业务错误码**（HTTP 状态 400，code 为业务码，常见值）：

| code | 说明 |
| --- | --- |
| 1001 | 用户名或密码错误 |
| 1002 | 账号已禁用 |
| 1003 | 用户名已存在 |
| 1004 | 用户不存在 |
| 1005 | 部门不存在 |
| 1006 | 角色不存在 |
| 1007 | 不能授予高于自己级别的角色 |
| 1008 | 不能删除/重置级别不低于自己的用户 |
| 1009 | 旧密码不正确 |
| 1010 | 新密码长度不能少于 6 位 |
| 2001 | 活动不存在 |
| 2007 | 任务进度需在 0-100 / 结束日期早于开始日期 |
| 2008 | 任务不存在 |
| 2009 | 无效的状态值 |
| 2101 | 议程不存在 |
| 2301 | 签到记录不存在 |
| 2302 | 签到码无效或已失效 |
| 2303 | 签到码已过期 |
| 2401 | 材料不存在 |
| 2402 | 模板不存在 |
| 2404 | 公告不存在 |
| 2601 | 群聊分类名称不能为空 |
| 2602 | 群聊分类不存在 |
| 2603 | 群聊不存在 |
| 2705 | 文件不存在或读取失败 |
| 4001 | 文件上传/导入相关错误（临时文件、格式不支持、空文件等） |

### 1.6 角色与权限模型

**角色**（roleCode / level，数字越大级别越高）：

| roleCode | 名称 | level | 数据范围 |
| --- | --- | --- | --- |
| TEACHER | 指导老师 | 5 | 全部 |
| DIRECTOR | 主任 | 4 | 全部 |
| ORG_LEADER / SECRETARY_LEADER / MEDIA_LEADER / TECH_LEADER | 各部部长 | 3 | 全部 |
| STAFF | 干事 | 1 | 本部门（DEPT） |

**本文档权限标注说明**：

- `登录即可`：无 @PreAuthorize，仅需有效 JWT。
- `LEADER`：`hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')`（部长及以上）。
- `hasAuthority('xxx')`：细粒度权限码（可在权限管理页按角色配置），常见权限码：`member:view`、`member:manage`、`member:export`、`member:import_account`、`quality:add`、`quality:delete`、`material:view`、`party:view`、`user:permission` 等。

---

## 2. 认证与系统

### 2.1 认证 AuthController（/api/auth）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | /api/auth/login | 登录，Body：LoginRequest；返回 Result&lt;LoginResponse&gt; | 公开 |
| GET | /api/auth/me | 当前登录用户信息（含最新权限码）；返回 Result&lt;Map&gt; | 登录即可 |

**LoginRequest**

| 字段 | 类型 | 校验 |
| --- | --- | --- |
| username | String | @NotBlank |
| password | String | @NotBlank |

**LoginResponse**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| token | String | JWT 令牌 |
| user | Object | `id`、`username`、`realName`、`roleCode`、`roleLevel`(int)、`deptId`(可 null)、`deptName`(可 null)、`permissions`(List&lt;String&gt; 权限码) |

### 2.2 健康检查 PingController（/api）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/ping | 返回 Result&lt;String&gt;，data 为 `"pong"` | 公开 |

### 2.3 系统信息 SystemController（/api/system）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/system/info | 返回 Result&lt;SystemInfoVO&gt;：`version`、`uploadDir`（脱敏目录名）、`ping` | 登录即可 |

### 2.4 部门 DepartmentController（/api/depts）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/depts | 部门列表；返回 Result&lt;List&lt;Department&gt;&gt; | 登录即可 |

### 2.5 角色 RoleController（/api/roles）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/roles | 角色列表；返回 Result&lt;List&lt;Role&gt;&gt; | 登录即可 |

---

## 3. 用户与权限管理

### 3.1 用户 UserController（/api/users）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/users | 分页查询用户。参数：`keyword`?、`deptId`?、`page`=1、`size`=10；返回 Result&lt;PageResult&lt;Map&gt;&gt;。数据范围：DEPT 角色强制只查本部门 | LEADER |
| POST | /api/users | 新建用户，Body：UserSaveRequest；返回 Result&lt;Long&gt;（新用户 id）。防提权：不能授予高于自己级别的角色 | LEADER |
| PUT | /api/users/{id} | 更新用户，Body：UserSaveRequest；返回 Result&lt;Void&gt; | LEADER |
| DELETE | /api/users/{id} | 删除用户（软删）；返回 Result&lt;Void&gt;。防提权：不能删除级别不低于自己的用户 | LEADER |
| POST | /api/users/{id}/reset-password | 重置指定用户密码；返回 Result&lt;Void&gt;。防提权：不能重置级别不低于自己的账号 | LEADER |
| POST | /api/users/change-password | 当前用户修改自己密码，Body：ChangePasswordRequest；返回 Result&lt;Void&gt; | 登录即可 |

**UserSaveRequest**

| 字段 | 类型 | 校验 |
| --- | --- | --- |
| username | String | @NotBlank |
| password | String | -（空则默认 123456） |
| realName | String | @NotBlank |
| studentNo | String | - |
| phone | String | - |
| deptId | Long | - |
| roleId | Long | @NotNull |
| status | Integer | - |

**ChangePasswordRequest**

| 字段 | 类型 | 校验 |
| --- | --- | --- |
| oldPassword | String | @NotBlank |
| newPassword | String | @NotBlank（长度 ≥ 6，业务校验 1010） |

### 3.2 权限配置 PermissionController（/api/permissions）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/permissions | 权限树 + 各角色权限映射；返回 Result&lt;Map&gt; | hasAuthority('user:permission') |
| PUT | /api/permissions/roles/{role} | 保存某角色权限。路径：`role`(角色编码)；Body：`List<String>` 权限码列表；返回 Result&lt;Void&gt; | hasAuthority('user:permission') |
| POST | /api/permissions/restore-default | 恢复全部角色默认权限；返回 Result&lt;Void&gt; | hasAuthority('user:permission') |

> 权限码为登录时快照，变更后需重新登录（或前端启动时调 `GET /api/auth/me` 刷新）。

---

## 4. 活动管理

### 4.1 活动 ActivityController（/api/activities）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/activities | 分页查询。参数：`keyword`?、`status`?、`type`?、`page`=1、`size`=10；返回 Result&lt;PageResult&lt;Map&gt;&gt; | 登录即可 |
| GET | /api/activities/{id} | 活动详情；返回 Result&lt;Map&gt; | 登录即可 |
| GET | /api/activities/{id}/detail | 活动详情聚合（策划书/议程/座位/评分/签到/任务）；返回 Result&lt;Map&gt; | 登录即可 |
| POST | /api/activities | 创建活动，Body：ActivityRequest(@Valid)；返回 Result&lt;Long&gt; | LEADER |
| PUT | /api/activities/{id} | 更新活动，Body：ActivityRequest(@Valid)；返回 Result&lt;Void&gt; | LEADER |
| PUT | /api/activities/{id}/status | 变更状态，Body：StatusChangeRequest(@Valid)；返回 Result&lt;Void&gt; | LEADER |
| DELETE | /api/activities/{id} | 删除活动；返回 Result&lt;Void&gt; | LEADER |

**ActivityRequest**

| 字段 | 类型 | 校验 |
| --- | --- | --- |
| name | String | @NotBlank、@Size(max=100) |
| theme | String | @Size(max=100) |
| type | String | @Size(max=50)，默认 "OTHER" |
| startDate / endDate | LocalDate | - |
| location | String | @Size(max=200) |
| organizer | String | @Size(max=100) |
| targetAudience | String | @Size(max=200) |
| host | String | @Size(max=100) |
| leader | String | @Size(max=100) |
| description | String | @Size(max=2000) |

**StatusChangeRequest**：`status`(String, @NotBlank，须为 ActivityStatus 枚举名，非法值 2009)

### 4.2 议程 AgendaController（/api/agendas）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/agendas | 议程列表。参数：`activityId`(必填)；返回 Result&lt;List&lt;ActivityAgenda&gt;&gt;（按 stepNo 升序） | 登录即可 |
| POST | /api/agendas | 创建议程，Body：AgendaRequest(@Valid)；返回 Result&lt;ActivityAgenda&gt; | LEADER |
| PUT | /api/agendas/{id} | 更新议程，Body：AgendaRequest(@Valid)；返回 Result&lt;Void&gt; | LEADER |
| DELETE | /api/agendas/{id} | 删除议程；返回 Result&lt;Void&gt; | LEADER |

**AgendaRequest**：`activityId`(Long, @NotNull)、`stepNo`(Integer, @NotNull)、`title`(String, @NotBlank)、`remark`(String)

### 4.3 策划书 PlanController（/api/plans）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/plans | 查活动最新策划书。参数：`activityId`(必填)；返回 Result&lt;ActivityPlan&gt; | 登录即可 |
| GET | /api/plans/{id} | 策划书详情；返回 Result&lt;ActivityPlan&gt; | 登录即可 |
| POST | /api/plans | 创建策划书，Body：PlanRequest(@Valid)；返回 Result&lt;ActivityPlan&gt; | LEADER |
| PUT | /api/plans/{id} | 更新策划书（可同步活动信息），Body：PlanRequest(@Valid)；返回 Result&lt;Void&gt; | LEADER |
| PUT | /api/plans/{id}/submit | 提交送审；返回 Result&lt;Void&gt; | LEADER |
| PUT | /api/plans/{id}/review | 审核，Body：PlanReviewRequest(@Valid)；返回 Result&lt;Void&gt; | LEADER |

**PlanRequest**（*Override 字段用于策划书内覆盖活动基本信息展示）

| 字段 | 类型 | 校验 |
| --- | --- | --- |
| activityId | Long | @NotNull |
| version | Integer | - |
| background / purpose / content / flow / notice / emergency / budget | String | -（flow/budget 为 JSON 字符串） |
| nameOverride / themeOverride / timeOverride / locationOverride / organizerOverride / targetOverride | String | - |
| sectionOrder | String | -（JSON：章节顺序+自定义节名） |
| syncActivity | boolean | -（是否同步更新活动信息） |

**PlanReviewRequest**：`approved`(Boolean, @NotNull)、`comment`(String)

### 4.4 甘特图任务 TaskController（/api/tasks）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/tasks | 任务列表。参数：`activityId`(必填)；返回 Result&lt;List&lt;Task&gt;&gt; | 登录即可 |
| POST | /api/tasks | 创建任务（创建人=当前用户），Body：TaskRequest(@Valid)；返回 Result&lt;Task&gt; | LEADER |
| PUT | /api/tasks/{id} | 更新任务，Body：TaskRequest(@Valid)；返回 Result&lt;Void&gt; | LEADER |
| PUT | /api/tasks/{id}/progress | 更新进度，Body：ProgressRequest(@Valid)；返回 Result&lt;Void&gt; | LEADER |
| DELETE | /api/tasks/{id} | 删除任务；返回 Result&lt;Void&gt; | LEADER |

**TaskRequest**：`activityId`(Long, @NotNull)、`name`(String, @NotBlank)、`deptId`?、`assignee`?、`startDate`/`endDate`(LocalDate)、`dependsOn`(Long 前置任务)、`isMilestone`(Integer)、`progress`?、`status`?、`priority`?、`description`?

**ProgressRequest**：`progress`(Integer, @NotNull、@Min(0)、@Max(100))

### 4.5 座位表 SeatController（/api/seats）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/seats | 按活动查座位表。参数：`activityId`(必填)；返回 Result&lt;Map&lt;String, List&lt;SeatMap&gt;&gt;&gt;（按 zone 分组） | 登录即可 |
| POST | /api/seats | 创建座位，Body：SeatRequest(@Valid)；返回 Result&lt;SeatMap&gt; | LEADER |
| PUT | /api/seats/{id} | 更新座位，Body：SeatRequest(@Valid)；返回 Result&lt;Void&gt; | LEADER |
| DELETE | /api/seats/{id} | 删除座位；返回 Result&lt;Void&gt; | LEADER |

**SeatRequest**：`activityId`(Long, @NotNull)、`roomName`?、`zone`(String, @NotBlank)、`rowNo`/`colNo`(Integer)、`personName`?、`seatType`?

### 4.6 评分 ScoreController（/api/scores）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/scores | 查活动评分。参数：`activityId`(必填)；返回 Result&lt;Map&gt;（`{rules, records}`） | 登录即可 |
| POST | /api/scores/rules | 创建评分维度，Body：ScoreRuleRequest(@Valid)；返回 Result&lt;Long&gt; | LEADER |
| POST | /api/scores/records | 创建评分记录，Body：ScoreRecordRequest(@Valid)；返回 Result&lt;Long&gt; | LEADER |
| PUT | /api/scores/rules/{id} | 更新评分维度，Body：ScoreRuleRequest(@Valid)；返回 Result&lt;Void&gt; | LEADER |
| PUT | /api/scores/records/{id} | 更新评分记录，Body：ScoreRecordRequest(@Valid)；返回 Result&lt;Void&gt; | LEADER |
| DELETE | /api/scores/rules/{id} | 删除评分维度；返回 Result&lt;Void&gt; | LEADER |
| DELETE | /api/scores/records/{id} | 删除评分记录；返回 Result&lt;Void&gt; | LEADER |

**ScoreRuleRequest**：`activityId`(Long, @NotNull)、`dimensionName`(String, @NotBlank)、`fullMarks`(Integer, @NotNull)、`sortOrder`?

**ScoreRecordRequest**：`activityId`(Long, @NotNull)、`teamName`(String, @NotBlank)、`groupName`?、`dimensionScores`(String，JSON 如 `{"1":28,"2":18}`)、`rankNo`?、`remark`?

---

## 5. 签到（应签名单 / 扫码 / 分组）

### 5.1 签到记录 SigninController（/api/signins）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/signins | 签到记录列表。参数：`activityId`(必填)、`keyword`?；返回 Result&lt;List&lt;Signin&gt;&gt; | LEADER |
| POST | /api/signins | 手动新增签到，Body：SigninRequest(@Valid)；返回 Result&lt;Signin&gt; | LEADER |
| POST | /api/signins/token | 生成签到令牌与二维码。Body：`{"activityId": 1}`；返回 Result&lt;Map&gt;（`token`、`activityId`、`expiresAt`、`qrContent`） | LEADER |
| POST | /api/signins/scan | 扫码签到提交。Body：`{"token": "...", "fields": {"姓名": "...", "学号": "..."}}`（兼容旧格式 `name`/`studentNo`）；返回 Result&lt;Signin&gt; | **公开** |
| GET | /api/signins/scan-config | 扫码落地页配置。参数：`token`(必填)；返回 Result&lt;Map&gt;（`activityId`、`fields` 核验字段配置） | **公开** |
| GET | /api/signins/{activityId}/count | 活动签到人数统计；返回 Result&lt;Long&gt; | 登录即可 |
| DELETE | /api/signins/{id} | 删除签到记录；返回 Result&lt;Void&gt; | LEADER |

**SigninRequest**

| 字段 | 类型 | 校验 |
| --- | --- | --- |
| activityId | Long | @NotNull |
| personId | Long | - |
| name | String | @NotBlank |
| studentNo / className / identityType / location / phone / remark | String | - |
| signType | Signin.SignType 枚举 | - |
| signTime | LocalDateTime | - |

### 5.2 应签名单与分组 SigninRosterController（/api/signins）

> 类级 @PreAuthorize(LEADER)，**本组全部接口均需 LEADER 权限**。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /api/signins/roster | 应签名单。参数：`activityId`(必填)、`status`?(ALL/SIGNED/UNSIGNED，默认 ALL)；返回 Result&lt;List&lt;SigninRosterVO&gt;&gt; |
| POST | /api/signins/roster/upload | Excel 上传应签名单。multipart：`activityId`(必填)、`file`(必填)；返回 Result&lt;Map&lt;String,Integer&gt;&gt; |
| DELETE | /api/signins/roster/{id} | 删除名单行；返回 Result&lt;Void&gt; |
| GET | /api/signins/roster/summary | 应签汇总（expected/signed/unsigned）。参数：`activityId`(必填)；返回 Result&lt;SigninSummaryVO&gt; |
| GET | /api/signins/roster/headers | 名单表头字段列表。参数：`activityId`(必填)；返回 Result&lt;List&lt;String&gt;&gt; |
| GET | /api/signins/fields | 核验字段配置列表。参数：`activityId`(必填)；返回 Result&lt;List&lt;SigninFieldConfig&gt;&gt; |
| PUT | /api/signins/fields | 保存核验字段配置。参数：`activityId`(必填)；Body：`List<SigninFieldConfigRequest>`；返回 Result&lt;Void&gt; |
| POST | /api/signins/backfill | 手动补签。Body：`{"activityId": 1, "rosterIds": [1,2]}`（均必填）；返回 Result&lt;Integer&gt;（补签条数） |
| GET | /api/signins/groups | 分组列表（keyword 跨组过滤姓名/学号）。参数：`activityId`(必填)、`keyword`?；返回 Result&lt;List&lt;SignInGroupVO&gt;&gt; |
| GET | /api/signins/groups/summary | 分组汇总（total/signed/unsigned/groupCount）；返回 Result&lt;SignInGroupSummaryVO&gt; |
| POST | /api/signins/groups/upload | 上传名单建分组。multipart：`activityId`(必填)、`file`(必填)、`groupId`?(并入已有分组)；返回 Result&lt;GroupUploadResultVO&gt; |
| PUT | /api/signins/groups/{id}/rename | 分组重命名。Body：`{"groupName": "..."}`；返回 Result&lt;Void&gt; |
| PUT | /api/signins/groups/sort | 分组排序。Body：`{"ids": [3,1,2]}`（必填非空）；返回 Result&lt;Void&gt; |
| DELETE | /api/signins/groups/{id} | 删除分组及其名单行；返回 Result&lt;Void&gt; |
| DELETE | /api/signins/groups/batch | 批量删除分组。Body：`{"ids": [...]}`；返回 Result&lt;Integer&gt; |
| DELETE | /api/signins/groups/persons/{rosterId} | 删除单个名单行；返回 Result&lt;Void&gt; |
| DELETE | /api/signins/groups/persons/batch | 批量删除名单行。Body：`{"ids": [...]}`；返回 Result&lt;Integer&gt; |

**SigninFieldConfigRequest**（List 元素）：`fieldName`?、`fieldKey`?、`required`(Boolean)?、`fieldType`?、`sortOrder`?

---

## 6. 座位布局（可视化编辑器）SeatLayoutController（/api/seat-layouts）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/seat-layouts/activity | 活动座位布局。参数：`activityId`(必填)；返回 Result&lt;Map&gt; | 登录即可 |
| GET | /api/seat-layouts/activity/all | 活动全部布局（含模板）。参数：`activityId`(必填)；返回 Result&lt;List&lt;Map&gt;&gt; | 登录即可 |
| GET | /api/seat-layouts/templates | 模板列表；返回 Result&lt;List&lt;Map&gt;&gt; | 登录即可 |
| POST | /api/seat-layouts | 新建布局，Body：SeatLayoutRequest；返回 Result&lt;Long&gt; | LEADER |
| PUT | /api/seat-layouts/{id} | 更新布局，Body：SeatLayoutRequest；返回 Result&lt;Void&gt; | LEADER |
| POST | /api/seat-layouts/{id}/save-as-template | 保存为模板。参数：`category`?；返回 Result&lt;Long&gt;（模板 id） | LEADER |
| POST | /api/seat-layouts/from-template | 从模板新建。参数：`templateId`(必填)、`activityId`(必填)、`name`?；返回 Result&lt;Long&gt; | LEADER |
| DELETE | /api/seat-layouts/templates/{id} | 删除模板；返回 Result&lt;Void&gt; | LEADER |

**SeatLayoutRequest**

| 字段 | 类型 | 校验 |
| --- | --- | --- |
| activityId | Long | - |
| name | String | @NotBlank、@Size(max=100) |
| rows / cols | Integer | @Min(1)、@Max(100)，默认 10 |
| aisleCols | String | -（过道列索引，逗号分隔如 "5,12"） |
| aisleWidthRatio | BigDecimal | -（默认 1.5） |
| seatData | String | -（格子状态 JSON 数组字符串） |
| colorLabels | String | -（自定义配色 JSON：`[{color,label}]`） |
| asTemplate | Boolean | -（是否存为模板） |
| templateCategory | String | - |

---

## 7. 例行事务（排班 / 考勤 / 无课表）

### 7.1 排班 ScheduleController（/api/schedules）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/schedules | 值班表查询。参数：`type`?、`weekNo`?、`weekday`?(1-7)、`activityId`?；返回 Result&lt;List&lt;Schedule&gt;&gt; | 登录即可 |
| POST | /api/schedules | 新建排班，Body：ScheduleRequest；返回 Result&lt;Long&gt; | LEADER |
| PUT | /api/schedules/{id} | 更新排班，Body：ScheduleRequest；返回 Result&lt;Void&gt; | LEADER |
| DELETE | /api/schedules/{id} | 删除排班；返回 Result&lt;Void&gt; | LEADER |
| GET | /api/schedules/export | 导出 Excel（查询参数同 GET 列表）；返回 xlsx 二进制流 | LEADER |

**ScheduleRequest**：`scheduleType`(String, @NotBlank)、`activityId`?、`weekNo`?、`weekday`?(1-7)、`sessionName`?、`location`?、`scheduleDate`(LocalDate)、`notes`?、`persons`(List，每项 `{userId, personName, isPrimary}` 1主班/0副班)

### 7.2 考勤 AttendanceController（/api/attendances）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/attendances | 考勤列表。参数：`scheduleId`?、`weekNo`?、`personName`?；返回 Result&lt;List&lt;Attendance&gt;&gt; | 登录即可 |
| POST | /api/attendances | 登记考勤，Body：AttendanceRequest；返回 Result&lt;Attendance&gt; | LEADER |
| DELETE | /api/attendances/{id} | 删除考勤记录；返回 Result&lt;Void&gt; | LEADER |
| GET | /api/attendances/summary | 按人汇总（应到/实到/请假/缺勤）。参数：`weekNo`?、`type`?；返回 Result&lt;List&lt;Map&gt;&gt; | 登录即可 |

**AttendanceRequest**：`scheduleId`(Long, @NotNull)、`personId`?、`personName`(String, @NotBlank)、`status`(String, @NotBlank，PRESENT/ABSENT/LEAVE)、`remark`?

### 7.3 无课表制作 CourseScheduleController（/api/course-schedules）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/course-schedules/configs | 时间格配置列表；返回 Result&lt;List&lt;ScheduleConfig&gt;&gt; | 登录即可 |
| PUT | /api/course-schedules/configs | 保存时间格配置（覆盖式）。Body：`List<Map>`；返回 Result&lt;Void&gt; | LEADER |
| GET | /api/course-schedules/mine | 我的课程表。参数：`semester`?；返回 Result&lt;Map&gt; | 登录即可 |
| POST | /api/course-schedules/mine | 保存我的课程表（覆盖式）。Body：`{"semester": "...", "cells": [{"dayOfWeek":1,"period":1,"courseName":"..."}]}`；返回 Result&lt;Void&gt; | 登录即可 |
| GET | /api/course-schedules/analyze | 共同空闲热力图。参数：`semester`?、`userIds`?(逗号分隔，缺省全员)；返回 Result&lt;Map&gt; | 登录即可 |
| POST | /api/course-schedules/import | 批量上传班级课表 xlsx 生成无课表。multipart：`files`(MultipartFile[]，必填，多文件)、`deptId`?、`semester`?、`maxWeek`?；返回 Result&lt;NoClassScheduleImportVO&gt; | LEADER |
| GET | /api/course-schedules/import/download | 下载生成的无课表。参数：`path`(必填)；返回文件流 | 登录即可 |
| GET | /api/course-schedules/generated | 查询某部门+学期最新生成的无课表。参数：`deptId`?、`semester`?；返回 Result&lt;NoClassScheduleGeneratedVO&gt; | 登录即可 |

**NoClassScheduleImportVO**：`deptName`、`semester`、`rows`(List&lt;NoClassScheduleRowVO&gt;)、`markdown`、`downloadUrl`、`totalFiles`、`successCount`、`failed`(List&lt;ImportFileFailureVO&gt;)、`warnings`(List&lt;String&gt;)

**NoClassScheduleGeneratedVO**：`deptName`、`semester`、`rows`、`createdAt`

---

## 8. 党务台账

### 8.1 党务成员 PartyMemberController（/api/party/members）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/party/members | 分页查询。参数：`keyword`?、`stage`?、`page`=1、`size`=10；返回 Result&lt;PageResult&lt;Map&gt;&gt;（STAFF 仅见脱敏字段） | 登录即可 |
| GET | /api/party/members/{id} | 成员详情（STAFF 脱敏）；返回 Result&lt;Map&gt; | 登录即可 |
| POST | /api/party/members | 新建成员，Body：PartyMemberRequest(@Valid)；返回 Result&lt;Long&gt; | LEADER |
| PUT | /api/party/members/{id} | 更新成员，Body：PartyMemberRequest(@Valid)；返回 Result&lt;Void&gt; | LEADER |
| DELETE | /api/party/members/{id} | 删除成员；返回 Result&lt;Void&gt; | LEADER |
| PUT | /api/party/members/{id}/stage | 追加流转阶段并更新政治面貌，Body：PartyStageRequest(@Valid)；返回 Result&lt;Void&gt; | LEADER |
| GET | /api/party/members/stages | 流转历史。参数：`memberId`(必填)；返回 Result&lt;List&lt;PartyStage&gt;&gt; | 登录即可 |

**PartyMemberRequest**：`name`(String, @NotBlank)、`gender`?、`nation`?、`idCard`?、`birthDate`(LocalDate)、`nativePlace`?、`education`?、`phone`?、`homeAddress`?、`className`?、`college`?、`branchName`?、`politicalStatus`?、`studentNo`?、`remark`?

**PartyStageRequest**：`stage`(String, @NotBlank)、`issueNo`?、`startDate`/`endDate`(String)、`remark`?

### 8.2 政审考察 PartyInvestigationController（/api/party/investigations）

> 类级 @PreAuthorize(LEADER)，全部接口需 LEADER。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /api/party/investigations | 政审列表。参数：`memberId`?；返回 Result&lt;List&lt;PartyInvestigation&gt;&gt; |
| POST | /api/party/investigations | 新建，Body：PartyInvestigationRequest(@Valid)；返回 Result&lt;Long&gt; |
| PUT | /api/party/investigations/{id} | 更新，Body：PartyInvestigationRequest(@Valid)；返回 Result&lt;Void&gt; |
| DELETE | /api/party/investigations/{id} | 删除；返回 Result&lt;Void&gt; |

**PartyInvestigationRequest**：`memberId`(Long, @NotNull)、`fatherName`/`fatherBranch`/`fatherBranchAddr`、`motherName`/`motherBranch`/`motherBranchAddr`、`relativeName`/`relativeBranch`/`relativeBranchAddr`

### 8.3 入党申请登记 PartyRegisterController（/api/party/registers）

> 类级 @PreAuthorize(LEADER)，全部接口需 LEADER。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /api/party/registers | 登记列表。参数：`memberId`?；返回 Result&lt;List&lt;PartyRegister&gt;&gt; |
| POST | /api/party/registers | 新建，Body：PartyRegisterRequest(@Valid)；返回 Result&lt;Long&gt; |
| PUT | /api/party/registers/{id} | 更新，Body：PartyRegisterRequest(@Valid)；返回 Result&lt;Void&gt; |
| DELETE | /api/party/registers/{id} | 删除；返回 Result&lt;Void&gt; |

**PartyRegisterRequest**：`memberId`(Long, @NotNull)、`college`?、`branch`?、`className`?、`name`?、`gender`?、`birthDate`?、`nativePlace`?、`nation`?、`idCard`?、`phone`?、`homeAddress`?、`applyDate`?、`education`?、`talkPerson`?、`conditionNote`?、`remark`?

### 8.4 花名册 PartyRosterController（/api/party/rosters）

> 类级 @PreAuthorize(LEADER)，全部接口需 LEADER。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /api/party/rosters | 花名册列表。参数：`type`?、`issueNo`?；返回 Result&lt;List&lt;PartyRoster&gt;&gt; |
| POST | /api/party/rosters | 新建，Body：PartyRosterRequest(@Valid)；返回 Result&lt;Long&gt; |
| PUT | /api/party/rosters/{id} | 更新，Body：PartyRosterRequest(@Valid)；返回 Result&lt;Void&gt; |
| DELETE | /api/party/rosters/{id} | 删除；返回 Result&lt;Void&gt; |

**PartyRosterRequest**：`rosterType`(String, @NotBlank)、`issueNo`?、`name`(String, @NotBlank)、`gender`?、`studentNo`?、`className`?、`branchName`?、`remark`?

### 8.5 组织关系转接 PartyTransferController（/api/party/transfers）

> 类级 @PreAuthorize(LEADER)，全部接口需 LEADER。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /api/party/transfers | 转接列表。参数：`memberId`?；返回 Result&lt;List&lt;PartyTransfer&gt;&gt; |
| POST | /api/party/transfers | 新建，Body：PartyTransferRequest(@Valid)；返回 Result&lt;Long&gt; |
| PUT | /api/party/transfers/{id} | 更新，Body：PartyTransferRequest(@Valid)；返回 Result&lt;Void&gt; |
| DELETE | /api/party/transfers/{id} | 删除；返回 Result&lt;Void&gt; |

**PartyTransferRequest**：`memberId`(Long, @NotNull)、`className`?、`name`?、`gender`?、`nation`?、`isProbationary`(Integer)、`idCard`?、`receiveOrg`?、`phone`?、`wechat`?、`isOnline`(Integer)、`signDate`(LocalDate)、`remark`?

---

## 9. 成员管理（部门纳新与账号）

### 9.1 成员 MemberController（/api/members）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/members | 分页查询。参数：`sessionId`?、`deptId`?、`position`?、`status`?、`keyword`?、`page`=1、`size`=10；返回 Result&lt;PageResult&lt;MemberVO&gt;&gt; | hasAuthority('member:view') |
| GET | /api/members/stats | 成员统计。参数：`sessionId`?；返回 Result&lt;MemberStatsVO&gt; | hasAuthority('member:view') |
| GET | /api/members/{id} | 成员详情；返回 Result&lt;MemberDetailVO&gt; | hasAuthority('member:view') |
| POST | /api/members | 新建成员，Body：MemberRequest；返回 Result&lt;Long&gt; | hasAuthority('member:manage') |
| PUT | /api/members/{id} | 更新成员，Body：MemberRequest；返回 Result&lt;Void&gt; | hasAuthority('member:manage') |
| DELETE | /api/members/{id} | 删除成员；返回 Result&lt;Void&gt; | hasAuthority('member:manage') |
| POST | /api/members/batch-delete | 批量删除。Body：`{"ids": [...]}`；返回 Result&lt;Void&gt; | hasAuthority('member:manage') |
| POST | /api/members/import | Excel 导入。multipart：`sessionId`(必填)、`file`(xlsx，必填)；返回 Result&lt;MemberImportResultVO&gt; | hasAuthority('member:manage') |
| GET | /api/members/import/template | 下载导入模板；返回 xlsx 流 | hasAuthority('member:export') |
| GET | /api/members/export | 导出花名册（筛选参数同列表）；返回 xlsx 流 | hasAuthority('member:export') |
| POST | /api/members/{sessionId}/archive | 按届归档；返回 Result&lt;Map&gt;（`{"count": n}`） | hasAuthority('member:manage') |
| GET | /api/members/unregistered | 未开通账号的成员。参数：`sessionId`?；返回 Result&lt;List&lt;UnregisteredMemberVO&gt;&gt; | hasAuthority('member:import_account') |
| POST | /api/members/import-accounts | 从花名册导入账号，Body：AccountImportRequest；返回 Result&lt;AccountImportResultVO&gt; | hasAuthority('member:import_account') |

**MemberRequest**（record）：`sessionId`?、`deptId`?、`position`?、`name`?、`gender`?、`studentNo`?、`className`?、`phone`?、`politicalStatus`?、`status`?、`remark`?

**AccountImportRequest**（record）：`sessionId`?、`memberIds`(List&lt;Long&gt;)、`roleCodes`(Map&lt;Long,String&gt;，成员 id → 角色码覆盖)

### 9.2 届别 MemberSessionController（/api/member-sessions）

> 类级 @PreAuthorize("hasAuthority('member:manage')")，全部接口需该权限。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /api/member-sessions | 届别列表；返回 Result&lt;List&lt;MemberSessionVO&gt;&gt; |
| POST | /api/member-sessions | 新建届别，Body：MemberSessionRequest；返回 Result&lt;Long&gt; |
| PUT | /api/member-sessions/{id} | 更新届别，Body：MemberSessionRequest；返回 Result&lt;Void&gt; |
| DELETE | /api/member-sessions/{id} | 删除届别；返回 Result&lt;Void&gt; |
| POST | /api/member-sessions/{id}/set-current | 设为当前届；返回 Result&lt;Void&gt; |

**MemberSessionRequest**（record）：`name`?、`isCurrent`(Integer)、`sortOrder`(Integer)、`remark`?

---

## 10. 内容宣传

### 10.1 推文 ArticleController（/api/articles）

> 工作流：创建(草稿) → 提交 → 审核 → 发布(回填公众号链接) → 更新数据。
> 注：update/submit/publish/stats/delete 无 @PreAuthorize，业务层按"操作人 id + 是否负责人角色"二次校验。

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/articles | 分页查询。参数：`status`?、`type`?、`keyword`?、`activityId`?、`page`=1、`size`=10；返回 Result&lt;PageResult&lt;Map&gt;&gt; | 登录即可 |
| POST | /api/articles | 创建推文，Body：ArticleRequest(@Valid)；返回 Result&lt;Article&gt; | MEDIA_LEADER / TEACHER / DIRECTOR |
| PUT | /api/articles/{id} | 更新草稿，Body：ArticleRequest(@Valid)；返回 Result&lt;Void&gt; | 登录即可（业务层校验） |
| PUT | /api/articles/{id}/submit | 提交审核；返回 Result&lt;Void&gt; | 登录即可（业务层校验） |
| PUT | /api/articles/{id}/review | 审核，Body：ReviewRequest(@Valid)；返回 Result&lt;Void&gt; | MEDIA_LEADER / TEACHER / DIRECTOR |
| PUT | /api/articles/{id}/publish | 发布，Body：PublishRequest(@Valid)；返回 Result&lt;Void&gt; | 登录即可（业务层校验） |
| PUT | /api/articles/{id}/stats | 回填阅读/点赞数据，Body：StatsRequest(@Valid)；返回 Result&lt;Void&gt; | 登录即可（业务层校验） |
| DELETE | /api/articles/{id} | 删除；返回 Result&lt;Void&gt; | 登录即可（业务层校验） |

**ArticleRequest**

| 字段 | 类型 | 校验 |
| --- | --- | --- |
| title | String | @NotBlank |
| summary / content / coverUrl | String | - |
| activityId | Long | @NotNull |
| deadline | LocalDateTime | @NotNull |
| authorId | Long | -（负责人） |
| imageUrls | List&lt;String&gt; | -（长图列表） |
| articleType | String | - |

**ReviewRequest**：`approved`(Boolean, @NotNull)、`comment`?
**PublishRequest**：`wxUrl`(String, @NotBlank)
**StatsRequest**：`readCount`(Integer)、`likeCount`(Integer)

### 10.2 新闻稿 NewsController（/api/news）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/news | 分页查询。参数：`keyword`?、`page`=1、`size`=10；返回 Result&lt;PageResult&lt;Map&gt;&gt; | 登录即可 |
| POST | /api/news | 新增，Body：NewsRequest(@Valid)；返回 Result&lt;News&gt; | LEADER |
| PUT | /api/news/{id} | 更新，Body：NewsRequest(@Valid)；返回 Result&lt;Void&gt; | LEADER |
| DELETE | /api/news/{id} | 删除；返回 Result&lt;Void&gt; | LEADER |

**NewsRequest**：`title`(String, @NotBlank)、`subtitle`?、`content`?、`activityId`?

---

## 11. 档案资产

### 11.1 归档材料 MaterialController（/api/materials）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/materials | 分页查询。参数：`keyword`?、`bizType`?、`activityId`?、`deptId`?、`page`=1、`size`=10；返回 Result&lt;PageResult&lt;Map&gt;&gt; | 登录即可 |
| GET | /api/materials/tree | 材料树。参数：`activityId`?；返回 Result&lt;List&lt;Map&gt;&gt; | 登录即可 |
| POST | /api/materials | 新增材料（干事也可上传），Body：MaterialRequest(@Valid)；返回 Result&lt;Material&gt; | 登录即可 |
| PUT | /api/materials/{id} | 更新，Body：MaterialRequest(@Valid)；返回 Result&lt;Void&gt; | LEADER |
| DELETE | /api/materials/{id} | 删除；返回 Result&lt;Void&gt; | LEADER |

**MaterialRequest**：`name`(String, @NotBlank)、`bizType`(String, @NotBlank)、`activityId`?、`deptId`?、`tag`?、`description`?、`fileId`?

### 11.2 模板 TemplateController（/api/templates）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/templates | 模板列表。参数：`category`?；返回 Result&lt;List&lt;Map&gt;&gt; | 登录即可 |
| POST | /api/templates | 新增，Body：TemplateRequest(@Valid)；返回 Result&lt;TemplateAsset&gt; | LEADER |
| PUT | /api/templates/{id} | 更新，Body：TemplateRequest(@Valid)；返回 Result&lt;Void&gt; | LEADER |
| DELETE | /api/templates/{id} | 删除；返回 Result&lt;Void&gt; | LEADER |

**TemplateRequest**：`name`(String, @NotBlank)、`category`(String, @NotBlank)、`description`?、`fileId`?

### 11.3 素拓加分 CreditController（/api/credits）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/credits | 分页查询。参数：`keyword`?、`userId`?、`activityId`?、`page`=1、`size`=10；返回 Result&lt;PageResult&lt;Map&gt;&gt; | 登录即可 |
| POST | /api/credits | 手动加分（同项目同人存在则累加），Body：CreditRequest(@Valid)；返回 Result&lt;CreditRecord&gt; | hasAuthority('quality:add') |
| PUT | /api/credits/{id} | 更新，Body：CreditRequest(@Valid)；返回 Result&lt;Void&gt; | hasAuthority('quality:add') |
| DELETE | /api/credits/{id} | 删除；返回 Result&lt;Void&gt; | hasAuthority('quality:delete') |
| POST | /api/credits/activity-batch | 活动批量加分。Body：`{"sourceActivityId": 1, "project": "...", "credit": 0.5, "remark": "...", "people": [{"personName": "...", "studentNo": "..."}]}`；返回 Result&lt;Map&lt;String,Integer&gt;&gt; | hasAuthority('quality:add') |
| DELETE | /api/credits/batch/{batchId} | 按批次撤回。路径：`batchId`(String)；返回 Result&lt;Integer&gt;（撤回条数） | hasAuthority('quality:delete') |

**CreditRequest**：`userId`?、`personName`(String, @NotBlank)、`studentNo`?、`activityId`?、`project`(String, @NotBlank)、`credit`(BigDecimal, @NotNull)、`basis`?、`remark`?

### 11.4 文件 FileController（/api/files）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | /api/files/upload | 通用文件上传。multipart：`file`(必填)、`bizType`?；返回 Result&lt;FileRecord&gt; | 登录即可 |
| GET | /api/files/{id}/download | 文件下载；直接返回文件流（非 Result 包装） | hasAuthority('material:view') or hasAuthority('party:view') |
| POST | /api/files/import | 导入花名册（仅 .xlsx/.xls，拒绝 `~$` 临时文件与空文件）。multipart：`file`(必填)、`type`?(默认 ACTIVE)；返回 Result&lt;Map&gt;（`{"added": n, "skipped": n}`） | LEADER |

### 11.5 公告 AnnouncementController（/api/announcements）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/announcements | 公告列表（全量）；返回 Result&lt;List&lt;Announcement&gt;&gt; | 登录即可 |
| POST | /api/announcements | 新增，Body：AnnouncementRequest(@Valid)；返回 Result&lt;Announcement&gt; | LEADER |
| PUT | /api/announcements/{id} | 更新，Body：AnnouncementRequest(@Valid)；返回 Result&lt;Void&gt; | LEADER |
| DELETE | /api/announcements/{id} | 删除；返回 Result&lt;Void&gt; | LEADER |

**AnnouncementRequest**：`title`(String, @NotBlank)、`content`(String, @NotBlank)、`publishTime`(LocalDateTime)

---

## 12. 群聊管理 GroupChatController（/api/chat）

### 12.1 分类

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/chat/categories | 分类列表；返回 Result&lt;List&lt;Map&gt;&gt; | 登录即可 |
| POST | /api/chat/categories | 新增分类。Body：`{"name": "..."}`；返回 Result&lt;Long&gt; | LEADER |
| PUT | /api/chat/categories/{id} | 重命名。Body：`{"name": "..."}`；返回 Result&lt;Void&gt; | LEADER |
| DELETE | /api/chat/categories/{id} | 删除分类；返回 Result&lt;Void&gt; | LEADER |
| PUT | /api/chat/categories/sort | 分类排序。Body：`{"ids": [3,1,2]}`；返回 Result&lt;Void&gt; | LEADER |

### 12.2 群聊

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/chat | 群聊列表。参数：`keyword`?、`categoryId`?、`status`?、`department`?；返回 Result&lt;List&lt;Map&gt;&gt; | 登录即可 |
| GET | /api/chat/{id} | 群聊详情；返回 Result&lt;Map&gt; | 登录即可 |
| POST | /api/chat | 新增群聊，Body：GroupChatRequest(@Valid)；返回 Result&lt;Long&gt; | LEADER |
| PUT | /api/chat/{id} | 更新群聊，Body：GroupChatRequest(@Valid)；返回 Result&lt;Void&gt; | LEADER |
| POST | /api/chat/{id}/archive | 归档群聊；返回 Result&lt;Void&gt; | LEADER |
| DELETE | /api/chat/{id} | 删除群聊；返回 Result&lt;Void&gt; | LEADER |

**GroupChatRequest**：`name`(String, @NotBlank、@Size(max=50))、`categoryId`?、`activityId`?、`departments`(List&lt;String&gt;，枚举：文秘部/组织部/新媒体中心/青年科技部)、`ownerId`?、`qrCodeUrl`(@Size(max=500))、`remark`(@Size(max=200))、`status`?(默认 ACTIVE，ACTIVE/DISSOLVED/ARCHIVED)

---

## 13. 通知 NotificationController（/api/notifications）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/notifications | 当前用户通知（最近 50 条）；返回 Result&lt;List&lt;NotificationVO&gt;&gt; | 登录即可 |
| GET | /api/notifications/page | 分页查询。参数：`type`?(NotificationType 枚举名)、`page`=1、`size`=10；返回 Result&lt;PageResult&lt;NotificationVO&gt;&gt; | 登录即可 |
| GET | /api/notifications/unread-count | 未读数；返回 Result&lt;Long&gt; | 登录即可 |
| PUT | /api/notifications/{id}/read | 标记单条已读；返回 Result&lt;Void&gt; | 登录即可 |
| PUT | /api/notifications/read-all | 全部标记已读；返回 Result&lt;Void&gt; | 登录即可 |
| GET | /api/notifications/preferences | 查询通知偏好；返回 Result&lt;List&lt;NotificationPreferenceVO&gt;&gt; | 登录即可 |
| PUT | /api/notifications/preferences | 保存通知偏好。Body：`List<NotificationPreferenceVO>`；返回 Result&lt;Void&gt; | 登录即可 |

---

## 14. 仪表盘 DashboardController（/api/dashboard）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | /api/dashboard | 工作台聚合数据（按当前用户角色定制：活动统计、本周排班、最近事项等）；返回 Result&lt;Map&gt; | 登录即可 |

---

## 15. WebSocket（实时通知）

| 项 | 说明 |
| --- | --- |
| 端点 | `/ws`（SockJS），STOMP 协议 |
| Broker 前缀 | `/queue`、`/topic` |
| 应用前缀 | `/app` |
| 用户前缀 | `/user` |
| 鉴权 | 握手阶段由 `WebSocketAuthInterceptor` 校验 token |
| 典型订阅 | `/user/queue/notifications`（个人通知推送） |

前端使用 `@stomp/stompjs` + `sockjs-client`（见 `pams-web/src/hooks/useWebSocket.ts`）。

---

## 附录 A：接口统计

| 模块 | Controller 数 | 接口数 |
| --- | --- | --- |
| 认证与系统 | 5 | 6 |
| 用户与权限 | 2 | 9 |
| 活动管理 | 6 | 33 |
| 签到（含名单/分组） | 2 | 24 |
| 座位布局 | 1 | 8 |
| 例行事务 | 3 | 17 |
| 党务台账 | 5 | 23 |
| 成员管理 | 2 | 18 |
| 内容宣传 | 2 | 12 |
| 档案资产 | 5 | 22 |
| 群聊 | 1 | 11 |
| 通知 | 1 | 7 |
| 仪表盘 | 1 | 1 |
| **合计** | **36+3** | **≈191** |

## 附录 B：文件上传接口汇总

| 接口 | 参数名 | 类型 | 说明 |
| --- | --- | --- | --- |
| POST /api/files/upload | file | MultipartFile | 通用上传，可附 bizType |
| POST /api/files/import | file | MultipartFile | 花名册 xlsx/xls |
| POST /api/members/import | file | MultipartFile | 成员名单 xlsx |
| POST /api/signins/roster/upload | file | MultipartFile | 应签名单 Excel |
| POST /api/signins/groups/upload | file | MultipartFile | 分组名单 Excel |
| POST /api/course-schedules/import | files | MultipartFile[] | 班级课表 xlsx（多文件） |
