# 业务通知子系统设计文档

> 日期：2026-08-05 | 状态：已批准

## 1. 背景与目标

PAMS 项目当前缺少业务流程驱动的通知机制。现有"通知公告"仅为手动发布的广播公告，与任务发布、策划书审核等业务流程完全脱钩。

**目标**：新建一套站内通知 + 实时推送的业务通知子系统，覆盖以下核心流程：

1. 主任发布任务 → 组织部收到通知
2. 组织部提交策划书审核 → 主任 + 指导老师收到通知
3. 主任驳回策划书 → 组织部（提交人）收到修改通知
4. 主任审核通过策划书 → 全员收到通知
5. 主任与指导老师权限一致，收到相同通知

## 2. 技术选型

| 维度 | 选择 | 理由 |
|---|---|---|
| 推送协议 | STOMP over WebSocket | Spring 原生支持，双向通信，适合当前用户规模 |
| 事件机制 | Spring ApplicationEvent | 业务与通知解耦，无额外中间件，同步可靠 |
| 前端状态 | Zustand store | 复用项目现有状态管理方案 |
| 认证 | 复用现有 JWT | WebSocket 握手时从 token 提取用户信息 |

## 3. 数据模型

### 3.1 Notification 实体

```sql
CREATE TABLE notification (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  type          VARCHAR(30)  NOT NULL,     -- 通知类型枚举
  title         VARCHAR(100) NOT NULL,     -- 简短标题
  content       VARCHAR(500) NOT NULL,     -- 详情描述
  entity_type   VARCHAR(20),              -- 关联实体类型: TASK/PLAN/ACTIVITY
  entity_id     BIGINT,                   -- 关联实体ID（点击跳转用）
  sender_id     BIGINT,                   -- 发送者用户ID
  recipient_id  BIGINT,                   -- 接收者用户ID（个人定向时非null）
  recipient_role     VARCHAR(30),         -- 接收角色（按角色推送时非null）
  recipient_dept_id  BIGINT,             -- 接收部门（按部门推送时非null）
  is_read       TINYINT DEFAULT 0,        -- 已读标记
  read_at       DATETIME,                 -- 阅读时间
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted       TINYINT DEFAULT 0         -- 软删除
);
```

索引：
- `idx_notification_recipient` ON `(recipient_id, is_read, created_at)` — 个人通知查询
- `idx_notification_role` ON `(recipient_role, is_read, created_at)` — 角色通知查询
- `idx_notification_dept` ON `(recipient_dept_id, is_read, created_at)` — 部门通知查询

### 3.2 NotificationType 枚举

```java
public enum NotificationType {
    TASK_ASSIGNED,    // 任务指派
    PLAN_SUBMITTED,   // 策划书提交审核
    PLAN_APPROVED,    // 策划书审核通过
    PLAN_REJECTED     // 策划书审核驳回
}
```

| 类型 | 触发场景 | 推送目标 | 标题示例 |
|---|---|---|---|
| `TASK_ASSIGNED` | `TaskService.create()` | 任务指派部门全体成员 | "新任务指派" |
| `PLAN_SUBMITTED` | `PlanService.submit()` | TEACHER + DIRECTOR 角色 | "策划书待审核" |
| `PLAN_APPROVED` | `PlanService.review(true)` | 全员 | "策划书审核通过" |
| `PLAN_REJECTED` | `PlanService.review(false)` | 策划书提交人 | "策划书已驳回" |

## 4. 后端架构

### 4.1 新增文件清单

```
pams-backend/src/main/java/com/pams/module/notification/
├── entity/Notification.java
├── entity/NotificationType.java
├── dto/NotificationVO.java
├── repository/NotificationRepository.java
├── service/NotificationService.java          // CRUD + 标记已读
├── service/NotificationEventListener.java    // 事件监听 → 创建通知 + 推送
└── controller/NotificationController.java    // REST API

pams-backend/src/main/java/com/pams/config/
└── WebSocketConfig.java                      // STOMP 配置

pams-backend/src/main/java/com/pams/security/
└── WebSocketAuthInterceptor.java             // WebSocket 握手认证
```

### 4.2 事件定义

```java
// 四个事件类，放在 notification/event/ 下
public class TaskAssignedEvent {
    Long taskId; Long activityId; Long deptId; String taskName; Long senderId;
}
public class PlanSubmittedEvent {
    Long planId; Long activityId; String planTitle; Long submitterId;
}
public class PlanApprovedEvent {
    Long planId; Long activityId; String planTitle; Long reviewerId;
}
public class PlanRejectedEvent {
    Long planId; Long activityId; String planTitle; Long reviewerId; String comment;
}
```

### 4.3 事件发布点（现有文件修改）

| 文件 | 方法 | 插入位置 | 发布事件 |
|---|---|---|---|
| `TaskService.java` | `create()` | `taskRepo.save()` 之后 | `TaskAssignedEvent` |
| `PlanService.java` | `submit()` | `planRepo.save()` 之后 | `PlanSubmittedEvent` |
| `PlanService.java` | `review()` | `planRepo.save()` 之后，按 approved 分支 | `PlanApprovedEvent` 或 `PlanRejectedEvent` |

发布方式：注入 `ApplicationEventPublisher`，调用 `publisher.publishEvent(event)`。

### 4.4 事件监听器逻辑

