# 党务管理系统（PAMS）

信息与智能工程学院党建办公室 · 党务管理系统

覆盖活动管理（策划书/议程/座位表/评分/签到/任务甘特图/活动排班）、例行事务（排班/考勤/无课表）、党务台账（党员发展/考察/转入转出/名册）、内容宣传（推文/新闻稿）、档案资产、成员管理与通知中心；内置「灵思」AI 助手（对话工具调用 / 多 Agent 协作 / 知识库问答 / 对比评估实验）。支持多组织注册与数据隔离（党建/学生会/社团等组织类型），组织可按领域包与术语表定制菜单与文案，并沿用基于角色（指导老师/主任/部长/干事）的细粒度权限控制。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Spring Boot 4.0.2（Java 21）、Spring Data JPA（Hibernate 多租户 `@TenantId` 隔离）、Spring Security + JWT（含用户级吊销水位）、Flyway（V1–V34 迁移）、MySQL 8（测试用 H2 MySQL 模式）、WebSocket（STOMP/SockJS）、Jsoup |
| 前端 | React 18、TypeScript 5.7、Vite 7、Ant Design 5、Zustand、React Router 6、dayjs、@stomp/stompjs + sockjs-client |
| AI | DeepSeek（OpenAI 兼容接口）接入，未配置密钥时自动降级为 stub 规则模式、不阻塞启动；知识库采用关键词检索（MySQL ngram 全文索引，失败自动降级 LIKE） |
| 测试 | 后端 JUnit 5 + MockMvc + H2（MySQL 模式）；前端 Vitest + Playwright e2e |
| 样式 | 自研 glass 组件体系 + 灵思（lingsi）灰阶极简设计层；品牌色由组织主题配置驱动 |

## 快速启动

### 一键启动（推荐）

确保已安装 MySQL / JDK 21+ / Maven / Node.js，然后双击或运行：

```bash
cmd //c start.bat
```

脚本会自动：检查 MySQL 服务（密码默认 `root`，可设环境变量 `DB_PASSWORD` 覆盖）→ 创建数据库 `pams_db` → 生成随机 JWT 密钥 → 分别弹出窗口启动后端（8080）与前端（5173）→ 自动打开浏览器访问登录页。

### IDEA 启动

根目录 `pom.xml` 是聚合工程（`<modules>` 指向 `pams-backend`），用 IntelliJ IDEA 打开**项目根目录**即可：

1. 用 IDEA 打开本仓库根目录 → 自动识别 Maven 聚合工程与 `pams-backend` 子模块
2. 等待 Maven 依赖导入完成（右下角进度条结束；首次需下载依赖，耗时较长）
3. 运行配置里找到 **`PartyAffairsManagementSystemApplication`** 主类，点击 Run/Debug 一键启动后端（端口 8080）
   - 若没有自动生成，可手动创建：`Run → Edit Configurations → + → Spring Boot → Main class` 选 `com.pams.PartyAffairsManagementSystemApplication`
   - 无需配置 JWT 密钥：未设置 `PAMS_JWT_SECRET` 时自动使用**每次启动随机生成**的密钥（重启后端后需重新登录）；希望本地会话跨重启保留时，可在 Run Configuration 的 Environment variables 里显式设置该变量
   - 需要演示账号（teacher/zhuren/...）时，在 Active profiles 填 `dev`（仅空库首启注入，已有账号的库不受影响）
4. 前端另开终端启动：`cd pams-web && npm run dev`（端口 5173）
5. 浏览器访问 `http://localhost:5173`（后端需已启动，MySQL 需已运行）

> 提示：后端启动前确认 MySQL 服务已运行（默认账号 `root / root`），首次启动会自动建表并注入演示数据。

### 手动启动

1. 创建数据库：`mysql -u root -p < database/init_db.sql`（创建 `pams_db`，默认 utf8mb4）
2. 后端：`cd pams-backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev`（端口 8080）
   - 数据库账号密码默认 `root / root`，可用环境变量覆盖：`DB_PASSWORD=yourpass`
   - 启动时 Flyway 自动建表；**空库首次启动（dev profile）会自动注入演示种子数据**（含"第四十期入党积极分子培训班"全流程示例，便于验收演示；已有数据的库不会重复注入）
   - `dev` profile 才注入 8 个演示账号；生产部署**不要**激活 dev profile（docker-compose 默认不激活，空库首启不落任何弱口令账号）
   - 灵思 AI 默认无密钥以 stub 规则模式运行；接入 DeepSeek 时设置环境变量 `PAMS_AGENT_API_KEY`
3. 前端：`cd pams-web && npm i && npm run dev`（端口 5173，已配置 `/api`、`/uploads`、`/ws` 代理到 8080）

### 生产部署首个管理员账号

生产环境（未激活 dev profile）空库首启后 `sys_user` 为空、无人能登录。两种方式创建首个管理员：

