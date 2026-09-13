# PAMS 全项目代码审计报告

**日期**：2026-08-23
**方式**：Workflow 多 agent 编排——7 个维度并行审查（后端安全/业务正确性/性能质量、数据库 schema、前端安全/正确性、前后端契约与测试缺口）→ 所有 critical/high 发现由独立验证员逐条对抗核实（默认怀疑、尝试推翻）→ 完整性批评家检查盲区。共 14 个 agent、728 次工具调用，所有结论均基于实际读取的代码（附 file:line）。

**结果概览**

| 指标 | 数值 |
|---|---|
| 原始发现 | 71 条 |
| 进入对抗验证（critical/high） | 17 条 |
| **验证确认** | **14 条** |
| 全盘推翻 | 0 条 |
| 部分成立（降级/缩窄） | 3 条 |
| medium/low（未单独验证） | 54 条 |

---

## 一、确认的高危问题（14 条 CONFIRMED）

### A. 越权与账号安全（4 条）

**A1. 重置密码接口缺少级别校验——部长可重置指导老师/主任密码实现账号接管**
- 位置：`pams-backend/.../module/user/UserService.java:195`（`UserController.java:73` 入口）
- 问题：createUser/updateUser/deleteUser 都有防提权闭环（checkAssignableLevel/checkDeleteLevel），唯独 resetPassword 漏掉。组织部长（level 3）可以直接对指导老师（level 5）、主任（level 4）甚至 admin 账号调 `POST /api/users/{id}/reset-password`，把对方密码重置为已知值后登录其账号。
- 攻击路径：登录 `orgleader/123456` → 重置 teacher 的密码 → 用 teacher 账号进入系统。
- 修法：resetPassword 增加 currentLevel 参数复用 checkDeleteLevel 同等逻辑；或收紧为仅 TEACHER/DIRECTOR 可重置他人密码。

**A2. 素拓加分模块对所有登录用户开放增删改**
- 位置：`pams-backend/.../archive/controller/CreditController.java:33`
- 问题：全文件零 `@PreAuthorize`（38 个 Controller 中唯一为 0 的之一）。任何登录用户（含干事 STAFF）可给任意姓名/学号写任意分数、物理删除他人加分记录、从活动批量造分、整批撤回。Service 层同样无归属校验。素拓分直接影响评优评先，属高价值数据完整性风险。
- 修法：写接口加 `@PreAuthorize("hasAuthority('quality:add')")` 等（双轨链路已具备 hasAuthority 能力）；查询保持全员可见。

**A3. 材料/新闻稿/模板/值班表/考勤/议程/文件 七个控制器写接口零授权**
- 位置：MaterialController.java:44、NewsController、TemplateController、ScheduleController(routine)、AttendanceController、AgendaController、FileController
- 问题：任一干事登录后可直接 PUT/DELETE 归档材料、上传名单文件；`GET /api/files/{id}/download` 无鉴权无归属校验，按自增 id 可枚举下载任意上传文件（含学生名单 xlsx）。
- 修法：按 member 模块已验证的模式补权限码（读 view / 写 manage），FileController.download 至少要求 material:view。

**A4. DataSeeder 向每个新建库注入 8 个固定弱口令账号（含 admin/123456）**
- 位置：`pams-backend/.../config/DataSeeder.java:152`
- 问题：空库首启即创建 teacher/zhuren/orgleader/.../admin/staff 共 8 个账号，统一密码 123456，其中 admin 是 DIRECTOR(level 4)。仅 test profile 跳过演示数据，生产空库首启同样生效；配合「登录无失败锁定」（见未验证项），知道约定的人可直接进系统。
- 修法：种子数据限定 dev profile；生产首启强制随机初始密码+首登改密；至少上线后立即改掉全部种子口令并停用 admin。

### B. 功能性 Bug（1 条）

**B1. 扫码签到的「人数达标通知」永远不会发出**
- 位置：`pams-backend/.../activity/service/SigninService.java:107`
- 问题：签到完成事件监听器声明为 AFTER_COMMIT 且无 fallbackExecution，但唯一发布点 scanSignin 没有 @Transactional（save 的隐式事务早已提交），Spring 默认直接丢弃无事务上下文的事件。链路4「签到人数达标通知活动创建者」在主路径上静默失效。其余事件都在事务内发布，不受影响。
- 修法：scanSignin 加 @Transactional，或监听器加 fallbackExecution=true。

