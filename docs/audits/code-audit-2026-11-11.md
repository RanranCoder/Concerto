# PAMS（协奏）代码审计报告

审计日期为 2026-11-11，基线为 `Concerto` 分支的 `2bec9ee51730c4da0be9d04866291223e87b6ff7`。本轮按业务影响抽查了活动子资源与租户隔离、JWT 与实时连接、文件访问、Concerto Agent 持久任务恢复、前端测试与镜像发布流程。源码发现均为静态审查结论。验证环境及测试结果见文末。

## 审计发现

| 编号 | 优先级 | 问题 | 影响 |
| --- | --- | --- | --- |
| PAMS-01 | P1 | WebSocket 未检查令牌吊销水位 | 登出或改密后，已吊销的 JWT 仍可在有效期内建立实时连接 |
| PAMS-02 | P1 | 活动子资源创建缺少父活动归属校验 | 有相应管理权限的用户可写入租户归属与父活动不一致的行 |
| PAMS-03 | P2 | 评分服务缺少服务端分值校验 | 错误维度或越界分值可进入总分，畸形输入可被记录为零分 |
| PAMS-04 | P2 | Concerto Agent 恢复逻辑将进程身份用于判断存活 | 共享数据库的多后端实例可能误判仍在执行的任务 |
| PAMS-05 | P2 | 镜像发布流程缺少测试门禁 | 当前测试失败仍可通过相应构建步骤 |
| PAMS-06 | P3 | Concerto Agent 样式违反现有字号刻度约束 | 前端测试稳定出现一项失败 |

P1 优先处理认证与租户边界。P2 涉及数据正确性、条件性运行风险或发布保障。P3 为影响局部的工程回归。

### PAMS-01 · WebSocket 未检查令牌吊销水位

