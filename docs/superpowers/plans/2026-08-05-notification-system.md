# 业务通知子系统实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 PAMS 新建一套站内通知 + WebSocket 实时推送的业务通知子系统，覆盖任务指派、策划书提交/审核/驳回的通知流程。

**Architecture:** 后端通过 Spring ApplicationEvent 在业务 Service 中发布事件，独立的 NotificationEventListener 监听事件并创建通知记录 + 推送 STOMP WebSocket。前端通过 @stomp/stompjs 连接 WebSocket，在 MainLayout Header 中展示通知铃铛角标和下拉列表。

**Tech Stack:** Spring Boot 4.0.2, Spring WebSocket (STOMP), Spring ApplicationEvent, JPA/Hibernate, Flyway, React 18, Ant Design 5, Zustand 5, @stomp/stompjs, sockjs-client

## Global Constraints

- Java 21, Spring Boot 4.0.2
- 实体用 `@Data` + `@Entity` + `@SQLRestriction("deleted = 0")`，枚举字段用 `@Enumerated(EnumType.STRING)`
- 外键用裸 Long 字段，不做 JPA `@ManyToOne` 关联
- Service 构造器注入，写操作加 `@Transactional`
- Controller 返回 `Result<T>`，权限用 `@PreAuthorize`
- 业务异常统一 `BizException(数字码, 中文消息)`，错误码从 2050 起
- 数据库迁移用 Flyway `V4__notification.sql`
- 前端 API 用 `get<T>` / `post<T>` / `put<T>` / `del<T>` from `./http`
- 前端状态管理用 Zustand v5，store 文件放在 `src/stores/`
- Glass UI 组件：`GlassCard`, `GlassModal` 等

---

## File Structure

### 后端新增文件

| 文件 | 职责 |
|---|---|
| `pams-backend/src/main/java/com/pams/module/notification/entity/Notification.java` | 通知实体 |
| `pams-backend/src/main/java/com/pams/module/notification/entity/NotificationType.java` | 通知类型枚举 |
| `pams-backend/src/main/java/com/pams/module/notification/dto/NotificationVO.java` | 响应 DTO |
| `pams-backend/src/main/java/com/pams/module/notification/repository/NotificationRepository.java` | 数据访问 |
| `pams-backend/src/main/java/com/pams/module/notification/service/NotificationService.java` | 通知 CRUD + 标记已读 |
| `pams-backend/src/main/java/com/pams/module/notification/event/TaskAssignedEvent.java` | 任务指派事件 |
| `pams-backend/src/main/java/com/pams/module/notification/event/PlanSubmittedEvent.java` | 策划书提交事件 |
| `pams-backend/src/main/java/com/pams/module/notification/event/PlanReviewedEvent.java` | 策划书审核事件（通过/驳回共用） |
| `pams-backend/src/main/java/com/pams/module/notification/listener/NotificationEventListener.java` | 事件监听 → 创建通知 + 推送 |
| `pams-backend/src/main/java/com/pams/module/notification/controller/NotificationController.java` | REST API |
| `pams-backend/src/main/java/com/pams/config/WebSocketConfig.java` | STOMP WebSocket 配置 |
| `pams-backend/src/main/java/com/pams/security/WebSocketAuthInterceptor.java` | WebSocket 握手认证 |
| `pams-backend/src/main/resources/db/migration/V4__notification.sql` | 数据库迁移 |

### 后端修改文件

| 文件 | 修改内容 |
|---|---|
| `pams-backend/pom.xml` | 添加 `spring-boot-starter-websocket` 依赖 |
| `pams-backend/src/main/java/com/pams/module/activity/service/TaskService.java:57-74` | create() 中发布 TaskAssignedEvent |
| `pams-backend/src/main/java/com/pams/module/activity/service/PlanService.java:72-82` | submit() 中发布 PlanSubmittedEvent |
| `pams-backend/src/main/java/com/pams/module/activity/service/PlanService.java:93-111` | review() 中发布 PlanReviewedEvent |
| `pams-backend/src/main/java/com/pams/module/user/repository/UserRepository.java` | 添加按 deptId / roleCode 查询方法 |

### 前端新增文件

| 文件 | 职责 |
|---|---|
| `pams-web/src/api/notification.ts` | 通知 API 接口 |
| `pams-web/src/stores/notification.ts` | Zustand 通知状态管理 |
| `pams-web/src/hooks/useWebSocket.ts` | WebSocket 连接管理 Hook |
| `pams-web/src/components/notification/NotificationBell.tsx` | 铃铛 + 角标 + 下拉列表 |
| `pams-web/src/components/notification/NotificationToast.tsx` | 实时弹窗封装 |

### 前端修改文件

| 文件 | 修改内容 |
|---|---|
| `pams-web/package.json` | 添加 sockjs-client, @stomp/stompjs, @types/sockjs-client |
| `pams-web/src/layouts/MainLayout.tsx:107-134` | Header 中插入 NotificationBell 组件 |

---

## Task 1: 数据库迁移 + 通知实体

**Files:**
- Create: `pams-backend/src/main/resources/db/migration/V4__notification.sql`
- Create: `pams-backend/src/main/java/com/pams/module/notification/entity/Notification.java`
- Create: `pams-backend/src/main/java/com/pams/module/notification/entity/NotificationType.java`

**Interfaces:**
- Produces: `Notification` entity with fields: id, type(NotificationType), title, content, entityType, entityId, senderId, recipientId, recipientRole, recipientDeptId, isRead(Integer), readAt, createdAt, deleted(Integer)
- Produces: `NotificationType` enum: TASK_ASSIGNED, PLAN_SUBMITTED, PLAN_APPROVED, PLAN_REJECTED

- [ ] **Step 1: 创建迁移脚本 V4__notification.sql**

```sql
-- ===================== 4、业务通知 =====================
CREATE TABLE IF NOT EXISTS notification (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  type             VARCHAR(30)  NOT NULL COMMENT '通知类型: TASK_ASSIGNED/PLAN_SUBMITTED/PLAN_APPROVED/PLAN_REJECTED',
  title            VARCHAR(100) NOT NULL COMMENT '通知标题',
  content          VARCHAR(500) NOT NULL COMMENT '通知内容详情',
  entity_type      VARCHAR(20)  COMMENT '关联实体类型: TASK/PLAN/ACTIVITY',
  entity_id        BIGINT       COMMENT '关联实体ID',
  sender_id        BIGINT       COMMENT '发送者用户ID',
  recipient_id     BIGINT       COMMENT '接收者用户ID(个人定向)',
  recipient_role   VARCHAR(30)  COMMENT '接收角色(角色定向)',
  recipient_dept_id BIGINT      COMMENT '接收部门(部门定向)',
  is_read          TINYINT DEFAULT 0 COMMENT '已读标记: 0未读 1已读',
  read_at          DATETIME     COMMENT '阅读时间',
  created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted          TINYINT DEFAULT 0,
  INDEX idx_notification_recipient (recipient_id, is_read, created_at),
  INDEX idx_notification_role (recipient_role, is_read, created_at),
  INDEX idx_notification_dept (recipient_dept_id, is_read, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务通知';
```

