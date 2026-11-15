# Concerto Web 技术概要

> Concerto Web 是协奏的前端工程（`pams-web/`），React 18 单页应用，承载活动、排班考勤、党务、成员、宣传、档案、知识库、权限治理与灵思（Agent）工作台等全部界面。本文概要其技术选型、工程结构、权限体系、实时流式与状态管理设计。

---

## 1. 技术栈总览

| 类别 | 选型 | 版本 |
|---|---|---|
| 框架 | React + react-dom | 18.3.1 |
| 路由 | react-router-dom（`createBrowserRouter`） | 6.28.0 |
| UI | Ant Design + icons | 5.24.0 |
| 状态 | Zustand | 5.0.3 |
| HTTP | axios | 1.7.9 |
| 实时 | @stomp/stompjs + sockjs-client（Agent 流）/ @microsoft/fetch-event-source（通知流 SSE） | 7.3 / 1.6 / 2.0.1 |
| 构建 | Vite + @vitejs/plugin-react | 7.0.0 |
| 语言 | TypeScript（strict、`verbatimModuleSyntax`、ES2022） | 5.7.2 |
| 文档/表格 | superdoc 2.24、docx 9.7、docx-preview、xlsx、revogrid | — |
| 测试 | vitest 3 + jsdom + @testing-library/react；Playwright 1.63（25 spec E2E） | — |

构建命令 `build: tsc -b && vite build`。**注意：根 tsconfig 是 solution-style**（`files: []` + references 指向 `tsconfig.app.json` / `tsconfig.node.json`），做类型检查必须用 `tsc -b`，直接 `tsc --noEmit` 会空转零输出。

---

## 2. 工程结构

```
src/
├── api/        # 40 个按业务域拆分的请求模块（agent/party/dept/knowledge…）
│               # + http.ts（axios 封装）+ types.ts
├── components/ # 业务组件按域分目录：admin/agent/auth/credit/file/form/gantt/
│               # glass/notification/records/routine/seat/signin/word
├── hooks/      # useWebSocket / useNotificationStream / useAuthorizationSync /
│               # useMediaQuery / useStreamReveal / useActivityTypes
├── layouts/    # MainLayout.tsx（Sider 216px + Header + Content + 移动端 Drawer）
├── pages/      # 页面按模块分目录 + 根级 Login/Dashboard/Forbidden/Building/ActivateAccount
├── router/     # index.tsx 全部路由定义
├── stores/     # 7 个 zustand store
├── styles/     # tokens.css / global.css / lingsi*.css + tokens.ts/theme.ts/color.ts
└── utils/      # 纯函数工具（markdown/thinking/citationSnippet/orgTree/agentLinks/
                # artifactDiff/sanitizeHtml…），多数带同名 .test.ts
```

---

## 3. 路由与布局

- `router/index.tsx`：全部页面 `lazy()` + Suspense 按需加载。
- 公开路由：`/login`、`/activate-account`、`/register`、`/signin/:token`（免登录扫码签到）。
- 受保护路由统一挂在 `RequireAuth > MainLayout` 下，按权限码用守卫组件包裹；兜底 `*` → `/`。
- `MainLayout`：antd Layout，Sider 216px（移动端 Drawer）、面包屑、主题切换、通知铃、修改密码弹窗、页面切换动画（key=pathname）。
- 菜单过滤：`menuItems` useMemo 逐项 `hasPerm(code)` 过滤，党务/团务/志愿还需 `isPackEnabled(packs, code)`（领域包开关），再按「日常工作 / 组织与资料 / 智能协作 / 系统管理」四组渲染。

---

## 4. 权限体系（前端侧）

实现位于 `src/components/glass/`：

| 组件/钩子 | 用法 |
|---|---|
| `RequirePerm({codes})` | `user.permissions` 含任一 code 即通过，否则跳 `/403` |
| `RequireRole({roles})` | 按 `user.roleCode` 白名单；导出 `LEADER_ROLES`（六部长及以上）、`ADMIN_ROLES`（TEACHER/DIRECTOR） |
| `useHasPerm()` | 返回判定函数，供菜单/按钮显隐 |
| `PackRoute` | 领域包启用判定 + `RequirePerm(['{code}:view'])` |

前端在用的权限码（节选）：`activity:view/edit`、`task:view`、`schedule:view/self:view/manage/free_table`、`attendance:view/self:view`、`member:view`、`party:view`、`youth/view`、`volunteer:view`、`content:view`、`material:view`、`notice:view`、`kb:read`、`org:manage`、`user:view`、`role:view`、`checkin:manage`、`plan:edit` 等。