**方式一：自助注册组织（推荐）** — 浏览器打开 `http://<host>/register`，一次提交创建「组织 + 组织架构树 + 岗位模板 + DIRECTOR 管理员账号」。该接口免登录（新组织尚无账号），按来源 IP 每小时限流 5 次。

**方式二：手工插入** — 密码用 BCrypt 哈希值（可用 `mvn -f pams-backend/pom.xml exec:java -Dexec.mainClass=org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder` 或在线 bcrypt 工具生成，cost 10）：

```sql
INSERT INTO sys_user (username, real_name, role_id, password, status, org_id, created_at)
SELECT 'admin', '系统管理员', id, '$2a$10$xxxBCrypt哈希xxx', 1, 1, NOW() FROM sys_role WHERE code='TEACHER';
```

### 容器化部署（可选）

复制 `.env.example` 为 `.env` 并填写 `MYSQL_ROOT_PASSWORD` / `MYSQL_USER` / `MYSQL_PASSWORD` / `PAMS_JWT_SECRET`（可选 `PAMS_AGENT_API_KEY`），然后：

```bash
docker compose up -d --build
```

编排包含 mysql + backend + web 三个服务，web 暴露在 `127.0.0.1:8081`。

## 初始账号（dev profile，密码均 `123456`）

> 仅 `dev`/`test` profile 注入；生产环境不创建这些账号。

| 用户名 | 角色 | 说明 |
| --- | --- | --- |
| `teacher` | 指导老师 | 最高权限，可查看/审批全部 |
| `zhuren` | 主任 | 二级权限 |
| `orgleader` | 组织部长 | 组织部，活动/党务主责 |
| `secleader` | 文秘部长 | 文秘部，会务/材料/公告 |
| `medialeader` | 新媒体部长 | 新媒体中心，推文/新闻稿 |
| `techleader` | 青年科技部长 | 青年科技部 |
| `admin` | 系统管理员（主任权限） | 系统管理 |
| `staff` | 干事 | 数据范围限本部门（DEPT） |

> 角色权限：指导老师（5）> 主任（4）> 各部长（3）> 干事（1，仅本部门数据）。

## 能力总览

- **基础业务**：活动全流程（策划书/议程/座位表/评分/签到/任务甘特图/活动排班）、例行事务（排班/我的排班/考勤管理与人工修正审计/无课表）、党务台账（成员发展阶段/考察/转入转出/名册/届别）、内容宣传（推文/新闻稿）、档案资产（材料库/模板库/素拓加分/公告已读/群聊管理）、成员管理、通知中心。
- **灵思 AI**（`/agent`）：对话式助手，内置 21 个工具（11 读 + 10 写），写操作经一次性确认令牌门禁；工具按「声明权限 → 派发前断言 → 测试兜底」三层防护，过程事件经 WebSocket 实时推送、支持断线补拉。
- **多 Agent 协作**（`/agent/team`）：8 个内置 Agent 定义（协调/检索/策划/文书/排班/事务/审查专家 + 单 Agent 基线「通才」），四种协作模式 P1 串行 / P2 并行汇聚 / P3 反思循环 / P4 委派-汇报，黑板过程可按 Run 回放。
- **对比评估实验**（`/admin/benchmark`，主任/指导老师）：在 5 个内置任务上对比单 Agent / 多 Agent 的完成率、步骤数、工具调用数与耗时。
- **知识库**（`/knowledge`）：6 类知识源（制度文档/公告/推文/策划书/事实快照/成员名册），关键词检索（ngram 全文 + LIKE 降级），检索期按来源权限逐条复核；每日 03:10 生成事实快照、03:40 重建索引。
- **多组织（多租户）**：`/register` 注册组织，`/org/structure` 维护组织架构树（物化路径）与岗位-角色映射；业务数据按组织自动隔离（Hibernate `@TenantId`，请求级组织回源校验）；领域包（party/volunteer/club）与术语表驱动菜单显隐与文案，组织主题色驱动前端主色。

## 目录结构

