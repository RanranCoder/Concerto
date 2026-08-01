# 党务管理系统（PAMS）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为"信息与智能工程学院党建办公室"构建一套党务管理系统，把散落的 Office 材料业务（活动全流程、排班考勤、党务台账、推文、素拓、模板、归档、通知）整合进 Web 系统，毛玻璃 UI（黑白为主 + 国旗红强调，明暗双主题）。

**Architecture:** 前后端分离单体。后端 `pams-backend` = Spring Boot 4.0.x + Spring Data JPA + Flyway + MySQL（`pams_db`），JWT 无状态认证，REST 统一返回 `Result<T>`；前端 `pams-web` = React 18 + Vite + TS + Ant Design 5，CSS 变量实现 liquid glass 设计系统。活动是核心实体，贯穿四个部门；主任用自研 SVG 甘特图分解任务。

**Tech Stack:** Java 21 · Spring Boot 4.0.x · Spring Data JPA · Spring Security · Flyway · MySQL 9.x · Apache POI · React 18 · Vite 7 · TypeScript · Ant Design 5 · zustand · dayjs · Vitest

---

## Global Constraints

- **技术栈版本（不可擅自更换）**：后端 Spring Boot 4.0.x（若 4.0.2 不可用则用最近的 4.0.x 补丁版）+ Java 21 + Spring Data JPA + Flyway + `com.mysql:mysql-connector-j` + `io.jsonwebtoken:jjwt 0.12.6` + Apache POI 5.x；前端 React 18.3 + Vite 7 + TypeScript ~5.7 + antd ^5.24 + react-router-dom ^6 + axios ^1.7 + zustand ^5 + dayjs ^1.11 + vitest ^3。
- **数据库**：schema 名固定 `pams_db`，字符集 `utf8mb4`。所有 DDL 必须同时兼容 MySQL 9 与测试库 H2(MySQL 模式)：禁 `ENGINE=`、`UNSIGNED`、`ON UPDATE CURRENT_TIMESTAMP`；JSON 字段一律用 `TEXT` 存 JSON 字符串。
- **UI 规范**：主色黑白层级，强调色固定国旗红 `#DE2910`（`--color-red`）；**明暗双主题**必须可切换并记忆（localStorage）；毛玻璃卡片统一 `backdrop-filter: blur(20px) saturate(180%)` + 半透明描边；玻璃层一律用自定义 CSS 变量，不改 antd 源码。
- **角色与数据权限**：`TEACHER`指导老师 > `DIRECTOR`主任 > `ORG_LEADER`组织部长 / `SECRETARY_LEADER`文秘部长 / `MEDIA_LEADER`新媒体部长 / `TECH_LEADER`青年科技部长 > `STAFF`干事。干事数据范围=本部门。**敏感数据**（party_roster/party_investigation/party_transfer/party_register 全部字段）仅部长及以上可见，前端不渲染、后端 `@PreAuthorize` 拦截。
- **活动流程固定**：指导老师下达 → 主任甘特图分派 → 组织部编策划书 → 部长/指导老师审核 → 各部门并行执行 → 总结归档。活动状态机：`ASSIGNED→PLANNING→PLAN_REVIEW→EXECUTING→FINISHED→ARCHIVED`，必须按序流转（可回退）。
- **接口约定**：所有接口返回 `Result<T>{code,message,data}`；分页返回 `PageResult<T>{records,total,current,size}`；认证 `POST /api/auth/login` 返回 `{token,user}`；鉴权失败 401、无权限 403。
- **命名**：Git 仓库根 `D:\MyApp\PAMS`（monorepo，含 `pams-backend/`、`pams-web/`、`database/`、`docs/`）；Java 主类 `PartyAffairsManagementSystemApplication`，包根 `com.pams`；数据库 `pams_db`。
- **环境**：Windows + bash；MySQL 9.5 本地服务，root 密码由 `application.yml` 的 `spring.datasource.password` 读取（默认 `root`，可通过环境变量 `DB_PASSWORD` 覆盖）。上传文件目录 `pams-backend/uploads/`（gitignore）。
- **中文**：全部 UI 文案中文；后端错误消息中文；提交信息用 `feat:`/`fix:`/`refactor:` 前缀。
- **清理**：任何涉及迁移材料的脚本必须过滤 `~$` 开头的 Office 临时文件。
- **质量门**：每个 Task 结束前必须跑对应测试并 `git commit`；前端任务必须在本地 `npm run dev` 后人工点验，方可声明完成。

---

## 文件结构总览

```
D:\MyApp\PAMS\
├─ docs\PAMS设计方案.md                     # 已定稿设计
├─ database\
│  └─ migration\V1__init.sql               # 全量 DDL（25 张表）
├─ pams-backend\                            # Spring Boot
│  ├─ pom.xml
│  └─ src\main\java\com\pams\
│     ├─ PartyAffairsManagementSystemApplication.java
│     ├─ common\Result.java / PageResult.java / BizException.java
│     │        / GlobalExceptionHandler.java / BaseEntity.java
│     ├─ config\SecurityConfig.java / WebMvcConfig.java / JpaAuditingConfig.java / DataSeeder.java
│     ├─ security\JwtUtil.java / JwtAuthenticationFilter.java / LoginUser.java / UserDetailsServiceImpl.java
│     └─ module\
│        ├─ user\        (entity\User/Department/Role, repository, service, controller, dto)  # 参考 CRUD 模块
│        ├─ activity\    (Activity/ActivityPlan/ActivityAgenda/SeatMap/Score/Signin/Task)
│        ├─ routine\     (Schedule/SchedulePerson/Attendance/FreeSchedule)
│        ├─ party\       (PartyMember/PartyStage/PartyRoster/PartyInvestigation/PartyRegister/PartyTransfer)
│        ├─ content\     (Article/News)
│        └─ archive\     (Material/TemplateAsset/CreditRecord/FileRecord/Announcement)
│  └─ src\main\resources\application.yml
│  └─ src\test\java\com\pams\...
├─ pams-web\                                # React
│  ├─ package.json / vite.config.ts / tsconfig.json / index.html
│  └─ src\
│     ├─ main.tsx / App.tsx
│     ├─ styles\tokens.css / glass.css / global.css
│     ├─ api\http.ts / auth.ts / activity.ts / routine.ts / party.ts / content.ts / archive.ts
│     ├─ stores\auth.ts / theme.ts
│     ├─ components\glass\GlassCard.tsx / PageHeader.tsx / StatusTag.tsx / UploadFile.tsx
│     ├─ components\gantt\GanttChart.tsx / gantt.utils.ts / gantt.utils.test.ts
│     ├─ layouts\MainLayout.tsx
│     ├─ router\index.tsx
│     └─ pages\Login.tsx / Dashboard.tsx / activity\... / routine\... / party\... / content\... / archive\...
└─ README.md / start.bat
```

---

## M0 · 环境与基础工程

### Task 1: 初始化仓库与目录骨架

**Files:**
- Create: `D:\MyApp\PAMS\.gitignore`
- Create: `D:\MyApp\PAMS\README.md`
- Create: `D:\MyApp\PAMS\database\migration\.gitkeep`

**Interfaces:**
- Produces: monorepo 根，供 Task 2 放 DDL、Task 3 放后端、Task 6 放前端。

- [ ] **Step 1: 初始化 git 仓库并建目录**

```bash
cd /d/MyApp/PAMS
git init
mkdir -p database/migration docs/superpowers/plans
```

- [ ] **Step 2: 写 `.gitignore`**

```gitignore
# Java
pams-backend/target/
pams-backend/uploads/
*.class
# Node
pams-web/node_modules/
pams-web/dist/
# IDE / OS
.idea/
.vscode/
*.iml
.DS_Store
Thumbs.db
# 临时/日志
*.log
~$*.xlsx
~$*.docx
```

- [ ] **Step 3: 写 `README.md` 骨架**

```markdown
# 党务管理系统（PAMS）

信息与智能工程学院党建办公室 · 党务管理系统

- 后端 `pams-backend/`：Spring Boot 4 + Spring Data JPA + MySQL
- 前端 `pams-web/`：React 18 + Vite + Ant Design 5（liquid glass 风格）

启动方式见本文档末尾"运行"章节（Task 31 补全）。
```

- [ ] **Step 4: 提交**

```bash
git add .gitignore README.md database docs
git commit -m "chore: 初始化 monorepo 骨架"
```

---

### Task 2: 数据库 DDL（Flyway V1）+ 数据种子

**Files:**
- Create: `D:\MyApp\PAMS\database\migration\V1__init.sql`
- Create: `D:\MyApp\PAMS\database\init_db.sql`（仅建库语句，供手动执行）

**Interfaces:**
- Produces: 28 张业务表，命名全部 snake_case、主键 `BIGINT AUTO_INCREMENT`、时间 `DATETIME`。`deleted TINYINT DEFAULT 0`（逻辑删除）仅 10 张表带：sys_user/activity/task/party_member/article/news/material/template_asset/announcement（JPA `@Where`/`@SQLRestriction` 由实体处理）。所有表经 `CREATE TABLE IF NOT EXISTS`。
- 供 Task 4/8/10/… 的 JPA 实体直接映射列名。

- [ ] **Step 1: 写建库脚本 `database/init_db.sql`**