### C. 数据库与数据完整性（3 条）

**C1. course_schedule / schedule_config 两张表不在任何 Flyway 迁移中——全新部署相关接口必然 500**
- 位置：`pams-backend/.../schedule/entity/CourseSchedule.java:12`
- 问题：生产配置 ddl-auto=none，schema 完全靠 Flyway V1~V14，但这两个活跃实体的建表语句在全部迁移中零命中。当前开发库是因 Flyway 引入前手工建过表才侥幸可用；docker-compose 全新部署=空库+Flyway，「无课表制作」整组接口（configs/保存课表/空闲统计）启动即坏。
- 修法：新增 V15 迁移补两张表的 CREATE TABLE IF NOT EXISTS（列定义与实体对齐，已有库幂等）。

**C2. V13 成员表软删唯一键设计缺陷——同一成员第二次删除必然失败**
- 位置：`pams-backend/src/main/resources/db/migration/V13__member.sql:32`
- 问题：`(session_id, student_no, deleted)` 以 deleted 作成分，MySQL 只允许存在一行 deleted=1。复现：导入(届1,学号X)→删除→重建同号→再删除 → 两行 (1,X,1) 撞唯一键 → DuplicateKeyException，该成员从此无法删除。(name, deleted) 同理。
- 修法：新迁移把 deleted 换成 deleted_at DATETIME NULL（多行 NULL 在 InnoDB 唯一索引中共存），或软删时置 deleted=id。

**C3. sys_user.username 硬唯一与软删模型冲突——删除账号后无法重建同名账号，一键导入整批 500**
- 位置：`pams-backend/src/main/resources/db/migration/V1__init.sql:21`
- 问题：username 自 V1 硬唯一（含已软删行），而 existsByUsername 被 @SQLRestriction 过滤看不到已删行。误删账号后再建同学号账号、或走「从花名册一键导入」，INSERT 必撞 UNIQUE(username)：单建报错、批量导入整个事务回滚。
- 修法：改为 (username, deleted) 复合唯一键；或在 createUser/导入前显式查出已软删同名行做「复活」而非插入新行。

### D. 性能（3 条，随数据量增长恶化）

**D1. 仪表盘单次请求 6 张表 findAll 全表加载后内存过滤** — `DashboardService.java:69`。首页接口遍历所有活动数状态、拉全部排班只留本周、三个 recent 各加载整表（含 Article.content 正文大字段）排序取前 5、任务全表按姓名匹配。应改数据库端聚合/分页/LIMIT。

**D2. 无课表空闲统计最内层逐人查库** — `CourseScheduleService.java:136`。天×节次×人员三重循环，同一人课表被重复查 35 次，选 50 人≈1750 条 SQL，秒级耗时；userIds 为空时还会放大到全体用户。应循环外一次 findByUserIdIn 建 Map 后纯内存计算。

**D3. 通知列表全量查询 + 逐条查发送者 + 写侧膨胀** — `NotificationService.java:53`。铃铛列表无分页拉全量、每条 toVO 单查 sender、controller 拿到 VO 后才截 50；审批通过事件对全体用户逐人插通知行。应改 pageForUser 分页 + findAllById 批量映射 + 广播行替代逐人插行。

### E. 前端（3 条，其中两条是本次 RBAC 接通的遗留）

**E1. zustand persist 无 version/migrate——老登录缓存缺 permissions，菜单塌缩成只剩仪表盘**
- 位置：`pams-web/src/stores/auth.ts:20`
- 问题：RBAC 升级（545bae9）新增 user.permissions 后，老用户 localStorage 里的旧 user 对象原样恢复、permissions 为 undefined → 全部权限判断 false → 侧边栏塌缩、RequirePerm 页面全 403，直到重新登录才恢复。对已部署环境做这次升级会立刻触发。
- 修法：短期给 persist 加 migrate 补字段；彻底方案是应用启动时用 JWT 调 /api/auth/me 换最新 user+permissions（顺带解决下一条「改权限必须重登」）。

