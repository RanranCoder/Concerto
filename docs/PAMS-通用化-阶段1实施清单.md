# PAMS 通用化 · 阶段 1（先建列）实施清单

> **配套文档**：[PAMS-通用化与多Agent协作改造策划.md](PAMS-通用化与多Agent协作改造策划.md) §9.6（D6 三段式）
> **本阶段目标**：让「组织注册 + 架构自定义」功能**完整可演示**，同时**为多租户把列建好、数据归属写对**，但**先不开自动过滤**（过滤留到阶段 2 / 批次 A3）。
> **对齐事实**：实体约 53 个、仓储 53 个、裸 SQL 仅 3 处（知识库）；全库无实体继承 `BaseEntity`（该类为死代码，不可依赖）；迁移范式见 `V25`（`information_schema` 判存 + `PREPARE` 动态 DDL）。

---

## 0. 范围与设计原则

| 原则 | 说明 |
|---|---|
| **只写不过滤** | 本阶段所有表加 `org_id` 并正确写入；**不启用** `@TenantId`/`@Filter`，查询行为与现状完全一致 → 零回归风险 |
| **单轨不双轨** | 组织架构**不新建 `sys_department` 之外的第二套部门表**——把 `sys_department` **原地演进**为架构树（加 `parent_id/unit_type/...`）。理由：项目已因 `seat_map` 与 `seat_layout` 双轨吃过亏（见业务设计方案「座位表双轨收敛」） |
| **不动 ORM 查询** | 阶段 1 不改任何 Repository 方法、不改 Service 查询——隔离靠阶段 2 的声明式过滤 |
| **org 不进 JWT** | 租户从 `sys_user.org_id` 回源读取（见策划 §9.5），与现有「每请求查库」安全哲学一致 |

---

## 1. 交付物总览

| 类别 | 交付物 | 数量 |
|---|---|---|
| Flyway 迁移 | `V30__organization.sql`、`V31__tenant_org_columns.sql` | 2（+1 可选） |
| 新增实体 | `Organization`、`OrgPosition`、`OrgInvitation`（`OrgUnit` = 演进后的 `Department`） | 3 |
| 改实体 | 约 **50** 张租户表对应的实体加 `orgId`（其中 `Role`/`TemplateAsset` 为「双层」可空） | ~50 |
| 后端基础设施 | `OrgContext`、`TenantFillListener`、`OrgScoped`、`OrgContextFilter` | 4 |
| 改鉴权/配置 | `LoginUser`、`JwtAuthenticationFilter`、`AuthController`、`DataSeeder`、`SecurityConfig` | 5 |
| 组织 API | `OrgController` + `OrgService` + 2 Repository | 1 套 |
| 前端 | `api/org.ts`、`OrgRegister`、`OrgStructure`、路由/菜单 | 3+ |

---

## 2. Flyway 迁移清单

### 2.1 `V30__organization.sql` — 组织表 + `sys_department` 演进

> 沿用 `V25` 的幂等范式（`information_schema` 判存 + `PREPARE`）。全新库与已有库都能跑。

