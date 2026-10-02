import { get, post, del } from './http'

/* ============================================================================
 * 灵思（Agent）API —— 契约见 docs/PAMS-Agent业务设计方案.md §3.4
 * 产品名「灵思」，代码标识 agent。
 * ========================================================================== */

/** 过程事件类型（D7）。MESSAGE_DELTA 为二期字符流预留，一期后端不发。 */
export type AgentStreamEventType =
  | 'THINKING'
  | 'TOOL_START'
  | 'TOOL_END'
  | 'PLAN_READY'
  | 'INPUT_NEEDED'
  | 'MESSAGE_DONE'
  | 'ERROR'
  | 'ABORTED'
  | 'MESSAGE_DELTA'

/**
 * 过程事件。
 * ⚠️ 与 /user/queue/notifications 的「薄载荷」策略有意不同：
 * 这里必须携带真实内容（工具名、耗时、状态），否则前端无从渲染时间轴。
 */
export interface AgentStreamEvent {
  streamId: string
  seq: number
  type: AgentStreamEventType
  payload: Record<string, unknown>
  ts: number
}

/** 拟执行清单项 */
export interface AgentPlanItem {
  id: number | null
  label: string
  sub: string
}

/** 拟执行清单的跳过项（必现且不进折叠，灵思 §4.3） */
export interface AgentSkippedItem {
  label: string
  sub: string
  reason: string
}

/**
 * 拟执行清单（对应后端 AgentActionPlan）。
 * 前端 AgentActionCard 只依赖本结构、不感知具体业务 —— 新增写类工具前端零改动（D11）。
 */
export interface AgentActionPlan {
  planId: number
  summary: string
  total: number
  items: AgentPlanItem[]
  skipped: AgentSkippedItem[]
  detailLines: string[]
  reversible: boolean
  confirmToken: string
  needsConfirm: boolean
  /**
   * 内容预览（§3.9.1）：策划书章节草稿 / 推文草稿这类"写进去的是一篇长文"的工具，
   * 正文随清单一起下发，用户点「查看全文」在抽屉里通读后再确认。非内容类工具为空串。
   */
  preview?: string | null
}

/** 工具调用摘要（随消息一起返回） */
export interface AgentToolCallEntry {
  tool: string
  label: string
  status: string
  durationMs: number
  write: boolean
  sensitivity: string
  error?: string
}

/** 表单字段 schema（D2.5 Form Elicitation，自描述 → 新增表单型工具前端零改动） */
export interface AgentFormField {
  name: string
  label: string
  type: 'text' | 'textarea' | 'date' | 'select' | 'number'
  required?: boolean
  options?: string[]
  placeholder?: string
}

/**
 * 表单补全请求（D2.5）：写类工具关键参数不全时后端下发，
 * 前端弹窗渲染（预填 values），提交后转入既有确认门禁。
 */
export interface AgentInputForm {
  taskId: number
  formKey: string
  toolName?: string
  title: string
  description?: string
  fields: AgentFormField[]
  values: Record<string, unknown>
  status?: string
}

/** 实体引用（可跳转，避免"AI 说了但找不到"） */
export interface AgentReference {
  type: string
  id: number | string | null
  label: string
  link?: string
}

/**
 * 产物（UI v2 §4「产物卡」）。
 *
 * 语义：一次写操作**真正落库后**产生的实体变更。与 references 的区别在于时序 ——
 * references 是"我查到了/将要用到的东西"（dry-run 阶段即可有），
 * artifacts 是"我改动了的东西"（只有 commit 之后才有）。
 *
 * 与后端 `TaskGate.toArtifacts` 一一对应：
 * `{entityType, entityId, name, path}`。
 * **变更量不在这里**：它是卡片级属性（affectedCount / failedCount / detail），
 * 摊到每个产物头上会撒谎（"3 个产物各 +41"）。
 */
export interface AgentArtifact {
  entityType: string
  entityId: number | string | null
  name: string
  path?: string | null
}