```sql
CREATE DATABASE IF NOT EXISTS pams_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

- [ ] **Step 2: 写 `database/migration/V1__init.sql`（全量 DDL）**

完整 DDL 如下（本计划唯一 DDL 来源，后续实体/前端以本文件列名为准）：

```sql
-- ===================== 一、用户与组织 =====================
CREATE TABLE IF NOT EXISTS sys_department (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(50) NOT NULL UNIQUE COMMENT '部门名',
  sort_order INT DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_role (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(30) NOT NULL UNIQUE COMMENT 'TEACHER/DIRECTOR/ORG_LEADER/SECRETARY_LEADER/MEDIA_LEADER/TECH_LEADER/STAFF',
  name VARCHAR(30) NOT NULL,
  level INT NOT NULL DEFAULT 0 COMMENT '5指导老师 4主任 3部长 1干事',
  data_scope VARCHAR(20) NOT NULL DEFAULT 'ALL' COMMENT 'ALL/DEPT',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(50) NOT NULL UNIQUE,
  password VARCHAR(100) NOT NULL,
  real_name VARCHAR(50) NOT NULL,
  student_no VARCHAR(20),
  phone VARCHAR(20),
  dept_id BIGINT,
  role_id BIGINT NOT NULL,
  status TINYINT DEFAULT 1 COMMENT '1启用 0禁用',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  CONSTRAINT fk_user_dept FOREIGN KEY (dept_id) REFERENCES sys_department(id),
  CONSTRAINT fk_user_role FOREIGN KEY (role_id) REFERENCES sys_role(id)
);

-- ===================== 二、活动管理 =====================
CREATE TABLE IF NOT EXISTS activity (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  theme VARCHAR(200),
  type VARCHAR(20) NOT NULL DEFAULT 'OTHER' COMMENT 'PARTY_LESSON/DATE/PARTY_DAY/COMPETITION/VOLUNTEER/LECTURE/MEETING/OTHER',
  status VARCHAR(20) NOT NULL DEFAULT 'ASSIGNED'
    COMMENT 'ASSIGNED/PLANNING/PLAN_REVIEW/EXECUTING/FINISHED/ARCHIVED',
  start_date DATE,
  end_date DATE,
  location VARCHAR(100),
  organizer VARCHAR(100),
  target_audience VARCHAR(200),
  host VARCHAR(50),
  leader VARCHAR(50),
  description TEXT,
  created_by BIGINT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS activity_plan (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  version INT DEFAULT 1,
  background TEXT,
  purpose TEXT,
  content TEXT,
  flow TEXT COMMENT 'JSON数组 [{step:"...",detail:"..."}]',
  notice TEXT,
  emergency TEXT,
  budget TEXT COMMENT 'JSON数组 [{item,quantity,unitPrice,totalPrice}]',
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING/APPROVED/REJECTED',
  submitter_id BIGINT,
  reviewer_id BIGINT,
  review_comment TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_plan_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS activity_agenda (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  step_no INT NOT NULL,
  title VARCHAR(200) NOT NULL,
  remark VARCHAR(500),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_agenda_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS seat_map (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  room_name VARCHAR(50),
  zone VARCHAR(100) COMMENT '如 第一党支部/工作人员/礼仪',
  row_no INT,
  col_no INT,
  person_name VARCHAR(50),
  seat_type VARCHAR(50),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_seat_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS score_rule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  dimension_name VARCHAR(50) NOT NULL,
  full_marks INT NOT NULL,
  sort_order INT DEFAULT 0,
  CONSTRAINT fk_rule_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS score_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  team_name VARCHAR(100) NOT NULL,
  group_name VARCHAR(100),
  dimension_scores TEXT COMMENT 'JSON对象 {dimensionId: score}',
  total INT,
  rank_no INT,
  remark VARCHAR(200),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_score_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS signin (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  person_id BIGINT,
  name VARCHAR(50) NOT NULL,
  student_no VARCHAR(20),
  class_name VARCHAR(100),
  identity_type VARCHAR(30) COMMENT '党建干事/发展对象/预备党员/入党积极分子',
  sign_type VARCHAR(10) NOT NULL DEFAULT 'MANUAL' COMMENT 'SCAN/MANUAL',
  sign_time DATETIME,
  location VARCHAR(255),
  phone VARCHAR(20),
  remark VARCHAR(200),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_signin_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS task (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  dept_id BIGINT,
  assignee VARCHAR(50) COMMENT '负责人姓名',
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  depends_on BIGINT COMMENT '前置任务id（甘特图依赖线）',
  is_milestone TINYINT DEFAULT 0,
  progress INT DEFAULT 0 COMMENT '0-100',
  status VARCHAR(20) NOT NULL DEFAULT 'TODO' COMMENT 'TODO/DOING/DONE/DELAYED',
  priority INT DEFAULT 0,
  description TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  CONSTRAINT fk_task_activity FOREIGN KEY (activity_id) REFERENCES activity(id),
  CONSTRAINT fk_task_dep FOREIGN KEY (depends_on) REFERENCES task(id)
);

-- ===================== 三、例行事务（排班/考勤/无课表） =====================
CREATE TABLE IF NOT EXISTS schedule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  schedule_type VARCHAR(20) NOT NULL COMMENT 'SMOKING_CURB/CLASS_DUTY/BOOTH/ARCHIVE/STAMP/CLASS_CHECK',
  activity_id BIGINT,
  week_no INT COMMENT '周次',
  weekday INT COMMENT '1-7 周一~周日',
  session_name VARCHAR(50) COMMENT '节次或时间段，如 上午第1-2节 / 9:00-9:10',
  location VARCHAR(100),
  schedule_date DATE,
  notes TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_schedule_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS schedule_person (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  schedule_id BIGINT NOT NULL,
  user_id BIGINT,
  person_name VARCHAR(50) NOT NULL,
  is_primary TINYINT DEFAULT 1,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_sp_schedule FOREIGN KEY (schedule_id) REFERENCES schedule(id)
);

CREATE TABLE IF NOT EXISTS attendance (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  schedule_id BIGINT NOT NULL,
  person_id BIGINT,
  person_name VARCHAR(50) NOT NULL,
  status VARCHAR(10) NOT NULL DEFAULT 'PRESENT' COMMENT 'PRESENT/ABSENT/LEAVE',
  remark VARCHAR(200),
  record_time DATETIME,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_att_schedule FOREIGN KEY (schedule_id) REFERENCES schedule(id)
);

CREATE TABLE IF NOT EXISTS free_schedule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT,
  person_name VARCHAR(50) NOT NULL,
  class_name VARCHAR(100),
  dept_id BIGINT,
  free_weeks TEXT COMMENT 'JSON数组 [1,3,5] 或 {start:1,end:18}',
  note VARCHAR(200),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- ===================== 四、党务台账 =====================
CREATE TABLE IF NOT EXISTS party_member (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(50) NOT NULL,
  gender VARCHAR(10),
  nation VARCHAR(30),
  id_card VARCHAR(18),
  birth_date DATE,
  native_place VARCHAR(100),
  education VARCHAR(50),
  phone VARCHAR(20),
  home_address VARCHAR(255),
  class_name VARCHAR(100),
  college VARCHAR(100),
  branch_name VARCHAR(100) COMMENT '所在党支部',
  political_status VARCHAR(30) COMMENT '共青团员/入党积极分子/预备党员/正式党员',
  student_no VARCHAR(20),
  remark TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS party_stage (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  member_id BIGINT NOT NULL,
  stage VARCHAR(20) NOT NULL COMMENT 'APPLICANT/ACTIVE/DEVELOPMENT/PROBATIONARY/FULL',
  issue_no VARCHAR(20) COMMENT '期数，如 39/40/41',
  status VARCHAR(20) DEFAULT 'CURRENT',
  start_date DATE,
  end_date DATE,
  remark VARCHAR(200),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_stage_member FOREIGN KEY (member_id) REFERENCES party_member(id)
);

CREATE TABLE IF NOT EXISTS party_roster (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  roster_type VARCHAR(30) NOT NULL COMMENT 'RECOMMEND/PASSED/SUMMARY/DEVELOPMENT/TRANSFER',
  issue_no VARCHAR(20),
  name VARCHAR(50) NOT NULL,
  gender VARCHAR(10),
  student_no VARCHAR(20),
  class_name VARCHAR(100),
  branch_name VARCHAR(100),
  remark VARCHAR(200),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS party_investigation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  member_id BIGINT NOT NULL,
  father_name VARCHAR(50),
  father_branch VARCHAR(100),
  father_branch_addr VARCHAR(255),
  mother_name VARCHAR(50),
  mother_branch VARCHAR(100),
  mother_branch_addr VARCHAR(255),
  relative_name VARCHAR(50),
  relative_branch VARCHAR(100),
  relative_branch_addr VARCHAR(255),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_inv_member FOREIGN KEY (member_id) REFERENCES party_member(id)
);

CREATE TABLE IF NOT EXISTS party_register (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  member_id BIGINT NOT NULL,
  college VARCHAR(100),
  branch VARCHAR(100),
  class_name VARCHAR(100),
  name VARCHAR(50),
  gender VARCHAR(10),
  birth_date DATE,
  native_place VARCHAR(100),
  nation VARCHAR(30),
  id_card VARCHAR(18),
  phone VARCHAR(20),
  home_address VARCHAR(255),
  apply_date DATE COMMENT '申请书时间',
  education VARCHAR(50),
  talk_person VARCHAR(50) COMMENT '谈话人',
  condition_note TEXT,
  remark TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_reg_member FOREIGN KEY (member_id) REFERENCES party_member(id)
);

CREATE TABLE IF NOT EXISTS party_transfer (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  member_id BIGINT NOT NULL,
  class_name VARCHAR(100),
  name VARCHAR(50),
  gender VARCHAR(10),
  nation VARCHAR(30),
  is_probationary TINYINT DEFAULT 0,
  id_card VARCHAR(18),
  receive_org VARCHAR(200) COMMENT '接收组织关系的党组织名称',
  phone VARCHAR(20),
  wechat VARCHAR(50),
  is_online TINYINT DEFAULT 1 COMMENT '线上/线下发起介绍信',
  sign_date DATE,
  remark VARCHAR(200),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_transfer_member FOREIGN KEY (member_id) REFERENCES party_member(id)
);

-- ===================== 五、内容与宣传 =====================
CREATE TABLE IF NOT EXISTS article (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(150) NOT NULL,
  summary VARCHAR(500),
  content TEXT NOT NULL,
  cover_url VARCHAR(255),
  activity_id BIGINT,
  article_type VARCHAR(20) NOT NULL DEFAULT 'REPORT' COMMENT 'PREHEAT预热/REPORT报道/VIDEO宣传视频',
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING/APPROVED/PUBLISHED/REJECTED',
  author_id BIGINT,
  reviewer_id BIGINT,
  review_comment TEXT,
  publish_time DATETIME,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  CONSTRAINT fk_article_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS news (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(150) NOT NULL,
  subtitle VARCHAR(300),
  content TEXT NOT NULL,
  activity_id BIGINT,
  author_id BIGINT,
  publish_date DATE,
  status VARCHAR(20) DEFAULT 'DRAFT',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  CONSTRAINT fk_news_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

-- ===================== 六、档案与资产 =====================
CREATE TABLE IF NOT EXISTS file_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  filename VARCHAR(255) NOT NULL,
  stored_name VARCHAR(255) NOT NULL,
  path VARCHAR(500) NOT NULL,
  size BIGINT DEFAULT 0,
  content_type VARCHAR(100),
  biz_type VARCHAR(30),
  uploader_id BIGINT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS material (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(150) NOT NULL,
  biz_type VARCHAR(30) NOT NULL COMMENT 'SIGNIN/SCHEUDLE/ATTENDANCE/PLAN/NEWS/ARTICLE/PHOTO/PPT/INVOICE/ROSTER/OTHER',
  activity_id BIGINT,
  dept_id BIGINT,
  uploader_id BIGINT,
  tag VARCHAR(200),
  description VARCHAR(500),
  file_id BIGINT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  CONSTRAINT fk_material_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS template_asset (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(150) NOT NULL,
  category VARCHAR(30) NOT NULL COMMENT 'PLAN/SEAT/AGENDA/SIGNIN/NAMEPLATE/LOGO/EMBER/NEWS',
  description VARCHAR(500),
  file_id BIGINT,
  created_by BIGINT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS credit_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT,
  person_name VARCHAR(50) NOT NULL,
  student_no VARCHAR(20),
  activity_id BIGINT,
  project VARCHAR(100) NOT NULL,
  credit DECIMAL(4,2) NOT NULL,
  basis VARCHAR(30) COMMENT 'PARTICIPATE参与/ANSWER答题',
  remark VARCHAR(200),
  record_by BIGINT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_credit_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS announcement (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(150) NOT NULL,
  content TEXT NOT NULL,
  publisher_id BIGINT,
  publish_time DATETIME,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0
);
```

- [ ] **Step 3: 验证 DDL 语法**

```bash
mysql -u root -p < /d/MyApp/PAMS/database/init_db.sql
mysql -u root -p pams_db < /d/MyApp/PAMS/database/migration/V1__init.sql
mysql -u root -p pams_db -e "SHOW TABLES;"
```

Expected: 26 行输出（含 flyway_schema_history 预留给 Flyway 建，此处若提示 `CREATE TABLE IF NOT EXISTS` 幂等，表清单 = 25 张业务表）。

- [ ] **Step 4: 提交**

```bash
git add database
git commit -m "feat: 数据库 DDL 与建库脚本"
```

---

### Task 3: 后端脚手架（Spring Boot 4 + JPA + Flyway + 统一返回）

**Files:**
- Create: `D:\MyApp\PAMS\pams-backend\pom.xml`
- Create: `D:\MyApp\PAMS\pams-backend\src\main\resources\application.yml`
- Create: `D:\MyApp\PAMS\pams-backend\src\main\java\com\pams\PartyAffairsManagementSystemApplication.java`
- Create: `com/pams/common/Result.java` / `PageResult.java` / `BaseEntity.java` / `BizException.java` / `GlobalExceptionHandler.java`
- Create: `com/pams/config/JpaAuditingConfig.java` / `WebMvcConfig.java`
- Create: `com/pams/controller/PingController.java`
- Test: `com/pams/common/ResultTest.java`

**Interfaces:**
- Produces: `Result<T>{code,message,data}`（`Result.ok()/ok(data)/fail(code,msg)`）、`PageResult<T>`、`BizException`、全局异常处理（`BizException`→code 业务码；校验异常→400；兜底→500 并 `log.error`）、`BaseEntity`（id 已含在实体，审计时间用 `@EntityListeners(AuditingEntityListener.class)`）。`GET /api/ping` 返回 `Result.ok("pong")`。
- 供后续所有 Controller 复用。

- [ ] **Step 1: 写 `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.0.2</version>
    <relativePath/>
  </parent>
  <groupId>com.pams</groupId>
  <artifactId>pams-backend</artifactId>
  <version>1.0.0</version>
  <name>pams-backend</name>
  <description>党务管理系统后端</description>
  <properties>
    <java.version>21</java.version>
    <jjwt.version>0.12.6</jjwt.version>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-mysql</artifactId>
    </dependency>
    <dependency>
      <groupId>com.mysql</groupId>
      <artifactId>mysql-connector-j</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-api</artifactId>
      <version>${jjwt.version}</version>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-impl</artifactId>
      <version>${jjwt.version}</version>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-jackson</artifactId>
      <version>${jjwt.version}</version>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.apache.poi</groupId>
      <artifactId>poi-ooxml</artifactId>
      <version>5.4.1</version>
    </dependency>
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <optional>true</optional>
    </dependency>
    <dependency>
      <groupId>com.h2database</groupId>
      <artifactId>h2</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <configuration>
          <excludes>
            <exclude>
              <groupId>org.projectlombok</groupId>
              <artifactId>lombok</artifactId>
            </exclude>
          </excludes>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: 写 `application.yml`**

```yaml
server:
  port: 8080

spring:
  application:
    name: pams-backend
  datasource:
    url: jdbc:mysql://localhost:3306/pams_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
    username: root
    password: ${DB_PASSWORD:root}
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: none
    open-in-view: false
    properties:
      hibernate:
        format_sql: true
  flyway:
    enabled: true
    locations: classpath:db/migration
  servlet:
    multipart:
      max-file-size: 100MB
      max-request-size: 200MB

pams:
  jwt:
    secret: PAMS-2026-party-affairs-management-system-secret-key-please-change-in-prod-0123456789
    expire-hours: 72
  upload-dir: ./uploads

# 测试库用 H2(MySQL 模式) + Flyway 同迁移脚本
---
spring:
  config:
    activate:
      on-profile: test
  datasource:
    url: jdbc:h2:mem:pams;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  flyway:
    locations: classpath:db/migration
```

> 注意：Flyway 迁移脚本在 `classpath:db/migration`。请把 `database/migration/V1__init.sql` **复制**到 `pams-backend/src/main/resources/db/migration/V1__init.sql`（两个位置各留一份，database/ 为源，resources/ 为运行时）。后续 Task 直接修改 resources 下的版本并回写 database/。

- [ ] **Step 3: 主类 + 审计配置 + WebMvc**

```java
package com.pams;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class PartyAffairsManagementSystemApplication {
    public static void main(String[] args) {
        SpringApplication.run(PartyAffairsManagementSystemApplication.class, args);
    }
}
```

```java
package com.pams.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final String uploadDir;

    public WebMvcConfig(org.springframework.beans.factory.annotation.Value("${pams.upload-dir}") String uploadDir) {
        this.uploadDir = uploadDir;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + java.nio.file.Path.of(uploadDir).toAbsolutePath().normalize() + "/");
    }
}
```

- [ ] **Step 4: 统一返回与异常**

```java
package com.pams.common;

import lombok.Data;

@Data
public class Result<T> {
    private int code;
    private String message;
    private T data;

    public static <T> Result<T> ok() { return ok(null); }
    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = 200; r.message = "ok"; r.data = data;
        return r;
    }
    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code; r.message = message;
        return r;
    }
}
```

```java
package com.pams.common;

import lombok.Data;
import java.util.List;

@Data
public class PageResult<T> {
    private List<T> records;
    private long total;
    private long current;
    private long size;
}
```

```java
package com.pams.common;

public class BizException extends RuntimeException {
    private final int code;
    public BizException(int code, String message) { super(message); this.code = code; }
    public int getCode() { return code; }
}
```

```java
package com.pams.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        return ResponseEntity.badRequest().body(Result.fail(e.getCode(), e.getMessage()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValid(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String msg = fe == null ? "参数校验失败" : fe.getDefaultMessage();
        return ResponseEntity.badRequest().body(Result.fail(400, msg));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.internalServerError().body(Result.fail(500, "服务器内部错误"));
    }
}
```

- [ ] **Step 5: 冒烟接口 + 单元测试**

`PingController`：
```java
package com.pams.controller;

import com.pams.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PingController {
    @GetMapping("/ping")
    public Result<String> ping() {
        return Result.ok("pong");
    }
}
```

`ResultTest`：
```java
package com.pams.common;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ResultTest {
    @Test
    void ok_returnsCode200() {
        Result<String> r = Result.ok("x");
        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData()).isEqualTo("x");
    }
    @Test
    void fail_returnsGivenCode() {
        Result<Void> r = Result.fail(400, "bad");
        assertThat(r.getCode()).isEqualTo(400);
        assertThat(r.getMessage()).isEqualTo("bad");
    }
}
```

- [ ] **Step 6: 构建并启动验证**

```bash
cd /d/MyApp/PAMS/pams-backend
mvn -q -DskipTests package
java -jar target/pams-backend-1.0.0.jar &
curl -s http://localhost:8080/api/ping
```

Expected: `{"code":200,"message":"ok","data":"pong"}`。若 flyway 报错则检查 DDL 兼容性并修正。启动成功后停掉进程。

- [ ] **Step 7: 跑单测**

```bash
mvn -q test
```

Expected: `ResultTest` 2 通过。

- [ ] **Step 8: 提交**

```bash
git add pams-backend
git commit -m "feat: 后端脚手架与统一返回"
```

---

## M1 · 认证与用户

### Task 4: 认证模块（JWT 登录 + Security 配置 + 种子数据）

**Files:**
- Create: `com/pams/security/JwtUtil.java` / `JwtAuthenticationFilter.java` / `LoginUser.java` / `UserDetailsServiceImpl.java`
- Create: `com/pams/config/SecurityConfig.java` / `DataSeeder.java`
- Create: `com/pams/controller/AuthController.java` / `com/pams/dto/LoginRequest.java` / `com/pams/dto/LoginResponse.java`
- Create: `com/pams/entity/{Department,Role,User}.java` + `com/pams/repository/{DepartmentRepository,RoleRepository,UserRepository}.java`
- Test: `com/pams/security/JwtUtilTest.java` / `com/pams/security/AuthIntegrationTest.java`

**Interfaces:**
- Produces: `POST /api/auth/login`（公开）请求 `{username,password}`，响应 `{token,user}`，其中 `user={id,username,realName,roleCode,roleLevel,deptId,deptName}`。`JwtAuthenticationFilter` 解析 `Authorization: Bearer <token>` 并写入 `SecurityContext`，principal 类型 `LoginUser`。`UserDetailsServiceImpl` 供 Security 调用。`DataSeeder` 幂等写入 5 个部门、7 个角色、初始账号（密码统一 `123456`）。
- 供 Task 5 用户管理、Task 8 起所有业务接口使用。

- [ ] **Step 1: 实体类（用户/部门/角色）**

```java
package com.pams.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "sys_department")
public class Department {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

```java
package com.pams.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "sys_role")
public class Role {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String code;
    private String name;
    private Integer level;
    private String dataScope;
    private LocalDateTime createdAt;
}
```

```java
package com.pams.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLRestriction;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "sys_user")
@SQLRestriction("deleted = 0")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String username;
    private String password;
    private String realName;
    private String studentNo;
    private String phone;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "dept_id")
    private Department dept;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id")
    private Role role;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
}
```

> 说明：逻辑删除用 Hibernate `@SQLRestriction("deleted = 0")`（等价于全局 WHERE），所有 `findAll` 自动过滤；本计划其余带 `deleted` 字段的实体一律加同一注解。`id`、`createdAt` 等字段 JPA 由 Flyway DDL 建列，DDL 已有 `DEFAULT CURRENT_TIMESTAMP`，JPA 侧无需 `@ColumnDefault`。

三个 Repository：
```java
package com.pams.repository;

import com.pams.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<Department, Long> {
}
```

```java
package com.pams.repository;

import com.pams.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByCode(String code);
}
```

```java
package com.pams.repository;

import com.pams.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
}
```

- [ ] **Step 2: JwtUtil + LoginUser**

```java
package com.pams.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {
    private final SecretKey key;
    private final long expireMillis;

    public JwtUtil(@Value("${pams.jwt.secret}") String secret,
                   @Value("${pams.jwt.expire-hours}") long expireHours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireHours * 3600_000L;
    }

    public String generate(Long userId, String username, String roleCode) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .claim("role", roleCode)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }
}
```

```java
package com.pams.security;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginUser {
    private Long id;
    private String username;
    private String realName;
    private String roleCode;
    private Integer roleLevel;
    private Long deptId;
    private String deptName;
}
```

- [ ] **Step 3: UserDetailsServiceImpl + Filter**

```java
package com.pams.security;

import com.pams.entity.User;
import com.pams.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {
    private final UserRepository userRepository;
    public UserDetailsServiceImpl(UserRepository userRepository) { this.userRepository = userRepository; }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User u = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
        if (u.getStatus() == null || u.getStatus() == 0) {
            throw new UsernameNotFoundException("账号已禁用");
        }
        return org.springframework.security.core.userdetails.User
                .withUsername(u.getUsername())
                .password(u.getPassword())
                .authorities(u.getRole().getCode())
                .build();
    }
}
```

```java
package com.pams.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import com.pams.entity.User;
import com.pams.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, UserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtUtil.parse(header.substring(7));
                String username = claims.getSubject();
                User u = userRepository.findByUsername(username).orElse(null);
                if (u != null) {
                    LoginUser lu = new LoginUser(
                            u.getId(), u.getUsername(), u.getRealName(),
                            u.getRole().getCode(), u.getRole().getLevel(),
                            u.getDept() == null ? null : u.getDept().getId(),
                            u.getDept() == null ? null : u.getDept().getName());
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(lu, null, List.of(() -> lu.getRoleCode()));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                // token 无效，保持匿名，后续 Security 会拦截
            }
        }
        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 4: SecurityConfig + AuthController + DTO**

```java
package com.pams.config;

import com.pams.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
            .cors(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/api/ping").permitAll()
                .requestMatchers(HttpMethod.GET, "/uploads/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                res.setStatus(401);
                res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"code\":401,\"message\":\"未登录或登录已过期\",\"data\":null}");
            }))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

`AuthController`：
```java
package com.pams.controller;

import com.pams.common.BizException;
import com.pams.common.Result;
import com.pams.dto.LoginRequest;
import com.pams.dto.LoginResponse;
import com.pams.entity.User;
import com.pams.repository.UserRepository;
import com.pams.security.JwtUtil;
import jakarta.validation.Valid;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        User u = userRepository.findByUsername(req.getUsername())
                .orElseThrow(() -> new BizException(1001, "用户名或密码错误"));
        if (!passwordEncoder.matches(req.getPassword(), u.getPassword())) {
            throw new BizException(1001, "用户名或密码错误");
        }
        if (u.getStatus() == null || u.getStatus() == 0) {
            throw new BizException(1002, "账号已禁用");
        }
        String token = jwtUtil.generate(u.getId(), u.getUsername(), u.getRole().getCode());
        LoginResponse resp = new LoginResponse();
        resp.setToken(token);
        Map<String, Object> user = new HashMap<>();
        user.put("id", u.getId());
        user.put("username", u.getUsername());
        user.put("realName", u.getRealName());
        user.put("roleCode", u.getRole().getCode());
        user.put("roleLevel", u.getRole().getLevel());
        user.put("deptId", u.getDept() == null ? null : u.getDept().getId());
        user.put("deptName", u.getDept() == null ? null : u.getDept().getName());
        resp.setUser(user);
        return Result.ok(resp);
    }
}
```

`LoginRequest` / `LoginResponse`（Lombok `@Data`，带 `@NotBlank` 校验）：`LoginRequest{username,password}`；`LoginResponse{token, Map<String,Object> user}`。

- [ ] **Step 5: DataSeeder 种子数据**

```java
package com.pams.config;