- [ ] **Step 2: 创建 NotificationType 枚举**

```java
package com.pams.module.notification.entity;

public enum NotificationType {
    TASK_ASSIGNED,
    PLAN_SUBMITTED,
    PLAN_APPROVED,
    PLAN_REJECTED
}
```

- [ ] **Step 3: 创建 Notification 实体**

```java
package com.pams.module.notification.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "notification")
@SQLRestriction("deleted = 0")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 500)
    private String content;

    @Column(name = "entity_type", length = 20)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "sender_id")
    private Long senderId;

    @Column(name = "recipient_id")
    private Long recipientId;

    @Column(name = "recipient_role", length = 30)
    private String recipientRole;

    @Column(name = "recipient_dept_id")
    private Long recipientDeptId;

    @Column(name = "is_read")
    private Integer isRead;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    private Integer deleted;
}
```

- [ ] **Step 4: 启动应用验证迁移脚本通过**

Run: `cd pams-backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=default`，确认 Flyway V4 迁移成功，无报错。启动后 Ctrl+C 停止。

- [ ] **Step 5: Commit**

```bash
git add pams-backend/src/main/resources/db/migration/V4__notification.sql \
       pams-backend/src/main/java/com/pams/module/notification/entity/
git commit -m "feat(notification): 数据库迁移 + Notification 实体和枚举"
```

---

## Task 2: 通知 Repository + Service + VO

**Files:**
- Create: `pams-backend/src/main/java/com/pams/module/notification/dto/NotificationVO.java`
- Create: `pams-backend/src/main/java/com/pams/module/notification/repository/NotificationRepository.java`
- Create: `pams-backend/src/main/java/com/pams/module/notification/service/NotificationService.java`
- Test: `pams-backend/src/test/java/com/pams/module/notification/NotificationServiceTest.java`

**Interfaces:**
- Consumes: `Notification` entity, `NotificationType` enum
- Produces: `NotificationService` with methods:
  - `Notification createAndSave(NotificationType type, String title, String content, String entityType, Long entityId, Long senderId, Long recipientId, String recipientRole, Long recipientDeptId)`
  - `List<Notification> findForUser(Long userId, String roleCode, Long deptId)`
  - `long countUnreadForUser(Long userId, String roleCode, Long deptId)`
  - `void markAsRead(Long id, Long userId)`
  - `void markAllAsRead(Long userId, String roleCode, Long deptId)`
- Produces: `NotificationVO` with fields: id, type(String), title, content, entityType, entityId, senderName, read(boolean), createdAt

- [ ] **Step 1: 创建 NotificationVO**

```java
package com.pams.module.notification.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class NotificationVO {
    private Long id;
    private String type;
    private String title;
    private String content;
    private String entityType;
    private Long entityId;
    private String senderName;
    private boolean read;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 2: 创建 NotificationRepository**

```java
package com.pams.module.notification.repository;

import com.pams.module.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("""
        SELECT n FROM Notification n
        WHERE n.deleted = 0
          AND (n.recipientId = :userId
               OR n.recipientRole = :roleCode
               OR n.recipientDeptId = :deptId)
        ORDER BY n.createdAt DESC
    """)
    List<Notification> findForUser(@Param("userId") Long userId,
                                   @Param("roleCode") String roleCode,
                                   @Param("deptId") Long deptId);

    @Query("""
        SELECT COUNT(n) FROM Notification n
        WHERE n.deleted = 0 AND n.isRead = 0
          AND (n.recipientId = :userId
               OR n.recipientRole = :roleCode
               OR n.recipientDeptId = :deptId)
    """)
    long countUnreadForUser(@Param("userId") Long userId,
                            @Param("roleCode") String roleCode,
                            @Param("deptId") Long deptId);

    @Modifying
    @Query("""
        UPDATE Notification n SET n.isRead = 1, n.readAt = :now
        WHERE n.deleted = 0 AND n.isRead = 0
          AND (n.recipientId = :userId
               OR n.recipientRole = :roleCode
               OR n.recipientDeptId = :deptId)
    """)
    void markAllAsRead(@Param("userId") Long userId,
                       @Param("roleCode") String roleCode,
                       @Param("deptId") Long deptId,
                       @Param("now") LocalDateTime now);
}
```

- [ ] **Step 3: 编写 NotificationServiceTest**

```java
package com.pams.module.notification;