```sql
-- ===================== 组织（租户）基座 =====================

CREATE TABLE IF NOT EXISTS organization (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL COMMENT '组织全称',
  type VARCHAR(30) NOT NULL DEFAULT 'OTHER'
    COMMENT 'PARTY/STUDENT_UNION/CLUB/VOLUNTEER/SCIENCE/OTHER',
  short_name VARCHAR(50),
  logo_url VARCHAR(255),
  theme_json JSON NULL COMMENT '主题令牌：{primaryColor,...}',
  enabled_packs JSON NULL COMMENT '启用的领域包：["party","volunteer"]',
  terminology_json JSON NULL COMMENT '术语表：{activity:"活动",member:"成员",...}',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  owner_user_id BIGINT NULL COMMENT '注册人/负责人',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织（租户）';

CREATE TABLE IF NOT EXISTS org_position (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  org_id BIGINT NOT NULL,
  name VARCHAR(50) NOT NULL COMMENT '职务名：主席/部长/干事',
  level INT NOT NULL DEFAULT 1,
  role_code VARCHAR(30) NOT NULL COMMENT '映射系统角色码 TEACHER/DIRECTOR/*_LEADER/STAFF',
  sort_order INT DEFAULT 0,
  INDEX idx_org_pos_org (org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织-职务';

CREATE TABLE IF NOT EXISTS org_invitation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  org_id BIGINT NOT NULL,
  code VARCHAR(64) NOT NULL,
  role_code VARCHAR(30) NOT NULL,
  org_unit_id BIGINT NULL COMMENT '预约部门（=sys_department.id）',
  expire_at DATETIME NULL,
  used_by BIGINT NULL,
  used_at DATETIME NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_org_inv_code (code),
  INDEX idx_org_inv_org (org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织-邀请入组';

-- 默认组织（承接现有全部数据）
INSERT INTO organization (id, name, type, short_name, status)
SELECT 1, '默认组织', 'OTHER', '默认', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM organization WHERE id = 1);

-- ---------------------------------------------------------------------------
-- 列级幂等助手：MySQL 无 ADD COLUMN IF NOT EXISTS。
-- ⚠️ 刻意「逐列判存」，不用「判一次 org_id 就把 7 列一起加」的写法——
--    若中途失败或部分列已存在，后者会把后面的列整批跳过（漏列）。
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS pams_add_col;
DELIMITER $$
CREATE PROCEDURE pams_add_col(IN p_tbl VARCHAR(64), IN p_col VARCHAR(64), IN p_ddl VARCHAR(500))
BEGIN
  DECLARE c INT DEFAULT 0;
  SELECT COUNT(*) INTO c FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_tbl AND COLUMN_NAME = p_col;
  IF c = 0 THEN
    SET @s = p_ddl; PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END$$
DELIMITER ;

CALL pams_add_col('sys_department','org_id',
  'ALTER TABLE sys_department ADD COLUMN org_id BIGINT NOT NULL DEFAULT 1 COMMENT ''组织ID'' AFTER id');
CALL pams_add_col('sys_department','parent_id',
  'ALTER TABLE sys_department ADD COLUMN parent_id BIGINT NULL COMMENT ''上级架构节点'' AFTER org_id');
CALL pams_add_col('sys_department','unit_type',
  'ALTER TABLE sys_department ADD COLUMN unit_type VARCHAR(20) NOT NULL DEFAULT ''部门'' COMMENT ''部门/中心/小组/分队'' AFTER name');
CALL pams_add_col('sys_department','code',
  'ALTER TABLE sys_department ADD COLUMN code VARCHAR(50) NULL AFTER unit_type');
CALL pams_add_col('sys_department','level',
  'ALTER TABLE sys_department ADD COLUMN level INT NOT NULL DEFAULT 1 AFTER code');
CALL pams_add_col('sys_department','path',
  'ALTER TABLE sys_department ADD COLUMN path VARCHAR(255) NULL COMMENT ''物化路径 /1/3/7'' AFTER level');
CALL pams_add_col('sys_department','leader_user_id',
  'ALTER TABLE sys_department ADD COLUMN leader_user_id BIGINT NULL AFTER path');
DROP PROCEDURE pams_add_col;

-- name 由「全局唯一」降级为普通列：按「列名 + 唯一性」定位索引，不硬编码索引名
-- （库可能由 init 脚本建、索引名与 V1 不同）
SET @idx = (
  SELECT INDEX_NAME FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_department'
    AND COLUMN_NAME = 'name' AND NON_UNIQUE = 0
  LIMIT 1
);
SET @ddl = IF(@idx IS NOT NULL,
  CONCAT('ALTER TABLE sys_department DROP INDEX `', @idx, '`'),
  'SELECT ''sys_department.name 唯一索引已处理'' AS note');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
```

> **注**：`sys_department` 的 `name` 原为列级 `UNIQUE`（V1 实证）。阶段 1 先降级为普通列（避免第二个组织建「组织部」时唯一冲突）。**⚠️ 该表在 V1 中无 `deleted` 列**——复合唯一键应为 `(org_id, parent_id, name)`，不能写 `deleted`（本文档早期版本的 `(org_id, name, deleted)` 是错的）。

### 2.2 `V31__tenant_org_columns.sql` — 租户表加 `org_id`