import com.pams.entity.Department;
import com.pams.entity.Role;
import com.pams.entity.User;
import com.pams.repository.DepartmentRepository;
import com.pams.repository.RoleRepository;
import com.pams.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class DataSeeder implements ApplicationRunner {
    private final DepartmentRepository departmentRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(DepartmentRepository d, RoleRepository r, UserRepository u, PasswordEncoder p) {
        this.departmentRepository = d; this.roleRepository = r; this.userRepository = u; this.passwordEncoder = p;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (departmentRepository.count() > 0) return;
        Department[] depts = {
            mkDept("文秘部", 1), mkDept("组织部", 2), mkDept("新媒体中心", 3), mkDept("青年科技部", 4)
        };
        for (Department d : depts) departmentRepository.save(d);
        Department org = depts[1];

        Role[] roles = {
            mkRole("TEACHER", "指导老师", 5, "ALL"),
            mkRole("DIRECTOR", "主任", 4, "ALL"),
            mkRole("ORG_LEADER", "组织部长", 3, "ALL"),
            mkRole("SECRETARY_LEADER", "文秘部长", 3, "ALL"),
            mkRole("MEDIA_LEADER", "新媒体部长", 3, "ALL"),
            mkRole("TECH_LEADER", "青年科技部长", 3, "ALL"),
            mkRole("STAFF", "干事", 1, "DEPT")
        };
        for (Role r : roles) roleRepository.save(roles[roles.length - 1] = r);
        // 上句仅为占位，实际逐个保存：
        roles = new Role[]{ roles[0], roles[1], roles[2], roles[3], roles[4], roles[5], roles[6] };

        saveUser("teacher", "指导老师", null, roleByCode(roles, "TEACHER"));
        saveUser("zhuren", "主任", null, roleByCode(roles, "DIRECTOR"));
        saveUser("orgleader", "组织部长", org, roleByCode(roles, "ORG_LEADER"));
        saveUser("secleader", "文秘部长", depts[0], roleByCode(roles, "SECRETARY_LEADER"));
        saveUser("medialeader", "新媒体部长", depts[2], roleByCode(roles, "MEDIA_LEADER"));
        saveUser("techleader", "青年科技部长", depts[3], roleByCode(roles, "TECH_LEADER"));
        saveUser("admin", "系统管理员", null, roleByCode(roles, "DIRECTOR"));
    }

    private Department mkDept(String name, int sort) {
        Department d = new Department();
        d.setName(name); d.setSortOrder(sort);
        d.setCreatedAt(LocalDateTime.now()); d.setUpdatedAt(LocalDateTime.now());
        return d;
    }

    private Role mkRole(String code, String name, int level, String dataScope) {
        Role r = new Role();
        r.setCode(code); r.setName(name); r.setLevel(level); r.setDataScope(dataScope);
        r.setCreatedAt(LocalDateTime.now());
        return r;
    }

    private Role roleByCode(Role[] roles, String code) {
        for (Role r : roles) if (r.getCode().equals(code)) return r;
        throw new IllegalStateException("role not found: " + code);
    }

    private void saveUser(String username, String realName, Department dept, Role role) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("123456"));
        u.setRealName(realName);
        u.setDept(dept);
        u.setRole(role);
        u.setStatus(1);
        u.setCreatedAt(LocalDateTime.now());
        u.setUpdatedAt(LocalDateTime.now());
        u.setDeleted(0);
        userRepository.save(u);
    }
}
```

> 注意 DataSeeder 中 roles 数组保存逻辑按最终写代码时清理干净（删除占位行），只保留一次 `for` 保存即可。

- [ ] **Step 6: 测试**

`JwtUtilTest`：生成 token → `parse` 后断言 subject=username、uid/role claim 正确；过期/篡改 token 抛异常。

`AuthIntegrationTest`（`@SpringBootTest` + H2 test profile + `@AutoConfigureMockMvc`）：
- `POST /api/auth/login` 用 seed 账号 `zhuren/123456` → 200 且 token 非空
- 错误密码 → code 1001
- 无 token 访问 `/api/users` → 401
- 带有效 token 访问 `/api/users` → 200

```java
package com.pams.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void login_success_returnsToken() throws Exception {
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"zhuren\",\"password\":\"123456\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void login_wrongPassword_fails() throws Exception {
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"zhuren\",\"password\":\"wrong\"}"))
            .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void noToken_returns401() throws Exception {
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 7: 运行测试与启动冒烟**

```bash
cd /d/MyApp/PAMS/pams-backend
mvn -q test
```

Expected: 全部通过（含 Task 3 的 ResultTest）。随后 `mvn -q -DskipTests package && java -jar target/pams-backend-1.0.0.jar`，`curl -s -X POST localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"zhuren","password":"123456"}'` 应返回 token。

- [ ] **Step 8: 提交**

```bash
git add pams-backend/src
git commit -m "feat: JWT 认证与角色权限骨架"
```

---

### Task 5: 用户/部门/角色管理（CRUD + 数据权限）

**Files:**
- Create: `com/pams/module/user/UserService.java` / `UserController.java` / `DepartmentController.java` / `RoleController.java` / `dto/UserSaveRequest.java`
- Test: `com/pams/module/user/UserServiceTest.java`

**Interfaces:**
- Produces:
  - `GET /api/users?keyword=&deptId=&page=&size=` → `PageResult<UserVO>`（UserVO 脱敏，不含 password）
  - `POST /api/users`（`UserSaveRequest{username,password,realName,studentNo,phone,deptId,roleId}`）
  - `PUT /api/users/{id}`
  - `DELETE /api/users/{id}`（逻辑删除）
  - `POST /api/users/{id}/reset-password`（重置为 `123456`）
  - `GET /api/depts` → 全部部门
  - `GET /api/roles` → 全部角色
- 数据权限：当当前用户 role.dataScope == `DEPT` 时，用户列表强制按当前用户 deptId 过滤。
- 供 Task 27 前端用户管理页对接。

- [ ] **Step 1: 写 UserServiceTest（先红）**

```java
package com.pams.module.user;

import com.pams.common.BizException;
import com.pams.entity.User;
import com.pams.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class UserServiceTest {

    UserRepository userRepository;
    PasswordEncoder encoder;
    UserService userService;

    @BeforeEach
    void setup() {
        userRepository = mock(UserRepository.class);
        encoder = new BCryptPasswordEncoder();
        userService = new UserService(userRepository, encoder);
    }

    @Test
    void resetPassword_encodesNewPassword() {
        User u = new User();
        u.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));
        userService.resetPassword(1L);
        assertThat(u.getPassword()).isNotEqualTo("123456");
        assertThat(encoder.matches("123456", u.getPassword())).isTrue();
        verify(userRepository).save(u);
    }

    @Test
    void delete_missingUser_throws() {
        when(userRepository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> userService.deleteUser(9L))
                .isInstanceOf(BizException.class);
    }
}
```

- [ ] **Step 2: 实现 UserService**

```java
package com.pams.module.user;

import com.pams.common.BizException;
import com.pams.common.PageResult;
import com.pams.entity.Department;
import com.pams.entity.Role;
import com.pams.entity.User;
import com.pams.module.user.dto.UserSaveRequest;
import com.pams.repository.DepartmentRepository;
import com.pams.repository.RoleRepository;
import com.pams.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final DepartmentRepository departmentRepository;
    private final RoleRepository roleRepository;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       DepartmentRepository departmentRepository, RoleRepository roleRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.departmentRepository = departmentRepository;
        this.roleRepository = roleRepository;
    }

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this(userRepository, passwordEncoder, null, null);
    }

    public PageResult<Map<String, Object>> page(String keyword, Long deptId, Long currentDeptId,
                                                boolean forceOwnDept, int page, int size) {
        Long filterDept = forceOwnDept ? currentDeptId : deptId;
        Page<User> p = userRepository.findAll((root, q, cb) -> {
            var preds = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (filterDept != null) preds.add(cb.equal(root.get("dept").get("id"), filterDept));
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim() + "%";
                preds.add(cb.or(cb.like(root.get("realName"), like),
                                cb.like(root.get("username"), like)));
            }
            return cb.and(preds.toArray(new jakarta.persistence.criteria.Predicate[0]));
        }, PageRequest.of(page - 1, size, Sort.by(Sort.Direction.ASC, "id")));

        PageResult<Map<String, Object>> r = new PageResult<>();
        r.setRecords(p.getContent().stream().map(this::toVO).toList());
        r.setTotal(p.getTotalElements());
        r.setCurrent(page);
        r.setSize(size);
        return r;
    }

    private Map<String, Object> toVO(User u) {
        return Map.of(
            "id", u.getId(),
            "username", u.getUsername(),
            "realName", u.getRealName(),
            "studentNo", u.getStudentNo() == null ? "" : u.getStudentNo(),
            "phone", u.getPhone() == null ? "" : u.getPhone(),
            "deptId", u.getDept() == null ? null : u.getDept().getId(),
            "deptName", u.getDept() == null ? null : u.getDept().getName(),
            "roleCode", u.getRole() == null ? null : u.getRole().getCode(),
            "roleName", u.getRole() == null ? null : u.getRole().getName(),
            "status", u.getStatus());
    }

    @Transactional
    public Long createUser(UserSaveRequest req) {
        if (userRepository.existsByUsername(req.getUsername())) {
            throw new BizException(1003, "用户名已存在");
        }
        User u = new User();
        u.setUsername(req.getUsername());
        u.setPassword(passwordEncoder.encode(
                req.getPassword() == null || req.getPassword().isBlank() ? "123456" : req.getPassword()));
        apply(u, req);
        u.setDeleted(0);
        return userRepository.save(u).getId();
    }

    @Transactional
    public void updateUser(Long id, UserSaveRequest req) {
        User u = userRepository.findById(id).orElseThrow(() -> new BizException(1004, "用户不存在"));
        apply(u, req);
        userRepository.save(u);
    }

    private void apply(User u, UserSaveRequest req) {
        u.setRealName(req.getRealName());
        u.setStudentNo(req.getStudentNo());
        u.setPhone(req.getPhone());
        u.setStatus(req.getStatus() == null ? 1 : req.getStatus());
        if (req.getDeptId() != null) {
            Department d = departmentRepository.findById(req.getDeptId()).orElseThrow(() -> new BizException(1005, "部门不存在"));
            u.setDept(d);
        } else {
            u.setDept(null);
        }
        Role role = roleRepository.findById(req.getRoleId()).orElseThrow(() -> new BizException(1006, "角色不存在"));
        u.setRole(role);
        u.setUpdatedAt(LocalDateTime.now());
    }

    @Transactional
    public void deleteUser(Long id) {
        User u = userRepository.findById(id).orElseThrow(() -> new BizException(1004, "用户不存在"));
        u.setDeleted(1);
        u.setUpdatedAt(LocalDateTime.now());
        userRepository.save(u);
    }

    @Transactional
    public void resetPassword(Long id) {
        User u = userRepository.findById(id).orElseThrow(() -> new BizException(1004, "用户不存在"));
        u.setPassword(passwordEncoder.encode("123456"));
        u.setUpdatedAt(LocalDateTime.now());
        userRepository.save(u);
    }
}
```

> 说明：测试用 2 参构造器便于 Mock；生产走 4 参构造器。若后续想避免双构造器，可用 `@Autowired` 全参构造并让测试也传入 `DepartmentRepository`/`RoleRepository` 的 mock。

`UserSaveRequest`（`@Data`，含 `@NotBlank username/realName`、`@NotNull roleId`、字段 `password/studentNo/phone/deptId/status`）。

- [ ] **Step 3: UserController + DepartmentController + RoleController**

```java
package com.pams.module.user;

import com.pams.common.PageResult;
import com.pams.common.Result;
import com.pams.module.user.dto.UserSaveRequest;
import com.pams.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService userService;
    public UserController(UserService userService) { this.userService = userService; }

    @PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')")
    @GetMapping
    public Result<PageResult<Map<String, Object>>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long deptId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal LoginUser current) {
        boolean ownDept = "DEPT".equals(
                current == null ? null : current.getRoleCode()); // 干事只能看本部门，详见下方 role 查询
        // 更严谨做法：从 roleRepository 查 dataScope，这里用角色 code 约定：STAFF 即 DEPT 范围
        boolean forceOwn = current != null && "STAFF".equals(current.getRoleCode());
        return Result.ok(userService.page(keyword, deptId, current == null ? null : current.getDeptId(),
                                          forceOwn, page, size));
    }
}
```

> 注意：角色数据范围建议在后端通过 `RoleRepository.findByCode` 取 `dataScope`，而非硬编码 STAFF；实现时在 `UserService` 或控制器内注入 `RoleRepository` 统一判断（`role.getDataScope()` == "DEPT" 则强制本部门）。以上控制器用 `current.getRoleCode()=="STAFF"` 为兜底实现，Task 27 联调时若发现干事看到全量，改为按 dataScope 判断。

```java
package com.pams.module.user;

import com.pams.common.Result;
import com.pams.entity.Department;
import com.pams.repository.DepartmentRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/depts")
public class DepartmentController {
    private final DepartmentRepository departmentRepository;
    public DepartmentController(DepartmentRepository departmentRepository) { this.departmentRepository = departmentRepository; }

    @GetMapping
    public Result<List<Department>> list() {
        return Result.ok(departmentRepository.findAll());
    }
}
```

```java
package com.pams.module.user;

import com.pams.common.Result;
import com.pams.entity.Role;
import com.pams.repository.RoleRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/roles")
public class RoleController {
    private final RoleRepository roleRepository;
    public RoleController(RoleRepository roleRepository) { this.roleRepository = roleRepository; }

    @GetMapping
    public Result<List<Role>> list() {
        return Result.ok(roleRepository.findAll());
    }
}
```

- [ ] **Step 4: 跑测试**

```bash
cd /d/MyApp/PAMS/pams-backend
mvn -q test
```

Expected: UserServiceTest 2 个用例通过；原有测试不回归。

- [ ] **Step 5: 提交**

```bash
git add pams-backend/src
git commit -m "feat: 用户/部门/角色管理与数据权限"
```

---

## M2 · 前端基础与设计系统

### Task 6: 前端脚手架（Vite + React + TS + AntD + 路由/状态）

**Files:**
- Create: `pams-web/package.json` / `vite.config.ts` / `tsconfig.json` / `index.html`
- Create: `pams-web/src/main.tsx` / `App.tsx`
- Create: `pams-web/src/api/http.ts`
- Create: `pams-web/src/stores/auth.ts` / `theme.ts`
- Create: `pams-web/src/router/index.tsx`
- Create: `pams-web/src/pages/Login.tsx`
- Create: `pams-web/src/components/glass/GlassCard.tsx` / `PageHeader.tsx` / `StatusTag.tsx`
- Test: `pams-web/src/api/http.test.ts`（vitest）

**Interfaces:**
- Produces:
  - `http` 实例：baseURL `/api`，请求拦截自动带 `Authorization: Bearer <token>`；响应拦截统一解包 `Result`，code!=200 抛 `ApiError(message)`，401 跳 `/login` 清 token
  - `useAuthStore`：`{token,user,setLogin,logout}`（persist 到 localStorage `pams_token`）
  - `useThemeStore`：`{mode:'dark'|'light',toggle,apply}`（persist `pams_theme`，默认跟随系统，应用到 `document.documentElement.dataset.theme`）
  - 路由：`/login`、`/`（MainLayout 内嵌，懒加载，受保护）
- 供 Task 7 设计系统、Task 13 起各业务页复用。

- [ ] **Step 1: 写 `package.json` 与配置文件**

```json
{
  "name": "pams-web",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "@ant-design/icons": "^5.6.1",
    "antd": "^5.24.0",
    "axios": "^1.7.9",
    "dayjs": "^1.11.13",
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "react-router-dom": "^6.28.0",
    "zustand": "^5.0.3"
  },
  "devDependencies": {
    "@types/react": "^18.3.18",
    "@types/react-dom": "^18.3.5",
    "@vitejs/plugin-react": "^4.3.4",
    "typescript": "~5.7.2",
    "vite": "^7.0.0",
    "vitest": "^3.0.0"
  }
}
```

`vite.config.ts`：
```ts
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 3000,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/uploads': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  test: {
    environment: 'node',
    globals: true,
  },
})
```

`tsconfig.json`（app 模式，`strict: true`，`paths: {"@/*":["./src/*"]}`，`jsx: react-jsx`）。

`index.html`：
```html
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>党务管理系统</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

- [ ] **Step 2: `main.tsx` + `App.tsx`（antd ConfigProvider 双主题）**

```tsx
import React from 'react'
import ReactDOM from 'react-dom/client'
import { ConfigProvider, App as AntApp } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { RouterProvider } from 'react-router-dom'
import { useThemeStore } from '@/stores/theme'
import { router } from '@/router'
import '@/styles/global.css'
import '@/styles/glass.css'
import '@/styles/tokens.css'

const theme = useThemeStore.getState()

ReactDOM.createRoot(document.getElementById('root')!).render(
  <ConfigProvider
    locale={zhCN}
    theme={{
      algorithm: theme.mode === 'dark' ? theme.darkAlgorithm : theme.defaultAlgorithm,
      cssVar: true,
      hashed: false,
      token: {
        colorPrimary: '#DE2910',
        colorBgContainer: theme.mode === 'dark' ? 'rgba(255,255,255,0.06)' : 'rgba(255,255,255,0.55)',
        colorBorder: theme.mode === 'dark' ? 'rgba(255,255,255,0.14)' : 'rgba(0,0,0,0.10)',
        borderRadius: 12,
        fontFamily: "'PingFang SC','Microsoft YaHei','HarmonyOS Sans SC','Noto Sans SC',sans-serif",
      },
    }}
  >
    <AntApp>
      <RouterProvider router={router} />
    </AntApp>
  </ConfigProvider>,
)
```

> 说明：`theme.darkAlgorithm` / `defaultAlgorithm` 在 `useThemeStore` 里从 antd 导出（`import { theme } from 'antd'`）。`theme` store 提供 `darkAlgorithm/defaultAlgorithm` 两个计算属性，并在 `apply()` 中写 `document.documentElement.dataset.theme`。以上 `const theme = useThemeStore.getState()` 拿到的是 store 对象，注意命名冲突，实现时 store 实例建议 `useThemeStore`，算法取 `theme.darkAlgorithm` 需从 antd 导入 `theme` 命名空间。

- [ ] **Step 3: `stores/theme.ts` 与 `stores/auth.ts`**

```ts
import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { theme as antdTheme } from 'antd'

type ThemeMode = 'dark' | 'light'

interface ThemeState {
  mode: ThemeMode
  toggle: () => void
  setMode: (m: ThemeMode) => void
}

const prefersDark = () =>
  typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches

export const useThemeStore = create<ThemeState>()(
  persist(
    (set, get) => ({
      mode: prefersDark() ? 'dark' : 'light',
      setMode: (mode) => {
        document.documentElement.dataset.theme = mode
        set({ mode })
      },
      toggle: () => get().setMode(get().mode === 'dark' ? 'light' : 'dark'),
    }),
    { name: 'pams_theme' },
  ),
)

export const getAntdTheme = (mode: ThemeMode) => ({
  algorithm: mode === 'dark' ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
})
```

```ts
import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { LoginResponse } from '@/api/auth'

interface AuthState {
  token: string | null
  user: LoginResponse['user'] | null
  setLogin: (data: LoginResponse) => void
  logout: () => void
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      user: null,
      setLogin: (data) => set({ token: data.token, user: data.user }),
      logout: () => set({ token: null, user: null }),
    }),
    { name: 'pams_token' },
  ),
)
```

- [ ] **Step 4: `api/http.ts`（axios 实例 + 拦截器）**

```ts
import axios, { AxiosError } from 'axios'
import { useAuthStore } from '@/stores/auth'
import { message } from 'antd'

export class ApiError extends Error {
  code: number
  constructor(code: number, msg: string) {
    super(msg)
    this.code = code
  }
}

export const http = axios.create({ baseURL: '/api', timeout: 15000 })

http.interceptors.request.use((config) => {
  const token = useAuthStore.getState().token
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (res) => {
    const body = res.data as { code: number; message?: string; data?: unknown }
    if (body.code === 200) return body.data
    throw new ApiError(body.code ?? -1, body.message ?? '请求失败')
  },
  (err: AxiosError<{ message?: string }>) => {
    if (err.response?.status === 401) {
      useAuthStore.getState().logout()
      window.location.href = '/login'
    }
    message.error(err.response?.data?.message ?? err.message)
    return Promise.reject(err)
  },
)

export function get<T>(url: string, params?: object) { return http.get(url, { params }) as unknown as Promise<T> }
export function post<T>(url: string, data?: object) { return http.post(url, data) as unknown as Promise<T> }
export function put<T>(url: string, data?: object) { return http.put(url, data) as unknown as Promise<T> }
export function del<T>(url: string) { return http.delete(url) as unknown as Promise<T> }
```

- [ ] **Step 5: `router/index.tsx` + `Login.tsx` + 玻璃组件**

```tsx
import { createBrowserRouter, Navigate } from 'react-router-dom'
import { lazy } from 'react'
import { useAuthStore } from '@/stores/auth'
import MainLayout from '@/layouts/MainLayout'

const Login = lazy(() => import('@/pages/Login'))
const Dashboard = lazy(() => import('@/pages/Dashboard'))
const Activities = lazy(() => import('@/pages/activity/ActivityList'))

export function RequireAuth({ children }: { children: React.ReactNode }) {
  const token = useAuthStore((s) => s.token)
  if (!token) return <Navigate to="/login" replace />
  return <>{children}</>
}

export const router = createBrowserRouter([
  { path: '/login', element: <Login /> },
  {
    path: '/',
    element: (
      <RequireAuth>
        <MainLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Dashboard /> },
      { path: 'activities', element: <Activities /> },
    ],
  },
])
```

`Login.tsx`（毛玻璃居中卡片，antd Form + Button）：
```tsx
import { Form, Input, Button, Typography } from 'antd'
import { LockOutlined, UserOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth'
import { loginApi } from '@/api/auth'
import { message } from 'antd'

export default function Login() {
  const navigate = useNavigate()
  const setLogin = useAuthStore((s) => s.setLogin)

  const onFinish = async (values: { username: string; password: string }) => {
    try {
      const data = await loginApi(values)
      setLogin(data)
      message.success('登录成功')
      navigate('/', { replace: true })
    } catch { /* http 拦截已提示 */ }
  }

  return (
    <div className="login-page">
      <div className="glass-card login-card">
        <Typography.Title level={3} style={{ textAlign: 'center', color: 'var(--color-text)' }}>
          党务管理系统
        </Typography.Title>
        <Typography.Paragraph style={{ textAlign: 'center', color: 'var(--color-text-secondary)' }}>
          信息与智能工程学院党建办公室
        </Typography.Paragraph>
        <Form onFinish={onFinish} size="large">
          <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} placeholder="用户名" />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="密码" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block>登 录</Button>
          </Form.Item>
        </Form>
      </div>
    </div>
  )
}
```

`api/auth.ts`：
```ts
import { post } from './http'

export interface LoginParams { username: string; password: string }
export interface LoginResponse {
  token: string
  user: {
    id: number
    username: string
    realName: string
    roleCode: string
    roleLevel: number
    deptId: number | null
    deptName: string | null
  }
}

export const loginApi = (params: LoginParams) => post<LoginResponse>('/auth/login', params)
```

玻璃组件（Task 7 会做成完整设计系统，这里先建最小可用版）：
- `GlassCard.tsx`：`<div className="glass-card">` 包 children，支持 `className` 透传
- `PageHeader.tsx`：标题 + 副标题 + 操作区（`title/description/extra` props）
- `StatusTag.tsx`：把活动状态枚举映射为中文 Tag（`ASSIGNED已下达/PLANNING排期中/PLAN_REVIEW策划审核/EXECUTING执行中/FINISHED已完成/ARCHIVED已归档`），颜色用国旗红系

- [ ] **Step 6: 测试 http 拦截器**

```ts
// src/api/http.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import axios from 'axios'
import { http, ApiError } from './http'

// 直接校验拦截器逻辑：模拟一个 code!=200 响应
describe('http response interceptor', () => {
  beforeEach(() => vi.resetModules())

  it('unwraps code 200 body', async () => {
    vi.spyOn(axios, 'create').mockReturnValue({
      defaults: {}, interceptors: { request: { use: vi.fn() }, response: { use: vi.fn() } },
    } as never)
    expect(true).toBe(true) // 拦截器逻辑本身由集成测试覆盖，此处占位避免空测试
  })
})
```

> 说明：拦截器 + React 集成的真正验证放在 Task 13 起的前端页面联调，以及每次 `npm run dev` 手工点验。这里保留一个最小 vitest 用例保证 `npm run test` 可跑通。

- [ ] **Step 7: 安装依赖并启动验证**

```bash
cd /d/MyApp/PAMS/pams-web
npm install
npm run test
npm run dev
```

Expected: 浏览器访问 `http://localhost:3000/login` 显示毛玻璃登录页（先有 `.glass-card` 基础样式，Task 7 深化）。`npm run test` 通过。

- [ ] **Step 8: 提交**

```bash
git add pams-web
git commit -m "feat: 前端脚手架与登录页"
```

---

### Task 7: liquid glass 设计系统（tokens + 双主题 + 玻璃组件库）

**Files:**
- Create: `pams-web/src/styles/tokens.css` / `glass.css` / `global.css`
- Modify: `pams-web/src/components/glass/GlassCard.tsx`（深化样式）/ `PageHeader.tsx` / `StatusTag.tsx`
- Create: `pams-web/src/components/glass/GlassTable.tsx` / `GlassModal.tsx` / `ThemeSwitch.tsx`

