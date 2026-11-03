# Concerto AI 服务设置实施记录

日期：2026-10-31。入口：`/admin/settings?section=ai`。

## 实现范围

- 移除 DeepSeekProvider 及其专用请求、推理字段解析／回传逻辑。
- Chat Completions 与 Responses 分别使用原生请求体、工具调用结构和 SSE 事件；不做协议互转或厂商字段映射。
- Responses 使用 `store:false`、原生 output items 和加密 reasoning 状态延续工具循环；展示的推理摘要与不透明状态分离。推理摘要可选，未返回摘要不视为失败。
- 当前组织管理员管理多份服务配置；支持连接测试、设为默认、恢复部署配置、编辑、删除和版本冲突处理。
- API Key 使用 AES-256-GCM 加密，读取接口只返回 hasApiKey，编辑留空保留。测试凭据绑定组织、配置版本及连接字段，有效期 30 分钟。
- 普通响应、SSE、虚拟工具调用及工具结果回传全部通过才允许启用。测试内容固定，不发送组织业务资料。
- 默认服务修改先验证再原子更新；每个任务固定配置，主对话、知识库、摘要及协作任务共用快照，结束后释放。
- 管理操作保存审计，任务保存实际使用的配置 ID、版本、协议和请求模型，供排查；不保存密钥副本。
- Concerto 输入框读取 `/api/agent/model-status` 展示当前组织已启用的服务，进入页面、切换会话、任务开始／结束及窗口重新获得焦点时刷新。该接口向本组织已登录成员提供最小配置标识，不返回端点和密钥，不表示实时健康检查通过。
- 历史消息的规则模式标记只描述该条答复的执行方式，不再覆盖输入框的当前模型标签；状态接口异常显示「服务状态未确认」，不擅自判定当前服务为规则模式。
- 端点默认只允许 HTTPS 和公网目标，内网地址通过部署白名单开放。HTTP 连接池的 DNS resolver 校验实际连接地址，禁用跳转及传输层自动重试；复用既有应用重试和 Stub 降级机制。

## 部署配置

更新并重启后端后，数据库迁移 V59、V60 由 Flyway 自动执行。默认无需手动配置加密环境变量，首次使用自动初始化密钥存储。

| 变量 | 用途 |
| --- | --- |
| `PAMS_AI_ENCRYPTION_KEY` | 可选显式主密钥；留空时自动生成。已有显式密钥的部署保持原值，所有实例必须一致 |
| `PAMS_AI_KEY_FILE` | 可选自动密钥文件位置，默认 `./data/secrets/ai-provider.key`；Docker 中使用独立持久化卷 |
| `PAMS_AI_INTERNAL_HOSTS` | 可选，逗号分隔的内网模型主机白名单，精确匹配；默认空 |
| `PAMS_AGENT_PROTOCOL` | 部署默认协议：`openai_chat_completions` 或 `openai_responses` |
| `PAMS_AGENT_BASE_URL` | 部署默认 API 根地址，默认 `https://api.openai.com/v1` |
| `PAMS_AGENT_MODEL` | 部署默认请求模型 ID，需显式填写 |
| `PAMS_AGENT_API_KEY` | 部署默认 API Key |
| `PAMS_AGENT_SUMMARY_ENABLED` | 部署默认 Responses 是否请求摘要，默认 false |

自动生成的主密钥采用私有文件权限、文件锁和原子写入，重启时复用。Docker 使用 `pams-ai-secrets` 数据卷，容器重建不会重置密钥。备份数据库时同时保留该文件或数据卷，多实例共享同一份密钥文件或继续使用已有显式主密钥。源码 Git 与 Docker 构建上下文均排除自动生成的密钥文件。

主密钥不需要用户填写，管理员直接新增服务、填写模型信息、测试并保存即可。如果持久化目录不可写、密钥文件损坏或与数据库指纹不匹配，仍允许打开表单，测试／保存时提示存储异常并保留填写内容。已有密文或初始化指纹后，不会因文件丢失而自动生成另一把密钥；恢复原文件后可直接重试，避免破坏已有配置。

原有显式密钥通过解密验证后登记指纹，保持向后兼容；已有密文但没有原始密钥时不支持自动恢复。部署默认服务仍可使用；未同时配置部署模型和密钥时使用规则模式。原 DeepSeek 专用配置不再自动识别，需要管理员显式配置符合原生协议的服务。

Docker Compose 已透传上述变量，`.env.example` 已补充说明。组织配置保存和切换无需重启；部署环境变量变更需按原部署方式重启加载。

## UI 验证

复用现有 `Settings` 分类导航、`Panel`、`InfoList`、`StatusTag`、`FormSurface` 抽屉以及系统主题 token，没有新增独立设计系统。

- 浏览器测试通过新增、协议选择、测试请求、修改后测试凭据失效、编辑不回填密钥和关闭未保存表单。
- 1440px 和 390px 分别核对浅色／深色设置页与编辑抽屉，共 8 张 PNG；断言页面没有横向溢出。
- 对保存的设置页 DOM 快照执行 app-screen inspector，覆盖 1440／768／390px。放行 `placeholder-data`：example.com 是明确的测试端点，避免虚构真实供应商配置；放行 `no-title`：保留系统共享 PageHeader 的标题结构，不单独改造全站标题。已有系统字号和导航高度的提示不作为本功能改造范围。
- Ant Design lint 的三条 Alert.message 提示针对较新版本；仓库安装的是 5.29.3，沿用该版本受支持的 message 属性与现有系统组件写法。

截图：`settings-{light|dark}-{1440|390}.png`、`editor-{light|dark}-{1440|390}.png`。DOM 快照和检查报告位于同目录及 `inspection/`。

## 验证命令与边界

后端已通过新增协议、配置／加密、权限、测试凭据绑定、配置快照及既有取消、上下文预算、编排、协作回归用例。Flyway V59／V60 在 H2 MySQL 模式下验证；自动密钥新增验证首次生成、重启复用、并发初始化、文件丢失／损坏保护、显式密钥兼容、数据库绑定和存储失败后的恢复。

前端构建、TypeScript 检查、58 项推理展示／任务状态单元测试和 9 项浏览器测试通过。新增回归覆盖加密未初始化、配置接口失败时的表单入口及填写内容保留，以及当前模型配置与旧规则答复分开展示。

```bash
mvn -q -f pams-backend/pom.xml -Dtest=ProviderSecretsTest,OpenAiNativeProtocolsTest,AgentProviderServiceTest,AgentProviderPermissionTest,LlmCancellationTest,LlmConfigMissingKeyTest,ContextBudgetTest,EnvFileImportConfigTest,AgentOrchestratorTurnSensitivityTest,SpecialistLoopTest,SpecialistPipelineTest,SpecialistExecutorContractTest test
cd pams-web
npm run build
npm test -- src/components/agent/LingsiTimelineRow.test.tsx src/utils/thinking.test.ts src/stores/agent.lifecycle.test.ts
npx playwright test e2e/ai-services.spec.ts
```

模型请求使用本地模拟服务验证，未使用真实商业 API Key 进行付费调用，也未部署到生产。真实服务需在系统设置中完成连接测试；推理摘要取决于所选服务与模型能力。Responses 本期不包含内置搜索、computer use、音视频等额外工具。
