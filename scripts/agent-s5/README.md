# Concerto Agent S5 隔离验收

普通 `mvn test` 保持离线，S5 外部验收默认跳过。不要将生产业务库的连接交给本工具。

## 环境

- Java 21、Maven、Node.js、`pams-web` 的 npm 依赖；浏览器验收需要 Playwright 浏览器（Windows 使用 Edge）。
- 新建独立 MySQL 8.0 或 PostgreSQL 18 库，名称为 `pams_s5_...`。测试账号仅拥有该库的权限。通过本机端口或 SSH 隧道连接。PostgreSQL 先执行 `empty-seed` 迁移，并使用运行账号。 [PostgreSQL 迁移指引](../postgresql/README.md)
- 设置 `PAMS_S5_DB_URL`、`PAMS_S5_DB_USER`、`PAMS_S5_DB_PASSWORD`。PostgreSQL 示例为 `jdbc:postgresql://127.0.0.1:5432/pams_s5_example?currentSchema=pams&stringtype=unspecified`。凭据通过进程环境注入。
- 真实模型至少需要同时配置 `PAMS_AGENT_API_KEY` 与 `PAMS_AGENT_MODEL`，并按服务设置 `PAMS_AGENT_PROTOCOL`、`PAMS_AGENT_BASE_URL`。可使用进程环境或本地 `.env`。模型验收使用合成业务数据。

库名与回环地址在 Spring 建立连接前校验。数据为测试 profile 的合成账号和专用场景，不导入本地或线上的业务备份。测试保留结果便于检查；重复执行会追加唯一名称的数据，推荐为每轮新建隔离库。

## 执行

Windows 在 PowerShell 中从仓库根目录运行启动器。启动器通过 `C:\dev\Git\bin\bash.exe` 执行 Maven，Java 和 Maven 需能从 Git Bash 的 PATH 找到。

```powershell
# 21 项确定性能力 + 真实 JVM 终止恢复，无付费模型调用
./scripts/agent-s5/run-acceptance.ps1

# 加真实模型与真实浏览器（明确启用模型调用）
./scripts/agent-s5/run-acceptance.ps1 -Live -Browser
```

Linux 手动运行时设置同样的数据库变量及 `PAMS_S5_ACCEPTANCE=true`。真实模型模式另设 `PAMS_S5_LIVE=true` 和 `PAMS_TEST_AGENT_PROVIDER=openai`，浏览器验收再设 `PAMS_S5_BROWSER=true`。协议与目标服务由 `PAMS_AGENT_PROTOCOL` 和 `PAMS_AGENT_BASE_URL` 决定。

```bash
mvn -q -pl pams-backend -am \
  -Dtest=S5AcceptanceTest,S5ProcessRecoveryTest,S5BrowserIntegrationTest \
  -Dsurefire.runOrder=alphabetical -Dsurefire.failIfNoSpecifiedTests=false test
```

[GitHub Actions 工作流](../../.github/workflows/agent-s5-acceptance.yml)的 live 分支当前只注入模型密钥。使用该分支验收真实模型前，还需向运行环境提供模型 ID 和对应的服务配置。

输出在 `docs/audits/lingsi-upgrade-implementation/s5-*`，截图在 `docs/ui-v6-lingsi-implementation/s5-acceptance/`。只把这些脱敏的合成数据证据归档；不要归档数据库密码文件、`.env`、生产 SQL 或本地工具工作区。

## 验收口径

- S5 的历史 21 工具矩阵使用真实注册表和业务服务；10 个写工具经过专家结果处理、预览门禁、确认、真实业务落库与重复确认。另验证无权限、只读、适用的实体歧义、空组织读取。
- 真实模型测试验证自然语言草稿生成、确认后真实新增、重复提交，以及独立审查定位人为植入的日期/地点错误。
- 对照使用相同 DAG 内核、任务、权限与调用/输出预算。只把通才与专家分工作为变量；P1、P2 分组统计，不合并不同模式。交替先运行的分组以减少固定缓存顺序的影响，不能据此声称已消除缓存影响。
- 对照是只读/预览质量评估，自动取消未提交清单；不自动确认业务写入。业务完成率不得用 Run SUCCESS 替代。实际提交的验收单独保存业务行数证据。
- 默认每个对照样本最多 8 次模型尝试、每次至多 2048 输出 token（由验收配置设定）；重试也占调用预算。输入 token 由供应商报告，未知价格、失败或中断后的未知用量不计为零费用。未知总成本不提供虚构估计。
- JVM 恢复测试实际创建、强制终止并重启独立 Java 进程，覆盖未确认清单，以及“业务已提交、Agent 回执未写入”的故障点。结果待核对的动作不能自动重放。
- 浏览器测试绑定独立进程的 PID/数据库就绪标记，使用真实 HTTP、WebSocket、模型和数据库；测试服务端口 18080、前端 5185，拒绝复用其他服务。
- 小样本用于建立基线和发现缺陷，不支持泛化的完成率或性能提升结论。

## 灰度和回退

`pams.agent.benchmark-enabled` 默认 `false`；启用时还必须设置 `pams.agent.benchmark-org-ids`。管理页面只采集预览，不提供业务自动确认。多样本采样可能持续数分钟，前端和 Nginx 对该接口单独等待 30 分钟；离开页面不会取消服务器上的采样，成功结束后可刷新最近记录。

`pams.agent.collaboration-enabled=false` 将新请求路由到单 Agent 稳定路径；`pams.agent.collaboration-org-ids` 非空时仅对列出的组织开放协作。空集合保持原有范围，不能把空集合当作关闭开关。配置变更需按当前部署方式重启应用。

回退只改变新请求路由，不删除 Run、待确认清单和版本记录；已有清单继续保留查询、取消和人工核对入口。V46 是评估能力引入时的迁移。当前版本应按工作区完整的 Flyway 迁移链验证。生产发布前保留数据库与镜像备份，不能因代码回退直接删除 V43–V46 的状态数据。
