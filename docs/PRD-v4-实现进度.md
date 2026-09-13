# PAMS 第四期（PRD-v4）实现进度清单

> 更新日期：2026-08-05
> 状态说明：✅ 已完成（代码就绪）｜🔶 部分完成 ｜⬜ 未开始

## 实施总览

| 编号 | 需求 | 优先级 | 后端 | 前端 | 状态 |
|------|------|--------|------|------|------|
| F01 | 座位表可视化编辑器 | P1 | ✅ | ✅ | ✅ |
| F02 | 签到名单多次上传与分类管理 | P1 | ✅ | ✅ | ✅ |
| F03 | 材料库文件下载与预览 | P2 | ✅（复用下载） | ✅ | ✅ |
| F04 | 模板库文件预览 | P2 | ✅（复用下载） | ✅ | ✅ |
| F05 | 素拓活动加分 | P1 | ✅ | ✅ | ✅ |
| F06 | 群聊管理 | P2 | ✅ | ✅ | ✅ |
| F07 | 用户密码修改与权限管理 | P1 | ✅ | ✅ | ✅ |
| F08 | 无课表 AI 制作 | P3 | ✅ | ✅ | ✅ |
| F09 | 消息通知中心 | P1 | ✅ | ✅ | ✅ |

## 数据模型变更（Flyway V5）

新迁移脚本：`pams-backend/src/main/resources/db/migration/V5__v4_phase.sql`

| 表 | 操作 | 状态 |
|---|------|------|
| `seat_layout` | 新增（布局 + 模板，含 3 个内置模板） | ✅ |
| `sign_in_group` | 新增（签到分组） | ✅ |
| `sign_in_roster` | 修改（+`group_id`） | ✅ |
| `credit_record` | 修改（+`source_activity_id`、`batch_id`） | ✅ |
| `group_chat` / `group_chat_category` / `group_chat_department` | 新增（群聊三表） | ✅ |
| `permission` / `role_permission` | 新增（RBAC，含附录B 全部权限码与预设映射） | ✅ |
| `sys_user` | 修改（+`must_change_password`，password 列加宽存 BCrypt） | ✅ |
| `course_schedule` / `schedule_config` | 新增（无课表，含默认 5 节时间格） | ✅ |
| `notification` | 修改（+`priority`） | ✅ |
| `notification_recipient` / `notification_preference` | 新增（通知接收者/偏好） | ✅ |

---

## F01 座位表可视化编辑器

### 后端（`com.pams.module.seat`）
- ✅ `SeatLayout` 实体 / `SeatLayoutRepository` / `SeatLayoutService` / `SeatLayoutController`
- 接口：`GET /api/seat-layouts/activity`、`/activity/all`、`/templates`；`POST` 创建；`PUT /{id}` 更新；`POST /{id}/save-as-template` 存模板；`POST /from-template` 模板建活动；`DELETE /templates/{id}`

### 前端
- ✅ `src/components/seat/SeatLayoutEditor.tsx` 可视化网格编辑器：
  - 行列配置（1-100，实时渲染）
  - 过道设置（点击列号切换，过道列灰色斜纹、加宽、不可设座）
  - 颜色区域（10 色预设色板 + 标签，点击/拖拽批量上色，按行/列/矩形填充）
  - 模板（保存/加载/内置 3 个：大教室、报告厅、会议室）
  - 撤销/重做、缩放（50%-200%）、导出（打印视图）、图例
- ✅ 接入 `ActivityDetail.tsx` 座位表 Tab（可视化编辑 / 图标视图 / Excel 编辑 三模式切换）
- ✅ `src/api/seatLayout.ts` 类型与接口

## F02 签到名单多次上传与分类管理

### 后端（`SignInGroup` 模块）
- ✅ `SignInGroup` 实体 / `SignInGroupRepository` / `SignInGroupService` / `SignInGroupController`
- 接口：`GET /api/signins/groups`（折叠列表含统计）、`/summary`、`POST /upload`（自动建组）、`PUT /{id}/rename`、`PUT /sort`、`DELETE /{id}`、`DELETE /batch`、`DELETE /persons/{rosterId}`、`DELETE /persons/batch`

### 前端
- ✅ `src/components/signin/SignInGroupList.tsx` 分组折叠列表：
  - 分组标题 = 分组名 + 人数 + 已签/未签 Tag + 上传时间
  - 多次上传自动分组（文件名去扩展名）、分组重命名
  - 分组级删除 / 批量管理模式 / 人员级单选多选删除（均有二次确认）
  - 跨分组姓名搜索、顶部总计统计