/**
 * 运行模式（UI v2 §E 输入框单侧控制点 + V27 对话档位）。
 *
 * `readonly` **不是权限降级** —— 用户权限码一个没少，只是本次会话主动放弃写能力。
 * 实现上等价于「工具可见性过滤」的又一个条件：后端在 `ToolRegistry.visibleTo` 里
 * 把 `write=true` 的工具整体摘掉，模型根本看不到，因此不可诱导。
 *
 * `kb` 是**任务轴**的切换（D12 知识库问答），不是"权限更严的只读"：
 * 后端走一条独立流水线（`KbPipeline`），根本不进工具循环 ——
 * 没有循环，也就无从诱导工具调用。它与 readonly 唯一相似的地方是"都不写数据"。
 *
 * ⚠️ 后端 `normalizeMode` 不识别 `kb` 时会**静默回退 standard**（模型拿到 10 个写工具
 * 且不报错），所以本档位必须与后端同批发布，禁止前端先上。
 */
export type AgentMode = 'readonly' | 'standard' | 'kb'

/**
 * 答案依据（V27，服务端回填）。
 *
 * **契约要点**：模型只输出 `[n]` 编号占位，出处元数据全部由服务端从检索结果映射 ——
 * 模型物理上无法编造出处，它最多只能"引用第几条已有依据"。
 *
 * 因此前端**不需要也绝不能**自行拼 `link`：路径规则由各知识源提供，
 * 前端拼接会在规则变化时散落成死链。
 */
export interface AgentCitation {
  /** 答案里的编号，与正文中的 `[n]` 对应 */
  index: number
  /**
   * 出处类型。**不要假设一定是人工文档** —— D12 之后
   * 公告/稿件/策划书/人工录入/事实快照都是合法的出处。
   */
  sourceType: string
  sourceId: number
  sourceTitle: string
  /** 标题路径，如「第三章 第二十六条」；无标题结构时为 null */
  path: string | null
  /** 片段摘要（已脱敏） */
  snippet: string
  /** 由后端按 sourceType 生成；null = 没有可跳转页面，渲染成不可点 */
  link: string | null
  /**
   * 数据截止时间（`YYYY-MM-DD`）。
   *
   * **快照类出处必须展示**：它表示"这是某时刻的数字"而不是"当前值"。
   * 不展示会让用户拿过期数字做决策 —— 比不答更糟。
   */
  asOf?: string | null
  /**
   * 该引用在**当前权限下**已不可见（V27 历史回看）。
   *
   * 权限收紧必须能撤回历史：用户被降权后重开旧对话，引用会降级为"已不可见"，
   * 且 `snippet` 已被服务端清空。这是正确行为但反直觉 —— 见到它不要当成 bug。
   */
  revoked?: boolean
}

/**
 * 「附加上下文」引用（UI v2 输入框 `+`）。
 *
 * 只传"选了什么"——类型与 ID 是前端从系统列表里点选出来的，不是模型猜的，
 * 因此后端可以信任它做确定性解析，不必再走名称消歧。
 * label 仅用于展示，后端会按 ID 重新取实体并生成自己的 label。
 */
export interface AgentChatRef {
  type: string
  id: number | string
  label?: string
}

/** POST /api/agent/chat 的返回 */
export interface AgentChatResponse {
  streamId: string
  conversationId: number
  messageId: number
  reply: string
  toolCalls: AgentToolCallEntry[]
  plan: AgentActionPlan | null
  /** 表单补全请求（D2.5）；plan 与 form 互斥 */
  form: AgentInputForm | null
  references: AgentReference[]
  /** 答案依据（V27 对话档位）。办事型为空数组 */
  citations?: AgentCitation[]
  /** 本轮实际使用的模式（后端归一化后的值） */
  mode?: AgentMode
  provider: string
  degraded: boolean
  durationMs: number
  pendingTaskCount: number
}

export interface AgentConversationVO {
  id: number
  title: string
  createdAt: string
  updatedAt: string
  pendingTaskCount: number
}

export interface AgentConversationsPage {
  records: AgentConversationVO[]
  total: number
  current: number
  size: number
  /** 用户级待确认任务总数（侧边栏角标） */
  pendingTaskCount: number
}