HTTP 认证会比较 JWT 的签发时间和用户的 `tokenInvalidBefore`，登出会推进该水位。通知 SSE 也会在发送前检查有效期、账号和组织状态以及吊销水位。STOMP 拦截器只在 `CONNECT` 解析 JWT 和检查用户状态，后续帧直接放行。此前已签发且未过期的 JWT 因此仍可通过 WebSocket 认证，既有连接也缺少同等的重新验证。Concerto Agent 通过该通道向用户推送会话和任务事件。 [HTTP 吊销检查](../../pams-backend/src/main/java/com/pams/security/JwtAuthenticationFilter.java#L45) · [登出](../../pams-backend/src/main/java/com/pams/controller/AuthController.java#L157) · [SSE 校验](../../pams-backend/src/main/java/com/pams/module/notification/service/NotificationStreamRegistry.java#L41) · [STOMP 拦截器](../../pams-backend/src/main/java/com/pams/security/WebSocketAuthInterceptor.java#L33) · [Concerto Agent 事件发布](../../pams-backend/src/main/java/com/pams/module/agent/stream/AgentEventPublisher.java#L157)

**修复与验收。** 复用 HTTP 的账号、组织和 JWT 吊销校验，并明确连接过期和吊销后的失效机制。验收应覆盖登出或改密后的重连、已打开连接停止接收后续消息、组织停用，以及重新登录后可建立连接。

### PAMS-02 · 活动子资源创建缺少父活动归属校验

议程、旧座位表、评分规则和评分记录的创建代码直接采用请求中的 `activityId`。子表的 `@TenantId` 约束子记录归属，数据库外键只引用父活动主键。两者结合仍允许子记录组织与父活动组织不同。相关入口也没有检查父活动是否已软删除。此类记录可能只出现在写入者所在组织的独立子资源查询中，形成归属不一致、正常业务视图难以发现的脏数据。该结论针对创建链路的父子归属校验。 [议程创建](../../pams-backend/src/main/java/com/pams/module/activity/controller/AgendaController.java#L27) · [座位创建](../../pams-backend/src/main/java/com/pams/module/activity/service/SeatService.java#L29) · [评分创建](../../pams-backend/src/main/java/com/pams/module/activity/service/ScoreService.java#L56) · [子资源外键](../../pams-backend/src/main/resources/db/migration/V1__init.sql#L78) · [租户字段](../../pams-backend/src/main/resources/db/migration/V31__tenant_org_columns.sql#L19)

**修复与验收。** 在写事务中按当前组织加载可用的父活动，再从父活动设置子记录归属。评估为相关表增加组合外键 `(org_id, activity_id)`，迁移前核查已有的父子归属不一致数据。验收覆盖本组织活动、其他组织活动、已删除活动，并确认被拒绝的请求没有落库。

### PAMS-03 · 评分服务缺少服务端分值校验

前端按评分规则检查分值范围。后端 DTO 将维度分数接收为普通字符串，服务端直接对 JSON 子节点调用 `asInt()` 并累加，解析异常返回零。创建和更新均未按活动加载评分规则验证维度归属、分值类型和满分。评分结果可能包含未知维度、越界值或被静默转为零分的输入。 [前端分值校验](../../pams-web/src/pages/activity/ScorePanel.tsx#L165) · [后端请求字段](../../pams-backend/src/main/java/com/pams/module/activity/dto/ScoreRecordRequest.java#L14) · [服务端求和](../../pams-backend/src/main/java/com/pams/module/activity/service/ScoreService.java#L27)

**修复与验收。** 按记录所属活动加载评分规则，逐项验证维度、数值类型和允许范围，校验通过后计算总分。验收覆盖合法边界值、畸形 JSON、未知维度、负分和超过满分，并确认失败更新保留原记录。

### PAMS-04 · Concerto Agent 多实例恢复存在存活误判

恢复扫描将执行所有者与本进程 ID 不同的 `RUNNING` 动作识别为未知结果。工作项状态代码也采用相同判断。另一个仍健康的实例可以持有该动作，因此同库运行时，滚动替换或横向扩容都可能导致待核对状态提前出现。仓库的 Compose 默认配置仅定义一个后端服务实例，本项影响取决于实际部署拓扑。 [恢复扫描](../../pams-backend/src/main/java/com/pams/module/agent/stream/AgentRunOutbox.java#L46) · [工作项状态判断](../../pams-backend/src/main/java/com/pams/module/agent/orchestration/WorkItemState.java#L449) · [Compose 配置](../../docker-compose.yml#L26)

**修复与验收。** 如需支持多个后端实例，以共享存储中的可续期执行租约判断所有者是否失效，用执行令牌保护状态提交。验收需覆盖两个健康实例共用数据库、其中一个实例失联后的接管，以及旧所有者晚到的完成回执。

### PAMS-05 · 镜像发布流程缺少测试门禁

镜像工作流直接构建并推送后端和前端。后端 Dockerfile 显式跳过测试，前端 Dockerfile 只运行构建，S5 专项工作流由手动触发。本轮前端生产构建成功，同时单元测试失败，说明当前构建检查无法覆盖该类回归。 [镜像发布工作流](../../.github/workflows/build-push-images.yml#L13) · [后端 Dockerfile](../../pams-backend/Dockerfile#L7) · [前端 Dockerfile](../../pams-web/Dockerfile#L9) · [S5 工作流](../../.github/workflows/agent-s5-acceptance.yml#L3)

**修复与验收。** 把前后端常规测试设为镜像推送的依赖。用一个预期失败的测试验证推送任务被阻止，再确认测试通过时发布可继续。

### PAMS-06 · Concerto Agent 样式违反字号刻度约束

协作运行卡片及相关样式使用了五处硬编码字号，其中四处为 `12px`，一处为 `14px`。现有 `tokens.test.ts` 的 CSS 字号断言报告了这五处。 [样式位置](../../pams-web/src/styles/lingsi-v6.css#L199) · [字号断言](../../pams-web/src/styles/tokens.test.ts#L243)

**修复与验收。** 使用对应字号变量，确认运行卡片层级与设计意图一致，复跑前端测试。

## 验证记录与边界

| 检查 | 本轮结果 |
| --- | --- |
| 前端测试 `npm test` | 36 个测试文件，235 项中 234 通过、1 失败，见 PAMS-06 |
| 前端构建 `npm run build` | 通过。Vite 报告大分包警告，DOCX 引擎产物约 11.62 MB，gzip 后约 3.40 MB。本轮未测量页面加载耗时 |
| 后端测试 `mvn -o -pl pams-backend test -B`，通过 `-DargLine` 显式加载 Mockito agent | 全量运行共 964 项，936 通过、20 跳过、8 项因沙箱限制本地回环连接报错。允许本地回环后，定向重跑受影响的 3 个测试类，9 项全部通过 |

本轮使用 Windows 本地环境、Java 21 和 Node.js 26.12.0。Git Bash 在受限环境下启动失败，命令改由 PowerShell 执行。覆盖范围限于本地仓库与本地验证，未执行生产验证、依赖漏洞库扫描、MySQL 专项验收、浏览器端到端验收或真实模型调用。报告之外未修改业务源码。

## 2026-11-12 修复记录

| 编号 | 修复结果 |
| --- | --- |
| PAMS-01 | STOMP 建连和出站消息检查令牌有效期、吊销水位及用户和组织状态。应用订阅限定在当前用户队列 |
| PAMS-02 | 议程、座位和评分创建在写事务内锁定并校验父活动，从父活动继承组织归属。旧数据清理和数据库组合外键留待独立迁移评估 |
| PAMS-03 | 评分写入校验活动维度、整数分值、上下限和总分范围，拒绝畸形 JSON 与重复维度 |
| PAMS-04 | Flyway V71 增加共享实例租约。恢复按租约过期判断，协作调度沿用令牌校验，动作完成仅更新仍由该实例执行的记录。无心跳记录的旧任务使用 30 分钟超时 |
| PAMS-05 | 镜像构建推送依赖前后端常规测试通过 |
| PAMS-06 | 五处字号改用与原尺寸一致的设计变量 |

后端全量回归共 974 项，954 通过、20 跳过。前端 235 项全部通过，生产构建成功。租约有效和过期分支在 H2 集成测试中通过，MySQL 双 JVM 验收与 GitHub Actions 实际发布尚未运行。