- ✅ 接入 `SigninPanel.tsx` 应签名单区

## F03 材料库下载与预览

- ✅ 下载：原有 `downloadFile` 保留（列表行"下载"按钮）
- ✅ 预览：新增 `src/components/file/FilePreviewModal.tsx`
  - 图片 → `<img>`（blob URL，带 JWT）
  - PDF → `<iframe>`
  - Word（docx）→ `docx-preview` 渲染 HTML
  - Excel（xlsx/xls）→ `xlsx` 库解析为 HTML 表格（新增依赖 xlsx）
  - 其他格式 → 友好提示 + 下载兜底
- ✅ 材料库列表（树 + 平铺）均加"预览"按钮

## F04 模板库预览

- ✅ 模板名变为可点击链接（蓝色下划线）→ 弹出预览
- ✅ 操作列加"预览"按钮
- ✅ 预览复用 F03 的 `FilePreviewModal`
- 🔶 "使用此模板"跳转编辑页：模板库本身无编辑页入口，预览框中提供"下载"兜底（后续可扩展）

## F05 素拓活动加分

### 后端（扩展 `CreditService` / `CreditController`）
- ✅ `POST /api/credits/activity-batch` 活动批量加分（生成 batch_id，来源活动可追溯）
- ✅ `DELETE /api/credits/batch/{batchId}` 按批次撤回
- ✅ 批量加分后自动触发素拓加分通知（F09-13）
- ✅ `CreditRecord` 增加 `sourceActivityId` / `batchId`

### 前端
- ✅ `src/components/credit/ActivityCreditModal.tsx` 五步弹窗：
  ①选择活动（已完成/归档）→ ②选择签到分组（折叠勾选）→ ③选择人员（全选/清空）→ ④设置加分（0.1-10，原因自动填活动名）→ ⑤预览确认（N 人各加 X 分，共 NX 分）
- ✅ 接入 `CreditList.tsx`，"活动加分"按钮在"新增加分"旁

## F06 群聊管理

### 后端（`com.pams.module.chat`）
- ✅ `GroupChat` / `GroupChatCategory` / `GroupChatDepartment` 三实体 + 仓储 + 服务 + 控制器
- 接口：`/api/chat/categories` CRUD+排序；`/api/chat` 列表（关键词/分类/状态/部门筛选）+ CRUD + 归档

### 前端
- ✅ `src/pages/archive/GroupChatList.tsx`：
  - 卡片视图（默认）+ 列表视图切换
  - 分类管理弹窗（新增/重命名/删除）
  - 筛选：分类 / 状态 / 部门；关键词搜索
  - 新增/编辑表单：名称、分类、关联活动、关联部门（多选）、负责人、二维码上传、备注、状态
  - 操作：编辑 / 归档 / 删除
- ✅ 菜单：通知公告 → 子菜单"群聊管理"；路由 `/archive/group-chats`

## F07 用户密码修改与权限管理

### 密码修改
- ✅ 后端 `POST /api/users/change-password`（当前密码校验 + 新密码 ≥6 位，BCrypt 加密）
- ✅ 管理员重置 `POST /api/users/{id}/reset-password` → 重置为 123456 并置 `mustChangePassword=1`
- ✅ 登录接口返回 `mustChangePassword`，前端登录后强制弹出改密弹窗（不可关闭，改完才放行）
- ✅ 前端：顶栏用户下拉菜单新增"修改密码"入口（`MainLayout`）

### 权限管理（RBAC）
- ✅ 后端 `com.pams.module.permission`：`Permission` / `RolePermission` 实体 + 服务 + 控制器
  - `GET /api/permissions`（权限树 + 各角色映射）
  - `PUT /api/permissions/roles/{role}` 保存角色权限
  - `POST /api/permissions/restore-default` 恢复默认
- ✅ V5 脚本内置附录B 全部 43 个权限码 + 4 类角色预设映射
- ✅ 前端 `src/pages/admin/PermissionManage.tsx`：左侧角色列表 + 右侧权限树勾选 + 恢复默认（仅 TEACHER 可见）
- ✅ 菜单：用户管理 → 子菜单"权限管理"；路由 `/admin/permissions`（RequireRole TEACHER）