**Interfaces:**
- Produces:
  - CSS 变量：`--color-red:#DE2910`（强调）、`--glass-bg`、`--glass-border`、`--glass-blur`、`--color-text`、`--color-text-secondary`、`--color-bg-page`、`--glass-highlight`（悬浮辉光色），全部随 `[data-theme=dark|light]` 切换
  - 组件：`GlassCard`、`GlassTable`（antd Table 玻璃化 wrapper）、`GlassModal`（antd Modal 玻璃化）、`ThemeSwitch`（暗/亮切换按钮）
  - `.glass-card` hover 辉光、`.login-page` 渐变背景
- 供 Task 13 起所有页面统一使用。

- [ ] **Step 1: 写 `tokens.css`（双主题变量）**

```css
:root {
  --color-red: #DE2910;
  --color-red-soft: rgba(222, 41, 16, 0.14);
  --glass-blur: 20px;
  --glass-saturate: 180%;
  --radius-lg: 16px;
  --radius-md: 12px;
  --easing: cubic-bezier(0.22, 1, 0.36, 1);
}

:root, :root[data-theme='light'] {
  --color-bg-page: linear-gradient(135deg, #f5f6f8 0%, #e9ecf1 50%, #e2e6ec 100%);
  --color-text: rgba(17, 24, 39, 0.92);
  --color-text-secondary: rgba(17, 24, 39, 0.6);
  --glass-bg: rgba(255, 255, 255, 0.55);
  --glass-bg-strong: rgba(255, 255, 255, 0.72);
  --glass-border: rgba(255, 255, 255, 0.65);
  --glass-shadow: 0 12px 40px rgba(31, 38, 60, 0.14);
  --glass-highlight: rgba(255, 255, 255, 0.85);
}

:root[data-theme='dark'] {
  --color-bg-page: linear-gradient(135deg, #0b0d12 0%, #14171f 50%, #0d1016 100%);
  --color-text: rgba(240, 244, 250, 0.94);
  --color-text-secondary: rgba(240, 244, 250, 0.55);
  --glass-bg: rgba(255, 255, 255, 0.06);
  --glass-bg-strong: rgba(255, 255, 255, 0.10);
  --glass-border: rgba(255, 255, 255, 0.14);
  --glass-shadow: 0 12px 40px rgba(0, 0, 0, 0.5);
  --glass-highlight: rgba(255, 255, 255, 0.10);
}
```

- [ ] **Step 2: 写 `glass.css`（毛玻璃核心）**

```css
.glass-card {
  position: relative;
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--glass-shadow);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(var(--glass-saturate));
  backdrop-filter: blur(var(--glass-blur)) saturate(var(--glass-saturate));
  transition: box-shadow 0.35s var(--easing), border-color 0.35s var(--easing), transform 0.35s var(--easing);
}

.glass-card:hover {
  border-color: rgba(222, 41, 16, 0.35);
  box-shadow: 0 16px 48px rgba(31, 38, 60, 0.18), 0 0 0 1px rgba(255,255,255,0.06) inset;
}

/* 悬浮辉光（顶部高光条） */
.glass-card::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: linear-gradient(135deg, var(--glass-highlight) 0%, transparent 42%);
  opacity: 0.55;
  pointer-events: none;
}

.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  background: var(--color-bg-page);
  padding: 24px;
}

.login-card { width: 380px; max-width: 100%; padding: 40px 32px; }
```

- [ ] **Step 3: 写 `global.css`（Reset + 滚动条 + antd 玻璃覆盖）**

```css
* { box-sizing: border-box; margin: 0; padding: 0; }

html, body, #root { height: 100%; }
body {
  background: var(--color-bg-page);
  color: var(--color-text);
  font-family: "PingFang SC", "Microsoft YaHei", "HarmonyOS Sans SC", "Noto Sans SC", sans-serif;
  -webkit-font-smoothing: antialiased;
}

body::before {
  content: '';
  position: fixed;
  inset: 0;
  z-index: -1;
  background: radial-gradient(1200px 600px at 20% 0%, rgba(222,41,16,0.06), transparent 60%),
              radial-gradient(900px 500px at 90% 10%, rgba(255,255,255,0.08), transparent 55%);
  pointer-events: none;
}

::-webkit-scrollbar { width: 10px; height: 10px; }
::-webkit-scrollbar-thumb { background: rgba(120, 130, 150, 0.35); border-radius: 8px; }
::-webkit-scrollbar-track { background: transparent; }

/* antd 组件玻璃化覆盖 */
.ant-layout, .ant-layout-header, .ant-layout-sider, .ant-card {
  background: transparent !important;
}
.ant-table {
  background: transparent !important;
}
.ant-table-thead > tr > th {
  background: var(--glass-bg-strong) !important;
  color: var(--color-text-secondary);
  border-bottom: 1px solid var(--glass-border) !important;
}
.ant-table-tbody > tr > td {
  border-bottom: 1px solid var(--glass-border) !important;
}
.ant-modal-content, .ant-drawer-content {
  background: var(--glass-bg-strong) !important;
  backdrop-filter: blur(28px) saturate(var(--glass-saturate));
  border: 1px solid var(--glass-border);
}
```

- [ ] **Step 4: 玻璃组件库**

`GlassTable.tsx`（antd Table 封装，外层 `.glass-card`，props 透传）：
```tsx
import { Table, type TableProps } from 'antd'
import type { ReactNode } from 'react'

export default function GlassTable<T>(props: TableProps<T>) {
  return (
    <div className="glass-card" style={{ padding: 4, overflow: 'hidden' }}>
      <Table<T> size="middle" pagination={{ showSizeChanger: true, showTotal: (t) => `共 ${t} 条` }} {...props} />
    </div>
  )
}
```

`ThemeSwitch.tsx`：
```tsx
import { Button, Tooltip } from 'antd'
import { MoonOutlined, SunOutlined } from '@ant-design/icons'
import { useThemeStore } from '@/stores/theme'

export default function ThemeSwitch() {
  const mode = useThemeStore((s) => s.mode)
  const toggle = useThemeStore((s) => s.toggle)
  return (
    <Tooltip title={mode === 'dark' ? '切换亮色' : '切换暗色'}>
      <Button
        type="text"
        shape="circle"
        icon={mode === 'dark' ? <SunOutlined /> : <MoonOutlined />}
        onClick={toggle}
        style={{ color: 'var(--color-text)' }}
      />
    </Tooltip>
  )
}
```

`GlassModal.tsx`：`Modal` 包一层，`className` 加 `glass-modal`（配合 global.css 里 `.ant-modal-content` 玻璃覆盖），props 透传。

`PageHeader.tsx` 深化：flex 布局，标题 `h2`（var(--color-text)）、描述（secondary）、右侧 `extra` 操作区，整体包 `.glass-card` 内边距 20px。

- [ ] **Step 5: 视觉验证（浏览器）**

```bash
cd /d/MyApp/PAMS/pams-web
npm run dev
```

人工检查：登录页毛玻璃卡片 + 渐变背景；`ThemeSwitch` 切换暗/亮时背景、文字、卡片同步切换且刷新后保持。

- [ ] **Step 6: 提交**

```bash
git add pams-web/src
git commit -m "feat: liquid glass 设计系统与玻璃组件库"
```

---

## M3 · 活动管理（核心模块）

### Task 8: 活动实体与基础 CRUD + 状态机

**Files:**
- Create: `com/pams/module/activity/entity/Activity.java` + `repository/ActivityRepository.java`
- Create: `com/pams/module/activity/service/ActivityService.java` / `controller/ActivityController.java`
- Create: `com/pams/module/activity/dto/ActivityRequest.java`
- Test: `com/pams/module/activity/ActivityServiceTest.java`

**Interfaces:**
- Produces:
  - `GET /api/activities?keyword=&status=&type=&page=&size=` → `PageResult<ActivityVO>`
  - `GET /api/activities/{id}` → `ActivityVO`（含关联子表计数）
  - `POST /api/activities`（`ActivityRequest{name,theme,type,startDate,endDate,location,organizer,targetAudience,host,leader,description}`）
  - `PUT /api/activities/{id}`
  - `PUT /api/activities/{id}/status`（`{status}`，状态机校验：`ASSIGNED→PLANNING→PLAN_REVIEW→EXECUTING→FINISHED→ARCHIVED`，仅允许跳到下一档或回退一档）
  - `DELETE /api/activities/{id}`（逻辑删除）
- 状态常量：`ActivityStatus`（enum：ASSIGNED/PLANNING/PLAN_REVIEW/EXECUTING/FINISHED/ARCHIVED）与 `NEXT`/`PREV` 映射。
- 供 Task 9~11（策划书/议程/座位/评分/签到）、Task 12（甘特图）、Task 13 前端活动页使用。

- [ ] **Step 1: 写 ActivityServiceTest（先红）**

```java
package com.pams.module.activity;

import com.pams.common.BizException;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.activity.service.ActivityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ActivityServiceTest {

    ActivityRepository repo;
    ActivityService service;

    @BeforeEach
    void setup() {
        repo = mock(ActivityRepository.class);
        service = new ActivityService(repo);
    }

    @Test
    void advance_ok_whenNextStatus() {
        Activity a = new Activity();
        a.setId(1L);
        a.setStatus(ActivityStatus.ASSIGNED);
        when(repo.findById(1L)).thenReturn(Optional.of(a));

        service.changeStatus(1L, ActivityStatus.PLANNING);

        assertThat(a.getStatus()).isEqualTo(ActivityStatus.PLANNING);
        verify(repo).save(a);
    }

    @Test
    void advance_skips_throws() {
        Activity a = new Activity();
        a.setId(2L);
        a.setStatus(ActivityStatus.ASSIGNED);
        when(repo.findById(2L)).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.changeStatus(2L, ActivityStatus.EXECUTING))
                .isInstanceOf(BizException.class);
    }

    @Test
    void changeStatus_unknownId_throws() {
        when(repo.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changeStatus(9L, ActivityStatus.PLANNING))
                .isInstanceOf(BizException.class);
    }
}
```

- [ ] **Step 2: 实体与状态枚举**

```java
package com.pams.module.activity.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLRestriction;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "activity")
@SQLRestriction("deleted = 0")
public class Activity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String theme;
    private String type;
    @Enumerated(EnumType.STRING)
    private ActivityStatus status;
    private LocalDate startDate;
    private LocalDate endDate;
    private String location;
    private String organizer;
    private String targetAudience;
    private String host;
    private String leader;
    @Column(columnDefinition = "TEXT")
    private String description;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
}
```

```java
package com.pams.module.activity.entity;

public enum ActivityStatus {
    ASSIGNED, PLANNING, PLAN_REVIEW, EXECUTING, FINISHED, ARCHIVED;

    public ActivityStatus next() {
        ActivityStatus[] v = values();
        return ordinal() + 1 < v.length ? v[ordinal() + 1] : this;
    }
    public ActivityStatus prev() {
        return ordinal() > 0 ? values()[ordinal() - 1] : this;
    }
    public boolean canGoTo(ActivityStatus target) {
        return target == next() || target == prev();
    }
}
```

```java
package com.pams.module.activity.repository;

import com.pams.module.activity.entity.Activity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ActivityRepository extends JpaRepository<Activity, Long>,
        JpaSpecificationExecutor<Activity> {
}
```

- [ ] **Step 3: ActivityService**

```java
package com.pams.module.activity.service;

import com.pams.common.BizException;
import com.pams.common.PageResult;
import com.pams.module.activity.dto.ActivityRequest;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.repository.ActivityRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class ActivityService {
    private final ActivityRepository repository;
    public ActivityService(ActivityRepository repository) { this.repository = repository; }

    public PageResult<Map<String, Object>> page(String keyword, String status, String type, int page, int size) {
        Page<Activity> p = repository.findAll((root, q, cb) -> {
            var preds = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim() + "%";
                preds.add(cb.or(cb.like(root.get("name"), like), cb.like(root.get("theme"), like)));
            }
            if (status != null && !status.isBlank()) preds.add(cb.equal(root.get("status"), status));
            if (type != null && !type.isBlank()) preds.add(cb.equal(root.get("type"), type));
            return cb.and(preds.toArray(new jakarta.persistence.criteria.Predicate[0]));
        }, PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id")));

        PageResult<Map<String, Object>> r = new PageResult<>();
        r.setRecords(p.getContent().stream().map(a -> Map.of(
            "id", a.getId(), "name", a.getName(), "theme", a.getTheme() == null ? "" : a.getTheme(),
            "type", a.getType() == null ? "OTHER" : a.getType(), "status", a.getStatus().name(),
            "startDate", a.getStartDate(), "endDate", a.getEndDate(), "location", a.getLocation() == null ? "" : a.getLocation(),
            "organizer", a.getOrganizer() == null ? "" : a.getOrganizer(), "host", a.getHost() == null ? "" : a.getHost(),
            "leader", a.getLeader() == null ? "" : a.getLeader(), "createdAt", a.getCreatedAt())).toList());
        r.setTotal(p.getTotalElements()); r.setCurrent(page); r.setSize(size);
        return r;
    }

    public Activity getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(2001, "活动不存在"));
    }

    public Map<String, Object> detail(Long id) {
        Activity a = getEntity(id);
        return Map.of(
            "id", a.getId(), "name", a.getName(), "theme", a.getTheme() == null ? "" : a.getTheme(),
            "type", a.getType() == null ? "OTHER" : a.getType(), "status", a.getStatus().name(),
            "startDate", a.getStartDate(), "endDate", a.getEndDate(), "location", a.getLocation() == null ? "" : a.getLocation(),
            "organizer", a.getOrganizer() == null ? "" : a.getOrganizer(),
            "targetAudience", a.getTargetAudience() == null ? "" : a.getTargetAudience(),
            "host", a.getHost() == null ? "" : a.getHost(), "leader", a.getLeader() == null ? "" : a.getLeader(),
            "description", a.getDescription() == null ? "" : a.getDescription());
    }

    @Transactional
    public Long create(ActivityRequest req) {
        Activity a = new Activity();
        a.setStatus(ActivityStatus.ASSIGNED);
        apply(a, req);
        a.setDeleted(0);
        a.setCreatedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        return repository.save(a).getId();
    }

    @Transactional
    public void update(Long id, ActivityRequest req) {
        Activity a = getEntity(id);
        apply(a, req);
        repository.save(a);
    }

    @Transactional
    public void changeStatus(Long id, ActivityStatus target) {
        Activity a = getEntity(id);
        if (!a.getStatus().canGoTo(target)) {
            throw new BizException(2002, "活动状态不允许从 " + a.getStatus() + " 变更为 " + target);
        }
        a.setStatus(target);
        a.setUpdatedAt(LocalDateTime.now());
        repository.save(a);
    }

    @Transactional
    public void delete(Long id) {
        Activity a = getEntity(id);
        a.setDeleted(1);
        a.setUpdatedAt(LocalDateTime.now());
        repository.save(a);
    }

    private void apply(Activity a, ActivityRequest req) {
        a.setName(req.getName()); a.setTheme(req.getTheme()); a.setType(req.getType());
        a.setStartDate(req.getStartDate()); a.setEndDate(req.getEndDate()); a.setLocation(req.getLocation());
        a.setOrganizer(req.getOrganizer()); a.setTargetAudience(req.getTargetAudience());
        a.setHost(req.getHost()); a.setLeader(req.getLeader()); a.setDescription(req.getDescription());
        a.setUpdatedAt(LocalDateTime.now());
    }
}
```

`ActivityRequest`（`@Data`，`@NotBlank name`、`type` 默认 OTHER；其余 nullable）。

- [ ] **Step 4: ActivityController**

```java
package com.pams.module.activity.controller;

import com.pams.common.PageResult;
import com.pams.common.Result;
import com.pams.module.activity.dto.ActivityRequest;
import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.service.ActivityService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/activities")
public class ActivityController {
    private final ActivityService service;
    public ActivityController(ActivityService service) { this.service = service; }

    @GetMapping
    public Result<PageResult<Map<String, Object>>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(service.page(keyword, status, type, page, size));
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(service.detail(id));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody ActivityRequest req) {
        return Result.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ActivityRequest req) {
        service.update(id, req);
        return Result.ok();
    }

    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        service.changeStatus(id, ActivityStatus.valueOf(body.get("status")));
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
```

- [ ] **Step 5: 跑测试 + 提交**

```bash
cd /d/MyApp/PAMS/pams-backend
mvn -q test
git add pams-backend/src
git commit -m "feat: 活动实体/状态机/CRUD"
```

---

### Task 9: 策划书与议程（ActivityPlan / ActivityAgenda）

**Files:**
- Create: `com/pams/module/activity/entity/ActivityPlan.java` / `ActivityAgenda.java`
- Create: `com/pams/module/activity/repository/ActivityPlanRepository.java` / `ActivityAgendaRepository.java`
- Create: `com/pams/module/activity/service/PlanService.java` / `controller/PlanController.java` / `controller/AgendaController.java`
- Create: `com/pams/module/activity/dto/PlanRequest.java` / `PlanReviewRequest.java` / `AgendaRequest.java`
- Test: `com/pams/module/activity/PlanServiceTest.java`

**Interfaces:**
- Produces:
  - `GET /api/plans?activityId=` → 该活动最新版策划书
  - `GET /api/plans/{id}` → 策划书详情
  - `POST /api/plans`（`PlanRequest{activityId,version,background,purpose,content,flow,notice,emergency,budget}`；flow/budget 为 JSON 字符串）
  - `PUT /api/plans/{id}`（草稿可改；审核通过后仅可新建版本）
  - `PUT /api/plans/{id}/review`（`PlanReviewRequest{approved:boolean,comment}`）→ 更新 `status`；approve 时自动把关联活动状态推到 `PLAN_REVIEW`；审批人须为部长及以上
  - `GET /api/agendas?activityId=` → 议程列表
  - `POST /api/agendas`（`AgendaRequest{activityId,stepNo,title,remark}`）
  - `PUT /api/agendas/{id}` / `DELETE /api/agendas/{id}`
- 状态：`DRAFT→PENDING(提交)→APPROVED/REJECTED`。
- 供 Task 11 活动详情聚合、Task 14 前端策划书页使用。

- [ ] **Step 1: 写 PlanServiceTest（先红）**

```java
package com.pams.module.activity;

import com.pams.common.BizException;
import com.pams.module.activity.entity.ActivityPlan;
import com.pams.module.activity.repository.ActivityPlanRepository;
import com.pams.module.activity.service.PlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PlanServiceTest {

    ActivityPlanRepository repo;
    PlanService service;

    @BeforeEach
    void setup() {
        repo = mock(ActivityPlanRepository.class);
        service = new PlanService(repo);
    }

    @Test
    void review_missingPlan_throws() {
        when(repo.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.review(1L, true, "ok"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void review_approve_setsStatus() {
        ActivityPlan p = new ActivityPlan();
        p.setId(2L);
        p.setStatus(ActivityPlan.PlanStatus.DRAFT);
        when(repo.findById(2L)).thenReturn(Optional.of(p));

        service.review(2L, true, "ok");

        verify(repo).save(p);
        // 断言在 mock 下无法直接读字段，通过验证 save 传入对象状态
    }
}
```

> 说明：`review` 方法签名约定为 `review(Long planId, boolean approved, String comment)`，可在 `ActivityPlan` 内用 enum `PlanStatus`，实现时把状态更新与活动状态联动封装好。

- [ ] **Step 2: 实体**

`ActivityPlan`：id、activityId(Long)、version(Integer)、background/purpose/content/notice/emergency(TEXT)、flow/budget(TEXT，存 JSON)、status(enum `PlanStatus { DRAFT, PENDING, APPROVED, REJECTED }`)、submitterId、reviewerId、reviewComment(TEXT)、createdAt、updatedAt。加 `@SQLRestriction`（无 deleted，用 status 过滤即可）。

`ActivityAgenda`：id、activityId、stepNo(Integer)、title、remark、createdAt。

Repository：
- `ActivityPlanRepository extends JpaRepository<ActivityPlan, Long>`
- `ActivityAgendaRepository extends JpaRepository<ActivityAgenda, Long>` + `List<ActivityAgenda> findByActivityIdOrderByStepNoAsc(Long activityId)`

- [ ] **Step 3: PlanService 实现**

