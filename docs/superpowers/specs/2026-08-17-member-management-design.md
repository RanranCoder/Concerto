# 成员管理模块（成员花名册）— 设计文档

> **日期**: 2026-08-17
> **状态**: 已获用户批准
> **需求来源**: 用户提供《第九届党建办公室干部干事信息登记表.xlsx》字段结构，要求新增成员管理模块

## 背景与目标

党建办公室每年换届，人员花名册（干部+干事）散落在 Excel 里手工维护（第九届登记表 66 人，含退部/被退部手工标注）。系统需要一套**成员花名册**：通过 Excel 导入或手动添加的方式管理成员，支持多届别、正式状态、统计与换届归档，并能在用户管理中一键从花名册注册登录账号。

**目标**：
1. 成员花名册独立成表，多届别可切换，支持完整 CRUD + Excel 导入导出。
2. 正式状态字段（在职/往届/退部/开除/离职）替代 Excel 手工标注。
3. 用户管理新增「从花名册一键导入注册账号」，衔接花名册与登录账号体系。
4. 成员详情页本期完整实现（含系统内参与记录聚合）。

## 需求决策记录（已与用户逐项确认）

| # | 决策点 | 结论 |
|---|--------|------|
| 1 | 花名册与登录账号关系 | **独立花名册**，不侵入 sys_user；用户在「用户管理」里手动从花名册一键导入注册账号 |
| 2 | 届别维度 | **支持多届别**（换届保留历史档案） |
| 3 | 成员状态 | **正式状态字段**：在职 / 往届 / 退部 / 开除 / 离职 |
| 4 | 查看/管理权限 | **仅干部可见**（主任、四部门部长、指导老师）；干事不可见 |
| 5 | 职位字段 | **固定枚举**：主任 / 副主任 / 部长 / 副部长 / 干事 |
| 6 | 数据模型方案 | **方案 B**：member 表 + member_session 届别字典表 |
| 7 | 业务范围 | 人数统计卡片、换届批量归档、模板下载+导入失败反馈、成员详情页（**本期完整做**，不等后期） |

## 设计

### 1. 数据模型（Flyway V13）

**迁移 `V13__member.sql`**（`pams-backend/src/main/resources/db/migration/`）：

```sql
CREATE TABLE IF NOT EXISTS member_session (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(50) NOT NULL UNIQUE COMMENT '届名，如"第九届"',
  is_current TINYINT DEFAULT 0 COMMENT '是否当前届',
  sort_order INT DEFAULT 0,
  remark VARCHAR(255),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS member (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL COMMENT '所属届别',
  dept_id BIGINT COMMENT '部门，主任/副主任为空',
  position VARCHAR(20) NOT NULL COMMENT 'DIRECTOR/SUB_DIRECTOR/DEPT_HEAD/SUB_DEPT_HEAD/STAFF',
  name VARCHAR(50) NOT NULL COMMENT '姓名',
  gender VARCHAR(2) COMMENT '男/女',
  student_no VARCHAR(30) COMMENT '学号',
  class_name VARCHAR(100) COMMENT '班级',
  phone VARCHAR(20) COMMENT '联系方式',
  political_status VARCHAR(20) COMMENT '政治面貌(中文)',
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ALUMNI/RESIGNED/EXPELLED/LEFT',
  remark VARCHAR(255),
  created_by BIGINT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  CONSTRAINT fk_member_session FOREIGN KEY (session_id) REFERENCES member_session(id),
  CONSTRAINT fk_member_dept FOREIGN KEY (dept_id) REFERENCES sys_department(id),
  UNIQUE KEY uk_member_session_student (session_id, student_no)
);
```

**枚举映射**（后端常量 + 前端标签）：

| 中文 | position 枚举 | status 枚举 |
|------|--------------|------------|
| 主任 | DIRECTOR | — |
| 副主任 | SUB_DIRECTOR | — |
| 部长 | DEPT_HEAD | — |
| 副部长 | SUB_DEPT_HEAD | — |
| 干事 | STAFF | — |
| 在职 | — | ACTIVE |
| 往届 | — | ALUMNI |
| 退部 | — | RESIGNED |
| 开除 | — | EXPELLED |
| 离职 | — | LEFT |

- `political_status` 存中文（共青团员/群众/无党派人士/中共党员/中共预备党员），导入时归一化 `团员→共青团员`。
- `gender` 存 `男`/`女`。
- 唯一键 `(session_id, student_no)`：同届内学号不重复；学号为 NULL 时 MySQL 唯一索引不生效，允许多条无学号成员。