> **分两类，默认值不同**（本文档早期版本把两类混为一谈，是缺陷）：
> - **① 租户数据表（48 张）** → `org_id BIGINT NOT NULL DEFAULT 1`（存量/未显式写入自动归默认组织）
> - **② 双层表（2 张：`sys_role`、`template_asset`）** → `org_id BIGINT NULL`（**内置项为全局 `NULL`**，不能被默认组织独占；组织自定义项才带组织 ID）
>
> **实现**：MySQL 无 `ADD COLUMN IF NOT EXISTS` → 存储过程判存；且**先把待处理表名物化到临时表再游标遍历**，避免「遍历 `information_schema` 的同时执行 DDL」导致的游标失效（DDL 隐式提交）。

```sql
-- 复用 §2.1 的列级助手（V31 内重新声明一次，保持脚本自包含）
DROP PROCEDURE IF EXISTS pams_add_col;
DELIMITER $$
CREATE PROCEDURE pams_add_col(IN p_tbl VARCHAR(64), IN p_col VARCHAR(64), IN p_ddl VARCHAR(500))
BEGIN
  DECLARE c INT DEFAULT 0;
  SELECT COUNT(*) INTO c FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_tbl AND COLUMN_NAME = p_col;
  IF c = 0 THEN
    SET @s = p_ddl; PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END$$

CREATE PROCEDURE pams_add_org_id()
BEGIN
  DECLARE done INT DEFAULT 0;
  DECLARE tname VARCHAR(64);
  DECLARE tcnt INT DEFAULT 0;
  DECLARE cur CURSOR FOR SELECT tname FROM tmp_org_tables;
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

  -- 先物化待处理表名（不直接游标 information_schema）
  CREATE TEMPORARY TABLE IF NOT EXISTS tmp_org_tables (tname VARCHAR(64) PRIMARY KEY);
  INSERT IGNORE INTO tmp_org_tables (tname) VALUES
      ('sys_user'),
      ('activity'),('activity_plan'),('activity_agenda'),('seat_map'),('score_rule'),
      ('score_record'),('signin'),('task'),
      ('signin_roster'),('signin_field_config'),('sign_in_group'),
      ('schedule'),('schedule_person'),('attendance'),('attendance_audit'),('no_class_schedule'),
      ('course_schedule'),('schedule_config'),
      ('party_member'),('party_stage'),('party_roster'),('party_investigation'),
      ('party_register'),('party_transfer'),
      ('article'),('news'),
      ('file_record'),('material'),('credit_record'),
      ('announcement'),('announcement_read'),('seat_layout'),
      ('group_chat'),('group_chat_category'),('group_chat_department'),
      ('member_session'),('member'),
      ('notification'),
      ('agent_conversation'),('agent_message'),('agent_task'),('agent_tool_call'),('agent_audit_log'),
      ('kb_document'),('kb_chunk'),('kb_retrieval_log'),('kb_snapshot');

  OPEN cur;
  read_loop: LOOP
    FETCH cur INTO tname;
    IF done = 1 THEN LEAVE read_loop; END IF;
    -- 临时表可能含尚未建出的表：存在才加列
    SELECT COUNT(*) INTO tcnt FROM information_schema.TABLES
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tname AND TABLE_TYPE = 'BASE TABLE';
    IF tcnt > 0 THEN
      CALL pams_add_col(tname, 'org_id',
        CONCAT('ALTER TABLE `', tname, '` ADD COLUMN org_id BIGINT NOT NULL DEFAULT 1 COMMENT ''租户组织ID'''));
    END IF;
  END LOOP;
  CLOSE cur;
  DROP TEMPORARY TABLE IF EXISTS tmp_org_tables;
END$$
DELIMITER ;

CALL pams_add_org_id();

-- ② 双层表：内置为全局 NULL，组织自定义才带组织 ID（不加 DEFAULT 1）
CALL pams_add_col('sys_role','org_id',
  'ALTER TABLE sys_role ADD COLUMN org_id BIGINT NULL COMMENT ''内置角色为NULL(全局)，自定义角色为组织ID''');
CALL pams_add_col('template_asset','org_id',
  'ALTER TABLE template_asset ADD COLUMN org_id BIGINT NULL COMMENT ''内置模板为NULL(全局)，组织自定义为组织ID''');

DROP PROCEDURE pams_add_org_id;
DROP PROCEDURE pams_add_col;
```

