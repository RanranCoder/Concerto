# 消息通知管线 v1：上线验收演练记录

日期：2026-10-29

状态：真实 MySQL、Nginx、浏览器及服务器存量快照演练已通过；尚未切换生产服务。用户已明确选择现有内网 8081 作为验收入口，公网/CDN 不计入本批范围。

## 本批发现并修复的问题

1. MySQL 8 中旧表与新增授权表可能分别使用 `utf8mb4_unicode_ci`、`utf8mb4_0900_ai_ci`，历史通知角色比较、摘要偏好比较、授权引导模板比较会报排序规则冲突。修复为双方显式字符转换，保持完整角色码，历史迁移用前缀不同的角色验证不会扩大受众。
2. 开户凭据查询涉及密码哈希跨表比较，改用双方 `BINARY(255)` 比较，避免排序规则冲突并保持字节大小写敏感。
3. Connector/J 对 DATETIME 的 `getObject` 返回 `LocalDateTime`，H2 则可能返回 `Timestamp`。新增 `JdbcDateTimes` 转换，修复维护订阅、组织策略、开户有效期、授权有效期及 SSE 令牌失效水位读取。

生产只读预检确认其版本仍为 V45，尚未执行 V50。本批因此修订未在该生产库应用的 V50 历史回填语句；没有对生产执行 Flyway repair，也没有修改其历史校验和。已应用旧版 V50 的其他环境不在本次演练目标内。

## 隔离验证结果

| 项目 | 结果 |
| --- | --- |
| MySQL 8.0.46 回归 | 64 项通过，使用真实 Connector/J 与 MySQL 专用迁移目录 |
| H2 兼容回归 | 79 项通过，覆盖通知、策略、维护、开户、治理及 SSE |
| V49 → V56 历史样本 | 个人已读、共享归档、原消息 ID 保留；角色前缀相似者不继承受众 |
| 真实 Nginx + HTTP | 10 项通过，无 HTTP mock |
| 真实浏览器 | 1 项通过，从业务发布、Outbox、SSE 到待办及公告独立确认 |
| 后端候选包 | 打包成功，隔离目标保护测试通过 |

HTTP 验证包含：Bearer 必需且 URL token 无效、无缓冲首帧、心跳、小包事件、个人收件箱隔离、业务确认、断线期间 REST 对账、实时业务撤权、停用账号关闭 SSE/REST 拒绝、后端重启后的持久 Outbox 补偿、过期租约恢复不重复入箱。

浏览器直接访问隔离 Nginx，使用合成验收账号，不 mock API。确认实际浏览器发出 Bearer SSE 请求、消息实时进入页面、确认待办可办理且完成后收敛。[浏览器证据](notification-acceptance/real-mysql-nginx-confirmation.png)。

本机使用独立 Docker 项目 `pams-notify-acceptance`、数据库 `notification_acceptance` / `notification_upgrade_acceptance` 和环回端口 13316/18085/18086。验收结束后已停止这些容器，保留隔离数据卷。没有修改开发者常用数据库。

## 服务器存量快照演练

通过受信任主机密钥连接既有部署目标，只读采集运行镜像、MySQL 版本和迁移版本。在服务器本地生成受限权限快照，恢复到无发布端口、内部网络的独立 MySQL 容器；生产原始数据没有下载到本机或提交到仓库。

第一次升级被既有权限迁移保护门正确拒绝，提示必须先执行 `PermissionMigrationCli report/approve`。确认单无源数据问题。用户随后在本会话批准权限映射，确认管理角色保留候选考勤职责、干事撤销。

按批准的源指纹从原快照重新恢复干净隔离库，生成一致报告、仅在隔离库记录批准，再执行 V45 → V56。结果：

- Flyway 升级和校验通过，存量账号数、原通知事实数均保持不变。
- 管理角色保留已确认的考勤权限，干事的组织级考勤查看/管理权限为零。
- 隔离批准状态被迁移消费为 APPLIED；未在生产库写入批准、执行升级或切换镜像。
- 生产镜像仍为原版本，迁移版本仍为 V45。

完整权限差异、源指纹、批准决策及备份只保存在受限演练目录；本机的确认单位于未提交的 `.backups/notification-acceptance/owner-review.md`。

## 候选包与后续切换

候选后端 JAR SHA-256：`0674598af7321d51703ea5b9a6f7bfd8b4f2d05c4d7117ecb84c168457059976`。

`scripts/notification-acceptance/package.mjs` 将已构建 JAR、前端资源、Nginx 配置和文件哈希组成候选包，排除环境凭据、数据库快照和权限确认单。产物及日志位于未提交目录 `.backups/notification-acceptance/`。脚本说明见[验收工具](../../scripts/notification-acceptance/README.md)。

生产切换仍需明确维护窗口：

1. 暂停业务写入，生成新的完整备份和授权报告，检查其与已确认决策是否一致；源状态变化时不得复用过期批准。
2. 按已确认的映射与考勤决策登记正式批准，并再次从最新备份完成隔离预演。
3. 保留旧镜像、原环境及备份，部署校验过的后端和包含 SSE 专用 location 的前端，保持既有 8081 端口、数据卷与运行凭据。
4. 在授权的验收账号/资源范围内检查内网登录、SSE、重连、权限、待办和指标，再恢复业务写入。
5. 如失败，在停写状态恢复匹配的数据库备份和旧镜像；不只回退应用而保留不兼容的新授权模式。

这份记录不把隔离演练当作生产上线完成。外部渠道、多实例共享唤醒等此前暂缓的范围没有在本批引入。

## 日志

本机：`.backups/notification-acceptance/mysql-tests.log`、`h2-regression.log`、`http-report.json`、`browser.log`，以及 `.backups/notification-approved-rehearsal.log`、`notification-remote-final-state.log`。日志、测试凭据、快照和正式授权资料不进入版本控制。