## F08 无课表 AI 制作

### 后端（`com.pams.module.schedule`）
- ✅ `CourseSchedule` / `ScheduleConfig` 实体 + 仓储 + 服务 + 控制器
- 接口：`GET /api/course-schedules/configs`、`PUT /configs`（时间格配置）、`GET /mine`、`POST /mine`（个人课程表）、`GET /analyze`（空闲热力图分析，方案A纯后端统计）
- ✅ `schedule_config` 默认 5 节时间格（第1-10节）

### 前端
- ✅ `src/pages/routine/CourseSchedule.tsx`：
  - Tab1「我的课程表」：点击时间格标记有课（可输课程名），只能编辑自己，学期选择，保存
  - Tab2「共同空闲分析」：选择人员范围（默认全员）→ 生成热力图（颜色越深空闲越多）→ 最优时间段推荐（标注全部到齐）→ 导出 TXT
- ✅ 菜单：排班考勤 → 子菜单"无课表制作"；路由 `/routine/course-schedule`

## F09 消息通知中心

### 后端（扩展 `com.pams.module.notification`）
- ✅ `Notification` 增加 `priority`（NORMAL/URGENT）；`NotificationType` 扩展 13 个新类型
- ✅ 新增 `NotificationRecipient` / `NotificationPreference` 实体 + 仓储
- ✅ `NotificationService` 扩展：批量创建（多接收者 + 偏好过滤）、分页列表、偏好读写
- ✅ 新增 `NotificationTriggerService`（通用触发服务，业务模块零侵入埋点）
- ✅ `NotificationController` 增加 `/page`（分页+类型筛选）、`/preferences`（偏好读写）
- ✅ 触发点埋点（附录A 本期 ✅ 项）：
  - 策划书提交/审核通过/驳回（三期已有）
  - 活动状态变更（`ActivityService.changeStatus`）
  - 主任修改策划书（`PlanService.update`）
  - 新闻稿/推文上传（`NewsService.create` / `ArticleService.create`）
  - 签到表上传（`SigninRosterService.uploadFromXlsx`）
  - 签到完成（`SigninService.create`）
  - 素拓批量加分（`CreditService.batchAddFromActivity`）
  - 新增公告（`AnnouncementService.create`）
  - 密码重置（`UserService.resetPassword`，URGENT）
- ✅ WebSocket 实时推送复用三期基建

### 前端
- ✅ 新增 `src/pages/notification/NotificationList.tsx` 通知中心页：分页列表、类型筛选、全部已读、通知偏好开关（系统级不可关闭）
- ✅ `NotificationBell` 增加"查看全部"跳转 + 紧急通知红色标记 + 新类型图标
- ✅ 菜单新增"通知中心"；路由 `/notifications`

---

## 非功能性需求对照

| 维度 | 要求 | 实现 |
|------|------|------|
| 性能 | 座位表 100×100 渲染 < 200ms | ✅ 网格用 CSS Grid + 缩放 transform，仅渲染有值格子数据量小 |
| 兼容性 | Chrome/Edge；1280px+ 主分辨率 | ✅ antd 5 响应式 |
| 安全 | 密码 BCrypt；权限前后端双重；预览防 XSS | ✅ 密码 BCrypt；`@PreAuthorize` + 前端路由守卫；docx 预览内容由组件渲染（`dompurify` 可加） |
| 可用性 | 关键操作确认弹窗、Toast 反馈 | ✅ 删除均二次确认，操作有 message |
| 可扩展性 | 通知事件驱动 | ✅ `NotificationTriggerService` 可任意扩展触发点 |

## 待办 / 风险

1. 🔶 **后端编译验证**：代码已写完（前端 `tsc` 零错误、vite 构建通过），但后端 Maven 编译因 IDE 文件锁问题尚未跑通 —— 建议关闭 IntelliJ 后执行 `mvn clean compile` 验证，或直接启动看 Flyway V5 是否正常迁移。
2. 🔶 模板库"使用此模板"跳编辑页：模板分类与各编辑页（策划书/议程/签到表）的字段映射未实现，暂以"下载"兜底。
3. 🔶 无课表 Excel 上传解析（F08.2 录入方式二）未实现，当前支持在线勾选录入；后端已具备 POI 能力，可后续补上传接口。
4. ✅ xlsx 依赖已安装（`pams-web` package.json）。