- **`DEFAULT 1`**：存量数据、DDL 后新写入（未显式设 org 时）自动归属默认组织 → 阶段 1 零代码即让多数数据带 org 归属。
- 全新库同样适用（表存在即有列）。
- **不在列表**（全局表）：`permission`、`role_permission`（全局权限目录，见策划 §9.3）。
- **注**：`sys_role` 加列后，**存量内置角色 `org_id` 为 `NULL`**（符合「全局共享」语义）；**`PermissionService.permissionsOfRole(roleCode)` 按角色码查询，天然全局，不受影响**。

### 2.3 `V32__org_unique_keys.sql`（**可选，或并入阶段 2**）

仅当同一部署内**开始出现第二个组织的同名数据**时才需要。建议**留到阶段 2**，与「开启过滤」同批做：

| 约束 | 现状（V1–V28 实证） | 改法 |
|---|---|---|
| `sys_department`（现为架构树） | 列级 `UNIQUE(name)`（**该表无 `deleted` 列**） | 新复合键 `(org_id, parent_id, name)` |
| `member_session.name` | `uk_member_session_name (name, deleted)` + 实体 `unique=true` | `(org_id, name, deleted)` |
| `sys_user.username` | 列级 `UNIQUE(username)` | 拍板：全局唯一（简单）或 `(org_id, username)` |
| `sys_role.code` | 列级 `UNIQUE(code)` | 内置 `org_id IS NULL` 全局唯一；自定义 `(org_id, code)` |
| **`kb_chunk`** | `uk_kb_chunk_source (source_key, seq)` | `(org_id, source_key, seq)` |
| **`kb_snapshot`** | `uk_kb_snapshot (spec_code, scope_type, scope_id, as_of)` | `(org_id, spec_code, scope_type, scope_id, as_of)` |
| `uk_no_class_dept_semester (dept_id, semester)` / `uk_member_session_student (session_id, student_no, deleted)` | 已含组织域内列 | **无需改** |

---

## 3. 实体改动清单

### 3.1 新增实体（`module/org/entity/`）

| 实体 | 表 | 关键字段 |
|---|---|---|
| `Organization` | `organization` | `id, name, type, shortName, logoUrl, themeJson, enabledPacks, terminologyJson, status, ownerUserId, createdAt, updatedAt, deleted` |
| `OrgPosition` | `org_position` | `id, orgId, name, level, roleCode, sortOrder` |
| `OrgInvitation` | `org_invitation` | `id, orgId, code, roleCode, orgUnitId, expireAt, usedBy, usedAt, createdAt` |

### 3.2 `Department` 实体演进（原地，不新建表）

`com/pams/entity/Department.java` 加字段（对应迁移 V30 的新列）：

```java
private Long orgId;
private Long parentId;
private String unitType;      // 部门/中心/小组/分队
private String code;
private Integer level;
private String path;          // 物化路径 /1/3/7
private Long leaderUserId;
```

> `User.dept`（`@ManyToOne Department`）、`Task.deptId`、`Material.deptId`、`GroupChatDepartment` 等**全部不动**——这正是「原地演进」的收益。
> **⚠️ `Department` 也必须 `implements OrgScoped`**（`public class Department implements OrgScoped`），否则 §4.1 的 `@PrePersist` 回填不生效。

### 3.3 租户实体加 `orgId`（~50 个）

统一改法（以 `Activity` 为例）：新增一行字段 + 实现 `OrgScoped`。

```java
// 现有
private Integer deleted;
// 新增
private Long orgId;
```

```java
public class Activity implements OrgScoped {  // 新增 implements
```

**清单（按模块）**：