**设计要点：`/agent` 路由与菜单对所有登录用户开放，前端没有 `agent:run` 权限码**——灵思的权限控制在工具层：每个工具声明 `requiredPermissions`，`GET /api/agent/tools` 只返回当前用户可见工具。这是"权限即上下文"在 UI 上的体现。

> 治理口径：产品可见性要同时核验前端守卫（RequirePerm/RequireRole）、菜单过滤、后端 `@PreAuthorize` 三处一致，不能只凭其中一处判断。

---

## 5. 状态管理（zustand stores）

| store | 职责 |
|---|---|
| `auth.ts` | token + user，persist 到 localStorage（key `pams_token`），带 migrate 补 permissions |
| `org.ts` | 当前组织：orgName、primaryColor（theme_json）、enabledPacks、terminology |
| `agent.ts` | 灵思核心（约 1440 行）：会话 / outcomes / timeline / 流事件 / 确认卡 / 表单 / 断线对账 |
| `agentPanel.ts` | 灵思右侧实体面板（分屏 tabs：artifact/text/version） |
| `notification.ts` | 通知列表与未读数，订阅 authStore 变化自动 reset |
| `theme.ts` | 明暗主题，persist（key `pams_theme`），写 `document.documentElement.dataset.theme` |
| `preferences.ts` | 列表密度偏好（key `concerto_preferences`） |

**跨标签页一致性不靠本地广播**（项目无 EventBus/BroadcastChannel）：两个标签页通过 WS 的 `AGENT_TASK_DONE` 事件（带 conversationId）感知彼此的确认操作，前端按 conversationId 过滤避免串会话。

---

## 6. API 层（api/http.ts）

- axios 实例：`baseURL '/api'`、timeout 30s。
- 请求拦截器：从 `useAuthStore` 取 token 注入 `Authorization: Bearer`。
- 响应拦截器按后端契约解包 `{code, message, data}`：`code===200` 直接返回 `body.data`，否则抛自定义 `ApiError(code, msg)`；`responseType==='blob'` 跳过解包。
- **401 且非登录请求**：自动 logout 并跳 `/login`。
- 错误提示经 `config.silentError` 开关控制（axios 类型扩展），提示统一走 `@/utils/feedback` 的 FeedbackBridge。
- 导出泛型辅助 `get/post/put/del`，各业务模块（agent.ts、party.ts 等 40 个）按域调用。

---

## 7. 灵思前端（Agent 工作台）

### 7.1 通道架构：REST 下单 + WS 过程事件 + REST 补拉对账

```
发起      agentChat() → POST /agent/chat（requestId、mode、refs、collab、background、timezone）
          → 返回 streamId（AgentChatAccepted）或同步结果
实时      useWebSocket：@stomp/stompjs + SockJS(/ws)，订阅三个队列
          /user/queue/agent/stream → AgentStreamEvent → useAgentStore.applyStreamEvent
          /user/queue/agent        → AGENT_TASK_DONE / AGENT_RUN_UPDATED
          /user/queue/authorization→ 权限/组织刷新（跨标签同步）
对账      GET /agent/streams/{streamId}?since={seq}（增量补拉）
          GET /agent/requests/{id}（快照），reconcileConnection() 每 2–3 秒驱动
中止      POST /agent/chat/{streamId}/abort
```

### 7.2 流事件重组（stores/agent.ts 的核心算法）

- `AgentStreamEvent = {streamId, seq, type, payload, ts}`，共 15 种 type。
- **MESSAGE_DELTA 按 round 重组**：状态字段 `deltaRound`，round 或 streamId 变化时 `flushStreamDeltas()` + 清空 reply + 清 `deltaParts`（覆盖工具轮/重试/降级三类场景）；同 round 内按 `seq` 排序拼接。
- **性能优化**：`deltaBuffer` + `requestAnimationFrame`，每帧最多一次 setState（`flushStreamDeltas`，agent.ts:614–652）；收官事件（MESSAGE_DONE/ERROR/ABORTED）先同步 flush。
- **seq 去重与乱序**：`receivedSeqs` / `streamCursor`（applyStreamEvent，agent.ts:1020–1036）。
- `THINKING_DELTA` 渲染为思维链（utils/thinking.ts）。

### 7.3 人机协同卡片