```
PAMS/
├── pams-backend/                 # Spring Boot 后端
│   ├── src/main/java/com/pams/
│   │   ├── config/               # 安全配置 / 多租户判别器 / 审计 / DataSeeder（基础字典 + 演示数据）
│   │   ├── common/               # OrgContext / 租户回填监听 / ExcelGuard 等公共设施
│   │   ├── entity/               # 用户组织（sys_user/sys_role/sys_department）
│   │   ├── security/             # JWT 认证（含吊销水位）与角色权限
│   │   ├── module/
│   │   │   ├── activity/         # 活动：策划书/议程/座位/评分/签到（应签名单+核验字段）/任务（甘特）
│   │   │   ├── routine/          # 排班/考勤/活动排班/无课表
│   │   │   ├── party/            # 党务台账：成员/阶段/考察/转入转出/名册
│   │   │   ├── content/          # 推文/新闻稿
│   │   │   ├── archive/          # 档案材料/模板/素拓加分/公告/群聊/文件下载鉴权
│   │   │   ├── member/           # 成员名册与届别
│   │   │   ├── notification/     # 通知中心
│   │   │   ├── agent/            # 灵思：对话/工具调用/多 Agent 协作/对比实验
│   │   │   ├── knowledge/        # 知识库：索引源适配/关键词检索/事实快照/权限复核
│   │   │   ├── org/              # 组织注册/架构树/岗位/邀请（多租户入口）
│   │   │   └── user/ system/ dashboard/ seat/ schedule/ permission/ chat/ ...
│   │   └── ...
│   └── src/main/resources/db/
│       ├── migration/            # Flyway 双库通用迁移（V1–V34）
│       ├── migration-mysql/      # MySQL 专有迁移（V25–V27、V34）
│       └── migration-h2/         # H2 对应实现（测试用）
├── pams-web/                     # React 前端
│   └── src/
│       ├── api/                  # axios 封装与各模块接口（含 agent/knowledge/org）
│       ├── components/           # glass 组件体系 / 灵思（agent）组件 / 甘特图
│       ├── layouts/              # 主布局（侧边导航 + 顶栏）
│       ├── pages/                # 业务页面 + agent 灵思 / knowledge 知识库 / org 组织
│       ├── router/               # 路由与权限守卫
│       ├── stores/               # zustand 状态（含组织主题/领域包/术语）
│       └── styles/               # tokens / glass / lingsi / agent-team 样式
├── database/                     # 建库脚本 / 存量材料迁移脚本
├── docs/                         # 设计方案 / PRD / 灵思与知识库方案 / 审计报告等
├── pom.xml                       # 聚合工程（Maven modules 指向 pams-backend，供 IDEA 打开根目录使用）
├── docker-compose.yml            # 容器编排（mysql + backend + web）
├── start.bat                     # Windows 一键启动：建库 → 后端 → 前端 → 打开浏览器
└── README.md
```

## 常用命令

```bash
# 后端测试（H2 内存库 + Flyway 全量迁移，不依赖本地 MySQL）
cd pams-backend && mvn test

# 前端单测与构建
cd pams-web && npm run test && npm run build

# 前端端到端冒烟（Playwright，需先启动 MySQL 与后端 dev profile）
cd pams-web && npm run test:e2e
```

## 环境变量

| 变量 | 说明 | 默认 |
| --- | --- | --- |
| `PAMS_JWT_SECRET` | JWT 签名密钥；未设置时每次启动随机生成（重启后需重新登录） | 随机 |
| `DB_PASSWORD` | 后端连接本地 MySQL 的密码 | `root` |
| `PAMS_AGENT_API_KEY` | 灵思 LLM（DeepSeek）密钥；留空自动降级 stub 规则模式 | 空（降级） |
| `PAMS_UPLOAD_DIR` | 上传文件目录（容器内为 `/app/uploads`） | `./uploads` |

> 容器编排另从 `.env` 读取 `MYSQL_ROOT_PASSWORD` / `MYSQL_USER` / `MYSQL_PASSWORD`（见 `.env.example`）。仓库内不存任何真实密钥。

## 演示种子数据（仅空库注入，幂等）

- 基础字典（任何环境空库首启）：4 个部门（文秘部/组织部/新媒体中心/青年科技部）+ 7 个内置角色；届别「第九届」为当前届
- 默认组织（V30 迁移种入，id=1）承接全部存量数据；新注册组织独立隔离
- dev/test 追加 8 个演示账号（见上表）
- 业务演示数据（非 test 环境、库中无活动时自动注入）：示例活动「第四十期入党积极分子培训班」（执行中），含 6 条甘特图任务（带依赖链）、已审核策划书（含流程/预算）、5 步议程、3 条签到、1 条已发布推文、1 条新闻稿、1 条归档材料、1 条公告；4 名不同发展阶段的党务成员（入党积极分子/重点发展对象/预备党员/正式党员）；1 条无课表、1 条素拓加分记录

演示数据的日期均挂当前日期前后，登录后即可在活动列表/详情、甘特图、党务台账、内容宣传等处直接查看。

## 文档

`docs/` 目录包含：总体设计方案与 PRD（`PAMS设计方案.md`、`PRD-v4.md`、`PRD-v5-开发规划.md`）、灵思与知识库方案（`PAMS-Agent业务设计方案.md`、`PAMS-灵思知识库问答方案-v1.md`）、通用化与多 Agent 协作策划（`PAMS-通用化与多Agent协作改造策划.md`）、UI 设计方案（`PAMS-UI-设计方案-v3-简约高效.md` 等）、接口文档（`API.md`）与历次代码/权限审计报告。