| 模块 | 实体 |
|---|---|
| 用户组织 | `User`、`Role`（**双层**：内置 `org_id=NULL`／自定义带组织 ID）、`Department`（见 3.2） |
| activity | `Activity`、`ActivityPlan`、`ActivityAgenda`、`SeatMap`、`ScoreRule`、`ScoreRecord`、`Signin`、`Task`、`SigninRoster`、`SigninFieldConfig`、`SignInGroup` |
| routine | `Schedule`、`SchedulePerson`、`Attendance`、`AttendanceAudit` |
| schedule | `CourseSchedule`、`ScheduleConfig`、`NoClassScheduleRecord` |
| party | `PartyMember`、`PartyStage`、`PartyRoster`、`PartyInvestigation`、`PartyRegister`、`PartyTransfer` |
| content | `Article`、`News` |
| archive | `FileRecord`、`Material`、`TemplateAsset`、`CreditRecord`、`Announcement`、`AnnouncementRead` |
| seat | `SeatLayout` |
| chat | `GroupChat`、`GroupChatCategory`、`GroupChatDepartment` |
| member | `MemberSession`、`Member` |
| notification | `Notification` |
| agent | `AgentConversation`、`AgentMessage`、`AgentTask`、`AgentToolCall`、`AgentAuditLog` |
| knowledge | `KbDocument`、`KbChunk`、`KbRetrievalLog`、`KbSnapshot` |

> **例外（可省列，靠父表继承）**：纯子表 `GroupChatDepartment`、`GroupChatCategory`（若永远随 `GroupChat` 存取）可不加，减少改动面。

---

## 4. 后端基础设施（4 个新类 + 4 处改造）

### 4.1 新增基础设施

| 类 | 位置 | 职责 |
|---|---|---|
| `OrgScoped`（接口） | `common/` | `Long getOrgId(); void setOrgId(Long);` —— 标记「租户实体」 |
| `OrgContext` | `common/` | `ThreadLocal<Long>` 当前组织；`get/set/clear`，`getOrDefault()` 兜底默认组织 |
| `TenantFillListener` | `common/` | Hibernate `PreInsertEventListener`：insert 前若实体是 `OrgScoped` 且 `orgId==null` → 从 `OrgContext` 回填 |
| `OrgContextFilter` | `security/` | 请求级设置/清理 `OrgContext`（与 `JwtAuthenticationFilter` 同链，或其内联） |

**注册方式（主选：Hibernate 事件监听，可靠、无需 XML）**：

```java
@Component
public class HibernateEventListenerConfig {
    @PersistenceContext private EntityManager em;   // 或注入 EntityManagerFactory
    @PostConstruct
    void register() {
        em.unwrap(SessionFactory.class).getServiceRegistry()
          .requireService(EventListenerRegistry.class)
          .appendListeners(EventType.PRE_INSERT, tenantFillListener);
    }
}
```

> **备选**：`META-INF/orm.xml` 的 `<persistence-unit-defaults><entity-listeners>`（声明式，但**本项目 Hibernate 版本对该默认监听器的支持需 spike 验证**——编排文档已提示，故不列为主选）。
> 项目当前**不存在** `META-INF/orm.xml`（已核实），故采用 XML 方案也不冲突。

**⚠️ 正确性缺陷（必须记录）：`OrgContext` 回填只对「当前登录用户的组织」成立，对「匿名/系统写入的子行」会错填。**
- 实证：`SigninService.scanSignin` 是**免登录**（`@Transactional`）→ 无登录用户 → `OrgContext` 为空 → 兜底默认组织 → 若该活动属于组织 B，`Signin` 行会被错标为默认组织。
- **正确的语义**：子行应**从父实体继承 org**（`Signin` ← `Activity`）。
- **阶段 1 的处理**：单组织下无差别（都是默认组织），可先不修；但**必须记入阶段 2 待办**，且**测试要如实覆盖**（见 §7 #8）。

### 4.2 改造鉴权链路

| 文件 | 改动 |
|---|---|
| `entity/User.java` | 加 `private Long orgId;`（对应迁移） |
| `security/LoginUser.java` | 加 `private Long orgId;`（构造器加参 → 同步改 `JwtAuthenticationFilter` 构造处） |
| `security/JwtAuthenticationFilter.java` | 构造 `LoginUser` 时传 `u.getOrgId()`；**构造后 `OrgContext.set(orgId)`，请求结束于 `finally` 清理** |
| `controller/AuthController.java` | 登录取 `u.getOrgId()`；`login` 响应与 `/me` 增加 `orgId`/`orgName`（前端品牌用） |
| `security/JwtUtil.java` | **不改**（org 不进 token，见策划 §9.5） |
| `config/DataSeeder.java` | ① 幂等键从 `departmentRepository.count()` 改为「organization 存在即跳过」；② 先种默认组织 id=1；③ `seedDevAccounts` 的部门写为默认架构树节点；④ 演示数据 org 默认 1 |
| **`config/SecurityConfig.java`**（**本文档早期版本漏列**） | `POST /api/orgs/register` 加入 `permitAll`（否则注册接口直接 401）；`/api/orgs/**` 其余保持 `authenticated()` |
| `module/org/entity/Organization.java` | `enabledPacks`/`terminologyJson`/`themeJson` 为 MySQL `JSON` 列 → 用 `@JdbcTypeCode(SqlTypes.JSON)` 或 `@Column(columnDefinition="JSON")` 映射（文档早期未提） |