### 2. 后端模块 `com.pams.module.member`

```
member/
├── entity/       Member, MemberSession
├── repository/   MemberRepository, MemberSessionRepository
├── dto/          MemberVO, MemberQuery, MemberRequest, MemberImportResultVO,
│                 MemberStatsVO, MemberSessionRequest, AccountImportResultVO
├── service/      MemberService, MemberImportService, MemberAccountImportService
└── controller/   MemberController, MemberSessionController
```

**接口清单**（控制器类级 `@PreAuthorize` 干部白名单 `hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')`，复用 `ActivityController.LEADER` 常量）：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/members` | 分页查询：sessionId、deptId、position、status、keyword（姓名/学号/手机号）筛选，默认当前届 |
| GET | `/api/members/{id}` | 成员详情（含统计聚合） |
| POST | `/api/members` | 新增成员 |
| PUT | `/api/members/{id}` | 编辑成员 |
| DELETE | `/api/members/{id}` | 删除成员（软删除 `deleted=1`） |
| POST | `/api/members/batch-delete` | 批量删除 |
| POST | `/api/members/import` | Excel 批量导入（multipart：sessionId + file） |
| GET | `/api/members/import/template` | 下载导入模板 xlsx |
| GET | `/api/members/export` | 按当前筛选导出 xlsx |
| POST | `/api/members/{sessionId}/archive` | 换届批量归档 |
| GET | `/api/members/stats` | 人数统计：总人数 / 部门分布 / 职位分布 / 状态分布 |
| GET | `/api/members/unregistered?sessionId=` | 列出该届未注册成员（学号在 sys_user 中无匹配的），供一键导入账号预览 |
| POST | `/api/members/import-accounts` | 从花名册导入注册账号（body: sessionId + memberIds[] + roleOverrides），返回创建/跳过统计 |
| GET | `/api/member-sessions` | 届别列表（is_current 排序优先） |
| POST | `/api/member-sessions` | 新增届别 |
| PUT | `/api/member-sessions/{id}` | 编辑届别 |
| DELETE | `/api/member-sessions/{id}` | 删除届别（届下已有成员则拒绝） |
| POST | `/api/member-sessions/{id}/set-current` | 设为当前届 |

**换届批量归档**：`UPDATE member SET status=ALUMNI WHERE session_id=? AND status=ACTIVE`，返回归档人数；前端二次确认后执行。仅操作指定届别，不影响其他届。

### 3. Excel 导入 / 导出

**模板列**（与用户表格一致）：`序号 | 部门 | 职位 | 姓名 | 性别 | 学号 | 班级 | 联系方式 | 政治面貌`。状态不在模板内，导入默认 `在职`。

**导入解析**（复用 party 模块 `RosterImportService` 的 `findHeaderRow`/`locateColumns`/`cellStr` 模式）：
1. POI `WorkbookFactory.create` 打开，扫描表头行（找「姓名/学号」单元格），按表头文本映射列 → 忽略列顺序、跳过标题行。
2. **部门列前向填充**：合并单元格导致部门只在每组首行出现，解析时遇到空部门沿用上一行的值。
3. 映射归一化：`团员→共青团员`；部门名→`sys_department`（部门列为「主任」「副主任」「主任室」或空 → dept_id 为空，其余按部门名精确匹配）；职位中文→枚举。
4. 去重：文件内 + 库内按「届别+学号」双重去重，重复记为跳过。
5. 逐行校验：姓名必填、部门/职位可识别，失败行收集 `{行号, 姓名, 原因}`。
6. 返回 `MemberImportResultVO{ total, success, skipped, failed:List<ImportFileFailureVO> }`，前端弹窗展示失败明细。

**导出**：按当前筛选导出，列 = 模板 9 列 + 状态列，导出的文件可直接再导入。

### 4. 前端

**API**：`pams-web/src/api/member.ts`（类型 + 薄函数，复用 `http.ts` 的 `get/post` 与 `PageResult<T>`）。

**页面**：
- `src/pages/member/MemberList.tsx` — 成员管理主列表页：
  - 顶部**届别切换**（Segmented，当前届默认选中）+「届别管理」按钮（届别增删改、设为当前届）
  - **统计卡片行**：总人数 / 各部门人数 / 各状态人数（调 `/api/members/stats`）
  - 工具栏：新增成员、导入 Excel（模板下载 + 结果报告弹窗）、导出、换届归档（二次确认）、批量删除
  - 筛选：部门 / 职位 / 状态 / 关键词（姓名·学号·手机号）
  - 表格（`GlassTable`）：姓名、部门、职位、性别、班级、学号、联系方式、政治面貌、状态（`Tag` 颜色区分）、操作（详情/编辑/删除）
  - 新增/编辑弹窗（`GlassModal` + `Form`，`destroyOnHidden` 需 `initialValues` 回填）：部门（含「主任室」空选项）、职位、届别（新增时锁定当前届）、姓名、性别、学号、班级、联系方式、政治面貌、状态、备注
- `src/pages/member/MemberDetail.tsx` — 成员详情页（见 §5）
- **路由**：`/members` + `/members/:id`，`RequireRole(LEADER_ROLES)` 包裹
- **菜单**：侧边栏顶层新增「成员管理」（`roleLevel >= 3` 显示），置于「排班考勤」之后
- 组件复用 `GlassCard/GlassTable/GlassModal/PageHeader`，样式沿用现有 design system

**用户管理一键导入**（`src/pages/admin/UserList.tsx` 加按钮「从花名册导入账号」，TEACHER/DIRECTOR）：
1. 弹窗选届别 → 加载**未注册成员**（学号在 sys_user 中无匹配的）
2. 勾选成员，预览表按职位自动映射角色（主任→DIRECTOR、部长→对应部门部长角色、副部长→对应部门部长角色、干事→STAFF），角色可逐行改
3. 确认 → 后端批量建账号，返回创建/跳过统计；已注册的自动跳过

### 5. 成员详情页 `/members/:id`

- **基础信息卡**：姓名、部门、职位、届别、状态标签、性别、学号、班级、联系方式、政治面貌、备注、创建人/时间
- **统计概览**：排班次数 / 考勤记录数 / 素拓累计分（小卡片）
- **素拓记录列表**：按 `studentNo` 精确聚合 `credit_record`（该表有 studentNo 字段）
- **排班/考勤记录列表**：按 `personName` 姓名聚合 `schedule_person`/`attendance`（尽力而为，两表只有 userId+姓名无学号）
- 页内可编辑、可快捷改状态（退部/开除）

### 6. 权限矩阵

| 角色 | 查看成员 | 管理成员 | 届别管理 | 一键导入账号 |
|------|:---:|:---:|:---:|:---:|
| 指导老师 TEACHER | ✅ | ✅ | ✅ | ✅ |
| 主任 DIRECTOR | ✅ | ✅ | ✅ | ✅ |
| 四部门部长 | ✅ | ✅ | ✅ | ❌ |
| 干事 STAFF | ❌ 403 | ❌ | ❌ | ❌ |

后端 `@PreAuthorize` 强制 + 前端 `RequireRole` + 菜单过滤三层保障。

### 7. 测试

**后端**（`pams-backend/src/test`）：
- `MemberServiceTest`：CRUD、筛选分页、软删除、换届归档（在职→往届）、届别删除拒绝
- `MemberImportServiceTest`：正常导入、合并单元格前向填充、去重（文件内+库内）、失败行报告、部门名映射
- `MemberAccountImportServiceTest`：未注册匹配、建号成功、已注册跳过、角色映射
- 全量 `mvn test` 通过（现有 124+ 用例不受影响）

**前端**：`npm run build`（tsc + vite）通过。

## 验收标准

1. 干部登录可进入「成员管理」，干事访问返回 403。
2. 可从登记表 Excel 导入 66 人，合并单元格部门正确填充，重复导入不产生重复，失败行有明确原因。
3. 可按届别/部门/职位/状态筛选分页，统计卡片数字与列表一致。
4. 换届归档把当前届全部在职批量置为往届，二次确认。
5. 用户管理可勾选未注册成员一键建账号（用户名=学号，密码 123456），已注册自动跳过。
6. 成员详情页展示基础信息 + 排班/考勤/素拓记录聚合。
7. 后端 `mvn test` + 前端 `npm run build` 全绿。

## 非目标

- 不做成员与 sys_user 的强关联（花名册独立，仅靠一键导入衔接）。
- 不做成员自助（成员本人无法登录查看/编辑自己的档案）。
- 不做排班/签到数据的双向联动（仅详情页只读聚合）。
- 不引入通知触发点（本期成员管理不产生站内通知）。