import com.pams.common.BizException;
import com.pams.module.notification.entity.Notification;
import com.pams.module.notification.entity.NotificationType;
import com.pams.module.notification.repository.NotificationRepository;
import com.pams.module.notification.service.NotificationService;
import com.pams.module.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class NotificationServiceTest {

    NotificationRepository repo;
    UserRepository userRepo;
    NotificationService service;

    @BeforeEach
    void setup() {
        repo = mock(NotificationRepository.class);
        userRepo = mock(UserRepository.class);
        service = new NotificationService(repo, userRepo);
    }

    @Test
    void createAndSave_savesEntity() {
        Notification n = new Notification();
        n.setId(1L);
        when(repo.save(any(Notification.class))).thenReturn(n);

        Notification result = service.createAndSave(
            NotificationType.TASK_ASSIGNED, "标题", "内容",
            "TASK", 10L, 1L, 2L, null, null);

        assertThat(result.getId()).isEqualTo(1L);
        verify(repo).save(any(Notification.class));
    }

    @Test
    void markAsRead_notFound_throws() {
        when(repo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markAsRead(99L, 1L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("通知不存在");
    }

    @Test
    void markAsRead_setsReadAndTime() {
        Notification n = new Notification();
        n.setId(1L);
        n.setIsRead(0);
        when(repo.findById(1L)).thenReturn(Optional.of(n));
        when(repo.save(any())).thenReturn(n);

        service.markAsRead(1L, 1L);

        assertThat(n.getIsRead()).isEqualTo(1);
        assertThat(n.getReadAt()).isNotNull();
        verify(repo).save(n);
    }
}
```

- [ ] **Step 4: 运行测试确认失败（Service 尚未实现）**

Run: `cd pams-backend && ./mvnw test -pl . -Dtest=NotificationServiceTest -q`
Expected: 编译失败，NotificationService 不存在

- [ ] **Step 5: 实现 NotificationService**

```java
package com.pams.module.notification.service;

import com.pams.common.BizException;
import com.pams.module.notification.dto.NotificationVO;
import com.pams.module.notification.entity.Notification;
import com.pams.module.notification.entity.NotificationType;
import com.pams.module.notification.repository.NotificationRepository;
import com.pams.module.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class NotificationService {

    private final NotificationRepository repo;
    private final UserRepository userRepo;

    public NotificationService(NotificationRepository repo, UserRepository userRepo) {
        this.repo = repo;
        this.userRepo = userRepo;
    }

    @Transactional
    public Notification createAndSave(NotificationType type, String title, String content,
                                      String entityType, Long entityId, Long senderId,
                                      Long recipientId, String recipientRole, Long recipientDeptId) {
        Notification n = new Notification();
        n.setType(type);
        n.setTitle(title);
        n.setContent(content);
        n.setEntityType(entityType);
        n.setEntityId(entityId);
        n.setSenderId(senderId);
        n.setRecipientId(recipientId);
        n.setRecipientRole(recipientRole);
        n.setRecipientDeptId(recipientDeptId);
        n.setIsRead(0);
        n.setDeleted(0);
        n.setCreatedAt(LocalDateTime.now());
        return repo.save(n);
    }

    public List<NotificationVO> findForUser(Long userId, String roleCode, Long deptId) {
        List<Notification> list = repo.findForUser(userId, roleCode, deptId);
        return list.stream().map(this::toVO).toList();
    }

    public long countUnreadForUser(Long userId, String roleCode, Long deptId) {
        return repo.countUnreadForUser(userId, roleCode, deptId);
    }

    @Transactional
    public void markAsRead(Long id, Long userId) {
        Notification n = repo.findById(id)
            .orElseThrow(() -> new BizException(2050, "通知不存在"));
        if (n.getIsRead() == 0) {
            n.setIsRead(1);
            n.setReadAt(LocalDateTime.now());
            repo.save(n);
        }
    }

    @Transactional
    public void markAllAsRead(Long userId, String roleCode, Long deptId) {
        repo.markAllAsRead(userId, roleCode, deptId, LocalDateTime.now());
    }

    private NotificationVO toVO(Notification n) {
        NotificationVO vo = new NotificationVO();
        vo.setId(n.getId());
        vo.setType(n.getType().name());
        vo.setTitle(n.getTitle());
        vo.setContent(n.getContent());
        vo.setEntityType(n.getEntityType());
        vo.setEntityId(n.getEntityId());
        vo.setRead(n.getIsRead() == 1);
        vo.setCreatedAt(n.getCreatedAt());
        // senderName 通过 senderId 查 UserRepository 获取
        if (n.getSenderId() != null) {
            userRepo.findById(n.getSenderId())
                .ifPresent(u -> vo.setSenderName(u.getRealName()));
        }
        return vo;
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `cd pams-backend && ./mvnw test -pl . -Dtest=NotificationServiceTest -q`
Expected: 3 tests PASS

- [ ] **Step 7: Commit**

```bash
git add pams-backend/src/main/java/com/pams/module/notification/dto/ \
       pams-backend/src/main/java/com/pams/module/notification/repository/ \
       pams-backend/src/main/java/com/pams/module/notification/service/ \
       pams-backend/src/test/java/com/pams/module/notification/
git commit -m "feat(notification): Repository + Service + VO 实现及测试"
```

---

## Task 3: 通知 REST API

**Files:**
- Create: `pams-backend/src/main/java/com/pams/module/notification/controller/NotificationController.java`
- Modify: `pams-backend/src/main/java/com/pams/module/notification/service/NotificationService.java` (如果需要添加 LIMIT 分页)

**Interfaces:**
- Consumes: `NotificationService.findForUser()`, `countUnreadForUser()`, `markAsRead()`, `markAllAsRead()`
- Produces: REST endpoints:
  - `GET /api/notifications` → `Result<List<NotificationVO>>` (当前用户的通知，限最近 50 条)
  - `GET /api/notifications/unread-count` → `Result<Long>`
  - `PUT /api/notifications/{id}/read` → `Result<Void>`
  - `PUT /api/notifications/read-all` → `Result<Void>`

- [ ] **Step 1: 创建 NotificationController**

```java
package com.pams.module.notification.controller;

import com.pams.common.Result;
import com.pams.module.notification.dto.NotificationVO;
import com.pams.module.notification.service.NotificationService;
import com.pams.security.LoginUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<NotificationVO>> list(@AuthenticationPrincipal LoginUser user) {
        List<NotificationVO> list = service.findForUser(user.getId(), user.getRoleCode(), user.getDeptId());
        // 限制返回最近 50 条
        if (list.size() > 50) {
            list = list.subList(0, 50);
        }
        return Result.ok(list);
    }

    @GetMapping("/unread-count")
    public Result<Long> unreadCount(@AuthenticationPrincipal LoginUser user) {
        return Result.ok(service.countUnreadForUser(user.getId(), user.getRoleCode(), user.getDeptId()));
    }

    @PutMapping("/{id}/read")
    public Result<Void> markAsRead(@PathVariable Long id, @AuthenticationPrincipal LoginUser user) {
        service.markAsRead(id, user.getId());
        return Result.ok();
    }

    @PutMapping("/read-all")
    public Result<Void> markAllAsRead(@AuthenticationPrincipal LoginUser user) {
        service.markAllAsRead(user.getId(), user.getRoleCode(), user.getDeptId());
        return Result.ok();
    }
}
```

- [ ] **Step 2: 启动应用，用 curl 验证 API**

```bash
# 先登录获取 token
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"zhuren","password":"123456"}' | jq -r '.data.token')

# 获取通知列表（应为空数组）
curl -s http://localhost:8080/api/notifications \
  -H "Authorization: Bearer $TOKEN" | jq

# 获取未读数（应为 0）
curl -s http://localhost:8080/api/notifications/unread-count \
  -H "Authorization: Bearer $TOKEN" | jq
```

- [ ] **Step 3: Commit**

```bash
git add pams-backend/src/main/java/com/pams/module/notification/controller/
git commit -m "feat(notification): REST API — 列表/未读数/标记已读"
```

---

## Task 4: 业务事件定义

**Files:**
- Create: `pams-backend/src/main/java/com/pams/module/notification/event/TaskAssignedEvent.java`
- Create: `pams-backend/src/main/java/com/pams/module/notification/event/PlanSubmittedEvent.java`
- Create: `pams-backend/src/main/java/com/pams/module/notification/event/PlanReviewedEvent.java`

**Interfaces:**
- Produces: 三个事件类，供 TaskService 和 PlanService 发布，NotificationEventListener 监听

- [ ] **Step 1: 创建 TaskAssignedEvent**

```java
package com.pams.module.notification.event;

public class TaskAssignedEvent {
    private final Long taskId;
    private final Long activityId;
    private final Long deptId;
    private final String taskName;
    private final Long senderId;

    public TaskAssignedEvent(Long taskId, Long activityId, Long deptId, String taskName, Long senderId) {
        this.taskId = taskId;
        this.activityId = activityId;
        this.deptId = deptId;
        this.taskName = taskName;
        this.senderId = senderId;
    }

    public Long getTaskId() { return taskId; }
    public Long getActivityId() { return activityId; }
    public Long getDeptId() { return deptId; }
    public String getTaskName() { return taskName; }
    public Long getSenderId() { return senderId; }
}
```

- [ ] **Step 2: 创建 PlanSubmittedEvent**

```java
package com.pams.module.notification.event;

public class PlanSubmittedEvent {
    private final Long planId;
    private final Long activityId;
    private final String planTitle;
    private final Long submitterId;

    public PlanSubmittedEvent(Long planId, Long activityId, String planTitle, Long submitterId) {
        this.planId = planId;
        this.activityId = activityId;
        this.planTitle = planTitle;
        this.submitterId = submitterId;
    }

    public Long getPlanId() { return planId; }
    public Long getActivityId() { return activityId; }
    public String getPlanTitle() { return planTitle; }
    public Long getSubmitterId() { return submitterId; }
}
```

- [ ] **Step 3: 创建 PlanReviewedEvent**

```java
package com.pams.module.notification.event;

public class PlanReviewedEvent {
    private final Long planId;
    private final Long activityId;
    private final String planTitle;
    private final Long reviewerId;
    private final boolean approved;
    private final String comment;

    public PlanReviewedEvent(Long planId, Long activityId, String planTitle,
                             Long reviewerId, boolean approved, String comment) {
        this.planId = planId;
        this.activityId = activityId;
        this.planTitle = planTitle;
        this.reviewerId = reviewerId;
        this.approved = approved;
        this.comment = comment;
    }

    public Long getPlanId() { return planId; }
    public Long getActivityId() { return activityId; }
    public String getPlanTitle() { return planTitle; }
    public Long getReviewerId() { return reviewerId; }
    public boolean isApproved() { return approved; }
    public String getComment() { return comment; }
}
```

- [ ] **Step 4: Commit**

```bash
git add pams-backend/src/main/java/com/pams/module/notification/event/
git commit -m "feat(notification): 业务事件定义 — TaskAssigned/PlanSubmitted/PlanReviewed"
```

---

## Task 5: UserRepository 查询方法 + 事件监听器

**Files:**
- Modify: `pams-backend/src/main/java/com/pams/module/user/repository/UserRepository.java` — 添加 `findByDeptId()` 和 `findByRoleCode()`
- Create: `pams-backend/src/main/java/com/pams/module/notification/listener/NotificationEventListener.java`

**Interfaces:**
- Consumes: `TaskAssignedEvent`, `PlanSubmittedEvent`, `PlanReviewedEvent`, `NotificationService.createAndSave()`, `UserRepository.findByDeptId()`, `UserRepository.findByRoleCode()`
- Produces: `NotificationEventListener` — 监听三个事件，创建通知记录（WebSocket 推送在 Task 7 中接入）

- [ ] **Step 1: 在 UserRepository 中添加查询方法**

在 `UserRepository.java` 中添加：

```java
List<User> findByDeptId(Long deptId);
List<User> findByRoleCode(String roleCode);
```

确认 `UserRepository` 继承了 `JpaRepository<User, Long>`（方法名派生查询即可，无需 @Query）。

- [ ] **Step 2: 创建 NotificationEventListener**

```java
package com.pams.module.notification.listener;

import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.notification.entity.NotificationType;
import com.pams.module.notification.event.PlanReviewedEvent;
import com.pams.module.notification.event.PlanSubmittedEvent;
import com.pams.module.notification.event.TaskAssignedEvent;
import com.pams.module.notification.service.NotificationService;
import com.pams.module.user.entity.User;
import com.pams.module.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.util.List;

@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationService notificationService;
    private final UserRepository userRepo;
    private final ActivityRepository activityRepo;

    public NotificationEventListener(NotificationService notificationService,
                                     UserRepository userRepo,
                                     ActivityRepository activityRepo) {
        this.notificationService = notificationService;
        this.userRepo = userRepo;
        this.activityRepo = activityRepo;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTaskAssigned(TaskAssignedEvent event) {
        String activityName = getActivityName(event.getActivityId());
        List<User> members = userRepo.findByDeptId(event.getDeptId());
        for (User member : members) {
            if (!member.getId().equals(event.getSenderId())) {
                notificationService.createAndSave(
                    NotificationType.TASK_ASSIGNED,
                    "新任务指派",
                    "为您所在部门指派了任务「" + event.getTaskName() + "」（活动：" + activityName + "）",
                    "TASK", event.getTaskId(), event.getSenderId(),
                    member.getId(), null, null
                );
            }
        }
        log.info("TaskAssigned 通知已发送给部门 {} 的 {} 名成员", event.getDeptId(), members.size());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePlanSubmitted(PlanSubmittedEvent event) {
        String activityName = getActivityName(event.getActivityId());
        // 通知 TEACHER 和 DIRECTOR 角色
        List<User> reviewers = new java.util.ArrayList<>();
        reviewers.addAll(userRepo.findByRoleCode("TEACHER"));
        reviewers.addAll(userRepo.findByRoleCode("DIRECTOR"));
        // 去重（理论上不会重复，但安全起见）
        reviewers = reviewers.stream().distinct().toList();
        for (User reviewer : reviewers) {
            notificationService.createAndSave(
                NotificationType.PLAN_SUBMITTED,
                "策划书待审核",
                "提交了活动「" + activityName + "」的策划书，请审核",
                "PLAN", event.getPlanId(), event.getSubmitterId(),
                null, reviewer.getRoleCode(), null
            );
        }
        log.info("PlanSubmitted 通知已发送给 {} 名审核人", reviewers.size());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePlanReviewed(PlanReviewedEvent event) {
        String activityName = getActivityName(event.getActivityId());
        if (event.isApproved()) {
            // 通过 → 通知全员（排除审核人自己）
            List<User> allUsers = userRepo.findAll();
            for (User user : allUsers) {
                if (!user.getId().equals(event.getReviewerId())) {
                    notificationService.createAndSave(
                        NotificationType.PLAN_APPROVED,
                        "策划书审核通过",
                        "活动「" + activityName + "」的策划书已审核通过",
                        "PLAN", event.getPlanId(), event.getReviewerId(),
                        user.getId(), null, null
                    );
                }
            }
            log.info("PlanApproved 通知已发送给 {} 名用户", allUsers.size() - 1);
        } else {
            // 驳回 → 仅通知提交人（通过 submitterId 查找，需要从 plan 获取）
            // PlanReviewedEvent 需要携带 submitterId
            // 注意：需要修改事件类添加 submitterId 字段
            notificationService.createAndSave(
                NotificationType.PLAN_REJECTED,
                "策划书已驳回",
                "活动「" + activityName + "」的策划书已驳回" +
                    (event.getComment() != null ? "，原因：" + event.getComment() : ""),
                "PLAN", event.getPlanId(), event.getReviewerId(),
                event.getSubmitterId(), null, null
            );
            log.info("PlanRejected 通知已发送给提交人 {}", event.getSubmitterId());
        }
    }

    private String getActivityName(Long activityId) {
        if (activityId == null) return "未知活动";
        return activityRepo.findById(activityId)
            .map(Activity::getName)
            .orElse("未知活动");
    }
}
```

> **注意**: `PlanReviewedEvent` 需要添加 `submitterId` 字段用于驳回时定向通知。请在 Task 4 的事件类中补充该字段。同时 `handlePlanSubmitted` 中按角色推送使用 `recipientRole` 字段而非 `recipientId`。

- [ ] **Step 3: 更新 PlanReviewedEvent 添加 submitterId**

```java
// 在 PlanReviewedEvent 中添加:
private final Long submitterId;

// 构造函数新增参数:
public PlanReviewedEvent(Long planId, Long activityId, String planTitle,
                         Long reviewerId, boolean approved, String comment, Long submitterId) {
    // ...
    this.submitterId = submitterId;
}

public Long getSubmitterId() { return submitterId; }
```

- [ ] **Step 4: 编写事件监听器测试**

```java
package com.pams.module.notification;

import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.notification.entity.NotificationType;
import com.pams.module.notification.event.TaskAssignedEvent;
import com.pams.module.notification.event.PlanSubmittedEvent;
import com.pams.module.notification.event.PlanReviewedEvent;
import com.pams.module.notification.listener.NotificationEventListener;
import com.pams.module.notification.service.NotificationService;
import com.pams.module.user.entity.User;
import com.pams.module.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

class NotificationEventListenerTest {

    NotificationService notificationService;
    UserRepository userRepo;
    ActivityRepository activityRepo;
    NotificationEventListener listener;

    @BeforeEach
    void setup() {
        notificationService = mock(NotificationService.class);
        userRepo = mock(UserRepository.class);
        activityRepo = mock(ActivityRepository.class);
        listener = new NotificationEventListener(notificationService, userRepo, activityRepo);
    }

    @Test
    void handleTaskAssigned_notifiesDeptMembersExceptSender() {
        User member1 = new User(); member1.setId(2L);
        User member2 = new User(); member2.setId(3L);
        User sender = new User(); sender.setId(1L);
        when(userRepo.findByDeptId(10L)).thenReturn(List.of(member1, member2, sender));
        Activity activity = new Activity(); activity.setName("测试活动");
        when(activityRepo.findById(100L)).thenReturn(Optional.of(activity));

        listener.handleTaskAssigned(new TaskAssignedEvent(1L, 100L, 10L, "布置会场", 1L));

        // 应该给 member1 和 member2 发通知，不给 sender 发
        verify(notificationService, times(2)).createAndSave(
            eq(NotificationType.TASK_ASSIGNED), anyString(), anyString(),
            eq("TASK"), eq(1L), eq(1L), anyLong(), isNull(), isNull()
        );
    }

    @Test
    void handlePlanSubmitted_notifiesTeacherAndDirector() {
        User teacher = new User(); teacher.setId(10L); teacher.setRoleCode("TEACHER");
        User director = new User(); director.setId(11L); director.setRoleCode("DIRECTOR");
        when(userRepo.findByRoleCode("TEACHER")).thenReturn(List.of(teacher));
        when(userRepo.findByRoleCode("DIRECTOR")).thenReturn(List.of(director));
        Activity activity = new Activity(); activity.setName("测试活动");
        when(activityRepo.findById(100L)).thenReturn(Optional.of(activity));

        listener.handlePlanSubmitted(new PlanSubmittedEvent(1L, 100L, "策划书", 5L));

        verify(notificationService, times(2)).createAndSave(
            eq(NotificationType.PLAN_SUBMITTED), anyString(), anyString(),
            eq("PLAN"), eq(1L), eq(5L), isNull(), anyString(), isNull()
        );
    }

    @Test
    void handlePlanRejected_notifiesSubmitterOnly() {
        Activity activity = new Activity(); activity.setName("测试活动");
        when(activityRepo.findById(100L)).thenReturn(Optional.of(activity));

        listener.handlePlanReviewed(new PlanReviewedEvent(
            1L, 100L, "策划书", 11L, false, "内容不完整", 5L));

        verify(notificationService).createAndSave(
            eq(NotificationType.PLAN_REJECTED), anyString(), contains("内容不完整"),
            eq("PLAN"), eq(1L), eq(11L), eq(5L), isNull(), isNull()
        );
    }
}
```

- [ ] **Step 5: 运行测试**

Run: `cd pams-backend && ./mvnw test -pl . -Dtest=NotificationEventListenerTest -q`
Expected: 3 tests PASS

- [ ] **Step 6: Commit**

```bash
git add pams-backend/src/main/java/com/pams/module/notification/listener/ \
       pams-backend/src/main/java/com/pams/module/notification/event/PlanReviewedEvent.java \
       pams-backend/src/main/java/com/pams/module/user/repository/UserRepository.java \
       pams-backend/src/test/java/com/pams/module/notification/NotificationEventListenerTest.java
git commit -m "feat(notification): 事件监听器 + UserRepository 查询方法"
```

---

## Task 6: 业务接入 — 在 TaskService 和 PlanService 中发布事件

**Files:**
- Modify: `pams-backend/src/main/java/com/pams/module/activity/service/TaskService.java:57-74` — create() 方法
- Modify: `pams-backend/src/main/java/com/pams/module/activity/service/PlanService.java:72-82` — submit() 方法
- Modify: `pams-backend/src/main/java/com/pams/module/activity/service/PlanService.java:93-111` — review() 方法

**Interfaces:**
- Consumes: `ApplicationEventPublisher`, `TaskAssignedEvent`, `PlanSubmittedEvent`, `PlanReviewedEvent`
- Produces: 业务操作后自动触发通知事件

- [ ] **Step 1: 修改 TaskService.create() 发布事件**

在 TaskService 中：

1. 添加字段和构造器参数：`private final ApplicationEventPublisher eventPublisher;`
2. 在 `create()` 方法的 `taskRepo.save(task)` 之后添加：

```java
// 发布任务指派事件
if (task.getDeptId() != null) {
    eventPublisher.publishEvent(new TaskAssignedEvent(
        task.getId(), task.getActivityId(), task.getDeptId(), task.getName(), /* senderId 需要从上下文获取 */ null
    ));
}
```

> **注意**: `senderId` 需要从 Controller 层的 `@AuthenticationPrincipal LoginUser` 传递到 Service。在 `create()` 方法签名中添加 `Long senderId` 参数，Controller 中传入 `user.getId()`。

- [ ] **Step 2: 修改 TaskController 传递当前用户 ID**

在 `TaskController.create()` 方法中添加 `@AuthenticationPrincipal LoginUser user` 参数，调用 `service.create(req, user.getId())`。

- [ ] **Step 3: 修改 PlanService.submit() 发布事件**

在 `PlanService.submit()` 方法的 `planRepo.save(plan)` 之后添加：

```java
// 发布策划书提交事件
eventPublisher.publishEvent(new PlanSubmittedEvent(
    plan.getId(), plan.getActivityId(), /* planTitle 从 activity 获取 */ "", submitterId
));
```

同样需要在 `submit()` 方法签名中添加 `Long submitterId` 参数。

- [ ] **Step 4: 修改 PlanService.review() 发布事件**

在 `PlanService.review()` 方法的 `planRepo.save(plan)` 之后、return 之前添加：

```java
// 发布策划书审核事件
String planTitle = activityRepo.findById(plan.getActivityId())
    .map(Activity::getName).orElse("未知活动");
Long submitterId = plan.getSubmitterId();
eventPublisher.publishEvent(new PlanReviewedEvent(
    plan.getId(), plan.getActivityId(), planTitle, reviewerId, approved, comment, submitterId
));
```

- [ ] **Step 5: 修改 PlanController 传递当前用户 ID**

在 `PlanController.submit()` 中添加 `@AuthenticationPrincipal LoginUser user`，传入 `user.getId()` 作为 submitterId。
在 `PlanController.review()` 中确认已有 `@AuthenticationPrincipal LoginUser user`，传入 `user.getId()` 作为 reviewerId。

- [ ] **Step 6: 启动应用，手动验证端到端**

```bash
# 1. 登录主任账号
TOKEN_ZHUREN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"zhuren","password":"123456"}' | jq -r '.data.token')

# 2. 登录组织部长账号
TOKEN_ORG=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"orgleader","password":"123456"}' | jq -r '.data.token')

# 3. 主任创建一个活动（获取 activityId）
# 4. 主任在甘特图中创建任务（指定 deptId 为组织部）
# 5. 检查组织部长的通知列表是否收到 TASK_ASSIGNED 通知
curl -s http://localhost:8080/api/notifications \
  -H "Authorization: Bearer $TOKEN_ORG" | jq
```

- [ ] **Step 7: Commit**

```bash
git add pams-backend/src/main/java/com/pams/module/activity/service/TaskService.java \
       pams-backend/src/main/java/com/pams/module/activity/service/PlanService.java \
       pams-backend/src/main/java/com/pams/module/activity/controller/TaskController.java \
       pams-backend/src/main/java/com/pams/module/activity/controller/PlanController.java
git commit -m "feat(notification): 在 TaskService/PlanService 中发布业务事件"
```

---

## Task 7: WebSocket 配置

**Files:**
- Modify: `pams-backend/pom.xml` — 添加 spring-boot-starter-websocket
- Create: `pams-backend/src/main/java/com/pams/config/WebSocketConfig.java`
- Create: `pams-backend/src/main/java/com/pams/security/WebSocketAuthInterceptor.java`
- Modify: `pams-backend/src/main/java/com/pams/config/SecurityConfig.java` — 放行 WebSocket 端点

**Interfaces:**
- Consumes: 现有 JWT 工具类 `JwtUtil`
- Produces: STOMP WebSocket 端点 `/ws`，认证拦截器，用户私有队列 `/user/queue/notifications`

- [ ] **Step 1: 在 pom.xml 中添加 WebSocket 依赖**

在 `<dependencies>` 中添加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
```

- [ ] **Step 2: 创建 WebSocketAuthInterceptor**

```java
package com.pams.security;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtUtil jwtUtil;

    public WebSocketAuthInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String token = accessor.getFirstNativeHeader("Authorization");
            if (token != null && token.startsWith("Bearer ")) {
                token = token.substring(7);
                if (jwtUtil.validateToken(token)) {
                    String username = jwtUtil.getUsernameFromToken(token);
                    // 创建一个简单的认证对象，principal 为 username
                    UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(username, null, List.of());
                    accessor.setUser(auth);
                }
            }
        }
        return message;
    }
}
```

- [ ] **Step 3: 创建 WebSocketConfig**

```java
package com.pams.config;

import com.pams.security.WebSocketAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor authInterceptor;

    public WebSocketConfig(WebSocketAuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/queue", "/topic");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}
```

- [ ] **Step 4: 在 SecurityConfig 中放行 WebSocket 端点**

在 `SecurityConfig.java` 的 `securityFilterChain` 中添加：

```java
.requestMatchers("/ws/**").permitAll()
```

- [ ] **Step 5: 在 NotificationEventListener 中添加 WebSocket 推送**

注入 `SimpMessagingTemplate`，在创建通知后推送：

```java
private final SimpMessagingTemplate messagingTemplate;

// 构造器中添加 SimpMessagingTemplate 参数

// 在每个 handle* 方法中，创建通知后添加：
// 对个人通知：
messagingTemplate.convertAndSendToUser(
    recipientUsername, "/queue/notifications", toVO(notification));

// 对角色/部门通知：需要先查出用户列表，逐个推送
```

> **注意**: `convertAndSendToUser` 的第一个参数是 username（String），不是 userId。需要在查用户列表时使用 `user.getUsername()` 作为 Principal。
> 
> 为了简化，可以让 WebSocket 推送通知前端"有新通知"（不携带完整数据），前端收到信号后调 REST API 刷新列表。这样推送的 payload 只需 `{ type: "NEW_NOTIFICATION", unreadCount: N }`。

- [ ] **Step 6: 启动应用验证 WebSocket 连接**

用浏览器开发者工具或 wscat 测试：

```bash
# 安装 wscat（如果没有）
npm install -g wscat

# 连接 WebSocket（需要先通过 SockJS 的 info 端点）
curl http://localhost:8080/ws/info | jq
```

- [ ] **Step 7: Commit**

```bash
git add pams-backend/pom.xml \
       pams-backend/src/main/java/com/pams/config/WebSocketConfig.java \
       pams-backend/src/main/java/com/pams/security/WebSocketAuthInterceptor.java \
       pams-backend/src/main/java/com/pams/config/SecurityConfig.java \
       pams-backend/src/main/java/com/pams/module/notification/listener/
git commit -m "feat(notification): WebSocket STOMP 配置 + 认证拦截器 + 实时推送"
```

---

## Task 8: 前端依赖 + API + Store

**Files:**
- Modify: `pams-web/package.json` — 添加 sockjs-client, @stomp/stompjs, @types/sockjs-client
- Create: `pams-web/src/api/notification.ts`
- Create: `pams-web/src/stores/notification.ts`

**Interfaces:**
- Produces: `NotificationVO` interface, API functions: `listNotifications()`, `getUnreadCount()`, `markAsRead(id)`, `markAllAsRead()`
- Produces: `useNotificationStore` Zustand store with: unreadCount, notifications, loading, fetchUnreadCount(), fetchNotifications(), markAsRead(), markAllAsRead(), addRealtimeNotification()

- [ ] **Step 1: 安装前端依赖**

```bash
cd pams-web && npm install sockjs-client @stomp/stompjs && npm install -D @types/sockjs-client
```

- [ ] **Step 2: 创建 notification.ts API**

```typescript
import { get, put } from './http'

export interface NotificationVO {
  id: number
  type: string
  title: string
  content: string
  entityType: string | null
  entityId: number | null
  senderName: string | null
  read: boolean
  createdAt: string
}

export const listNotifications = () => get<NotificationVO[]>('/notifications')
export const getUnreadCount = () => get<number>('/notifications/unread-count')
export const markAsRead = (id: number) => put<void>(`/notifications/${id}/read`)
export const markAllAsRead = () => put<void>('/notifications/read-all')
```

- [ ] **Step 3: 创建 notification.ts Store**

```typescript
import { create } from 'zustand'
import type { NotificationVO } from '@/api/notification'
import * as api from '@/api/notification'

interface NotificationState {
  unreadCount: number
  notifications: NotificationVO[]
  loading: boolean
  fetchUnreadCount: () => Promise<void>
  fetchNotifications: () => Promise<void>
  markAsRead: (id: number) => Promise<void>
  markAllAsRead: () => Promise<void>
  addRealtimeNotification: (n: NotificationVO) => void
  setUnreadCount: (count: number) => void
}

export const useNotificationStore = create<NotificationState>()((set, get) => ({
  unreadCount: 0,
  notifications: [],
  loading: false,

  fetchUnreadCount: async () => {
    try {
      const count = await api.getUnreadCount()
      set({ unreadCount: count ?? 0 })
    } catch {
      // http interceptor handles error
    }
  },

  fetchNotifications: async () => {
    set({ loading: true })
    try {
      const list = await api.listNotifications()
      set({ notifications: list ?? [] })
    } catch {
      // http interceptor handles error
    } finally {
      set({ loading: false })
    }
  },

  markAsRead: async (id: number) => {
    try {
      await api.markAsRead(id)
      set((state) => ({
        notifications: state.notifications.map((n) =>
          n.id === id ? { ...n, read: true } : n,
        ),
        unreadCount: Math.max(0, state.unreadCount - 1),
      }))
    } catch {
      // http interceptor handles error
    }
  },

  markAllAsRead: async () => {
    try {
      await api.markAllAsRead()
      set((state) => ({
        notifications: state.notifications.map((n) => ({ ...n, read: true })),
        unreadCount: 0,
      }))
    } catch {
      // http interceptor handles error
    }
  },

  addRealtimeNotification: (n: NotificationVO) => {
    set((state) => ({
      notifications: [n, ...state.notifications].slice(0, 50),
      unreadCount: state.unreadCount + 1,
    }))
  },

  setUnreadCount: (count: number) => set({ unreadCount: count }),
}))
```

- [ ] **Step 4: 前端编译验证**

Run: `cd pams-web && npm run build`
Expected: 无编译错误

- [ ] **Step 5: Commit**

```bash
git add pams-web/package.json pams-web/package-lock.json \
       pams-web/src/api/notification.ts \
       pams-web/src/stores/notification.ts
git commit -m "feat(notification): 前端 API + Zustand Store + WebSocket 依赖"
```

---

## Task 9: WebSocket 连接 Hook + 实时弹窗

**Files:**
- Create: `pams-web/src/hooks/useWebSocket.ts`
- Create: `pams-web/src/components/notification/NotificationToast.tsx`

**Interfaces:**
- Consumes: `useAuthStore` (token), `useNotificationStore` (addRealtimeNotification, setUnreadCount)
- Produces: `useWebSocket()` hook — 管理 STOMP 连接生命周期

- [ ] **Step 1: 创建 useWebSocket Hook**

```typescript
import { useEffect, useRef } from 'react'
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notification'
import { notification } from 'antd'
import type { NotificationVO } from '@/api/notification'

export function useWebSocket() {
  const token = useAuthStore((s) => s.token)
  const addRealtime = useNotificationStore((s) => s.addRealtimeNotification)
  const setUnreadCount = useNotificationStore((s) => s.setUnreadCount)
  const clientRef = useRef<Client | null>(null)

  useEffect(() => {
    if (!token) return

    const client = new Client({
      webSocketFactory: () => new SockJS('/ws'),
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        console.log('[WS] 已连接')
        client.subscribe('/user/queue/notifications', (msg) => {
          try {
            const data = JSON.parse(msg.body)
            if (data.type === 'NEW_NOTIFICATION') {
              // 简单信号：刷新未读数
              setUnreadCount(data.unreadCount ?? 0)
              // 拉取最新通知列表
              useNotificationStore.getState().fetchNotifications()
              // 弹窗提示
              notification.info({
                message: data.title ?? '新通知',
                description: data.content,
                placement: 'topRight',
                duration: 3,
              })
            } else {
              // 完整通知对象
              const n = data as NotificationVO
              addRealtime(n)
              notification.info({
                message: n.title,
                description: n.content,
                placement: 'topRight',
                duration: 3,
              })
            }
          } catch (e) {
            console.error('[WS] 解析通知消息失败', e)
          }
        })
      },
      onDisconnect: () => {
        console.log('[WS] 已断开')
      },
      onStompError: (frame) => {
        console.error('[WS] STOMP 错误', frame.headers['message'])
      },
    })

    client.activate()
    clientRef.current = client

    return () => {
      client.deactivate()
      clientRef.current = null
    }
  }, [token, addRealtime, setUnreadCount])
}
```

- [ ] **Step 2: 前端编译验证**

Run: `cd pams-web && npm run build`
Expected: 无编译错误

- [ ] **Step 3: Commit**

```bash
git add pams-web/src/hooks/useWebSocket.ts \
       pams-web/src/components/notification/NotificationToast.tsx
git commit -m "feat(notification): WebSocket 连接 Hook + 实时通知弹窗"
```

---

## Task 10: 通知铃铛组件 + MainLayout 集成

**Files:**
- Create: `pams-web/src/components/notification/NotificationBell.tsx`
- Modify: `pams-web/src/layouts/MainLayout.tsx:107-134` — Header 区域插入 NotificationBell

**Interfaces:**
- Consumes: `useNotificationStore`, `useWebSocket()` hook
- Produces: `<NotificationBell />` 组件 — Badge 角标 + Popover 下拉列表

- [ ] **Step 1: 创建 NotificationBell 组件**

```tsx
import { Badge, Popover, List, Button, Spin, Typography, Space } from 'antd'
import { BellOutlined, CheckOutlined } from '@ant-design/icons'
import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useNotificationStore } from '@/stores/notification'
import type { NotificationVO } from '@/api/notification'
import dayjs from 'dayjs'