**⚠️ 注册管理员必须显式设 org**：注册时创建的 `sys_user` 若依赖 `DEFAULT 1`，会归默认组织而非新组织 → `OrgService.register` 必须**显式 `setOrgId(新组织id)`**（`TenantFillListener` 只兜底、不纠错）。

**⚠️ 匿名端点边界**：`POST /api/signins/scan`（`permitAll`）没有登录用户 → `OrgContext` 为空。阶段 1 用 `getOrDefault()` 兜底默认组织即可；**但其子行 org 的正确性见 §4.1 缺陷**，多组织时需让扫码 token 携带 org（记入阶段 2 待办）。

---

## 5. 组织注册与架构 API

### 5.1 接口清单

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | `/api/orgs/register` | **注册组织**（含架构树）→ 创建组织 + 架构节点 + 管理员账号 | 免登录（限流） |
| GET | `/api/orgs/current` | 当前组织信息（品牌/术语/领域包） | 登录 |
| PUT | `/api/orgs/current` | 修改组织信息（名称/Logo/主题/领域包） | ADMIN |
| GET | `/api/orgs/current/units` | 架构树（返回树形） | 登录 |
| POST | `/api/orgs/current/units` | 新增架构节点 | ADMIN |
| PUT | `/api/orgs/current/units/{id}` | 修改节点（改名/换负责人/移动层级） | ADMIN |
| DELETE | `/api/orgs/current/units/{id}` | 删除节点（有子节点/成员时拒绝） | ADMIN |
| POST | `/api/orgs/current/invitations` | 生成邀请码 | ADMIN |

### 5.2 注册请求体（对齐 §2.3.2 四步）

```json
{
  "org": { "name": "信息与智能工程学院学生会", "type": "STUDENT_UNION",
           "shortName": "信工学生会", "themeColor": "#2F6FED" },
  "admin": { "username": "president", "password": "***", "realName": "张三" },
  "units": [
    { "name": "主席团", "unitType": "中心", "children": [
        { "name": "秘书处", "unitType": "部门" },
        { "name": "宣传部", "unitType": "部门" }
    ]}
  ],
  "enabledPacks": ["club"]
}
```

### 5.3 服务端处理（`OrgService.register`）

```
① 事务开始 → 校验组织名/账号唯一
② 落 organization（默认组织信息 + 主题令牌 + enabled_packs + terminology_json）
③ 递归落架构树：写 sys_department(org_id, parent_id, level, path)
④ 按节点类型套权限模板 → 复用 PermissionService.saveRolePermissions() 生成角色映射
⑤ 建管理员 sys_user(org_id=组织id, role=DIRECTOR, dept=根节点)
⑥ 提交；返回组织 + 管理员登录信息
```

> **复用而非新建**：角色/权限完全复用现有 `permission`/`role_permission`（全局目录）+ `PermissionService`；组织架构直接是演进后的 `sys_department`。

---

## 6. 前端

| 文件 | 内容 |
|---|---|
| `src/api/org.ts` | `registerOrg / getCurrentOrg / updateOrg / listUnits / createUnit / updateUnit / deleteUnit / createInvitation` + 类型 |
| `src/pages/org/OrgRegister.tsx` | 四步向导：`Steps` + 基本信息表单 + **架构树编辑器** + 角色模板勾选 + 邀请 |
| `src/pages/org/OrgStructure.tsx` | 架构编辑器独立页：antd `Tree`（`draggable`）+ 节点属性面板（类型/负责人/角色） |
| `src/router/index.tsx` | `/register`（免登录）、`/org/structure`（`RequireRole(ADMIN_ROLES)`） |
| `src/layouts/MainLayout.tsx` | 侧栏加「组织架构」（ADMIN）；品牌色/名称读 `org/current` |
| `src/App.tsx` | `ConfigProvider.colorPrimary` 由组织主题令牌驱动（替换硬编码 `#DE2910`） |