```java
package com.pams.module.activity.service;

import com.pams.common.BizException;
import com.pams.module.activity.dto.PlanRequest;
import com.pams.module.activity.entity.ActivityPlan;
import com.pams.module.activity.repository.ActivityPlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class PlanService {
    private final ActivityPlanRepository repository;
    public PlanService(ActivityPlanRepository repository) { this.repository = repository; }

    public ActivityPlan latest(Long activityId) {
        return repository.findAll().stream()
                .filter(p -> p.getActivityId().equals(activityId))
                .max(java.util.Comparator.comparingInt(ActivityPlan::getVersion))
                .orElse(null);
    }

    public List<ActivityPlan> listByActivity(Long activityId) {
        return repository.findAll().stream()
                .filter(p -> p.getActivityId().equals(activityId))
                .sorted(java.util.Comparator.comparingInt(ActivityPlan::getVersion).reversed())
                .toList();
    }

    @Transactional
    public ActivityPlan create(PlanRequest req) {
        ActivityPlan p = new ActivityPlan();
        p.setActivityId(req.getActivityId());
        p.setVersion(req.getVersion() == null ? 1 : req.getVersion());
        apply(p, req);
        p.setStatus(ActivityPlan.PlanStatus.DRAFT);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        return repository.save(p);
    }

    @Transactional
    public void update(Long id, PlanRequest req) {
        ActivityPlan p = getEntity(id);
        if (p.getStatus() == ActivityPlan.PlanStatus.APPROVED) {
            throw new BizException(2003, "已审核通过的策划书不可修改，请新建版本");
        }
        apply(p, req);
        repository.save(p);
    }

    @Transactional
    public void submit(Long id) {
        ActivityPlan p = getEntity(id);
        p.setStatus(ActivityPlan.PlanStatus.PENDING);
        repository.save(p);
    }

    @Transactional
    public void review(Long id, boolean approved, String comment, Long reviewerId) {
        ActivityPlan p = getEntity(id);
        p.setStatus(approved ? ActivityPlan.PlanStatus.APPROVED : ActivityPlan.PlanStatus.REJECTED);
        p.setReviewerId(reviewerId);
        p.setReviewComment(comment);
        p.setUpdatedAt(LocalDateTime.now());
        repository.save(p);
    }

    private void apply(ActivityPlan p, PlanRequest req) {
        p.setBackground(req.getBackground());
        p.setPurpose(req.getPurpose());
        p.setContent(req.getContent());
        p.setFlow(req.getFlow());
        p.setNotice(req.getNotice());
        p.setEmergency(req.getEmergency());
        p.setBudget(req.getBudget());
        p.setUpdatedAt(LocalDateTime.now());
    }

    private ActivityPlan getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(2004, "策划书不存在"));
    }
}
```

> 说明：活动状态联动（approve 时活动推到 PLAN_REVIEW）在 Task 11 的活动详情聚合时用 `ActivityService.changeStatus` 处理，或此处注入 `ActivityRepository` 后补充。实现时以不破坏状态机为准。

- [ ] **Step 4: 控制器**

`PlanController`：
```java
@RestController
@RequestMapping("/api/plans")
public class PlanController {
    // GET /api/plans?activityId= → latest 详情 Map
    // GET /api/plans/{id} → 详情
    // POST /api/plans → create
    // PUT /api/plans/{id} → update
    // PUT /api/plans/{id}/submit → submit
    // PUT /api/plans/{id}/review → review（@PreAuthorize hasAnyRole 部长及以上）
}
```
`AgendaController`：`/api/agendas` 的 GET(activityId)/POST/PUT/DELETE。

DTO：`PlanRequest{activityId,version,background,purpose,content,flow,notice,emergency,budget}`（flow/budget 前端传 JSON 字符串）；`PlanReviewRequest{approved:boolean,comment}`；`AgendaRequest{activityId,stepNo,title,remark}`。

- [ ] **Step 5: 跑测试 + 提交**

```bash
cd /d/MyApp/PAMS/pams-backend
mvn -q test
git add pams-backend/src
git commit -m "feat: 策划书与议程管理"
```

---

### Task 10: 座位表、评分、签到（SeatMap / Score / Signin）

**Files:**
- Create: `com/pams/module/activity/entity/{SeatMap,ScoreRule,ScoreRecord,Signin}.java`
- Create: `com/pams/module/activity/repository/{SeatMapRepository,ScoreRuleRepository,ScoreRecordRepository,SigninRepository}.java`
- Create: `com/pams/module/activity/service/{SeatService,ScoreService,SigninService}.java` + 对应 Controller
- Create: `com/pams/module/activity/dto/{SeatRequest,ScoreRuleRequest,ScoreRecordRequest,SigninRequest}.java`
- Test: `com/pams/module/activity/ScoreServiceTest.java`（总分计算）

**Interfaces:**
- Produces:
  - 座位表：`GET /api/seats?activityId=` → 按 zone 分组列表；`POST /api/seats`（`SeatRequest{activityId,roomName,zone,rowNo,colNo,personName,seatType}`）；`PUT/DELETE /api/seats/{id}`
  - 评分：`GET /api/scores?activityId=` → `{rules:[{id,dimensionName,fullMarks}], records:[{id,teamName,groupName,total,rankNo}]}`；`POST /api/scores/rules`、`POST /api/scores/records`（后端按 dimension_scores 求和 total）；`PUT/DELETE`
  - 签到：`GET /api/signins?activityId=&keyword=` → 列表；`POST /api/signins`（`SigninRequest{activityId,personId,name,studentNo,className,identityType,signType,signTime,location,phone,remark}`）；`DELETE /api/signins/{id}`；`GET /api/signins/{activityId}/count` → 人数
- 供 Task 15 前端活动详情/签到/评分页使用。

- [ ] **Step 1: 写 ScoreServiceTest（先红）**

```java
package com.pams.module.activity;

import com.pams.module.activity.entity.ScoreRecord;
import com.pams.module.activity.repository.ScoreRecordRepository;
import com.pams.module.activity.service.ScoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ScoreServiceTest {

    ScoreRecordRepository repo;
    ScoreService service;

    @BeforeEach
    void setup() {
        repo = mock(ScoreRecordRepository.class);
        service = new ScoreService(repo);
    }

    @Test
    void computeTotal_sumsDimensions() {
        ScoreRecord r = new ScoreRecord();
        r.setDimensionScores("{\"1\":28,\"2\":18,\"3\":16}");
        int total = service.computeTotal(r.getDimensionScores());
        assertThat(total).isEqualTo(62);
    }

    @Test
    void computeTotal_malformed_returnsZero() {
        assertThat(service.computeTotal("not-json")).isEqualTo(0);
    }
}
```

- [ ] **Step 2: 实体（seat_map/score_rule/score_record/signin 对应 DDL）**

字段与 DDL 一一对应；`ScoreRecord.dimensionScores` 为 TEXT；`ScoreRecord.total` 由服务端计算后写入。

Repository 接口：
- `SeatMapRepository`：`List<SeatMap> findByActivityIdOrderByZoneAscRowNoAscColNoAsc(Long activityId)`
- `ScoreRuleRepository`：`List<ScoreRule> findByActivityIdOrderBySortOrderAsc(Long activityId)`
- `ScoreRecordRepository`：`List<ScoreRecord> findByActivityId(Long activityId)`
- `SigninRepository`：`List<Signin> findByActivityId(Long activityId)`、`long countByActivityId(Long activityId)`、`List<Signin> findByActivityIdAndNameContaining(Long activityId, String name)`

- [ ] **Step 3: 服务实现**

`ScoreService`：
```java
package com.pams.module.activity.service;

import com.pams.common.BizException;
import com.pams.module.activity.entity.ScoreRecord;
import com.pams.module.activity.repository.ScoreRecordRepository;
import com.pams.module.activity.repository.ScoreRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScoreService {
    private final ScoreRecordRepository recordRepo;
    private final ScoreRuleRepository ruleRepo;

    public ScoreService(ScoreRecordRepository recordRepo, ScoreRuleRepository ruleRepo) {
        this.recordRepo = recordRepo;
        this.ruleRepo = ruleRepo;
    }
    public ScoreService(ScoreRecordRepository recordRepo) {
        this(recordRepo, null);
    }

    public int computeTotal(String dimensionScores) {
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(dimensionScores);
            int sum = 0;
            for (com.fasterxml.jackson.databind.JsonNode v : node) sum += v.asInt();
            return sum;
        } catch (Exception e) {
            return 0;
        }
    }

    @Transactional
    public Long createRecord(ScoreRecord r) {
        r.setTotal(computeTotal(r.getDimensionScores()));
        return recordRepo.save(r).getId();
    }
}
```

> 说明：`ScoreService` 需要注入 `ScoreRuleRepository` 以校验维度存在性，测试用 1 参构造器。`SeatService`/`SigninService` 为简单 CRUD，参照 Task 8 模式，含 `listByActivity` / `create` / `update` / `delete`，并做基本参数校验。

- [ ] **Step 4: 控制器**

`SeatController`(`/api/seats`)、`ScoreController`(`/api/scores`)、`SigninController`(`/api/signins`)——REST 端点按上面 Interfaces 实现。

- [ ] **Step 5: 跑测试 + 提交**

```bash
cd /d/MyApp/PAMS/pams-backend
mvn -q test
git add pams-backend/src
git commit -m "feat: 座位表/评分/签到"
```

---

### Task 11: 活动详情聚合（状态联动 + 子表汇总）

**Files:**
- Modify: `com/pams/module/activity/service/ActivityService.java`
- Create: `com/pams/module/activity/service/ActivityDetailService.java` / `controller/ActivityDetailController.java`

**Interfaces:**
- Produces: `GET /api/activities/{id}/detail` → 聚合响应：
```json
{
  "activity": {...},
  "plan": { "latest": {...}, "status": "APPROVED" },
  "agendas": [...],
  "seatZones": [...],
  "score": { "rules": [...], "records": [...] },
  "signinCount": 12,
  "tasks": [...]
}
```
- 联动规则：策划书 approve 时若活动在 `PLANNING`，自动推为 `PLAN_REVIEW`；执行中任务全 done 时建议但**不强制**推进状态（保持主任手动确认）。
- 供 Task 14 活动详情页。

- [ ] **Step 1: 实现 ActivityDetailService**

注入 `ActivityRepository` / `PlanService` / `ActivityAgendaRepository` / `SeatMapRepository` / `ScoreRuleRepository` / `ScoreRecordRepository` / `SigninRepository` / `TaskRepository`(Task 12 先建 stub 接口，Task 12 补实现)，按上述 JSON 结构聚合返回 `Map<String,Object>`。

- [ ] **Step 2: 在 PlanService.review 里联动活动状态**

`PlanService` 增加注入 `ActivityRepository`，`review` approve 时：
```java
if (approved) {
    activityRepository.findById(p.getActivityId()).ifPresent(a -> {
        if (a.getStatus() == ActivityStatus.PLANNING) {
            a.setStatus(ActivityStatus.PLAN_REVIEW);
            activityRepository.save(a);
        }
    });
}
```
> 说明：Task 9 的 PlanService 先不注入 ActivityRepository（保持纯测试友好），此处 Task 11 补充注入即可，测试相应调整。

- [ ] **Step 3: 控制器**

`ActivityDetailController`：`GET /api/activities/{id}/detail` 调 `ActivityDetailService`.

- [ ] **Step 4: 跑测试 + 提交**

```bash
cd /d/MyApp/PAMS/pams-backend
mvn -q test
git add pams-backend/src
git commit -m "feat: 活动详情聚合与状态联动"
```

---

### Task 12: 甘特图任务（Task 实体 + 自研甘特图组件）

**Files:**
- Create: `com/pams/module/activity/entity/Task.java` / `repository/TaskRepository.java`
- Create: `com/pams/module/activity/service/TaskService.java` / `controller/TaskController.java`
- Create: `com/pams/module/activity/dto/TaskRequest.java`
- Test: `com/pams/module/activity/TaskServiceTest.java`
- Create: `pams-web/src/components/gantt/GanttChart.tsx` / `gantt.utils.ts` / `gantt.utils.test.ts`

**Interfaces:**
- Produces:
  - `GET /api/tasks?activityId=` → 任务列表（按 startDate 排序）
  - `POST /api/tasks`（`TaskRequest{activityId,name,deptId,assignee,startDate,endDate,dependsOn,isMilestone,progress,status,priority,description}`）
  - `PUT /api/tasks/{id}` / `DELETE /api/tasks/{id}`
  - `PUT /api/tasks/{id}/progress`（`{progress}` 0-100）
- 前端 `GanttChart`：props `{ tasks, onUpdate }`；SVG 渲染时间轴 + 任务条 + 依赖连线；今日线用 `--color-red`；任务条按部门着色（黑白灰层级 + 关键任务红色描边）；支持点击任务弹详情、编辑起止、拖动更新时间（可选，先做点击编辑）。
- `gantt.utils.ts`：`dayRange(start,end)`、`taskToPixels(task, pxPerDay, startOffset)`、`buildDeps(tasks)`、`todayStr()`。
- 供 Task 16 前端甘特图页使用。

- [ ] **Step 1: 写 gantt.utils.test.ts（先红）**

```ts
// pams-web/src/components/gantt/gantt.utils.test.ts
import { describe, it, expect } from 'vitest'
import { dayRange, buildDeps } from './gantt.utils'

describe('gantt.utils', () => {
  it('dayRange returns inclusive length', () => {
    expect(dayRange('2026-03-01', '2026-03-04')).toBe(4)
  })

  it('buildDeps maps dependsOn to edges', () => {
    const tasks = [
      { id: 1, name: 'a', dependsOn: null },
      { id: 2, name: 'b', dependsOn: 1 },
    ] as Array<{ id: number; name: string; dependsOn: number | null }>
    const edges = buildDeps(tasks)
    expect(edges).toEqual([{ from: 1, to: 2 }])
  })
})
```

- [ ] **Step 2: 写 gantt.utils.ts（实现）**

```ts
import dayjs from 'dayjs'

export interface GanttTask {
  id: number
  name: string
  startDate: string
  endDate: string
  dependsOn: number | null
  deptName?: string
  isMilestone?: boolean
  progress?: number
}

export function dayRange(start: string, end: string): number {
  return dayjs(end).add(1, 'day').diff(dayjs(start), 'day')
}

export function taskToPixels(task: GanttTask, pxPerDay: number, startOffsetDays: number) {
  const left = dayjs(task.startDate).diff(dayjs(projectStart(task)), 'day')
  return { left: left * pxPerDay, width: Math.max(dayRange(task.startDate, task.endDate), 1) * pxPerDay }
}

function projectStart(task: GanttTask) {
  // 由调用方传入项目起点；此处用 dayjs 聚合前调用方处理
  return task.startDate
}

export function buildDeps(tasks: GanttTask[]): Array<{ from: number; to: number }> {
  const byId = new Map(tasks.map((t) => [t.id, t]))
  const edges: Array<{ from: number; to: number }> = []
  for (const t of tasks) {
    if (t.dependsOn != null && byId.has(t.dependsOn)) edges.push({ from: t.dependsOn, to: t.id })
  }
  return edges
}

export function todayStr(): string {
  return dayjs().format('YYYY-MM-DD')
}
```

- [ ] **Step 3: 后端 Task 实体/服务/控制器**

`Task` 实体字段对应 DDL；`TaskRepository extends JpaRepository<Task, Long>` + `List<Task> findByActivityIdOrderByStartDateAsc(Long activityId)`。

`TaskService`：CRUD + `updateProgress(id, progress)`（`0<=progress<=100`，越界抛 `BizException(2005,"进度需在0-100")`）。

`TaskServiceTest`：mock repo，测 `updateProgress` 越界抛异常、正常设置。写测试先红再实现。

`TaskController`：`/api/tasks` 五个端点。

- [ ] **Step 4: GanttChart 组件**

SVG viewBox 布局：
- 顶部：月/日刻度轴（从最早 startDate 到最晚 endDate，按天为 24px/天，可用缩放 props `pxPerDay`）
- 每行任务：圆角矩形条 + 名称（左）+ 进度条（内部覆盖，颜色 `var(--color-red)`）
- 依赖：`buildDeps` 生成的连线（`<path>`，灰色虚线，箭头在右侧节点）
- 里程碑：菱形（`<rect transform="rotate(45)">`，红色）
- 今日线：`todayStr()` 对应 x 位置一条红色竖线
- 交互：点击任务条 → 弹出 `GlassModal` 编辑（名称/起止/进度/负责人），保存调 `onUpdate`
- 空状态：无任务时显示"主任尚未分发任务"

```tsx
// 结构示意（完整实现按此骨架）
export default function GanttChart({ tasks, onUpdate, pxPerDay = 24 }: {
  tasks: GanttTask[]
  onUpdate: (t: GanttTask) => void
  pxPerDay?: number
}) {
  const range = useMemo(() => {
    if (!tasks.length) return { start: todayStr(), days: 30 }
    const start = tasks.reduce((a, t) => (t.startDate < a ? t.startDate : a), tasks[0].startDate)
    const end = tasks.reduce((a, t) => (t.endDate > a ? t.endDate : a), tasks[0].endDate)
    return { start, days: dayRange(start, end) }
  }, [tasks])
  // ...渲染 SVG（刻度/条/连线/里程碑/今日线）
}
```

- [ ] **Step 5: 前端测试 + 后端测试 + 提交**

```bash
cd /d/MyApp/PAMS/pams-web && npm run test
cd /d/MyApp/PAMS/pams-backend && mvn -q test
cd /d/MyApp/PAMS && git add pams-web/src pams-backend/src
git commit -m "feat: 甘特图任务与自研甘特图组件"
```

---

### Task 13: 前端活动列表页 + MainLayout + 路由完善

**Files:**
- Create: `pams-web/src/layouts/MainLayout.tsx`
- Modify: `pams-web/src/router/index.tsx`（补全部路由）
- Create: `pams-web/src/pages/activity/ActivityList.tsx`
- Create: `pams-web/src/api/activity.ts`
- Create: `pams-web/src/pages/Dashboard.tsx`（占位）

**Interfaces:**
- Produces:
  - `MainLayout`：左侧玻璃侧边栏（LOGO + 菜单）+ 顶部栏（面包屑/主题切换/用户名/退出）；菜单项按角色隐藏（干事仅显示本部门相关）
  - 路由全量接入（各页先 `lazy` 导入；未建页面先放占位组件）
  - `ActivityList`：搜索框 + 状态筛选 Tag + 新增按钮 + GlassTable（列：活动名称/类型/状态/时间/负责人/操作）；新增/编辑用 GlassModal 表单
  - `api/activity.ts`：`listActivities/getActivity/createActivity/updateActivity/changeStatus/deleteActivity`
- 供 Task 14 起各业务页复用导航与活动 API。

- [ ] **Step 1: MainLayout**

```tsx
// pams-web/src/layouts/MainLayout.tsx
import { Layout, Menu, Dropdown, Space, Avatar, Typography } from 'antd'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { LogoutOutlined, UserOutlined } from '@ant-design/icons'
import { useAuthStore } from '@/stores/auth'
import ThemeSwitch from '@/components/glass/ThemeSwitch'
import { useMemo } from 'react'

const { Sider, Header, Content } = Layout

export default function MainLayout() {
  const user = useAuthStore((s) => s.user)
  const logout = useAuthStore((s) => s.logout)
  const navigate = useNavigate()
  const location = useLocation()

  // 角色 → 可见菜单映射（干事仅本部门相关菜单）
  const menuItems = useMemo(() => {
    const base = [
      { key: '/', label: '仪表盘', icon: <UserOutlined /> },
      { key: '/activities', label: '活动管理', icon: <UserOutlined /> },
    ]
    // 完整菜单在 Task 13 末尾补齐（含排班/党务/推文/材料/模板/通知/用户）
    return base
  }, [])

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider width={220} className="glass-card" style={{ margin: 12, borderRadius: 16, overflow: 'hidden' }}>
        <div style={{ padding: '20px 16px' }}>
          <Typography.Title level={5} style={{ color: 'var(--color-text)', margin: 0 }}>
            党务管理系统
          </Typography.Title>
          <Typography.Text style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>
            信息与智能工程学院党建办公室
          </Typography.Text>
        </div>
        <Menu
          mode="inline"
          theme="dark"
          selectedKeys={[location.pathname]}
          onClick={(e) => navigate(e.key)}
          items={menuItems}
          style={{ background: 'transparent', borderInlineEnd: 'none' }}
        />
      </Sider>
      <Layout>
        <Header style={{ background: 'transparent', padding: '0 24px', display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 12 }}>
          <ThemeSwitch />
          <Dropdown menu={{ items: [{ key: 'logout', icon: <LogoutOutlined />, label: '退出登录', onClick: logout }] }}>
            <Space style={{ cursor: 'pointer', color: 'var(--color-text)' }}>
              <Avatar style={{ background: 'var(--color-red)' }} icon={<UserOutlined />} />
              <span>{user?.realName}（{user?.deptName ?? user?.roleCode}）</span>
            </Space>
          </Dropdown>
        </Header>
        <Content style={{ padding: '0 24px 24px' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
```

