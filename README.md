# 党务管理系统（PAMS）

信息与智能工程学院党建办公室 · 党务管理系统

覆盖活动管理（策划书/议程/座位表/评分/签到/甘特图任务）、例行事务（排班/考勤/无课表）、党务台账（党员发展/考察/转入转出/名册）、内容宣传（推文/新闻稿）与档案资产，支持基于角色（指导老师/主任/部长/干事）的细粒度权限控制。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Spring Boot 4.0、Spring Data JPA、Spring Security + JWT、Flyway、MySQL 8 |
| 前端 | React 18、TypeScript 5.7、Vite 7、Ant Design 5、Zustand、React Router 6、dayjs |
| 测试 | 后端 JUnit 5 + MockMvc + H2（MySQL 模式）；前端 Vitest |
| 样式 | 自研 liquid glass 设计系统（`pams-web/src/components/glass/`） |

## 快速启动

1. 创建数据库：`mysql -u root -p < database/init_db.sql`（创建 `pams_db`，默认 utf8mb4）
2. 后端：`cd pams-backend && mvn spring-boot:run`（端口 8080）
   - 数据库账号密码默认 `root / root`，可用环境变量覆盖：`DB_PASSWORD=yourpass`
   - 启动时 Flyway 自动建表；**空库首次启动会自动注入演示种子数据**（含"第四十期入党积极分子培训班"全流程示例，便于验收演示；已有数据的库不会重复注入）
3. 前端：`cd pams-web && npm i && npm run dev`（端口 3000，已配置 `/api` 与 `/uploads` 代理到 8080）

## 初始账号（密码均 `123456`）

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

## 目录结构

```
PAMS/
├── pams-backend/                 # Spring Boot 后端
│   ├── src/main/java/com/pams/
│   │   ├── config/               # 安全配置 / 审计 / DataSeeder（基础账号 + 演示数据）
│   │   ├── entity/               # 用户组织（sys_user/sys_role/sys_department）
│   │   ├── security/             # JWT 认证与角色权限
│   │   ├── module/
│   │   │   ├── activity/         # 活动：策划书/议程/座位/评分/签到/任务（甘特）
│   │   │   ├── routine/          # 排班/考勤/无课表
│   │   │   ├── party/            # 党务台账：成员/阶段/考察/转入转出/名册
│   │   │   ├── content/          # 推文/新闻稿
│   │   │   ├── archive/          # 档案材料/模板/素拓加分/公告
│   │   │   ├── dashboard/        # 仪表盘聚合
│   │   │   └── user/ system/     # 用户/部门/角色管理、系统信息
│   │   └── ...
│   └── src/main/resources/db/migration/   # Flyway 迁移（V1 建表 / V2 修正）
├── pams-web/                     # React 前端
│   └── src/
│       ├── api/                  # axios 封装与各模块接口
│       ├── components/           # glass 设计系统 / 甘特图组件
│       ├── layouts/              # 主布局（侧边导航 + 顶栏）
│       ├── pages/                # 活动/例行/党务/内容/档案/用户管理/仪表盘
│       ├── router/               # 路由与权限守卫
│       └── stores/               # zustand 状态
├── database/                     # 建库脚本 / 存量材料迁移脚本
├── docs/                         # 设计方案等文档
└── README.md
```

## 常用命令

```bash
# 后端测试（H2 内存库 + Flyway，不依赖本地 MySQL）
cd pams-backend && mvn test

# 前端测试与构建
cd pams-web && npm run test && npm run build
```

## 演示种子数据（仅空库注入，幂等）

首次启动后端且库中无活动时，自动注入以下演示数据（`DataSeeder`）：

- 示例活动「第四十期入党积极分子培训班」（执行中），含 6 条甘特图任务（带依赖链）、已审核策划书（含流程/预算）、5 步议程、3 条签到、1 条已发布推文、1 条新闻稿、1 条归档材料、1 条公告
- 4 名不同发展阶段的党务成员（入党积极分子/重点发展对象/预备党员/正式党员，各挂对应 stage）
- 1 条无课表、1 条素拓加分记录

演示数据的日期均挂当前日期前后，登录后即可在活动列表/详情、甘特图、党务台账、内容宣传等处直接查看。