**E2. 党务台账菜单对干事可见，点进去却是 403（本次 RBAC 接入引入的回归）**
- 位置：`pams-web/src/router/index.tsx:84` 与 `MainLayout.tsx:92`
- 问题：V7 种子给 STAFF 配了 party:view（「干事读脱敏数据」口径），改造后菜单按 party:view 显示入口；但 /party 路由仍包 RequireRole(LEADER_ROLES) 不含 STAFF——改造前该菜单按 roleLevel>=3 隐藏，干事看不到；现在看得到、进不去。
- 修法（推荐 a）：(a) 该菜单项显示条件改回 roleLevel>=3（维持「入口对干事隐藏」的原设计意图，改动最小）；(b) 若产品上接受干事看脱敏视图，则路由改 RequirePerm(['party:view']) 并核实 PartyMemberList 对 STAFF 的脱敏可靠。

**E3. 素拓加分页按钮无门控，与 A2 叠加构成完整越权面**
- 位置：`pams-web/src/pages/archive/CreditList.tsx:193`
- 问题：「新增加分/活动加分/删除」按钮无条件渲染（全站导出的 useHasPerm 无一处使用），路由也无守卫；配合后端零鉴权，STAFF 默认没有 quality:add/quality:delete 却能在页面上直接加删素拓记录——权限管理页的勾选对该模块完全不起作用。
- 修法：后端按 A2 补 hasAuthority；前端按钮用 useHasPerm 按 quality:add/quality:delete 显隐。

---

## 二、部分成立（3 条 PARTIAL，含验证员裁决理由）

**P1. JWT 签名密钥硬编码在 application.yml（降级为 medium）**
密钥确实随 Public 仓库泄露并打包进 GHCR 镜像；但 docker-compose 部署路径会用 `PAMS_JWT_SECRET` 环境变量覆盖、未设置时应用直接启动失败，不存在静默回退——「拉镜像即可伪造 token 打真实实例」不成立。**残余风险真实**：start.bat/mvn 直跑路径使用公开密钥；密钥已泄露且无轮换机制；过期 72h 偏长。建议：轮换密钥、dev 配置也改环境变量注入、expire-hours 降到 8-24h。

**P2. 扫码签到无幂等去重（核心缺陷属实，放大链路不成立）**
scanSignin 直接 save、无应用层查重、signin 表也无 (activity_id, student_no) 唯一约束——刷新重扫/双击会产生重复记录，且手动补签特意做了幂等，扫码路径漏掉属真实不一致。「重复记录流入素拓批量加分」的放大链被验证员否掉。建议：scanSignin 先查重 + 表加可空唯一索引 + 公开接口加简单频控。

**P3. 双轨权限链路零测试断言（属实，本次改动的高危空白）**
全部测试源码 grep 'permissions'/'hasAuthority' 零命中——JwtAuthenticationFilter 忘注入权限码、permissionsOfRole 返回空集、登录漏发 permissions 字段，现有测试依旧全绿，现实中却是全员 403/菜单消失。建议补三类断言：①登录响应断言 permissions 非空且 TEACHER 含 member:view；②STAFF 访问 member 写接口 403、无 user:permission 访问 /api/permissions 403；③V14 迁移后各角色权限码计数断言。

---

## 三、值得排期的 medium（未单独验证，抽样看证据可信）

