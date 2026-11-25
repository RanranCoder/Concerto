# 协奏 Concerto

**学生组织智能工作台 · PAMS**

协奏把活动筹备、排班考勤、成员资料、内容宣传和档案归集放进同一个工作台，并通过「灵思」AI 助手连接资料检索与业务操作。项目起源于信息与智能工程学院党建办公室的党务管理场景，现已支持多组织注册、数据隔离、组织主题和领域包配置。

GitHub 仓库 `RanranCoder/Concerto` 的默认分支为 `main`。后端工程沿用 PAMS 名称，界面产品名为「协奏」，智能助手名为「灵思」。生产部署状态以相应发布记录为准。

[快速启动](#快速启动) · [Docker Compose](#docker-compose) · [功能概览](#功能概览) · [灵思与知识库](#灵思与知识库) · [开发与验证](#开发与验证) · [文档导航](#文档导航)

![协奏工作台：周历、活动概况与待办](docs/design/ui-v6-implementation/dashboard-light.png)

*截图来自 UI 验收样本，用于展示布局与交互；示例名称、日期和数量不代表实际部署数据。*

## 功能概览

| 工作区 | 主要能力 |
| --- | --- |
| 工作台 | 本周活动、排班和任务截止聚合，待办入口，工作日 / 全周切换 |
| 活动管理 | 活动阶段推进、原生 DOCX 策划书编辑与版本审核、议程、座位表、评分、签到、任务甘特图与活动排班 |
| 排班考勤 | 常规排班、我的排班、活动排班预览与发布、签到考勤、按部门和学期导入并发布无课表 |
| 成员与党务 | 成员名册、届别、发展阶段、考察记录、转入转出和党务名册 |
| 宣传与档案 | 推文、新闻稿、材料、模板、素拓加分、公告和群聊资料 |
| 智能协作 | 灵思对话、资料问答、多 Agent 协作、持久任务与成果版本、变更确认、历史摘要与个人长期记忆 |
| 组织管理 | 自助注册、组织架构树、岗位、用户、租户角色、权限工作稿、发布历史与管理员委派 |
| 通知中心 | 业务消息、未读与截止提醒、公告确认、通知偏好、SSE 实时更新及投递运维 |

UI v6 提供明暗主题、组织品牌色、桌面侧栏和手机抽屉导航。活动、排班、档案与灵思采用各自的业务工作面，创建和编辑表单复用公共组件。菜单与操作入口会根据当前账号权限及组织领域包调整。

### 排班与考勤

- 活动排班可手动安排，也可按节次预览选人。保存为草稿后可单独或批量发布，发布时建立待签到考勤记录。活动使用统一签到码，刷新后旧码失效。
- 在「课表导入」选择部门和学期，上传文件名以姓名开头的 Excel 课表。解析全部成功时自动发布无课表。有文件失败时，可以修正后重试，或核对预览并确认「仅发布已解析文件」。同部门、同学期重新发布会更新当前无课表。

## 技术栈

| 层次 | 实现 |
| --- | --- |
| 前端 | React 18、TypeScript 5.7、Vite 7、Ant Design 5、Zustand、React Router 6 |
| 后端 | Java 21、Spring Boot 4.0.2、Spring Data JPA、Spring Security、JWT |
| 数据 | MySQL 8、Flyway；测试使用 H2 的 MySQL 兼容模式 |
| 实时通信 | 通知中心使用 SSE，灵思事件与权限刷新使用 WebSocket、STOMP、SockJS |
| 文档编辑 | SuperDoc 2.24.0 原生 DOCX 编辑、导入、保存与下载 |
| AI | OpenAI Chat Completions / Responses 协议、组织级 AI 服务、stub 规则模式、工具确认与多 Agent 编排 |
| 资料处理 | 关键词检索、MySQL ngram 全文索引及 LIKE 降级，Excel 与 Word 文档处理 |
| 验证与部署 | JUnit / MockMvc、Vitest、Playwright、Docker Compose、Nginx |

## 快速启动

[Docker Compose](#docker-compose) 可直接在容器中构建并启动整套应用。需要在宿主机运行后端和前端时，按下方步骤操作。

### 1. 准备环境与依赖

本地开发需要 **JDK 21、Maven 3.9+、Node.js 22.20+（22.x）和 MySQL 8**。将 `java`、`mvn`、`node`、`npm` 和 `mysql` 加入 PATH，Windows 一键启动还会使用 `mysqladmin`。

```bash
git clone https://github.com/RanranCoder/Concerto.git
cd Concerto

cd pams-web
npm ci
cd ..
```

### 2. 配置本地环境

Windows 上的 Bash 命令在 Git Bash 中执行。复制配置示例后编辑仓库根目录的 `.env`。

```bash
cp .env.example .env
```

在 `.env` 中填写本机 MySQL 的 root 密码。两种启动方式都创建 `pams_db`。下方命令通过环境变量指定该数据库，覆盖后端基础配置中的 `pams_concerto_demo`。

```dotenv
DB_PASSWORD=your_local_mysql_password
```

将示例中的 `PAMS_JWT_SECRET` 替换为自己的随机密钥。本地缺少该项时会使用临时密钥，重启后需重新登录。如需接入真实模型，同时填写 `PAMS_AGENT_MODEL` 和 `PAMS_AGENT_API_KEY`。后端从启动目录及其上一级读取 `.env`，系统环境变量优先。

### 3. 启动应用

**Windows 一键启动**：先启动 MySQL，再在仓库根目录的 PowerShell 中运行：

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://localhost:3306/pams_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false'
.\start.bat
```

脚本使用 MySQL `root` 用户和 `DB_PASSWORD` 创建 `pams_db`，在独立窗口中以 `dev` profile 启动后端、启动前端，再打开浏览器。

**手动启动**：在仓库根目录执行以下命令。建库命令中的密码应与后端配置一致。

```bash
# 创建数据库；表结构由后端启动时的 Flyway 迁移维护
mysql -u root -p < database/init_db.sql

# 终端一：后端（dev profile 用于首次初始化演示账号）
export SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/pams_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false'
mvn -f pams-backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=dev
```

```bash
# 终端二：前端
cd pams-web
npm run dev
```

| 服务 | 地址 |
| --- | --- |
| 前端 | <http://localhost:5173> |
| 后端 | <http://localhost:8080> |
| 注册组织 | <http://localhost:5173/register> |

Vite 已将 `/api`、`/uploads` 和 `/ws` 代理到后端。首次启动需等待 Maven 下载依赖、Flyway 迁移和种子初始化完成。

**使用 IntelliJ IDEA**：打开仓库根目录，导入根 `pom.xml` 聚合工程，运行 `com.pams.PartyAffairsManagementSystemApplication`。在运行配置的 Environment variables 中填写上面的 `SPRING_DATASOURCE_URL`。需要初始化开发演示账号时，将 Active profiles 设为 `dev`。前端单独运行 `npm run dev`。

### 演示账号与新组织

登录页的协奏演示账号预设对应[独立演示库](scripts/concerto-demo/README.md)。默认开发库的初始化账号如下。

开发 / 测试环境首次初始化基础部门与角色时，会创建以下演示账号，密码均为 **`123456`**：

| 用户名 | 初始业务角色 |
| --- | --- |
| `teacher` | 指导老师 |
| `zhuren` | 主任 |
| `orgleader` | 组织部长 |
| `secleader` | 文秘部长 |
| `medialeader` | 新媒体部长 |
| `techleader` | 青年科技部长 |
| `admin` | 主任（账号显示名为系统管理员） |
| `staff` | 干事 |

这些账号仅在 `dev` / `test` profile 下初始化；为已有数据库补开 `dev` 不会自动补齐演示账号。角色名称和历史级别不直接决定当前治理权限，实际授权以组织内已发布配置为准。

默认组织的示例活动、策划书、党务资料等由 `DataSeeder` 在非 `test` 环境、活动表为空时幂等生成；**示例业务数据与演示账号的初始化条件不同**。

需要独立组织时，从登录页进入「注册组织」，配置组织名称、架构、领域包与管理员账号。新组织拥有独立的租户角色和数据空间。生产部署不启用 `dev`，通过注册流程创建组织及其管理账号。

## 灵思与知识库

灵思入口为 `/agent`，任务列表、对话过程和工作成果在同一个工作区内呈现。

| 模式 | 行为 |
| --- | --- |
| 标准模式 | 查询和准备业务操作；写入前展示待确认清单，确认后执行 |
| 只读模式 | 查询业务信息，写工具不可用 |
| 资料问答 | 检索知识库资料，按引用来源回答，使用独立问答流水线 |

![灵思：确认清单与工作成果预览](docs/design/ui-v6-lingsi-implementation/plan-preview-light.png)

- **先准备，再确认**：缺少信息时在对话中补齐；执行前可检查内容、变更与参考资料，执行后查看回执、失败或跳过项。
- **排班预览不写库**：确认时重新计算并核对候选指纹；人员或条件变化后要求重新生成，确认成功只创建草稿，仍需在活动排班页发布。
- **自动协作**：多 Agent 协作已内聚到灵思标准模式，专家进度与审查过程在对话内展示；不再提供独立 `/agent/team` 页面。后端保留串行、并行汇聚、反思和委派等编排能力。
- **历史可恢复**：保存会话、任务状态、模型思考记录与过程旁白；历史内容仍按任务归属、数据敏感级别和当前权限处理。
- **资料可追溯**：知识库覆盖制度文档、公告、推文、策划书、事实快照和成员名册，检索与查看来源时检查访问权限。当前使用关键词检索，并非向量数据库方案。
- **实验入口**：`/admin/benchmark` 提供单 Agent / 多 Agent 对比，访问受权限守卫控制。

拥有 `org:manage` 的账号可在「系统设置 → AI 服务」（`/admin/settings?section=ai`）测试并启用本组织的默认服务。组织没有启用服务时使用部署配置，部署配置缺少模型 ID 或 API Key 时使用 stub 规则模式。部署协议、超时及输出上限见 [application.yml](pams-backend/src/main/resources/application.yml)，组织配置与密钥保存见 [AI 服务设置](docs/design/ai-services/README.md)。

### 任务恢复与协作开关

任务、执行检查点、待确认清单和成果版本持久化保存。恢复会核对持久化回执，避免重复提交业务写入。V71 为运行实例增加数据库租约，供恢复流程判断原执行实例是否仍存活。详情与隔离验收方法见 [S5 验收说明](scripts/agent-s5/README.md)。

`pams.agent.collaboration-enabled=false` 可将新请求切回单 Agent 路径；`pams.agent.collaboration-org-ids` 非空时限定协作组织。基准评估默认关闭，启用 `pams.agent.benchmark-enabled` 时还需设置 `pams.agent.benchmark-org-ids`。配置变更需重启应用，已有任务和清单继续保留。受控规划与受控执行默认关闭。受控执行要求两个开关均启用，且当前组织列在 `pams.agent.controlled-planning-org-ids` 中。

### 上下文与长期记忆

长对话采用近期完整轮次、增量持久化摘要与原文回查共同组装上下文，并在调用前执行输入预算检查。摘要引用真实来源消息，读取时重新校验归属、权限与来源变化；业务完成状态仍以数据库和工具回执为准。预算采用 UTF-8 字节估值，不是供应商精确 token 计数。

灵思顶部的「长期记忆」支持新增、编辑、候选确认、搜索、有效期、删除和关闭读取，也可直接发送：

```text
记住：风格=回答简洁，先给结论
记住：【项目:党建】格式=先列议程，再给分工
查看长期记忆
忘记：风格
关闭长期记忆
开启长期记忆
```

记忆绑定当前组织与当前用户；项目标签属于个人分类。项目偏好需要在本轮问题中明确带上对应标签。当前要求优先，记忆不能替代审批或业务确认。系统没有启用自动抽取，未确认候选不进入模型上下文；删除记忆不会删除旧聊天。管理抽屉中的使用统计按用户与组织隔离、在进程内限时保存，缺失 token 显示为空，不换算费用。实现边界见[上下文管理](docs/design/PAMS-Agent上下文管理落地说明.md)、[长期记忆](docs/design/PAMS-Agent长期记忆落地说明.md)与[产品化验收](docs/design/PAMS-Agent记忆产品化验收.md)。

## Word 策划书

活动策划书使用 SuperDoc 原生 DOCX 编辑器，提供开始、插入、布局、审阅、视图工具页签。支持导入 Word、编辑、保存及下载已保存文档，无需额外部署 Office 服务。保存时上传独立 DOCX 文件并绑定当前草稿，后端从文件提取知识库检索正文；已提交版本不可直接编辑，修订会创建新的业务版本，文档读取仍按版本与审核权限控制。

旧 HTML 策划书通过兼容迁移进入文档工作面。编辑器未就绪或保存失败时会保留错误与重试入口。部署使用的编辑器许可证通过前端构建变量 `VITE_SUPERDOC_LICENSE_KEY` 注入；本地开发可在 `pams-web/.env.local` 配置，Compose 构建从根目录 `.env` 读取同名值。详见 [SuperDoc 接入](docs/design/PAMS-SuperDoc-接入与验收.md)和[工具页签](docs/design/PAMS-SuperDoc-工具页签落地.md)。

## 通知中心

通知管线记录业务事实和接收人，由后台投递，并通过带认证的 `/api/notifications/stream` SSE 连接更新页面。功能覆盖任务与审核消息、截止提醒、公告确认、个人通知策略与摘要，以及投递指标、维护订阅和运维审计。反向代理需支持 SSE 并关闭该流的缓冲；仓库 Nginx 配置已包含相关设置。

隔离数据库、迁移和实时连接的演练方法见[通知验收工具](scripts/notification-acceptance/README.md)与[上线演练记录](docs/design/PAMS-消息通知管线-v1-上线验收演练记录.md)。

## 组织与权限

业务数据按组织隔离，Hibernate 租户过滤、请求上下文和异步任务上下文共同传递组织身份。组织可配置主题色和术语，并在系统设置的扩展功能中启用党建（`party`）、团务（`youth`）与志愿服务（`volunteer`）。业务入口同时受功能开关和账号权限控制。

当前授权模型将**业务主角色**与**组织治理身份**分开：

- 用户采用一个业务主角色；角色可从模板创建、复制，经历草稿、启用、停用和归档等状态。
- 权限工作稿不参与运行鉴权；预览并发布后生成生效授权及不可变历史快照。
- 所有者可委派或撤销组织管理员，配置可授权清单和有效期；管理身份不等于自动获得全部业务数据访问权。
- 用户角色替换通过预览与应用流程校验，角色发布、分配和委派变更保留审计记录。
- Agent 工具执行、知识来源读取和聚合接口同样执行权限检查，不能依赖前端菜单隐藏实现鉴权。

首版权限配置仅开放 **`ORG`（本组织）范围**，尚未实现节点子树、本人、指定资源等新范围，也不支持多业务角色叠加。旧批量开户与固定密码重置入口已关闭。所有者交接、平台治理后台、历史恢复和审计导出等能力仍在后续范围内。完整边界见[首版落地记录](docs/PAMS-角色权限与租户管理-首版落地记录.md)。

## 配置与部署

### 常用配置

| 配置项 | 用途与默认行为 |
| --- | --- |
| `DB_PASSWORD` | 后端本地 MySQL 密码，默认 `root` |
| `SPRING_DATASOURCE_URL` | 覆盖数据库连接地址。后端基础配置使用 `localhost:3306/pams_concerto_demo`，本地开发按快速启动连接 `pams_db`，Compose 使用 `mysql:3306/pams_db` |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | 通过系统环境变量覆盖数据库账号与密码 |
| `PAMS_JWT_SECRET` | JWT 签名密钥；本地未设置时随机生成，生产需显式配置 |
| `PAMS_AGENT_PROTOCOL` / `PAMS_AGENT_BASE_URL` | 部署默认协议与 API 根地址，默认 `openai_responses` 和 `https://api.openai.com/v1`。旧服务可显式配置 `openai_chat_completions` |
| `PAMS_AGENT_TIMEOUT_MS` | 单次模型调用的超时，默认 `120000` 毫秒 |
| `PAMS_AGENT_MODEL` / `PAMS_AGENT_API_KEY` | 部署默认模型 ID 与密钥，两项都需要填写 |
| `PAMS_AGENT_PROVIDER` | 可设为 `stub` 使部署默认服务使用规则模式 |
| `PAMS_AI_ENCRYPTION_KEY` / `PAMS_AI_KEY_FILE` | 组织 AI 服务密钥的加密主密钥或自动生成的密钥文件路径 |
| `PAMS_UPLOAD_DIR` | 上传文件保存目录，默认 `./uploads`，相对于后端进程工作目录 |
| `VITE_SUPERDOC_LICENSE_KEY` | 前端构建时注入的 SuperDoc 许可证配置，修改后需重新构建前端 |
| `MYSQL_ROOT_PASSWORD` / `MYSQL_USER` / `MYSQL_PASSWORD` | Docker Compose 中 MySQL 初始化与应用连接配置 |

`PAMS_AGENT_PROVIDER`、`PAMS_AGENT_MODEL`、`PAMS_UPLOAD_DIR` 等通过系统环境变量覆盖 Spring 配置；Compose 默认只向后端传入编排文件中列出的环境变量，新增配置需同步修改其 `environment`。

### Docker Compose

需要 Docker 和 Compose 插件。若还没有根目录 `.env`，先从 `.env.example` 复制，再填写 `MYSQL_ROOT_PASSWORD`、`MYSQL_USER`、`MYSQL_PASSWORD` 和 `PAMS_JWT_SECRET`。如需接入部署默认模型，填写模型 ID 与密钥，并按服务选择协议与 API 根地址。

```bash
docker compose up -d --build
docker compose ps
```

编排包含 MySQL、后端和 Nginx 前端三个服务，访问 <http://127.0.0.1:8081>。前端端口默认仅绑定宿主机回环地址；对外访问需另外配置反向代理或端口映射。MySQL 数据、上传文件和自动生成的 AI 服务加密密钥分别保存到 `pams-mysql-data`、`pams-uploads` 和 `pams-ai-secrets` 命名卷。备份组织 AI 配置时同时保留加密主密钥。

当前 Flyway 迁移最高为 [**V74**](pams-backend/src/main/resources/db/migration/V74__no_class_schedule_publication.sql)。V59、V60 加入 AI 服务配置和密钥状态，后续迁移扩展了业务回执、受控规划、提示词快照和请求级写入状态。V71 增加 Agent 实例租约，V72 加入团务与志愿服务的数据表和权限，V73 更新排班与考勤一致性约束，V74 支持导入后发布无课表。升级存量数据库前应备份，并在目标 MySQL 环境验证迁移、所有者归属和授权结果；H2 回归不能替代生产迁移验收。部分历史部署采用单独应用字段或表的发布方式，不能仅凭源码版本认定线上 Flyway 历史已经同步，也不能为启用记忆直接执行所有待处理迁移。

### 使用已构建的镜像

推送到 `main` 或手动触发后，GitHub Actions 先运行后端和前端测试，通过后发布后端与前端的 `latest` 镜像。若部署使用 GHCR，在服务器的 `.env` 中设置 `PAMS_IMAGE_PREFIX=ghcr.io/ranrancoder/pams`。运行 `./deploy.sh` 时，脚本会快进更新代码、拉取镜像并启动服务。有了可访问的镜像后，也可直接使用 `docker compose pull` 和 `docker compose up -d --no-build`。 [部署脚本](deploy.sh) · [Compose 配置](docker-compose.yml) · [镜像构建工作流](.github/workflows/build-push-images.yml)

## 开发与验证

在仓库根目录运行后端测试：

```bash
mvn -f pams-backend/pom.xml test
```

后端测试使用 H2 测试配置与 Flyway 迁移，不依赖本机 MySQL；测试 profile 默认使用 stub，避免因本机存在模型密钥而调用真实 API。

```bash
cd pams-web
npm test
npm run build
```

`npm run build` 会执行 TypeScript 检查和 Vite 生产构建。

Playwright 包含模拟 API 的 UI 回归及需要真实后端的冒烟测试。运行完整集合前，准备好 MySQL、`dev` 后端和浏览器：

```bash
cd pams-web
npx playwright install chromium
npm run test:e2e
```

配置会启动或复用 5173 上的 Vite 服务，不负责启动后端。部分 UI 用例在 Windows 上指定 Microsoft Edge，需本机已安装；其他用例可通过 `PAMS_E2E_CHROME_CHANNEL` 选择本机浏览器。浏览器样本测试用于验证页面行为，不能代替真实接口与数据库联调。

排班考勤和长期记忆可分别用 `npx playwright test e2e/routine-v6.spec.ts` 和 `npx playwright test e2e/agent-memory.spec.ts` 定向验证。S5 真实模型、进程恢复和浏览器验收使用独立 MySQL 测试库及显式开关，不属于默认离线测试；执行命令与前置条件见 [S5 工具说明](scripts/agent-s5/README.md)。通知系统的隔离验收另见[通知工具说明](scripts/notification-acceptance/README.md)。历史验收数量与结果记录在各自文档中，不代表每次拉取代码后的测试结果。

## 项目结构

```text
Concerto/
├── .github/workflows/                       # 镜像构建与专项验收
├── pams-backend/
│   └── src/
│       ├── main/java/com/pams/
│       │   ├── common/、config/、security/   # 公共设施、租户上下文与认证
│       │   └── module/                      # 活动、排班、党务、档案、Agent、知识库、组织与权限
│       ├── main/java/db/migration/           # Java Flyway 迁移
│       ├── main/resources/
│       │   ├── application.yml
│       │   └── db/                          # 通用 / MySQL / H2 迁移
│       └── test/                            # 单元与集成测试
├── pams-web/
│   ├── src/
│   │   ├── api/、stores/                     # 接口与应用状态
│   │   ├── components/                       # 品牌、表单、灵思及业务共享组件
│   │   ├── layouts/、pages/、router/          # 页面、导航与权限守卫
│   │   └── styles/、utils/                   # v6 主题、样式与工具函数
│   ├── dev/                                 # 开发代理辅助逻辑
│   ├── e2e/                                 # Playwright 用例
│   ├── public/                              # 静态资源
│   └── scripts/                             # 前端辅助脚本
├── database/                                # 建库与存量材料处理脚本
├── docs/                                    # 使用指南、设计档案与验收证据
├── scripts/                                 # 演示数据、灵思与通知验收工具
├── pom.xml                                  # Maven 聚合入口
├── .env.example                             # 容器环境配置示例
├── docker-compose.yml
├── deploy.sh
└── start.bat                                # Windows 本地开发启动入口
```

## 文档导航

| 主题 | 文档 |
| --- | --- |
| 当前使用与接口 | [业务操作手册](docs/协奏PAMS-当前业务功能与使用流程说明.md)、[API 接口清单](docs/API-inventory.md)、[AI 服务设置](docs/design/ai-services/README.md) |
| 历史设计与规划 | [总体设计](docs/PAMS设计方案.md)、[PRD v5 开发规划](docs/PRD-v5-开发规划.md)、[通用化与多 Agent 策划](docs/PAMS-通用化与多Agent协作改造策划.md) |
| 品牌与 UI | [协奏品牌标识](docs/design/协奏-品牌标识设计说明.md)、[UI v6 设计规范](docs/design/PAMS-UI-设计方案-v6-行业规范修订.md)、[表单工作面](docs/design/PAMS-表单工作面重设计.md) |
| 业务界面落地 | [排班考勤](docs/design/PAMS-UI-v6-排班考勤落地记录.md)、[组织宣传档案与党务](docs/design/PAMS-UI-v6-组织宣传档案与党务台账落地记录.md)、[登录与注册](docs/design/PAMS-登录与组织注册-改版与修复.md) |
| 灵思与资料 | [Agent 业务方案](docs/PAMS-Agent业务设计方案.md)、[灵思 v6 落地记录](docs/design/PAMS-UI-v6-灵思Agent落地记录.md)、[知识库问答方案](docs/PAMS-灵思知识库问答方案-v1.md) |
| 灵思运行与记忆 | [升级方案](docs/design/PAMS-灵思Agent升级优化方案.md)、[S5 联调评估](docs/design/PAMS-灵思Agent-S5联调评估报告.md)、[上下文管理](docs/design/PAMS-Agent上下文管理落地说明.md)、[长期记忆](docs/design/PAMS-Agent长期记忆落地说明.md)、[记忆产品化验收](docs/design/PAMS-Agent记忆产品化验收.md) |
| Word 与通知 | [SuperDoc 接入](docs/design/PAMS-SuperDoc-接入与验收.md)、[通知策略与指标](docs/design/PAMS-消息通知管线-v1-通知策略与指标落地记录.md)、[通知上线演练](docs/design/PAMS-消息通知管线-v1-上线验收演练记录.md) |
| 授权模型 | [角色权限与租户管理方案](docs/PAMS-用户角色权限与多租户管理设计方案-v1.md)、[管理员业务设计](docs/PAMS-管理员角色权限管理业务设计-v1.md)、[首版落地记录](docs/PAMS-角色权限与租户管理-首版落地记录.md) |
| 审计与历史资料 | [文档索引](docs/README.md)、[2026-11-11 代码审计及修复记录](docs/audits/code-audit-2026-11-11.md) |

设计方案包含后续规划；判断当前可用能力时，以本分支代码、相应落地记录及实际授权配置为准。

## 许可证

项目代码采用 [MIT License](LICENSE)。SuperDoc 等第三方组件的使用与部署遵循各自许可证。