const { Text } = Typography

export default function NotificationBell() {
  const [open, setOpen] = useState(false)
  const navigate = useNavigate()
  const { unreadCount, notifications, loading, fetchNotifications, fetchUnreadCount, markAsRead, markAllAsRead } =
    useNotificationStore()

  useEffect(() => {
    fetchUnreadCount()
  }, [fetchUnreadCount])

  const handleOpenChange = useCallback(
    (visible: boolean) => {
      setOpen(visible)
      if (visible) {
        fetchNotifications()
      }
    },
    [fetchNotifications],
  )

  const handleClick = useCallback(
    async (n: NotificationVO) => {
      if (!n.read) {
        await markAsRead(n.id)
      }
      setOpen(false)
      // 跳转到对应页面
      if (n.entityType === 'TASK' && n.entityId) {
        // 需要 activityId，暂跳转活动列表
        navigate(`/activities`)
      } else if (n.entityType === 'PLAN' && n.entityId) {
        navigate(`/activities`)
      }
    },
    [markAsRead, navigate],
  )

  const handleMarkAll = useCallback(async () => {
    await markAllAsRead()
  }, [markAllAsRead])

  const getNotificationIcon = (type: string) => {
    switch (type) {
      case 'TASK_ASSIGNED': return '📋'
      case 'PLAN_SUBMITTED': return '📝'
      case 'PLAN_APPROVED': return '✅'
      case 'PLAN_REJECTED': return '❌'
      default: return '🔔'
    }
  }

  const content = (
    <div style={{ width: 360, maxHeight: 400, overflow: 'auto' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8, padding: '0 4px' }}>
        <Text strong>通知</Text>
        {unreadCount > 0 && (
          <Button type="link" size="small" icon={<CheckOutlined />} onClick={handleMarkAll}>
            全部已读
          </Button>
        )}
      </div>
      {loading ? (
        <div style={{ textAlign: 'center', padding: 20 }}><Spin /></div>
      ) : notifications.length === 0 ? (
        <div style={{ textAlign: 'center', padding: 20, color: 'var(--color-text-secondary)' }}>
          暂无通知
        </div>
      ) : (
        <List
          dataSource={notifications.slice(0, 20)}
          renderItem={(n: NotificationVO) => (
            <List.Item
              onClick={() => handleClick(n)}
              style={{
                cursor: 'pointer',
                padding: '8px 12px',
                backgroundColor: n.read ? 'transparent' : 'var(--color-bg-elevated)',
                borderRadius: 6,
              }}
            >
              <Space direction="vertical" size={2} style={{ width: '100%' }}>
                <Space>
                  <span>{getNotificationIcon(n.type)}</span>
                  <Text strong={!n.read} style={{ fontSize: 13 }}>{n.title}</Text>
                  {!n.read && (
                    <span style={{ width: 6, height: 6, borderRadius: '50%', background: '#1677ff', display: 'inline-block' }} />
                  )}
                </Space>
                <Text type="secondary" style={{ fontSize: 12 }} ellipsis={{ tooltip: n.content }}>
                  {n.content}
                </Text>
                <Text type="secondary" style={{ fontSize: 11 }}>
                  {dayjs(n.createdAt).fromNow?.() ?? dayjs(n.createdAt).format('MM-DD HH:mm')}
                </Text>
              </Space>
            </List.Item>
          )}
        />
      )}
    </div>
  )

  return (
    <Popover
      content={content}
      trigger="click"
      open={open}
      onOpenChange={handleOpenChange}
      placement="bottomRight"
      arrow={false}
    >
      <Badge count={unreadCount} size="small" offset={[-2, 2]}>
        <BellOutlined style={{ fontSize: 18, cursor: 'pointer', color: 'var(--color-text)' }} />
      </Badge>
    </Popover>
  )
}
```

- [ ] **Step 2: 在 MainLayout 中集成**

在 `MainLayout.tsx` 中：

1. 添加 import：
```tsx
import NotificationBell from '@/components/notification/NotificationBell'
import { useWebSocket } from '@/hooks/useWebSocket'
```

2. 在组件函数体开头调用 Hook：
```tsx
useWebSocket()  // 登录后自动连接 WebSocket
```

3. 在 Header 的 `<Space>` 中，`<ThemeSwitch />` 之后、用户 `<Dropdown>` 之前插入：
```tsx
<NotificationBell />
```

- [ ] **Step 3: 前端编译验证**

Run: `cd pams-web && npm run build`
Expected: 无编译错误

- [ ] **Step 4: Commit**

```bash
git add pams-web/src/components/notification/NotificationBell.tsx \
       pams-web/src/layouts/MainLayout.tsx