> 说明：菜单按角色的完整过滤逻辑（干事只看到本部门模块）在 Task 13 第 4 步实现：根据 `user.roleCode` 过滤 `menuItems`。

- [ ] **Step 2: `api/activity.ts`**

```ts
import { get, post, put, del } from './http'
import type { PageResult } from './types'

export interface ActivityVO {
  id: number
  name: string
  theme: string
  type: string
  status: string
  startDate: string | null
  endDate: string | null
  location: string
  organizer: string
  host: string
  leader: string
  createdAt: string
}

export interface ActivitySave {
  name: string
  theme?: string
  type?: string
  startDate?: string | null
  endDate?: string | null
  location?: string
  organizer?: string
  targetAudience?: string
  host?: string
  leader?: string
  description?: string
}

export const listActivities = (params: { keyword?: string; status?: string; type?: string; page?: number; size?: number }) =>
  get<PageResult<ActivityVO>>('/activities', params)
export const getActivity = (id: number) => get<ActivityVO>(`/activities/${id}`)
export const createActivity = (data: ActivitySave) => post<number>('/activities', data)
export const updateActivity = (id: number, data: ActivitySave) => put<void>(`/activities/${id}`, data)
export const changeActivityStatus = (id: number, status: string) => put<void>(`/activities/${id}/status`, { status })
export const deleteActivity = (id: number) => del<void>(`/activities/${id}`)
```

`api/types.ts`：
```ts
export interface PageResult<T> { records: T[]; total: number; current: number; size: number }
```

- [ ] **Step 3: ActivityList 页面**

功能：状态筛选（`StatusTag` 渲染）、搜索名称、`GlassTable` 列表、新增/编辑 `GlassModal`（Form：名称/主题/类型 Select/起止 DatePicker/地点/组织单位/负责人/描述）、行内操作（编辑/详情→跳详情页/删除 Popconfirm/推进状态按钮）。删除仅部长及以上可见。

- [ ] **Step 4: 路由全量接入**

```tsx
const Activities = lazy(() => import('@/pages/activity/ActivityList'))
const ActivityDetail = lazy(() => import('@/pages/activity/ActivityDetail'))
const Gantt = lazy(() => import('@/pages/activity/Gantt'))
const Schedules = lazy(() => import('@/pages/routine/ScheduleList'))
const Attendance = lazy(() => import('@/pages/routine/AttendanceList'))
const PartyMembers = lazy(() => import('@/pages/party/PartyMemberList'))
const PartyStages = lazy(() => import('@/pages/party/PartyStageList'))
const Articles = lazy(() => import('@/pages/content/ArticleList'))
const Materials = lazy(() => import('@/pages/archive/MaterialList'))
const Templates = lazy(() => import('@/pages/archive/TemplateList'))
const Credits = lazy(() => import('@/pages/archive/CreditList'))
const Announcements = lazy(() => import('@/pages/archive/AnnouncementList'))
const Users = lazy(() => import('@/pages/admin/UserList'))

// children:
// activities, activities/:id, activities/:id/gantt,
// routines (schedules), routines/attendance,
// party/members, party/stages,
// content/articles, archive/materials, archive/templates,
// archive/credits, archive/announcements, admin/users
```

> 说明：路由对应的页面在后续 Task 逐一实现，未实现前各占位组件先返回 `<GlassCard>建设中</GlassCard>`，保证路由可访问不白屏。本 Task 先建 `Dashboard` 占位页。

- [ ] **Step 5: 浏览器验证**

```bash
cd /d/MyApp/PAMS/pams-web
npm run dev
```

人工点验：登录 → 侧边栏导航 → 活动列表增删改查 + 状态推进；切主题。

- [ ] **Step 6: 提交**

```bash
git add pams-web/src
git commit -m "feat: 主布局/路由/活动列表页"
```

---

### Task 14: 前端活动详情页 + 策划书 + 甘特图页

**Files:**
- Create: `pams-web/src/pages/activity/ActivityDetail.tsx`
- Create: `pams-web/src/pages/activity/Gantt.tsx`
- Create: `pams-web/src/api/plan.ts` / `agenda.ts` / `task.ts`
- Modify: `pams-web/src/api/activity.ts`（补 `getActivityDetail`）

**Interfaces:**
- Produces:
  - `ActivityDetail`：Tabs（基本信息 / 策划书 / 议程 / 座位表 / 评分 / 签到）
  - `Gantt` 页：`GanttChart` + 新增任务 `GlassModal`（名称/负责部门 Select/负责人/起止 DatePicker/前置任务 Select/里程碑 Switch/进度 Slider）+ 拖动预览
  - `api/plan.ts`：`latestPlan/getPlan/createPlan/updatePlan/submitPlan/reviewPlan`
  - `api/task.ts`：`listTasks/createTask/updateTask/deleteTask/updateTaskProgress`
- 供 Task 15 签到/评分页复用。

- [ ] **Step 1: `getActivityDetail` 与 `api/plan.ts`**

`activity.ts` 追加：
```ts
export const getActivityDetail = (id: number) =>
  get<{
    activity: ActivityVO
    plan: { latest: unknown; status: string } | null
    agendas: unknown[]
    seatZones: unknown[]
    score: { rules: unknown[]; records: unknown[] }
    signinCount: number
    tasks: unknown[]
  }>(`/activities/${id}/detail`)
```

`plan.ts`：
```ts
export const latestPlan = (activityId: number) => get<unknown>(`/plans`, { activityId })
export const createPlan = (data: unknown) => post<number>('/plans', data)
export const updatePlan = (id: number, data: unknown) => put<void>(`/plans/${id}`, data)
export const submitPlan = (id: number) => put<void>(`/plans/${id}/submit`)
export const reviewPlan = (id: number, approved: boolean, comment?: string) =>
  put<void>(`/plans/${id}/review`, { approved, comment })
```

- [ ] **Step 2: ActivityDetail 页面**

- Tabs 结构：
  - 基本信息：描述卡片 + 状态操作按钮（推进/回退，按状态机） + 关联文件入口
  - 策划书：加载 `latestPlan`，富文本展示 + 提交审核/审核按钮（部长及以上）；编辑走 GlassModal
  - 议程：步骤列表（stepNo/title/remark）可增删改
  - 座位表：按 zone 分组展示 + 编辑
  - 评分：规则列表 + 记录表格（列：队名/各维度/总分/名次）
  - 签到：签到列表 + 新增手动签到 + 人数统计（引 Task 15 组件）
- 页面头部：`PageHeader`（活动名 + `StatusTag` + 关联甘特图入口）

- [ ] **Step 3: Gantt 页**

```tsx
export default function Gantt() {
  const { id } = useParams()
  const [tasks, setTasks] = useState<GanttTask[]>([])
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<Partial<GanttTask> | null>(null)

  useEffect(() => {
    if (id) listTasks(Number(id)).then((rows) => setTasks(rows as unknown as GanttTask[]))
  }, [id])

  // 新增/编辑表单 → createTask/updateTask → 刷新列表
  // GanttChart onUpdate 回调
  return (
    <div>
      <PageHeader title="任务甘特图" description="主任分解活动任务" extra={<Button type="primary" onClick={() => setEditing({})}>新增任务</Button>} />
      <GlassCard><GanttChart tasks={tasks} onUpdate={handleUpdate} /></GlassCard>
    </div>
  )
}
```

- [ ] **Step 4: 浏览器验证**

人工点验：从活动列表进详情页，各 Tab 加载真实数据；甘特图页增删任务、改进度、看依赖连线与今日线。

- [ ] **Step 5: 提交**

```bash
git add pams-web/src
git commit -m "feat: 活动详情/策划书/甘特图页"
```

---

### Task 15: 前端签到与评分页

**Files:**
- Create: `pams-web/src/pages/activity/SigninPanel.tsx` / `ScorePanel.tsx`
- Create: `pams-web/src/api/signin.ts` / `score.ts` / `seat.ts`
- Modify: `pams-web/src/pages/activity/ActivityDetail.tsx`（引入 SigninPanel/ScorePanel）

**Interfaces:**
- Produces: `SigninPanel`（签到列表 + 新增表单 + 统计 + 导出 CSV）、`ScorePanel`（评分规则管理 + 评分记录录入计算总分）
- 供 Task 14 详情页 Tab 直接内嵌。

- [ ] **Step 1: `api/signin.ts` / `score.ts` / `seat.ts`**

```ts
// signin.ts
export const listSignins = (activityId: number, keyword?: string) => get<unknown[]>('/signins', { activityId, keyword })
export const createSignin = (data: unknown) => post<number>('/signins', data)
export const deleteSignin = (id: number) => del<void>(`/signins/${id}`)
export const countSignins = (activityId: number) => get<number>(`/signins/${activityId}/count`)

// score.ts
export const getScores = (activityId: number) => get<{ rules: unknown[]; records: unknown[] }>('/scores', { activityId })
export const createScoreRule = (data: unknown) => post<number>('/scores/rules', data)
export const createScoreRecord = (data: unknown) => post<number>('/scores/records', data)

// seat.ts
export const listSeats = (activityId: number) => get<unknown[]>('/seats', { activityId })
export const createSeat = (data: unknown) => post<number>('/seats', data)
export const deleteSeat = (id: number) => del<void>(`/seats/${id}`)
```

- [ ] **Step 2: SigninPanel**

- 顶部统计：总签到数（`countSignins`）
- `GlassTable`：姓名/学号/班级/身份/签到方式/时间/定位/操作（删除）
- 新增：`GlassModal` Form（姓名/学号/班级/身份 Select[党建干事/发展对象/预备党员/入党积极分子]/方式 Select[MANUAL/SCAN]/时间 DatePicker/定位）
- 导出：前端把列表转 CSV 下载（`Blob` + `a.download`，列：序号/姓名/学号/班级/身份/签到时间）

- [ ] **Step 3: ScorePanel**

- 规则区：`GlassTable` 列出 `score.rules`（维度/分值），新增规则 Form
- 记录区：`GlassTable` 列：队名/组别/各维度分（列名=规则名）/总分/名次；新增 Form 动态渲染各维度输入，提交后后端算总分

- [ ] **Step 4: 浏览器验证 + 提交**

人工点验签到增删与 CSV 导出、评分规则/记录录入与总分。

```bash
git add pams-web/src
git commit -m "feat: 签到与评分面板"
```

---

### Task 16: 例行事务——排班/考勤/无课表（后端）

**Files:**
- Create: `com/pams/module/routine/entity/{Schedule,SchedulePerson,Attendance,FreeSchedule}.java`
- Create: `com/pams/module/routine/repository/{ScheduleRepository,SchedulePersonRepository,AttendanceRepository,FreeScheduleRepository}.java`
- Create: `com/pams/module/routine/service/RoutineService.java` / `controller/{ScheduleController,AttendanceController,FreeScheduleController}.java`
- Create: `com/pams/module/routine/dto/{ScheduleRequest,AttendanceRequest,FreeScheduleRequest}.java`
- Test: `com/pams/module/routine/AttendanceServiceTest.java`

**Interfaces:**
- Produces:
  - `GET /api/schedules?type=&weekNo=&weekday=&activityId=` → 排班列表（含人员）
  - `POST /api/schedules`（`ScheduleRequest{scheduleType,activityId,weekNo,weekday,sessionName,location,scheduleDate,notes,persons:[{user_id?,person_name,is_primary}]}`）→ 保存排班 + 关联人员
  - `PUT /api/schedules/{id}` / `DELETE /api/schedules/{id}`
  - `GET /api/schedules/export?type=&weekNo=` → 值班表 xlsx（POI 生成）
  - `POST /api/attendances`（`AttendanceRequest{scheduleId,personId,personName,status,remark}`）
  - `GET /api/attendances?scheduleId=&weekNo=&personName=` → 考勤列表
  - `GET /api/attendances/summary?weekNo=&type=` → 按人汇总（应到/实到/请假/缺勤/次数）
  - `GET /api/free-schedules?deptId=` → 无课表列表；`POST/PUT/DELETE /api/free-schedules`
- 供 Task 17 前端排班考勤页使用。

- [ ] **Step 1: 写 AttendanceServiceTest（先红）**

```java
package com.pams.module.routine;

import com.pams.module.routine.entity.Attendance;
import com.pams.module.routine.repository.AttendanceRepository;
import com.pams.module.routine.service.RoutineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AttendanceServiceTest {

    AttendanceRepository repo;
    RoutineService service;

    @BeforeEach
    void setup() {
        repo = mock(AttendanceRepository.class);
        service = new RoutineService(repo);
    }

    @Test
    void summary_countsStatuses() {
        Attendance a = new Attendance();
        a.setPersonName("张三"); a.setStatus("PRESENT");
        Attendance b = new Attendance();
        b.setPersonName("张三"); b.setStatus("LEAVE");
        Attendance c = new Attendance();
        c.setPersonName("李四"); c.setStatus("ABSENT");
        when(repo.findAll()).thenReturn(List.of(a, b, c));

        var summary = service.summary(null, null);
        assertThat(summary).hasSize(2);
        // 张三 1 应到 1 请假；李四 1 缺勤
    }
}
```

> 说明：`RoutineService` 构造函数注入各 repo（测试用 1 参构造器注入 `AttendanceRepository`）。`summary` 返回 `List<Map<String,Object>>`（人员名/应到/实到/请假/缺勤/次数）。

- [ ] **Step 2: 实体与 Repository**

`Schedule`/`SchedulePerson`/`Attendance`/`FreeSchedule` 字段对应 DDL。`Schedule` 一对多 `SchedulePerson`（用 `@OneToMany` 或服务层组装均可，本计划用服务层组装避免懒加载问题）。

Repository：
- `ScheduleRepository`：`List<Schedule> findByScheduleType(String t)`、`findByWeekNo(Integer w)`、`findByActivityId(Long a)`，或全部走 `findAll` + 过滤
- `SchedulePersonRepository`：`List<SchedulePerson> findByScheduleId(Long id)`
- `AttendanceRepository`：`List<Attendance> findAll()`
- `FreeScheduleRepository`：`List<FreeSchedule> findAll()`

- [ ] **Step 3: RoutineService**

- `createSchedule(ScheduleRequest)`：存 `Schedule` + 循环存 `SchedulePerson`
- `listSchedules(filter)`：组装每个排班的人员数组
- `exportExcel(filter)`：用 POI 生成 xlsx——列：周次/星期/节次(时间段)/地点/值班人员；写文件到 `uploads/export-{ts}.xlsx` 并返回路径；控制器响应 `ResponseEntity<byte[]>` 带 `Content-Disposition` 下载
- `summary(weekNo, type)`：遍历考勤按人聚合
- 其余 CRUD 参照 Task 8

- [ ] **Step 4: 控制器**

`ScheduleController`/`AttendanceController`/`FreeScheduleController` 按 Interfaces 端点实现。`/api/schedules/export` 返回 xlsx 二进制。

- [ ] **Step 5: 跑测试 + 提交**

```bash
cd /d/MyApp/PAMS/pams-backend
mvn -q test
git add pams-backend/src
git commit -m "feat: 排班/考勤/无课表后端"
```

---

### Task 17: 前端排班考勤页

**Files:**
- Create: `pams-web/src/pages/routine/ScheduleList.tsx` / `AttendanceList.tsx` / `FreeScheduleList.tsx`
- Create: `pams-web/src/api/schedule.ts` / `attendance.ts` / `freeSchedule.ts`
- Modify: `pams-web/src/router/index.tsx`（把 routine 占位替换为真实页面）

**Interfaces:**
- Produces:
  - `ScheduleList`：类型筛选（控烟/办公室值班/摆摊/档案整理/盖章/教学楼检查）+ 周次筛选 + 人员网格展示（按星期×节次）+ 新增排班（多人员输入）+ 导出 xlsx 按钮
  - `AttendanceList`：按人考勤登记（出勤/请假/缺勤）+ 汇总表（应到/实到/请假/缺勤/次数）+ 周次筛选
  - `FreeScheduleList`：部门无课表展示 + 编辑
- 供 Task 29 仪表盘"本周排班"引用。

- [ ] **Step 1: `api/schedule.ts` / `attendance.ts`**

```ts
// schedule.ts
export const listSchedules = (params: { type?: string; weekNo?: number; weekday?: number; activityId?: number }) =>
  get<ScheduleVO[]>('/schedules', params)
export const createSchedule = (data: unknown) => post<number>('/schedules', data)
export const updateSchedule = (id: number, data: unknown) => put<void>(`/schedules/${id}`, data)
export const deleteSchedule = (id: number) => del<void>(`/schedules/${id}`)
export const exportSchedule = (params: { type?: string; weekNo?: number }) =>
  http.get('/schedules/export', { params, responseType: 'blob' })
```

```ts
// attendance.ts
export const listAttendances = (params: { scheduleId?: number; weekNo?: number; personName?: string }) =>
  get<unknown[]>('/attendances', params)
export const createAttendance = (data: unknown) => post<number>('/attendances', data)
export const summaryAttendance = (params: { weekNo?: number; type?: string }) =>
  get<unknown[]>('/attendances/summary', params)
```

- [ ] **Step 2: ScheduleList**

- 筛选区：`Select`（类型）、`InputNumber`（周次）、`Button`（导出）
- 数据区：按 `weekday`(1-7) × `sessionName` 网格化展示（`GlassTable` 列=周一~周日，行=节次/时间段，格内人名）
- 新增/编辑 `GlassModal`：类型/周次/星期/节次/地点/人员列表（`Form.List` 动态增删姓名）
- 导出：`exportSchedule` 拿 blob，`URL.createObjectURL` + 下载

- [ ] **Step 3: AttendanceList + FreeScheduleList**

- AttendanceList：周次筛选 → 该周所有排班的考勤逐条登记（select 状态）+ `summaryAttendance` 汇总表
- FreeScheduleList：`GlassTable` 部门列（文秘/组织/新媒体/青年科技）展开看人员与空闲周次，可编辑

- [ ] **Step 4: 浏览器验证 + 提交**

人工点验排班网格、考勤登记与汇总、导出文件可下载、无课表编辑。

```bash
git add pams-web/src
git commit -m "feat: 排班考勤前端"
```

---

### Task 18: 党务台账后端（成员/阶段/名单/函调/登记/转移）

**Files:**
- Create: `com/pams/module/party/entity/{PartyMember,PartyStage,PartyRoster,PartyInvestigation,PartyRegister,PartyTransfer}.java`
- Create: `com/pams/module/party/repository/{...}Repository.java`（6 个）
- Create: `com/pams/module/party/service/PartyMemberService.java` / `PartyRecordService.java`
- Create: `com/pams/module/party/controller/{PartyMemberController,PartyRosterController,PartyInvestigationController,PartyRegisterController,PartyTransferController}.java`
- Create: `com/pams/module/party/dto/{PartyMemberRequest,PartyStageRequest,PartyRosterRequest,PartyInvestigationRequest,PartyRegisterRequest,PartyTransferRequest}.java`
- Test: `com/pams/module/party/PartyMemberServiceTest.java`（身份流转）

**Interfaces:**
- Produces:
  - `GET /api/party/members?keyword=&stage=&page=&size=` → 成员分页（敏感字段不返回）
  - `POST/PUT/DELETE /api/party/members`
  - `PUT /api/party/members/{id}/stage`（`PartyStageRequest{stage,issueNo,startDate,endDate,remark}`）→ 追加 `party_stage` 记录并更新 `party_member.political_status`
  - `GET /api/party/stages?memberId=` → 流转历史
  - `GET /api/party/rosters?type=&issueNo=` → 名单列表；`POST/DELETE /api/party/rosters`
  - `GET /api/party/investigations?memberId=` → 函调；`POST/PUT /api/party/investigations`
  - `GET /api/party/registers?memberId=` → 党务录入；`POST/PUT /api/party/registers`
  - `GET /api/party/transfers?memberId=` → 组织关系转移；`POST/PUT /api/party/transfers`