---

## 7. 测试与验收

| # | 项 | 期望 |
|---|---|---|
| 1 | Flyway | `V30/V31` 干净应用；全新库与老库均可跑；重启 `up to date` |
| 2 | 数据归属 | 存量全部行 `org_id=1`；新写入未显式设 org 时自动回填当前组织 |
| 3 | 注册 | 注册学生会 → `organization`/`sys_department` 树/管理员账号全部落库，管理员可登录 |
| 4 | 架构 CRUD | 增删改查架构节点；删除有子节点的节点被拒 |
| 5 | **零回归** | `mvn test` 全绿；登录/活动/签到/党务/灵思 主链路行为与改造前**完全一致**（因为未开过滤） |
| 6 | 匿名边界 | 扫码签到（`/api/signins/scan`）在无 `OrgContext` 时仍按默认组织工作 |
| 7 | 多组织共存 | 第二个组织的账号登录后，`/api/orgs/current` 返回自己的组织（**注：此阶段数据仍可能互见——这是阶段 2 才修的，测试应如实记录**） |
| 8 | **子行 org 继承（已知缺陷）** | 免登录 `scanSignin` 在组织 B 的活动上产生 `Signin` 行时，**当前会被错标为默认组织**（§4.1）——测试应断言此现象并登记为阶段 2 待办，而非当作通过 |

---

## 8. 明确不在阶段 1 做的事（留给阶段 2 / A3）

- ❌ 开启 `@TenantId`/`@Filter` 自动过滤
- ❌ 补 3 处裸 SQL（`UserRepository` native + 知识库 2 处 JdbcTemplate）的 org 谓词
- ❌ 唯一键复合化重构（§2.3）
- ❌ 跨组织越权回归矩阵
- ❌ `@SQLRestriction` 与租户过滤叠加的 `count()` 语义校核
- ❌ 异步线程（`agentExecutor`）的租户上下文传播（阶段 1 有 `DelegatingSecurityContextExecutor` 先例可循）
- ❌ **子行按父实体继承 org**（§4.1 缺陷）——阶段 2 需为免登录/系统写入路径补「父实体推导」
- ❌ 免登录扫码 token 携带 org

---

## 9. 边界与已知坑

| # | 坑 | 处理 |
|---|---|---|
| 1 | **`BaseEntity` 是死代码** | 不要试图把 `orgId` 加到它上面——没实体继承它。用 `OrgScoped` 接口 + Hibernate `PreInsertEventListener`（§4.1） |
| 2 | **MySQL 无 `ADD COLUMN IF NOT EXISTS`** | 用列级助手 `pams_add_col` + `information_schema` 判存（沿用 V25） |
| 3 | **`sys_department.name` 列级 UNIQUE** | V30 按「列名+唯一性」定位并 DROP；复合唯一键留阶段 2 |
| 4 | **Flyway 的 `DELIMITER`** | MySQL 脚本支持；若解析异常，退化为 50 段显式 guarded ALTER |
| 5 | **实体列顺序与 schema 漂移** | 本项目已有 `seat_layout.color_labels` 前车之鉴（V25）→ 本阶段所有加列都用判存范式，天然幂等 |
| 6 | **注册接口免登录** | 必须挂限流（复用 `LoginRateLimiter` 同款思路），防刷注册 |
| 7 | **`OrgContext` 未清理的线程污染** | 必须在 `finally` 清理（Web 线程池复用，否则串租户） |
| 8 | **双层表被 `DEFAULT 1` 误伤**（`sys_role`/`template_asset`） | 见 §2.2：内置项须 `org_id NULL`（全局），不能归默认组织 |
| 9 | **「一次判存加全部列」的漏列** | 见 §2.1：必须逐列判存，否则部分列已存在时整批跳过 |
| 10 | **游标遍历 `information_schema` 同时 DDL** | 见 §2.2：先物化表名到临时表再遍历 |
| 11 | **子行 org 继承缺陷** | 见 §4.1：免登录/系统写入的子行不能靠 `OrgContext` 兜底 |
| 12 | **`SecurityConfig` 未放行注册接口** | 见 §4.2：`/api/orgs/register` 必须 `permitAll` |
| 13 | **`Organization` 的 JSON 列映射** | 见 §4.2：`@JdbcTypeCode(SqlTypes.JSON)` |
| 14 | **`org_invitation.org_unit_id` 悬挂** | 架构节点被删后邀请悬空 → 应用层校验/级联（文档早期未提） |