git commit -m "feat(notification): 通知铃铛组件 + MainLayout 集成"
```

---

## Task 11: 端到端集成验证

**Files:** 无新增文件，验证现有实现

- [ ] **Step 1: 启动后端**

```bash
cd pams-backend && ./mvnw spring-boot:run
```

- [ ] **Step 2: 启动前端**

```bash
cd pams-web && npm run dev
```

- [ ] **Step 3: 验证流程 1 — 主任发布任务 → 组织部收到通知**

1. 浏览器登录 `zhuren` (主任)
2. 进入某个活动的甘特图页面
3. 创建一个任务，负责部门选"组织部"
4. 浏览器切换到无痕窗口，登录 `orgleader` (组织部长)
5. 检查右上角铃铛是否有未读角标
6. 点击铃铛，确认看到"新任务指派"通知

- [ ] **Step 4: 验证流程 2 — 组织部提交策划书 → 主任/指导老师收到通知**

1. 在 `orgleader` 窗口，进入活动详情的策划书 Tab
2. 创建策划书 → 填写内容 → 点击"提交审核"
3. 切换到 `zhuren` (主任) 窗口，确认铃铛有新通知"策划书待审核"
4. 切换到 `teacher` (指导老师) 窗口，确认铃铛有新通知"策划书待审核"

- [ ] **Step 5: 验证流程 3 — 主任驳回 → 组织部收到修改通知**

1. 在 `zhuren` 窗口，审核策划书，点击"驳回"，填写驳回原因
2. 切换到 `orgleader` 窗口，确认铃铛有新通知"策划书已驳回"，内容包含驳回原因

- [ ] **Step 6: 验证流程 4 — 主任审核通过 → 全员收到通知**

1. 在 `orgleader` 窗口，修改策划书后重新提交审核
2. 在 `zhuren` 窗口，审核策划书，点击"通过"
3. 分别登录不同角色账号，确认所有用户都收到"策划书审核通过"通知

- [ ] **Step 7: 验证已读功能**

1. 点击某条未读通知 → 确认变已读，角标数减 1
2. 点击"全部已读" → 确认角标归零，所有通知变已读

- [ ] **Step 8: 验证 WebSocket 实时推送**

1. 保持页面打开状态
2. 用另一个窗口触发通知（如提交策划书）
3. 确认当前页面无需刷新即弹出通知弹窗，角标实时更新

- [ ] **Step 9: 最终 Commit**

```bash
git add -A
git commit -m "feat(notification): 业务通知子系统完成 — 站内通知 + WebSocket 实时推送"
```