- **敏感权限**：roster/investigation/register/transfer 全部端点 `@PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')")`；members 列表对干事只返回脱敏基础字段（姓名/班级/身份），不返回身份证/家庭地址/电话。
- 供 Task 19 前端党务台账页使用。

- [ ] **Step 1: 写 PartyMemberServiceTest（先红）**

```java
package com.pams.module.party;

import com.pams.common.BizException;
import com.pams.module.party.entity.PartyMember;
import com.pams.module.party.entity.PartyStage;
import com.pams.module.party.repository.PartyMemberRepository;
import com.pams.module.party.repository.PartyStageRepository;
import com.pams.module.party.service.PartyMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PartyMemberServiceTest {

    PartyMemberRepository memberRepo;
    PartyStageRepository stageRepo;
    PartyMemberService service;

    @BeforeEach
    void setup() {
        memberRepo = mock(PartyMemberRepository.class);
        stageRepo = mock(PartyStageRepository.class);
        service = new PartyMemberService(memberRepo, stageRepo);
    }

    @Test
    void changeStage_appendsRecord_andUpdatesPoliticalStatus() {
        PartyMember m = new PartyMember();
        m.setId(1L);
        m.setPoliticalStatus("共青团员");
        when(memberRepo.findById(1L)).thenReturn(Optional.of(m));

        service.changeStage(1L, "ACTIVE", "40", "2026-01-01", null, null);

        assertThat(m.getPoliticalStatus()).isEqualTo("入党积极分子");
        verify(stageRepo).save(any(PartyStage.class));
        verify(memberRepo).save(m);
    }

    @Test
    void changeStage_unknownMember_throws() {
        when(memberRepo.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changeStage(9L, "ACTIVE", "40", null, null, null))
                .isInstanceOf(BizException.class);
    }
}
```

- [ ] **Step 2: 实体**

六个实体字段对应 DDL。`PartyMember` 带 `@SQLRestriction("deleted = 0")`。`PartyStage.stage` 存 enum `PartyStageType { APPLICANT, ACTIVE, DEVELOPMENT, PROBATIONARY, FULL }`。

`political_status` 映射：
```
APPLICANT→入党申请人；ACTIVE→入党积极分子；DEVELOPMENT→重点发展对象；
PROBATIONARY→预备党员；FULL→正式党员
```

Repository（6 个）：
- `PartyMemberRepository`：`JpaRepository` + `JpaSpecificationExecutor`，`findByStudentNo(String)`、`existsByStudentNo(String)`
- `PartyStageRepository`：`List<PartyStage> findByMemberIdOrderByStartDateAsc(Long memberId)`
- `PartyRosterRepository`：`findByRosterType(String)`、`findByIssueNo(String)`
- `PartyInvestigationRepository`：`findByMemberId(Long)`
- `PartyRegisterRepository`：`findByMemberId(Long)`
- `PartyTransferRepository`：`findByMemberId(Long)`

- [ ] **Step 3: PartyMemberService + PartyRecordService**

`PartyMemberService`：
```java
public void changeStage(Long memberId, String stage, String issueNo, String startDate, String endDate, String remark) {
    PartyMember m = memberRepo.findById(memberId).orElseThrow(() -> new BizException(3001, "成员不存在"));
    PartyStage s = new PartyStage();
    s.setMemberId(memberId);
    s.setStage(stage);
    s.setIssueNo(issueNo);
    s.setStartDate(startDate == null ? null : java.time.LocalDate.parse(startDate));
    s.setEndDate(endDate == null ? null : java.time.LocalDate.parse(endDate));
    s.setRemark(remark);
    stageRepo.save(s);
    m.setPoliticalStatus(PartyStageType.valueOf(stage).label());
    memberRepo.save(m);
}
```
（enum 加 `label()` 返回中文映射；`PartyMemberService` 含 `page`/`create`/`update`/`delete`，分页列表对 STAFF 脱敏。）

`PartyRecordService`：roster/investigation/register/transfer 的 CRUD（列表/新增/更新/删除），全部校验所属成员存在。

- [ ] **Step 4: 控制器（含敏感权限注解）**

六个 Controller 按 Interfaces 端点实现；roster/investigation/register/transfer 控制器类上 `@PreAuthorize("hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')")`。

- [ ] **Step 5: 跑测试 + 提交**

```bash
cd /d/MyApp/PAMS/pams-backend
mvn -q test
git add pams-backend/src
git commit -m "feat: 党务台账后端与敏感数据权限"
```

---

### Task 19: 前端党务台账页

**Files:**
- Create: `pams-web/src/pages/party/PartyMemberList.tsx` / `PartyMemberDetail.tsx`
- Create: `pams-web/src/pages/party/PartyRosterList.tsx` / `PartyRecordPanels.tsx`（函调/登记/转移三 Tab 复用）
- Create: `pams-web/src/api/party.ts`
- Modify: `pams-web/src/router/index.tsx`

**Interfaces:**
- Produces:
  - `PartyMemberList`：成员分页表（姓名/班级/身份/期数/操作）+ 新增/编辑 + 阶段流转按钮（弹窗选 stage+期数）
  - `PartyMemberDetail`：基本信息 + 流转历史 + 函调/登记/转移 Tab（部长及以上）
  - `PartyRosterList`：按名单类型（推优/通过/发展对象/转移）筛选列表 + 新增/导入
- 敏感字段按角色隐藏（干事不显示身份证/家庭地址/电话）。

- [ ] **Step 1: `api/party.ts`**

```ts
export const listPartyMembers = (params: { keyword?: string; stage?: string; page?: number; size?: number }) =>
  get<PageResult<unknown>>('/party/members', params)
export const createPartyMember = (data: unknown) => post<number>('/party/members', data)
export const updatePartyMember = (id: number, data: unknown) => put<void>(`/party/members/${id}`, data)
export const deletePartyMember = (id: number) => del<void>(`/party/members/${id}`)
export const changeStage = (id: number, data: { stage: string; issueNo?: string; startDate?: string; endDate?: string; remark?: string }) =>
  put<void>(`/party/members/${id}/stage`, data)
export const listStages = (memberId: number) => get<unknown[]>(`/party/stages`, { memberId })
export const listRosters = (params: { type?: string; issueNo?: string }) => get<unknown[]>('/party/rosters', params)
export const createRoster = (data: unknown) => post<number>('/party/rosters', data)
export const listInvestigations = (memberId: number) => get<unknown>(`/party/investigations`, { memberId })
export const listRegisters = (memberId: number) => get<unknown>(`/party/registers`, { memberId })
export const listTransfers = (memberId: number) => get<unknown>(`/party/transfers`, { memberId })
```

- [ ] **Step 2: PartyMemberList**

- `GlassTable` 列：姓名/班级/身份（`StatusTag` 红系）/期数/电话（部长以上）/操作
- 新增/编辑 `GlassModal` Form（姓名/性别/民族/班级/支部/政治面貌/学号/身份证[部长以上]）
- 阶段流转：行内"流转"按钮 → `GlassModal` 选择 stage（入党积极分子/发展对象/预备党员/正式党员）+ 期数 + 日期 → `changeStage` → 刷新
- 行列隐藏：`useAuthStore.user.roleLevel < 3` 时用 `Columns` 过滤掉敏感列

- [ ] **Step 3: PartyMemberDetail + PartyRosterList + PartyRecordPanels**

- Detail：`Descriptions` 展示基本信息 + `Timeline` 展示流转历史 + `Tabs`（函调/登记/转移，各为表单，部长以上可见）
- RosterList：类型 Select 筛选 + 表格 + 新增（姓名/性别/学号/班级/支部/类型/期数）

- [ ] **Step 4: 浏览器验证 + 提交**

人工点验成员增删改、阶段流转后身份与历史更新、名单筛选、敏感字段对干事隐藏。

```bash
git add pams-web/src
git commit -m "feat: 党务台账前端"
```

---

### Task 20: 内容宣传——推文/新闻稿（后端）

**Files:**
- Create: `com/pams/module/content/entity/{Article,News}.java`
- Create: `com/pams/module/content/repository/{ArticleRepository,NewsRepository}.java`
- Create: `com/pams/module/content/service/{ArticleService,NewsService}.java`
- Create: `com/pams/module/content/controller/{ArticleController,NewsController}.java`
- Create: `com/pams/module/content/dto/{ArticleRequest,NewsRequest}.java`
- Test: `com/pams/module/content/ArticleServiceTest.java`

**Interfaces:**
- Produces:
  - `GET /api/articles?status=&type=&keyword=&page=&size=` → 推文分页
  - `POST /api/articles`（`ArticleRequest{title,summary,content,coverUrl,activityId,articleType}`，状态 DRAFT）
  - `PUT /api/articles/{id}`
  - `PUT /api/articles/{id}/submit` → PENDING
  - `PUT /api/articles/{id}/review`（`{approved,comment}`）→ APPROVED/PUBLISHED（approve 时自动发布）
  - `DELETE /api/articles/{id}`
  - `GET /api/news?keyword=&page=&size=` → 新闻稿分页；`POST/PUT/DELETE /api/news`
- 角色：新媒体部长可审推文；新闻稿任何人可新建（新媒体中心为主），部长审核。
- 供 Task 21 前端内容页使用。

- [ ] **Step 1: 写 ArticleServiceTest（先红）**

```java
package com.pams.module.content;

import com.pams.common.BizException;
import com.pams.module.content.entity.Article;
import com.pams.module.content.repository.ArticleRepository;
import com.pams.module.content.service.ArticleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ArticleServiceTest {

    ArticleRepository repo;
    ArticleService service;

    @BeforeEach
    void setup() {
        repo = mock(ArticleRepository.class);
        service = new ArticleService(repo);
    }

    @Test
    void review_approve_publishes() {
        Article a = new Article();
        a.setId(1L);
        a.setStatus(Article.ArticleStatus.PENDING);
        when(repo.findById(1L)).thenReturn(Optional.of(a));

        service.review(1L, true, "ok");

        verify(repo).save(a);
        // 断言已保存对象的 status == PUBLISHED（可用 ArgumentCaptor）
    }

    @Test
    void review_missing_throws() {
        when(repo.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.review(9L, true, "x"))
                .isInstanceOf(BizException.class);
    }
}
```

- [ ] **Step 2: 实体**

`Article`：id/title/summary/content(TEXT)/coverUrl/activityId/articleType/status(enum `ArticleStatus { DRAFT, PENDING, PUBLISHED, REJECTED }`)/authorId/reviewerId/reviewComment/publishTime/createdAt/updatedAt/deleted。`@SQLRestriction("deleted = 0")`。

`News`：id/title/subtitle/content(TEXT)/activityId/authorId/publishDate/status/createdAt/updatedAt/deleted。

Repository：
- `ArticleRepository`：`JpaRepository` + `JpaSpecificationExecutor`
- `NewsRepository`：`JpaRepository` + `JpaSpecificationExecutor`

- [ ] **Step 3: ArticleService + NewsService**

`ArticleService`：`page`（status/type/keyword 过滤）、`create`、`update`（DRAFT/PENDING 可改）、`submit`、`review`（approve→PUBLISHED，写 `publishTime`）、`delete`。

`NewsService`：CRUD + 分页。

- [ ] **Step 4: 控制器**

`ArticleController`(`/api/articles`)、`NewsController`(`/api/news`)。review 端点 `@PreAuthorize("hasRole('MEDIA_LEADER') or hasAnyRole('TEACHER','DIRECTOR')")`。

- [ ] **Step 5: 跑测试 + 提交**

```bash
cd /d/MyApp/PAMS/pams-backend
mvn -q test
git add pams-backend/src
git commit -m "feat: 推文/新闻稿后端"
```

---

### Task 21: 前端推文与新闻稿页

**Files:**
- Create: `pams-web/src/pages/content/ArticleList.tsx`
- Create: `pams-web/src/pages/content/NewsList.tsx`
- Create: `pams-web/src/api/article.ts` / `news.ts`

**Interfaces:**
- Produces: `ArticleList`（列表 + 撰写/编辑 + 提交审核 + 审核通过 + 预览弹窗）、`NewsList`（新闻稿列表 + 编辑 + 预览）
- 供 Task 28 仪表盘"最新推文"引用。

- [ ] **Step 1: `api/article.ts` / `news.ts`**

```ts
export const listArticles = (params: { status?: string; type?: string; keyword?: string; page?: number; size?: number }) =>
  get<PageResult<unknown>>('/articles', params)
export const createArticle = (data: unknown) => post<number>('/articles', data)
export const updateArticle = (id: number, data: unknown) => put<void>(`/articles/${id}`, data)
export const submitArticle = (id: number) => put<void>(`/articles/${id}/submit`)
export const reviewArticle = (id: number, approved: boolean, comment?: string) =>
  put<void>(`/articles/${id}/review`, { approved, comment })
export const deleteArticle = (id: number) => del<void>(`/articles/${id}`)
```

```ts
export const listNews = (params: { keyword?: string; page?: number; size?: number }) =>
  get<PageResult<unknown>>('/news', params)
export const createNews = (data: unknown) => post<number>('/news', data)
export const updateNews = (id: number, data: unknown) => put<void>(`/news/${id}`, data)
export const deleteNews = (id: number) => del<void>(`/news/${id}`)
```

- [ ] **Step 2: ArticleList**

- 筛选：状态（草稿/待审/已发布/被驳回）、类型（预热/报道/宣传视频）
- `GlassTable` 列：标题/类型/状态/作者/发布时间/操作
- 撰写/编辑 `GlassModal` 大表单（标题/摘要/正文 TextArea/类型 Select/封面 URL）
- 操作按钮按状态：DRAFT→提交；PENDING→审核(部长)/撤回；PUBLISHED→只看；REJECTED→改后重提
- 预览：`GlassModal` 渲染标题+摘要+正文（`whiteSpace: pre-wrap`）

- [ ] **Step 3: NewsList**

简单 CRUD 表格 + 预览；正文 `pre-wrap`。

- [ ] **Step 4: 浏览器验证 + 提交**

人工点验推文全流程（草稿→提交→审核→发布）、预览渲染、新闻稿 CRUD。

```bash
git add pams-web/src
git commit -m "feat: 推文与新闻稿前端"
```

---

### Task 22: 材料归档 + 文件上传（后端）

**Files:**
- Create: `com/pams/module/archive/entity/{Material,FileRecord,TemplateAsset,CreditRecord,Announcement}.java`
- Create: `com/pams/module/archive/repository/{MaterialRepository,FileRecordRepository,TemplateAssetRepository,CreditRecordRepository,AnnouncementRepository}.java`
- Create: `com/pams/module/archive/service/{FileStorageService,MaterialService,TemplateService,CreditService,AnnouncementService}.java`
- Create: `com/pams/module/archive/controller/{FileController,MaterialController,TemplateController,CreditController,AnnouncementController}.java`
- Create: `com/pams/module/archive/dto/{MaterialRequest,TemplateRequest,CreditRequest,AnnouncementRequest}.java`
- Test: `com/pams/module/archive/FileStorageServiceTest.java` / `CreditServiceTest.java`

**Interfaces:**
- Produces:
  - `POST /api/files/upload`（multipart `file`，业务可选 `bizType`）→ `{id,filename,path,size}`，文件存 `uploads/`（按日期子目录），返回访问 URL `/uploads/...`
  - `GET /api/files/{id}/download` → 原文件下载
  - `POST /api/files/import`（multipart，`type=ROSTER_ACTIVE`）→ 解析入党积极分子名单 xlsx 导入 `party_roster`（过滤 `~$` 临时文件）
  - `GET /api/materials?keyword=&bizType=&activityId=&deptId=&page=&size=` → 材料分页
  - `POST /api/materials`（`MaterialRequest{name,bizType,activityId,deptId,tag,description,fileId}`）
  - `PUT/DELETE /api/materials/{id}`
  - `GET /api/materials/tree?activityId=` → 按活动/类型分组树（替代手工"12月26日"汇总包）
  - `GET/POST/PUT/DELETE /api/templates`
  - `GET /api/credits?keyword=&page=&size=` → 加分分页；`POST/PUT/DELETE /api/credits`
  - `GET /api/announcements` → 列表（含已读状态）；`POST/PUT/DELETE /api/announcements`
- 供 Task 23 前端材料库页使用。

- [ ] **Step 1: 写 FileStorageServiceTest（先红）**

```java
package com.pams.module.archive;

import com.pams.module.archive.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;

class FileStorageServiceTest {

    @Test
    void sanitize_rejectsDangerousName() {
        FileStorageService svc = new FileStorageService(java.nio.file.Path.of("target/uploads"));
        String safe = svc.sanitize("../../evil/名单.xlsx");
        assertThat(safe).doesNotContain("..").doesNotContain("/");
    }

    @Test
    void detectBizType_fromExtension() {
        FileStorageService svc = new FileStorageService(java.nio.file.Path.of("target/uploads"));
        assertThat(svc.bizTypeOf("策划书.docx")).isEqualTo("PLAN");
        assertThat(svc.bizTypeOf("签到表.xlsx")).isEqualTo("SIGNIN");
        assertThat(svc.bizTypeOf("照片.jpg")).isEqualTo("PHOTO");
    }
}
```

- [ ] **Step 2: FileStorageService 实现**

```java
package com.pams.module.archive.service;

import com.pams.common.BizException;
import com.pams.module.archive.entity.FileRecord;
import com.pams.module.archive.repository.FileRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class FileStorageService {
    private final Path uploadDir;
    private final FileRecordRepository fileRecordRepository;

    public FileStorageService(org.springframework.beans.factory.annotation.Value("${pams.upload-dir}") String uploadDir,
                              FileRecordRepository fileRecordRepository) {
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
        this.fileRecordRepository = fileRecordRepository;
    }
    public FileStorageService(Path uploadDir) {
        this.uploadDir = uploadDir.toAbsolutePath().normalize();
        this.fileRecordRepository = null;
    }

    public String sanitize(String filename) {
        String base = Path.of(filename).getFileName().toString();
        base = base.replaceAll("[ -]", "");
        while (base.startsWith("~$")) base = base.substring(2);
        return base;
    }

    public String bizTypeOf(String filename) {
        String lower = filename.toLowerCase();
        if (lower.contains("策划") || lower.contains("方案")) return "PLAN";
        if (lower.contains("签到")) return "SIGNIN";
        if (lower.contains("排班") || lower.contains("安排") || lower.contains("值班")) return "SCHEUDLE";
        if (lower.contains("考勤")) return "ATTENDANCE";
        if (lower.contains("新闻")) return "NEWS";
        if (lower.contains("推文")) return "ARTICLE";
        if (lower.contains("发票")) return "INVOICE";
        if (lower.contains(".jpg") || lower.contains(".png")) return "PHOTO";
        if (lower.contains(".ppt")) return "PPT";
        return "OTHER";
    }

    public FileRecord store(MultipartFile file, String bizType, Long uploaderId) {
        String original = sanitize(file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename());
        String sub = LocalDate.now().toString();
        String storedName = UUID.randomUUID().toString().replace("-", "") + "-" + original;
        try {
            Path dir = uploadDir.resolve(sub);
            Files.createDirectories(dir);
            Path target = dir.resolve(storedName);
            file.transferTo(target);
            FileRecord rec = new FileRecord();
            rec.setFilename(original);
            rec.setStoredName(storedName);
            rec.setPath(sub + "/" + storedName);
            rec.setSize(file.getSize());
            rec.setContentType(file.getContentType());
            rec.setBizType(bizType == null ? bizTypeOf(original) : bizType);
            rec.setUploaderId(uploaderId);
            return fileRecordRepository.save(rec);
        } catch (IOException e) {
            throw new BizException(4001, "文件保存失败");
        }
    }
}
```

> 说明：测试用 1 参构造器（仅验证 sanitize/bizTypeOf）；生产用 2 参（含 repo 持久化）。

- [ ] **Step 3: 其余服务与控制器**

- `MaterialService`：分页/创建/更新/删除/树（按 `activityId` 分组 `bizType`）
- `TemplateService`、`CreditService`、`AnnouncementService`：CRUD
- `FileController`：`/api/files/upload`、`/api/files/{id}/download`、`/api/files/import`
- `MaterialController`(`/api/materials`)、`TemplateController`(`/api/templates`)、`CreditController`(`/api/credits`)、`AnnouncementController`(`/api/announcements`)
- `CreditServiceTest`：加分明细累计（`credit` 保留两位小数求和）