---

## 10. 审计记录（本文档自身）

> 本节记录对本实施清单的一次自查审计（2026-10）。所有「现状」结论均已对源码/迁移核查。

### 10.1 发现并已修复的缺陷

| # | 缺陷 | 严重度 | 证据 | 处置 |
|---|---|---|---|---|
| A | `sys_role`、`template_asset` 被扫进 `DEFAULT 1` | 高 | 与「内置全局 / 组织自定义」双层设计冲突 | §2.2 拆分：双层表 `org_id NULL` |
| B | `sys_department` 复合唯一键误含 `deleted` 列 | 高 | V1 该表无 `deleted` | §2.1/§2.3 改为 `(org_id, parent_id, name)` |
| C | `OrgContext` 回填对匿名子行错填 org | 高 | `SigninService.scanSignin` 免登录实测 | §4.1 显式记录 + §7 #8 测试 + §8 阶段2 待办 |
| D | V30「判一次 `org_id` 加全部列」漏列 | 中 | 部分列已存在时整批跳过 | §2.1 改逐列判存（`pams_add_col`） |
| E | V31 游标遍历 `information_schema` 同时 DDL | 中 | DDL 隐式提交致游标失效 | §2.2 先物化表名到临时表再遍历 |
| F | 漏掉 `kb_chunk`/`kb_snapshot` 唯一键 | 中 | V27/V28 实证 | §2.3 补入 |
| G | `SecurityConfig` 未列为改动点 | 中 | `permitAll` 需放行注册接口 | §4.2 补行 |
| H | `Department` 未声明 `implements OrgScoped` | 中 | 回填依赖该接口 | §3.2 补注 |
| I | `Organization` JSON 列映射未提 | 低 | `theme_json`/`enabled_packs` 为 JSON | §4.2 补 `@JdbcTypeCode(SqlTypes.JSON)` |
| J | `DROP INDEX name` 硬编码索引名 | 低 | 库可能由 init 脚本建 | §2.1 按列名+唯一性定位 |
| K | 表/实体计数前后不一致（51 vs 50） | 低 | 名单实为 48+2 | §1/§2.2/§3.3 统一为 50 |
| L | `INSERT ... SELECT ... WHERE` 未加 `FROM DUAL` | 低 | 兼容性 | §2.1 加 `FROM DUAL` |
| M | `org_invitation.org_unit_id` 悬挂未提 | 低 | 节点删除后悬空 | §9 #14 记录 |

### 10.2 核查确认「判断正确、无需改」的点

| 判断 | 核查结果 |
|---|---|
| 无实体继承 `BaseEntity` → 需逐实体改 | ✅ 全库仅 `BaseEntity.java` 自身引用该类 |
| 表清单无遗漏 | ✅ `database/init_db.sql` 无 `CREATE TABLE`；无 `notification_preference` 表 |
| 无现存 `META-INF/orm.xml` → 新增不冲突 | ✅ 目录不存在 |
| 裸 SQL 仅 3 处 → 阶段 2 收口可控 | ✅ 与策划 §9.3 一致 |
| `level`/`path` 可作列名 | ✅ 已有 `sys_role.level`、`file_record.path` 先例 |

### 10.3 仍需人工拍板 / 技术验证（未闭合）

1. **`sys_user.username` 唯一域**：全局唯一 vs `(org_id, username)`（§2.3）。
2. **Hibernate 事件监听注册**：`PreInsertEventListener` vs `orm.xml` 默认监听器——**建议先做一次 spike**（§4.1）。
3. **免登录子行的 org 来源**：阶段 2 是「父实体推导」还是「token 携带 org」（§8）。

---

> **文档结束**。本清单与策划 §9.6「阶段 1」一一对应；阶段 2（批次 A3）的施工清单待 D6 最终拍板后另行细化。