```java
@Component
public class NotificationEventListener {

    @TransactionalEventListener(phase = AFTER_COMMIT)  // 事务提交后才触发
    void handleTaskAssigned(TaskAssignedEvent event) {
        // 1. 查该部门所有用户
        List<User> members = userRepo.findByDeptId(event.deptId);
        // 2. 批量创建 Notification
        // 3. WebSocket 推送给每个成员
    }

    @TransactionalEventListener(phase = AFTER_COMMIT)
    void handlePlanSubmitted(PlanSubmittedEvent event) {
        // 1. 查 TEACHER + DIRECTOR 角色的所有用户
        // 2. 批量创建 + 推送
    }

    @TransactionalEventListener(phase = AFTER_COMMIT)
    void handlePlanApproved(PlanApprovedEvent event) {
        // 1. 查全员（排除发送者自己）
        // 2. 批量创建 + 推送
    }

    @TransactionalEventListener(phase = AFTER_COMMIT)
    void handlePlanRejected(PlanRejectedEvent event) {
        // 1. 仅查 submitterId 对应用户
        // 2. 创建单条通知 + 推送
    }
}
```

### 4.5 WebSocket 配置

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/queue", "/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(webSocketAuthInterceptor);
    }
}
```

- 用户私有队列：`/user/queue/notifications`（Spring 自动按 Principal 路由）
- SockJS 提供不支持 WebSocket 的浏览器回退
- 认证：握手时从 JWT token 解析用户信息，注入 `SimpUserRegistry`

### 4.6 REST API

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | `/api/notifications` | 当前用户的通知列表（分页、可筛选未读） | 已登录 |
| GET | `/api/notifications/unread-count` | 未读通知数 | 已登录 |
| PUT | `/api/notifications/{id}/read` | 标记单条已读 | 已登录 |
| PUT | `/api/notifications/read-all` | 全部标记已读 | 已登录 |

### 4.7 通知查询逻辑

通知查询需要合并两种来源：
- `recipient_id = currentUserId` — 个人定向通知
- `recipient_role = currentUserRole` — 角色定向通知
- `recipient_dept_id = currentUserDeptId` — 部门定向通知

用 OR 条件或 Specification 动态拼装，按 `created_at DESC` 排序。

## 5. 前端架构

### 5.1 新增文件清单

```
pams-web/src/
├── api/notification.ts               # API 接口
├── store/notificationStore.ts        # Zustand 通知状态
├── components/notification/
│   ├── NotificationBell.tsx          # 铃铛 + 角标 + 下拉列表
│   └── NotificationToast.tsx         # 实时弹窗
└── hooks/useWebSocket.ts             # WebSocket 连接管理 Hook
```

### 5.2 WebSocket 连接 Hook

```typescript
// hooks/useWebSocket.ts
// 用户登录后连接 /ws，认证传 JWT token
// subscribe /user/queue/notifications
// 收到消息 → notificationStore.addRealtimeNotification()
// 收到消息 → notification.success() 弹窗
// 断线自动重连（指数退避）
```

### 5.3 通知铃铛组件

在 `MainLayout.tsx` 的 Header 右侧区域添加 `<NotificationBell />`：

- Badge 显示未读数（> 99 显示 "99+"）
- 点击展开 Popover 下拉列表，展示最近 20 条通知
- 未读通知左侧蓝色圆点 + 标题加粗
- 点击通知 → 标记已读 + 跳转到对应页面
- 底部"全部标记已读"按钮
- 下拉列表底部"查看全部"链接（可选，后续扩展通知中心页面）

### 5.4 实时弹窗

收到 WebSocket 推送时：
- antd `notification` 组件在右上角弹出
- 显示通知标题和内容摘要
- 3 秒自动关闭
- 点击可跳转到对应活动/任务

### 5.5 通知跳转规则

| entity_type | 跳转路径 |
|---|---|
| `TASK` | `/activities/{activityId}/gantt` |
| `PLAN` | `/activities/{activityId}` (策划书 Tab) |
| `ACTIVITY` | `/activities/{activityId}` |

### 5.6 Zustand Store

```typescript
interface NotificationVO {
  id: number;
  type: string;
  title: string;
  content: string;
  entityType: string | null;
  entityId: number | null;
  senderName: string;
  read: boolean;
  createdAt: string;
}

interface NotificationState {
  unreadCount: number;
  notifications: NotificationVO[];
  loading: boolean;

  fetchUnreadCount: () => Promise<void>;
  fetchNotifications: () => Promise<void>;
  markAsRead: (id: number) => Promise<void>;
  markAllAsRead: () => Promise<void>;
  addRealtimeNotification: (n: NotificationVO) => void;
}
```

## 6. 数据库迁移

新增 Flyway 迁移脚本 `V3__add_notification_table.sql`（V2 已被签到应签名单占用）。

## 7. 测试策略

### 后端测试

1. `NotificationServiceTest` — CRUD、标记已读、查询逻辑
2. `NotificationEventListenerTest` — 事件监听是否正确创建通知、推送给正确的目标用户
3. `NotificationControllerTest` — API 权限和参数校验
4. 集成测试：TaskService.create() → 验证通知记录被创建

### 前端测试

1. `notificationStore` — 状态管理逻辑
2. `NotificationBell` — 角标显示、下拉列表交互
3. E2E：发布任务 → 接收方看到通知角标变化

## 8. 实施顺序

1. **后端基础**：实体 + Repository + Service + Controller + 迁移脚本
2. **WebSocket**：配置 + 认证拦截器
3. **事件系统**：事件定义 + 监听器
4. **业务接入**：在 TaskService/PlanService 中发布事件
5. **前端基础**：API + Store + 连接 Hook
6. **前端 UI**：通知铃铛组件 + 实时弹窗
7. **集成验证**：端到端流程测试