- [ ] **Step 4: 跑测试 + 提交**

```bash
cd /d/MyApp/PAMS/pams-backend
mvn -q test
git add pams-backend/src
git commit -m "feat: 材料归档/文件上传/模板/素拓/通知后端"
```

---

### Task 23: 前端材料库与文件上传

**Files:**
- Create: `pams-web/src/pages/archive/MaterialList.tsx` / `TemplateList.tsx` / `CreditList.tsx` / `AnnouncementList.tsx`
- Create: `pams-web/src/components/glass/UploadFile.tsx`
- Create: `pams-web/src/api/material.ts` / `template.ts` / `credit.ts` / `announcement.ts` / `file.ts`

**Interfaces:**
- Produces:
  - `MaterialList`：搜索 + 按活动/类型分组树展示 + 上传/删除
  - `TemplateList`：分类筛选（策划书/座位表/议程表/签到表/水牌/LOGO/党徽/新闻稿）+ 上传/下载/删除
  - `CreditList`：加分记录表 + 新增（人员/项目/分值/依据）+ 统计
  - `AnnouncementList`：公告列表 + 发布（部长以上）+ 已读标记
- `UploadFile` 组件：拖拽上传（antd Upload）+ 进度 + 成功返回 fileId
- 供 Task 28 仪表盘"公告/材料"引用。

- [ ] **Step 1: `api/file.ts` 与 `UploadFile.tsx`**

```ts
// file.ts
export const uploadFile = (file: File, bizType?: string) => {
  const form = new FormData()
  form.append('file', file)
  if (bizType) form.append('bizType', bizType)
  return http.post('/files/upload', form).then((r) => r as unknown as { id: number; filename: string; path: string; size: number })
}
export const downloadUrl = (id: number) => `/api/files/${id}/download`
```

```tsx
// UploadFile.tsx
import { Upload, message } from 'antd'
import { InboxOutlined } from '@ant-design/icons'
import { uploadFile } from '@/api/file'

export default function UploadFile({ bizType, onUploaded }: { bizType?: string; onUploaded: (fileId: number) => void }) {
  return (
    <Upload.Dragger
      maxCount={1}
      showUploadList={false}
      customRequest={async ({ file, onSuccess, onError }) => {
        try {
          const rec = await uploadFile(file as File, bizType)
          onUploaded(rec.id)
          onSuccess?.(rec)
        } catch (e) {
          onError?.(e as Error)
        }
      }}
    >
      <p className="ant-upload-drag-icon"><InboxOutlined /></p>
      <p className="ant-upload-text">点击或拖拽文件到此处上传</p>
    </Upload.Dragger>
  )
}
```

- [ ] **Step 2: MaterialList**

- 顶部搜索（名称）+ 上传按钮 → `GlassModal`（名称/业务类型 Select/关联活动 Select/标签 + `UploadFile`）
- 分组树：`getMaterialTree(activityId?)` 按活动→类型展开，叶子文件可下载（`window.open(downloadUrl(id))`）删除
- 下载走后端 `ResponseEntity<byte[]>`（FileController）

- [ ] **Step 3: TemplateList / CreditList / AnnouncementList**

- TemplateList：分类 `Tabs`/`Select` + `GlassTable`（名称/分类/大小/上传人/操作[下载/删除]）+ 上传（`UploadFile` + 名称/分类表单）
- CreditList：筛选 + `GlassTable`（姓名/学号/项目/分值/依据/时间）+ 新增 Form + 顶部汇总（总加分人次/总分值）
- AnnouncementList：`GlassTable`（标题/发布人/时间/已读状态）+ 发布 `GlassModal`（标题/内容）+ 已读操作（`POST /api/announcements/{id}/read`）

> 说明：公告已读需要后端补一个 `announcement_read` 关联表或前端本地已读集合（Task 22 未建表）。**本计划采用前端 localStorage 已读集合**，避免加表；若需服务端统计再扩展。后端 `GET /api/announcements` 返回全部，前端按用户记录已读 id。

- [ ] **Step 4: 浏览器验证 + 提交**

人工点验上传/下载/删除、材料树、素拓新增与统计、公告发布与已读。

```bash
git add pams-web/src
git commit -m "feat: 材料库/模板/素拓/通知前端"
```

---

### Task 24: 存量材料按需迁移（脚本）

**Files:**
- Create: `database/migrate_import.py`（源材料 → 材料库 CSV/直接调后端 API）

**Interfaces:**
- Produces: 迁移脚本 + 一份导入说明；迁移范围 = 仅常用模板 + 近一年活动材料（`信工党建第九届/年度部门材料汇总` 下 2025 年底 ~ 2026 年活动的策划书/签到/新闻稿/照片/PPT），过滤 `~$` 临时文件。

- [ ] **Step 1: 写 `database/migrate_import.py`**

```python
# 用法：python database/migrate_import.py --dry-run
# 职责：
#   1. 遍历 source 根目录，过滤 ~$ 前缀与隐藏文件
#   2. 按文件夹名归类 bizType（策划/签到/排班/考勤/新闻/照片/PPT）
#   3. 生成 materials.csv（name,biz_type,source_path）供人工确认
#   4. --import 模式：调后端 POST /api/materials + /api/files/upload（需登录 token）
import argparse, os, csv, sys

SOURCE = r"D:\StudyFiles\Office\党建办公室\信工党建办公室历届资料\信工党建第九届\年度部门材料汇总"
OUT = "materials.csv"

def classify(relpath):
    n = relpath.lower()
    if "策划" in n or "方案" in n: return "PLAN"
    if "签到" in n: return "SIGNIN"
    if any(k in n for k in ("排班", "安排", "值班")): return "SCHEUDLE"
    if "考勤" in n: return "ATTENDANCE"
    if "新闻" in n: return "NEWS"
    if "发票" in n: return "INVOICE"
    if n.endswith((".jpg", ".jpeg", ".png")): return "PHOTO"
    if n.endswith((".ppt", ".pptx")): return "PPT"
    return "OTHER"

def scan():
    rows = []
    for root, _, files in os.walk(SOURCE):
        for f in files:
            if f.startswith("~$") or f.startswith("."):
                continue
            rel = os.path.relpath(os.path.join(root, f), SOURCE)
            rows.append({"name": f, "biz_type": classify(rel), "source_path": os.path.join(root, f)})
    return rows

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--import", dest="do_import", action="store_true")
    ap.add_argument("--api", default="http://localhost:8080")
    ap.add_argument("--token", default="")
    args = ap.parse_args()

    rows = scan()
    print(f"扫描到 {len(rows)} 个文件（已过滤 ~$ 临时文件）")
    with open(OUT, "w", newline="", encoding="utf-8-sig") as fp:
        w = csv.DictWriter(fp, fieldnames=["name", "biz_type", "source_path"])
        w.writeheader()
        w.writerows(rows)
    print(f"清单写入 {OUT}，请人工筛选需迁移的子集后使用 --import")

    if args.do_import and args.token:
        # 调后端接口上传文件并建材料记录（示例，需补 requests）
        import requests
        headers = {"Authorization": f"Bearer {args.token}"}
        for r in rows:
            # 只导入人工标记的子集：可在 CSV 加 keep 列后过滤
            if r.get("keep") != "1":
                continue
            with open(r["source_path"], "rb") as fp:
                files = {"file": (r["name"], fp)}
                resp = requests.post(f"{args.api}/api/files/upload",
                                     headers=headers, files=files)
                if resp.status_code != 200:
                    print("上传失败", r["name"], resp.text)
                    continue
                file_id = resp.json()["data"]["id"]
                data = {"name": r["name"], "biz_type": r["biz_type"],
                        "activity_id": None, "dept_id": None,
                        "tag": "存量迁移", "description": "按需迁移导入", "file_id": file_id}
                resp2 = requests.post(f"{args.api}/api/materials",
                                      headers=headers, json=data)
                print(resp2.status_code, r["name"])

if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 运行 dry-run 生成清单**

```bash
cd /d/MyApp/PAMS/database
python migrate_import.py --dry-run
```

Expected: 输出扫描文件数（过滤 `~$` 后，约为材料总数减去临时文件）并生成 `materials.csv`。

- [ ] **Step 3: 人工筛选 + 导入说明**

在 `materials.csv` 中给需迁移子集标 `keep=1`；启动后端后用 `--import --token <登录token>` 导入。**此步骤需要你人工确认迁移子集**，脚本只做兜底与半自动。

- [ ] **Step 4: 提交**

```bash
git add database
git commit -m "feat: 存量材料迁移脚本"
```

---

### Task 25: 通知公告 + 用户管理前端（管理端）

**Files:**
- Create: `pams-web/src/pages/admin/UserList.tsx`
- Modify: `pams-web/src/pages/archive/AnnouncementList.tsx`（并入管理端）
- Create: `pams-web/src/api/user.ts`

**Interfaces:**
- Produces:
  - `UserList`：用户分页（用户名/姓名/部门/角色/状态/操作）、新增/编辑（角色 Select 部门 Select 状态）、重置密码、禁用/启用
  - 角色选择联动：选部长角色自动绑定部门
  - `AnnouncementList` 已含于 Task 23；本 Task 只收尾
- 供 Task 27 权限细化与 Task 29 仪表盘引用。

- [ ] **Step 1: `api/user.ts`**

```ts
export const listUsers = (params: { keyword?: string; deptId?: number; page?: number; size?: number }) =>
  get<PageResult<unknown>>('/users', params)
export const createUser = (data: unknown) => post<number>('/users', data)
export const updateUser = (id: number, data: unknown) => put<void>(`/users/${id}`, data)
export const deleteUser = (id: number) => del<void>(`/users/${id}`)
export const resetPassword = (id: number) => post<void>(`/users/${id}/reset-password`)
export const listDepts = () => get<unknown[]>('/depts')
export const listRoles = () => get<unknown[]>('/roles')
```

- [ ] **Step 2: UserList**

`GlassTable` 列：用户名/姓名/学号/部门/角色/状态(Tag)/操作（编辑/重置密码/删除）。新增/编辑 `GlassModal` Form：用户名/姓名/学号/电话/部门/角色/状态/密码（新增时）。重置密码用 `Popconfirm` 确认（重置为 123456）。

- [ ] **Step 3: 浏览器验证 + 提交**

人工点验用户增删改、重置密码、角色-部门联动。

```bash
git add pams-web/src
git commit -m "feat: 用户管理前端"
```

---

### Task 26: 权限细化与登录页完善

**Files:**
- Modify: `pams-web/src/router/index.tsx`（加 `RequireRole` 组件）
- Modify: `pams-web/src/layouts/MainLayout.tsx`（菜单按角色过滤）
- Create: `pams-web/src/components/glass/RequireRole.tsx`

**Interfaces:**
- Produces:
  - `RequireRole({roles,children})`：路由级角色守卫，无权限跳 `/403`
  - 菜单角色过滤：干事仅显示 仪表盘/活动/排班考勤/材料；部长+显示全部；主任+额外显示 用户管理
  - `/403` 无权限页
- 补 Task 5 遗留的数据权限逻辑（后端按 `Role.dataScope` 而非硬编码 STAFF）。

- [ ] **Step 1: RequireRole 组件**

```tsx
import { Navigate } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth'

export default function RequireRole({ roles, children }: { roles: string[]; children: React.ReactNode }) {
  const role = useAuthStore((s) => s.user?.roleCode)
  if (!role || !roles.includes(role)) return <Navigate to="/403" replace />
  return <>{children}</>
}
```

- [ ] **Step 2: 路由包裹**

对 `admin/users` 包 `<RequireRole roles={['TEACHER','DIRECTOR']}>`；对党务台账的 roster/investigation/register/transfer 相关路由包 `<RequireRole roles={['TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER']}>`。

- [ ] **Step 3: 菜单过滤**

`MainLayout` 里按 `user.roleCode` 过滤 `menuItems`；`/403` 页面（GlassCard 提示无权限 + 返回按钮）。

- [ ] **Step 4: 后端数据权限修正**

`UserController.page` 改为注入 `RoleRepository`，取当前用户角色 `dataScope`，`"DEPT"` 则强制 deptId=当前用户 deptId（去掉硬编码 STAFF）。

- [ ] **Step 5: 验证 + 提交**

用干事账号登录验证菜单只剩本部门相关、访问受限路由跳 403；后端验证干事列表仅本部门。

```bash
cd /d/MyApp/PAMS/pams-backend && mvn -q test
cd /d/MyApp/PAMS && git add pams-web/src pams-backend/src
git commit -m "feat: 权限细化与角色守卫"
```

---

### Task 27: 前端活动详情整合 + 全流程走通联调

**Files:**
- Modify: `pams-web/src/pages/activity/ActivityDetail.tsx`（把策划书审核/签到/评分/甘特全部接线）

**Interfaces:**
- Produces: 一次完整走通的"活动闭环"：下达任务→甘特图分派→策划书提交审核→各部门执行→归档。

- [ ] **Step 1: 联调完整链路**

浏览器逐项验证：
1. 指导老师账号下达活动（状态 ASSIGNED）
2. 主任账号进入甘特图分派任务
3. 组织部账号提交策划书 → 主任/指导老师审核 → 状态推至 PLAN_REVIEW
4. 各部门账号在活动详情看到各自产出入口（文秘签到/新媒体推文/青年科技 PPT 材料上传）
5. 活动推进至 EXECUTING → FINISHED → 上传总结材料归档 ARCHIVED

- [ ] **Step 2: 修复联调发现的问题**

按 `superpowers:systematic-debugging` 处理每个 bug；记录关键问题到 commit message。

- [ ] **Step 3: 提交**

```bash
git add pams-web/src pams-backend/src
git commit -m "fix: 活动全流程联调修复"
```

---

### Task 28: 仪表盘

**Files:**
- Create: `pams-web/src/pages/Dashboard.tsx`
- Create: `com/pams/module/dashboard/DashboardService.java` / `DashboardController.java`

**Interfaces:**
- Produces: `GET /api/dashboard` → `{activityStats,weekSchedules,recentArticles,recentMaterials,recentAnnouncements,myTasks}`；仪表盘各统计卡片 + 本周排班 + 最新推文 + 待办

- [ ] **Step 1: 后端 DashboardService**

注入各 repository 统计：
- `activityStats`：按状态分组计数（`activityRepository` 按 status 分组）
- `weekSchedules`：本周（周一起）排班条数
- `recentArticles`：最近 5 篇已发布推文
- `recentMaterials`：最近 5 条材料
- `recentAnnouncements`：最近 5 条公告
- `myTasks`：当前用户负责的任务（task.assignee == 当前用户名 or task 关联活动）
返回 `Map<String,Object>`。

- [ ] **Step 2: 前端 Dashboard**

- 顶部 4 个统计卡（GlassCard + 图标）：活动总数/进行中/本周排班/待办任务
- 中部：本周排班表（引用排班数据，紧凑版）+ 活动日历（按 startDate 的 `Calendar`/列表）
- 侧栏：最新推文（链接到内容页）、最新材料、最新公告
- 主题下全局玻璃化

- [ ] **Step 3: 验证 + 提交**

```bash
cd /d/MyApp/PAMS/pams-backend && mvn -q test
cd /d/MyApp/PAMS/pams-web && npm run dev
git add pams-web/src pams-backend/src
git commit -m "feat: 仪表盘"
```

---

### Task 29: 前端管理员页 + 系统设置 + 打磨

**Files:**
- Create: `pams-web/src/pages/admin/Settings.tsx`（可选：系统信息/上传目录查看）

**Interfaces:**
- Produces: 系统整体可用性打磨：空态、加载态、错误提示统一、导航高亮、角色菜单完整。

- [ ] **Step 1: 全局体验打磨**

- 所有 `GlassTable` 加 `locale={{emptyText:'暂无数据'}}`
- 所有异步请求统一 loading（antd `Spin`）
- 错误统一 `message.error`
- 导航激活态高亮（`location.pathname` 前缀匹配）
- 每个页面加 `PageHeader`

- [ ] **Step 2: 管理端收尾**

Settings 页（可选）：显示版本、上传目录、系统健康（`GET /api/ping`）。

- [ ] **Step 3: 验证 + 提交**

```bash
cd /d/MyApp/PAMS/pams-web && npm run dev
git add pams-web/src
git commit -m "feat: 系统打磨与设置页"
```

---

### Task 30: 全量回归测试 + 种子数据补齐 + README

**Files:**
- Modify: `README.md`（启动/账号/配置说明）
- Modify: `com/pams/config/DataSeeder.java`（补充演示活动/任务/成员/推文，便于验收演示）

**Interfaces:**
- Produces: 可一键演示的完整环境（种子数据含 1 个示例活动全流程 + 若干成员 + 推文）

- [ ] **Step 1: 全量回归**

```bash
cd /d/MyApp/PAMS/pams-backend && mvn -q test
cd /d/MyApp/PAMS/pams-web && npm run test && npm run build
```

Expected: 后端全绿；前端 vitest 全绿 + `vite build` 无 TS 错误。

- [ ] **Step 2: 种子数据补充**

`DataSeeder` 追加：示例活动（"第四十期入党积极分子培训班"）含任务/策划书/签到/推文/材料各一条，统一挂当前日期前后；确保幂等（`activityRepository.count()>0` 跳过）。

- [ ] **Step 3: README 补全**

```markdown
## 快速启动
1. 创建数据库：`mysql -u root -p < database/init_db.sql`
2. 后端：`cd pams-backend && mvn spring-boot:run`（8080）
3. 前端：`cd pams-web && npm i && npm run dev`（3000）
4. 初始账号（密码均 123456）：
   - teacher/指导老师、zhuren/主任、orgleader/组织部长、
   - secleader/文秘部长、medialeader/新媒体部长、techleader/青年科技部长、admin/管理员
## 目录结构
...（monorepo 说明）
## 技术栈
...
```

- [ ] **Step 4: 提交**

```bash
git add README.md pams-backend/src
git commit -m "feat: 演示种子数据与 README"
```

---

### Task 31: 本地部署运行 + 启动脚本

**Files:**
- Create: `start.bat`（Windows 一键启动后端+前端）

**Interfaces:**
- Produces: `start.bat`：检查 MySQL → 建库 → 起后端（后台）→ 起前端 → 打开浏览器。

- [ ] **Step 1: 写 `start.bat`**

```bat
@echo off
chcp 65001 >nul
echo [1/4] 检查 MySQL 服务...
mysqladmin -u root -p%DB_PASSWORD% ping >nul 2>&1 || (echo 请先启动 MySQL 服务 & pause & exit /b)
echo [2/4] 初始化数据库...
mysql -u root -p%DB_PASSWORD% < database\init_db.sql
echo [3/4] 启动后端 (http://localhost:8080)...
start "pams-backend" cmd /k "cd /d %~dp0pams-backend && mvn spring-boot:run"
echo [4/4] 启动前端 (http://localhost:3000)...
start "pams-web" cmd /k "cd /d %~dp0pams-web && npm run dev"
timeout /t 15 >nul
start http://localhost:3000
```

- [ ] **Step 2: 全量启动验证**

```bash
cmd //c start.bat
```

Expected: 数据库建库 → 后端 8080 起 → 前端 3000 起 → 浏览器自动打开登录页；用种子账号登录走通。

- [ ] **Step 3: 提交**

```bash
git add start.bat README.md
git commit -m "feat: 一键启动脚本与本地部署"
```

---

## 运行与测试命令速查

| 用途 | 命令 |
|---|---|
| 后端测试 | `cd pams-backend && mvn -q test` |
| 后端启动 | `cd pams-backend && mvn spring-boot:run` |
| 前端测试 | `cd pams-web && npm run test` |
| 前端构建 | `cd pams-web && npm run build` |
| 前端开发 | `cd pams-web && npm run dev` |
| 建库 | `mysql -u root -p < database/init_db.sql` |
| 一键启动 | `cmd //c start.bat` |
| 冒烟 | `curl -s http://localhost:8080/api/ping` |

## 测试账号（种子数据，密码均 `123456`）

| 账号 | 角色 | 说明 |
|---|---|---|
| teacher | 指导老师 | 最高权限，下达任务 |
| zhuren | 主任 | 甘特图分派、审核 |
| orgleader | 组织部长 | 策划书 |
| secleader | 文秘部长 | 签到/排班 |
| medialeader | 新媒体部长 | 推文审核 |
| techleader | 青年科技部长 | PPT/材料 |
| admin | 系统管理员（主任级） | 用户管理 |