| 卡片 | 触发事件 | 组件 | 行为 |
|---|---|---|---|
| 确认卡 | PLAN_READY | `LingsiActionCard.tsx` | 展示 dry-run 影响清单（skipped 必现、不可逆警示），确认走 `confirmAgentTask(planId, confirmToken, mode)`，成功后原位变结果块 |
| 表单卡 | INPUT_NEEDED | `LingsiFormCard.tsx` | `submitTaskInput` 提交，三态流转：PLAN（原位变确认卡）/ FORM（继续补全）/ CANCELLED；分组补全用 `LingsiPendingForms.tsx` + `utils/pendingForms.ts` |
| 结果块 | 任务收口 | `AgentResultNotice` | success/failed/partial/info 四态 + DeltaChip 变更量（白名单 DELTA_RULES 映射） |

### 7.4 artifacts 与引用

- **artifacts**（产物文档）：`LingsiArtifactCard / LingsiArtifactPreview` + diff 工具 `utils/artifactDiff.ts`；REST `/agent/artifacts` 列表/详情/编辑；右栏实体面板由 `agentPanel.ts` 管理（artifact/text/version 三 tab）。
- **references**（过程引用，可跳业务页）：`LingsiRefs.tsx`，链接经 `utils/agentLinks.ts` 按权限码/领域包过滤。
- **citations**（知识库依据）：`LingsiSources.tsx` 三态渲染——正常（可点开片段）、快照类（`snapshot`，虚线 + "数据截至"日期）、`revoked`（权限被收回，只提示不显内容）；citations 随 MESSAGE_DONE 下发，正文 `[n]` 编号由**服务端回填**，前端不做编号推断。
- 页面组装：`pages/agent/AgentHome.tsx`（任务栏 + 对话区 760px 居中 + 实体面板 ≤452px）；输入区 `LingsiComposer.tsx`；Markdown 渲染 `LingsiMarkdown.tsx`（含 `stripArgsMarker` 清洗）。

### 7.5 前端协议注意事项

- `userRefs` 与 `userTexts` **下标必须同步**（同一来源生成，禁止两处手工维护）。
- 失败状态白名单：`FAILED / TIMEOUT / DENIED`，其余中间态不落失败样式。

---

## 8. 通知流（SSE）

通知走独立通道：`useNotificationStream` 基于 `@microsoft/fetch-event-source` 连接后端 `NotificationStreamController`（SSE），与 Agent 的 STOMP 通道物理分离——过程型高频事件用 WS，通知推送用 SSE，各取所长。

---

## 9. 主题与样式

- CSS 设计令牌：`styles/tokens.css`（色板/间距/圆角变量）+ `tokens.ts` 常量对齐；`global.css` 基础样式；灵思界面独立 `lingsi*.css`；功能模块样式带版本后缀（`*-v6.css`）防遗留冲突。
- 明暗双主题：`theme.ts` 切换并写 `data-theme`；`App.tsx` 用 `ConfigProvider(zh_CN)` + **组织主色动态注入**（`org.ts` 的 primaryColor 来自组织 theme_json，多租户在 UI 上的体现）。

---

## 10. 构建与测试

- **类型检查**：`tsc -b`（solution-style 必须走构建模式）。
- **Vite**：dev 5173；代理 `/api`、`/uploads`（changeOrigin + 同源 Origin 重写，目标默认 `http://localhost:8080`，可被 `PAMS_E2E_API_TARGET` 覆盖且强制 localhost）、`/ws`（ws:true）；别名 `@ → ./src`。
- **单测**：`scripts/test.mjs` 驱动 vitest（node 环境，exclude `e2e/**`）；utils 下工具函数普遍配 `.test.ts`。
- **E2E**：Playwright 25 个 spec（`e2e/`），覆盖登录、权限、活动、签到、灵思对话等主链路。
- 容器化：`Dockerfile` + `nginx.conf` 静态托管。

---

## 11. 设计要点小结

1. **权限三处一致**：路由守卫、菜单过滤、后端校验同一权限码口径；灵思例外地把权限下沉到工具层。
2. **流式三件套**：REST 下单、STOMP 实时、REST 补拉对账；round 重组 + rAF 帧合并 + seq 去重是渲染稳定性的关键。
3. **服务端权威**：`[n]` 引用回填、citations 复核、权限刷新（/user/queue/authorization）都在服务端完成后才到前端——前端只做展示不做判定。
4. **契约化错误处理**：Result 解包 + ApiError + silentError + FeedbackBridge，全站错误体验一致。
5. **纯函数优先**：utils 层工具（markdown/orgTree/agentLinks/artifactDiff…）普遍带单测，复杂逻辑不藏进组件。