export interface AgentMessageVO {
  id: number
  conversationId: number
  role: string
  content: string
  sensitivity: string
  streamId: string | null
  provider: string | null
  durationMs: number | null
  createdAt: string
  toolCalls: AgentToolCallEntry[]
  /** V26 历史恢复：随消息持久化的工具结果引用（此前只随 /chat 同步返回） */
  references: AgentReference[]
  /** 该轮产生的产物（UI v2）：历史上已执行完的动作，重进会话时用于渲染产物卡 */
  artifacts: AgentArtifact[]
  /**
   * 该轮答案的依据（V27）。与 references 语义不同：
   * references = "我查到了什么"（过程），citations = "答案据此下了什么结论"（证据）。
   */
  citations?: AgentCitation[]
  /**
   * 该轮使用的模式。老数据为 `standard`（后端兜底），
   * 前端据此决定渲染依据卡还是产物卡。
   */
  mode?: AgentMode
  /** 仍待确认的任务卡（PENDING_CONFIRM 时后端挂回本消息），否则 null */
  plan: AgentActionPlan | null
  /** 仍待填写的表单卡（WAITING_INPUT 时后端挂回本消息），否则 null */
  form: AgentInputForm | null
}

export interface AgentMessagesPage {
  records: AgentMessageVO[]
  total: number
  current: number
  size: number
}

export interface AgentTaskVO {
  id: number
  toolName: string
  status: string
  summary: string
  conversationId: number | null
  createdAt: string
  confirmedAt: string | null
  finishedAt: string | null
  expired: boolean
  plan?: AgentActionPlan | null
  result?: Record<string, unknown> | null
  /** 产物清单（UI v2）：与 WS AGENT_TASK_DONE 的 artifacts 同构 */
  artifacts?: AgentArtifact[]
  reversible: boolean
}

export interface AgentToolVO {
  name: string
  label: string
  description: string
  write: boolean
  sensitivity: string
}

/* ---------------------------------------------------------------- 对话 */

export const agentChat = (
  message: string,
  conversationId?: number | null,
  mode: AgentMode = 'standard',
  refs: AgentChatRef[] = [],
) => post<AgentChatResponse>('/agent/chat', { message, conversationId: conversationId ?? null, mode, refs })

export const agentAbort = (streamId: string) =>
  post<{ streamId: string; aborted: boolean }>(`/agent/chat/${streamId}/abort`)

/** 补拉过程事件（断连补齐 / 历史回看，D7 降级通道） */
export const agentStreamSince = (streamId: string, since = 0) =>
  get<{ streamId: string; since: number; events: AgentStreamEvent[] }>(
    `/agent/streams/${streamId}`,
    { since },
  )

/* ---------------------------------------------------------------- 会话 */

export const listConversations = (page = 1, size = 20) =>
  get<AgentConversationsPage>('/agent/conversations', { page, size })

export const listMessages = (conversationId: number, page = 1, size = 50) =>
  get<AgentMessagesPage>(`/agent/conversations/${conversationId}/messages`, { page, size })

export const deleteConversation = (id: number) => del<void>(`/agent/conversations/${id}`)

/* ------------------------------------------------------------ 任务门禁 */

export const getAgentTask = (id: number) => get<AgentTaskVO>(`/agent/tasks/${id}`)

export const confirmAgentTask = (id: number, confirmToken: string, mode: AgentMode = 'standard') =>
  post<AgentTaskVO>(`/agent/tasks/${id}/confirm`, { confirmToken, mode })

/**
 * 提交表单补全（D2.5）。返回 status：
 * PLAN → 已生成待确认清单（附 plan + confirmToken）；FORM → 仍缺信息（附新表单）；
 * ERROR → 校验失败可修改重提；CANCELLED → 业务拦停，任务终态化。
 *
 * mode 随请求带上：只读模式下写类工具不可见，submitInput 重跑 dry-run 时会被拦下。
 */
export const submitTaskInput = (id: number, values: Record<string, string>, mode: AgentMode = 'standard') =>
  post<TaskInputResult>(`/agent/tasks/${id}/input`, { values, mode })

export interface TaskInputResult {
  status: 'PLAN' | 'FORM' | 'ERROR' | 'CANCELLED'
  plan?: AgentActionPlan
  form?: AgentInputForm
  message?: string
  error?: string
}

export const cancelAgentTask = (id: number) => post<void>(`/agent/tasks/${id}/cancel`)

/* ------------------------------------------------------------ 能力清单 */

/** mode=readonly 时后端不会返回任何写类工具（与模型实际可见集一致） */
export const listAgentTools = (mode: AgentMode = 'standard') =>
  get<AgentToolVO[]>('/agent/tools', { mode })