| # | 问题 | 位置 |
|---|---|---|
| M1 | 创建用户/导入账号/重置密码默认口令固定 123456 | UserService.java:110 |
| M2 | 签到记录列表无权限校验，任意登录用户可拉全部活动的姓名/学号/手机号 | SigninController.java:34 |
| M3 | 登录无失败次数限制/锁定，可无限速暴力破解 | AuthController.java:38 |
| M4 | /uploads/** 完全免登录直出（配合 A3 的 id 枚举） | SecurityConfig.java:55 |
| M5 | 权限管理页文案「变更刷新页面即生效」不实——permissions 是登录快照，必须重新登录 | PermissionManage.tsx:111（根治靠 E1 的 /auth/me 方案） |
| M6 | 权限管理页展示并可编辑全部 47 码，但后端仅 5 个真正生效（双轨迁移不完整的既知边界） | PermissionController.java:20 |
| M7 | Word 预览 docx-preview 输出未经消毒注入 DOM；座位表打印 document.write 注入 | FilePreviewModal.tsx:144、SeatLayoutEditor.tsx:336 |
| M8 | 活动状态机允许逐步跳过策划审核直达归档 | ActivityStatus.java:13 |
| M9 | 群聊列表/成员列表 toVO N+1；RoutineService 全表加载 | GroupChatService.java:97、MemberService.java:172 |
| M10 | DataSeeder 幂等判断基于软删视图，删光数据重启会把演示数据复活进生产库 | DataSeeder.java:161 |
| M11 | 通知类型枚举前后端错位（前端筛选含 9 个后端不存在的类型） | NotificationList.tsx:16 |
| M12 | 成员页可用性绑死 member:manage——只配 member:view 的自定义角色整页瘫痪 | MemberList.tsx:61 |

其余 low（临时文件不清理、错误码冲突、弹窗防重复点击等）见附录数据。

---

## 四、盲区（完整性批评家指出，本轮未深审）

1. **文件上传/下载链路**：uploads 卷 + FileController/httpBlob/FilePreviewModal——越权下载与存储型内容风险面（A3/M4 已部分触及，值得专项）。
2. **WebSocket 通道**：握手鉴权与订阅授权独立于 HTTP 过滤链，容易整段漏掉的攻击面。
3. **配置基线**：application.yml 弱密钥回退 + DB 密码 root 默认（P1 相关）。
4. **构建/CI 链**：GHCR :latest 单标签推送、deploy.sh 手动 pull，无 digest 固定/回滚机制。
5. **双轨建库引导漂移**：database/init_db.sql 与 Flyway 两套 schema 来源并存。
6. **定时任务**：ArticleDeadlineTask 异常静默、时区错位不经请求路径暴露。
7. **Excel 解析健壮性**：畸形 xlsx 的 OOM/500 面。
8. **⚠️ 仓库里的遗留数据产物**：根目录 extract_stdout.txt、field_extract_result.txt（105KB×2，已被 git 跟踪）记录了历届真实材料的字段清单与人名相关目录结构——**属于数据泄露而非代码问题，建议尽快从仓库移除**（Public 仓库）。

---

## 五、做得好的（验证员确认）

- **零原生 SQL**：持久层全 JPA 派生查询/Criteria，排序字段硬编码，SQL 注入面基本为零。
- **口令处理规范**：BCrypt 统一；登录错误文案不泄露账号存在性；password 不进任何响应 VO。
- **防提权成体系**：角色白名单排除 TEACHER、不能授予/删除高于自己级别的角色（唯 resetPassword 漏网，见 A1）。
- **文件路径防护到位**：normalize+startsWith 穿越校验、UUID 存储名、危险扩展名黑名单、transferTo 流式落盘。
- **权限种子三源一致**：V7/V14/restoreDefault 逐码一致，「恢复默认」不会产生漂移（本次审计顺带验证了上周 RBAC 工作的正确性）。
- **迁移卫生良好**：src 与 target 的 14 个 SQL 逐字节一致，41 个实体除缺失两表外逐列吻合。
- **前端契约对齐度高**：PageResult/member VO/四组枚举逐一核对无偏差；23 处 rowKey 全部业务 id；资源清理到位。
- **富文本入口统一 DOMPurify 净化且有针对性单测**；外链全带 rel="noreferrer"；401 统一拦截处理。

---

## 六、修复优先级建议

**第一批（安全止血，半天）**：A1 resetPassword 级别校验 · A2+E3 素拓前后端门控 · A3 七控制器写接口补 @PreAuthorize(LEADER 或权限码) · A4 生产改种子口令 · 移除根目录两个数据泄露文件
**第二批（数据正确性，半天）**：C1 V15 补缺失两表 · C2/C3 软删唯一键改造（deleted_at 方案）· B1 签到事务
**第三批（体验回归，2 小时）**：E1 persist migrate（或 /auth/me 启动刷新，顺带修 M5）· E2 党务台账菜单口径
**第四批（性能，随迭代）**：D1/D2/D3 按文内方案改数据库端聚合
**第五批（加固，随迭代）**：M2/M3/M4/M10 + P3 补测试断言 + P1 密钥轮换
