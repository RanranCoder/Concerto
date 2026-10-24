import { create } from 'zustand'
import type {
  AgentActionPlan,
  AgentArtifact,
  AgentChatRef,
  AgentCitation,
  AgentConversationVO,
  AgentInputForm,
  AgentMode,
  AgentReference,
  AgentStreamEvent,
  AgentSkippedItem,
  AgentThinkingNote,
  AgentThinkingStep,
  AgentToolCallEntry,
  TaskInputResult,
} from '@/api/agent'
import * as api from '@/api/agent'
import { thinkingHeadline, timelineFromMessage } from '@/utils/thinking'

/** 协作过程事件：需要按会话归属过滤的那几类（见 applyStreamEvent） */
const COLLAB_EVENT_TYPES = new Set([
  'COLLAB_DECISION', 'AGENT_START', 'AGENT_END', 'AGENT_DELEGATE', 'REVIEW_RESULT',
])
let requestVersion = 0
const stoppedStreams = new Set<string>()

/**
 * 收到这些事件就说明"这一轮的推理已经结束"：模型要么开始调工具、要么开始出正文、要么收官。
 *
 * <p>为什么需要它：{@code THINKING_DELTA} 到达时那一行是"推理中"（前缀脉冲），
 * 而推理结束后**不会**再发一个"推理结束"事件 —— 不封口的话，
 * 一段早已读完的推理会一直显示成正在思考，用户以为卡住了。
 */
const SEAL_THINKING_EVENTS = new Set([
  'TOOL_START', 'TOOL_END', 'PLAN_READY', 'INPUT_NEEDED', 'MESSAGE_DELTA',
  'MESSAGE_DONE', 'ERROR', 'ABORTED', 'REVIEW_RESULT',
])

/** 把仍在"推理中"的思维链段标记为完成（没有待封口段时原样返回，避免无谓重建） */
function sealThinking(list: TimelineItem[]): TimelineItem[] {
  if (!list.some((x) => x.kind === 'thinking' && !x.completed)) return list
  return list.map((x) => (x.kind === 'thinking' && !x.completed ? { ...x, completed: true } : x))
}

/** 过程区时间轴的一行（由 AgentStreamEvent 归并而成） */
export interface TimelineItem {
  /** TOOL_START 先入，TOOL_END 到位后合并为 completed */
  key: string
  seq: number
  /**
   * 协作内聚后新增两类行：
   *  - `agent`：某个专家 Agent 的开始/结束（协作时才出现，单 Agent 轮次不会有）
   *  - `review`：审查结论（PASS / REVISE）
   *  - `collab`：本次为何升级为协作的判定说明
   */
  kind: 'thinking' | 'tool' | 'plan' | 'error' | 'aborted' | 'agent' | 'review' | 'collab'
  tool?: string
  label: string
  /**
   * 思维链原文（V35）：kind==='thinking' 时的模型推理正文（可展开阅读）。
   * label 是它的折叠标题 —— 两者同源，label 由 detail 派生（见 utils/thinking.ts），
   * 不要手工各写一份，否则标题与正文会对不上。
   */
  detail?: string
  /** 该行所属的 LLM 调用轮次（thinking 行必有；工具行跟随其所在段） */
  round?: number
  /**
   * 工具行的入参（V36 下钻）。**只有后端给了才有值**（`normal` 级工具），
   * 值为遮罩后的明文；`undefined` 就表示不可下钻 —— 前端不自己按 sensitivity 猜。
   */
  argsJson?: string
  status?: string
  durationMs?: number
  error?: string
  note?: string
  completed: boolean
  ts: number
  /** kind==='agent'：专家标识（retrieval / writing …），用于头像与身份色 */
  agent?: string
  /** kind==='review'：裁决结论 PASS / REVISE 与理由 */
  verdict?: string
  reason?: string
}

/** 结果区的一条助手消息 */
export interface OutcomeMessage {
  status?: 'failed' | 'stopped'
  messageId: number | null
  streamId: string | null
  reply: string
  toolCalls: AgentToolCallEntry[]
  plan: AgentActionPlan | null
  /** 表单补全请求（D2.5）：plan 与 form 互斥——提交表单后 form 变 plan */
  form: AgentInputForm | null
  references: AgentReference[]
  /**
   * 该轮答案的依据（V27 对话档位）。与 references 语义不同，不能混用：
   * references = "我查到了什么"（过程），citations = "答案据此下了什么结论"（证据）。
   *
   * 后端已对历史消息做过**回源复核**：权限被收回的引用会带 `revoked:true`
   * 且 snippet 已被清空 —— 这是正确行为（权限收紧必须能撤回历史），不是 bug。
   */
  citations: AgentCitation[]
  provider: string
  degraded: boolean
  durationMs: number
  /** 本轮思维链（V35）：由落库/接口回放，历史轮次的过程区因此不再只有工具行 */
  thinking: AgentThinkingStep[]
  /**
   * 本轮编排旁白（V36）。与 thinking 一起按 ts 合并回放 ——
   * 只回放推理的话，刷新后过程区会少掉「正在理解你的问题…」这些行。
   */
  thinkingNotes: AgentThinkingNote[]
  /** 该轮自己的过程时间轴（V26 历史恢复：由 toolCalls 摘要派生，历史轮次不再空白） */
  timeline: TimelineItem[]
  /** 该条是否仍在等待后续事件（用于"进行中"骨架） */
  streaming: boolean
  /**
   * 执行结果块（用户反馈：任务完成**不要弹窗通知**，结果就落在对话框里）。
   * 有值时表示"确认卡执行完成后原地变成的结果块"，或"异步完成追加的结果消息"；
   * 渲染时以结果块替代清单卡，且不渲染 MetaBar。
   */
  notice?: AgentResultNotice
  /**
   * 该轮产生的产物（UI v2）。与 notice 分开是有意的：
   * notice 回答"成没成"，artifacts 回答"改了什么" —— 后者可能来自历史消息
   * （重进会话时从 AgentMessageVO.artifacts 水合），此时并没有 notice。
   */
  artifacts: AgentArtifact[]
}

/** 结果块等级（决定配色与图标） */
export type NoticeLevel = 'success' | 'failed' | 'partial' | 'info'

export interface AgentResultNotice {
  level: NoticeLevel
  text: string
  /** 关联任务 id（= planId）：用于「确认按钮 REST 返回」与「WS 完成事件」之间的去重 */
  taskId?: number
  /** 产物清单：非空时渲染产物卡（可折叠、可点进右栏） */
  artifacts?: AgentArtifact[]
  /** 变更量（卡片头右侧），由 detail 派生 */
  deltas?: DeltaChip[]
  skipped?: AgentSkippedItem[]
}

/** 产物卡头部的变更量标签（如「+41 条任务」「3 人跳过」） */
export interface DeltaChip {
  label: string
  tone: 'add' | 'mute' | 'warn'
}

/**
 * 由后端 `CommitResult.detail` 派生变更量标签。
 *
 * 为什么用白名单映射而不是直接遍历 detail：detail 的键是各工具自定义的
 * （created/failed/added/skipped/days/persons/version…），
 * 直接遍历会把 `activityId: 412` 渲染成「+412」这种假数字。
 * 白名单只认确实代表"变更条目数"的键，其余一律不进卡片。
 */
const DELTA_RULES: { key: string; unit: string; tone: DeltaChip['tone']; sign?: boolean }[] = [
  { key: 'created', unit: '条任务', tone: 'add', sign: true },
  { key: 'added', unit: '人', tone: 'add', sign: true },
  { key: 'failed', unit: '条失败', tone: 'warn' },
  { key: 'skipped', unit: '人跳过', tone: 'warn' },
  { key: 'days', unit: '天排班', tone: 'add' },
  { key: 'persons', unit: '人次', tone: 'add' },
  { key: 'slots', unit: '个班次', tone: 'add' },
]

function deltaChips(detail: unknown, affectedCount: unknown, failedCount: unknown): DeltaChip[] {
  const out: DeltaChip[] = []
  if (detail && typeof detail === 'object') {
    const d = detail as Record<string, unknown>
    for (const r of DELTA_RULES) {
      const n = Number(d[r.key])
      if (!Number.isFinite(n) || n === 0) continue
      out.push({ label: `${r.sign ? '+' : ''}${n} ${r.unit}`, tone: r.tone })
    }
  }
  // detail 没给出可读项时退回 affectedCount —— 宁可标签含糊，也不要卡片头空着
  if (out.length === 0) {
    const a = Number(affectedCount)
    if (Number.isFinite(a) && a > 0) out.push({ label: `影响 ${a} 条`, tone: 'add' })
    const f = Number(failedCount)
    if (Number.isFinite(f) && f > 0) out.push({ label: `${f} 条失败`, tone: 'warn' })
  }
  return out
}

/** 把后端来的 artifacts（可能缺字段 / 非数组）收窄成 AgentArtifact[] */
function sanitizeArtifacts(raw: unknown): AgentArtifact[] {
  if (!Array.isArray(raw)) return []
  const out: AgentArtifact[] = []
  for (const it of raw) {
    if (!it || typeof it !== 'object') continue
    const o = it as Record<string, unknown>
    const name = String(o.name ?? o.label ?? '').trim()
    if (!name) continue
    out.push({
      entityType: String(o.entityType ?? o.type ?? 'entity'),
      entityId: (o.entityId ?? o.id ?? null) as AgentArtifact['entityId'],
      name,
      path: (o.path ?? o.link ?? null) as string | null,
    })
  }
  return out
}

/** 空白结果消息（追加型结果块用；下标与 userTexts 保持一一对齐） */
function emptyOutcome(): OutcomeMessage {
  return {
    messageId: null,
    streamId: null,
    reply: '',
    toolCalls: [],
    plan: null,
    form: null,
    references: [],
    citations: [],
    artifacts: [],
    provider: '',
    degraded: false,
    durationMs: 0,
    thinking: [],
    thinkingNotes: [],
    timeline: [],
    streaming: false,
  }
}

/** 由任务终态推导结果块的等级与文案（替代此前的 corner 弹窗通知） */
function noticeFromStatus(
  status: string,
  src: {
    message?: unknown
    summary?: unknown
    affectedCount?: unknown
    failedCount?: unknown
    artifacts?: unknown
    detail?: unknown
    taskId?: number
    skipped?: AgentSkippedItem[]
  },
): AgentResultNotice {
  const level: NoticeLevel =
    status === 'FAILED' ? 'failed' : status === 'PARTIAL' ? 'partial' : status === 'CANCELLED' ? 'info' : 'success'
  const head =
    level === 'failed' ? '执行失败' : level === 'partial' ? '部分完成' : level === 'info' ? '已取消' : '执行完成'
  const body = String(src.message ?? src.summary ?? '').trim()
  return {
    level,
    text: body ? `${head}：${body}` : head,
    taskId: src.taskId,
    artifacts: sanitizeArtifacts(src.artifacts),
    deltas: deltaChips(src.detail, src.affectedCount, src.failedCount),
    skipped: src.skipped ?? [],
  }
}

interface AgentState {
  /* 会话 */
  conversations: AgentConversationVO[]
  conversationsLoading: boolean
  currentConversationId: number | null

  /* 消息 */
  outcomes: OutcomeMessage[]
  messagesLoading: boolean
  /** 历史会话的用户提问文案（与 outcomes 按下标配对；V26 前重进会话后用户气泡全丢） */
  userTexts: string[]

  /* 过程区 */
  timeline: TimelineItem[]
  activeStreamId: string | null
  thinking: boolean
  /**
   * 字符流（MESSAGE_DELTA）的 round 标记。
   * 后端约定：**round 变化 = 前端清空已拼装的回复再追加** ——
   * 这一个约定同时覆盖了工具调用轮、重试、降级三类场景，不需要额外的重置事件。
   */
  deltaRound: number | null

  /**
   * 运行模式（UI v2 §E）。默认 standard = 改造前行为，避免老用户"突然发现写不了数据"。
   * 切换时**必须**同时刷新能力清单（后端按 mode 过滤，面板要与实际可调用集一致）。
   */
  mode: AgentMode
  setMode: (m: AgentMode) => Promise<void>

  /**
   * 自动协作开关（协作内聚后新增，默认开）。
   *
   * 标准模式下由后端判定「这次要不要派专家团队」；这个开关是用户的**否决权**——
   * 判定可能出错（简单问题被升级成协作会变慢），所以留一个能立刻关掉的入口，
   * 而不是让用户只能忍受。它只在 standard 档位有意义：readonly 不许写、
   * kb 走的是完全不同的问答流水线，两者都不会升级为协作。
   */
  autoCollab: boolean
  setAutoCollab: (v: boolean) => void

  /**
   * 待附加的上下文引用（UI v2 输入框 `+`）。
   * 放 store 而不是 Composer 本地：发送时要一起带走，且「删除上一条消息里的引用」
   * 这类操作不该依赖组件实例的生命周期。
   */
  pendingRefs: AgentChatRef[]
  addRef: (r: AgentChatRef) => void
  removeRef: (r: AgentChatRef) => void

  /**
   * 每条用户消息实际附带的引用（与 userTexts/outcomes 下标一一对齐）。
   * 历史会话从 USER 消息的 references 水合（后端随 refs_json 落库）。
   * ⚠️ 任何往 userTexts 追加空串占位的地方，这里也必须同步追加 ——
   * 这正是此前"刚发出的消息不显示"那类下标错位的同款坑。
   */
  userRefs: AgentChatRef[][]

  /* 门禁 */
  pendingPlan: AgentActionPlan | null
  pendingTaskCount: number

  /* 动作 */
  fetchConversations: () => Promise<void>
  selectConversation: (id: number | null) => Promise<void>
  removeConversation: (id: number) => Promise<void>

  sendMessage: (text: string) => Promise<void>
  stop: () => Promise<void>

  /** WebSocket 收到过程事件时调用（useWebSocket 订阅 /user/queue/agent/stream） */
  applyStreamEvent: (e: AgentStreamEvent) => void
  /** 任务完成事件（/user/queue/agent，AGENT_TASK_DONE） */
  applyTaskDone: (payload: Record<string, unknown>) => void

  confirmPlan: (planId: number, confirmToken: string) => Promise<void>
  cancelPlan: (planId: number) => Promise<void>
  dismissPlan: () => void

  /** 提交表单补全（D2.5）：成功后把该条消息的 form 换成 plan（或更新 form） */
  submitForm: (taskId: number, values: Record<string, string>) => Promise<TaskInputResult>

  reset: () => void
}

/**
 * 把过程事件归并进时间轴。
 *
 * 归并规则（灵思 §7.1）：
 * - THINKING → 追加一行（编排旁白，一次性过渡态）
 * - THINKING_DELTA → **不新起行**：同 round 的推理片段就地拼进已有 thinking 行的 detail，
 *   标题随原文重算。这是"思维链"能读的前提 —— 每个片段一行的话，一次回答会刷出上百行
 * - TOOL_START → 该 seq 预留一行（completed=false，前端显示"进行中"）
 * - TOOL_END → 用 tool 名匹配最近一条未完成的 tool 行并补全
 * - seq 单调递增，按 seq 排序 → 乱序到达不错位
 */
function mergeTimeline(list: TimelineItem[], e: AgentStreamEvent): TimelineItem[] {
  const p = e.payload || {}
  switch (e.type) {
    case 'THINKING':
      return [
        ...list,
        {
          key: `t-${e.seq}`,
          seq: e.seq,
          kind: 'thinking',
          label: String(p.note ?? '正在思考…'),
          completed: true,
          ts: e.ts,
        },
      ]
    case 'THINKING_DELTA': {
      const round = Number(p.round ?? 0)
      const piece = String(p.text ?? '')
      if (!piece) return list
      // 从后往前找同 round 且仍在进行的推理段（重试会换 round → 自然新起一段）
      const idx = [...list].reverse().findIndex((x) => x.kind === 'thinking' && x.round === round)
      if (idx === -1) {
        return [
          ...list,
          {
            key: `think-${round}-${e.seq}`,
            seq: e.seq,
            kind: 'thinking',
            round,
            label: thinkingHeadline(piece),
            detail: piece,
            completed: false,
            ts: e.ts,
          },
        ]
      }
      const realIdx = list.length - 1 - idx
      const next = [...list]
      const prev = next[realIdx]
      const detail = (prev.detail ?? '') + piece
      next[realIdx] = { ...prev, detail, label: thinkingHeadline(detail), completed: false }
      return next
    }
    case 'TOOL_START':
      return [
        ...list,
        {
          key: `tool-${e.seq}`,
          seq: e.seq,
          kind: 'tool',
          tool: String(p.tool ?? ''),
          label: String(p.label ?? p.tool ?? ''),
          completed: false,
          ts: e.ts,
        },
      ]
    case 'TOOL_END': {
      const tool = String(p.tool ?? '')
      // 从后往前找最近的、同名且未完成的工具行（并发同名工具也能正确配对）
      const idx = [...list].reverse().findIndex((x) => x.kind === 'tool' && x.tool === tool && !x.completed)
      if (idx === -1) {
        return [
          ...list,
          {
            key: `tool-${e.seq}`,
            seq: e.seq,
            kind: 'tool',
            tool,
            label: String(p.label ?? tool),
            status: String(p.status ?? ''),
            durationMs: Number(p.durationMs ?? 0),
            error: p.error ? String(p.error) : undefined,
            completed: true,
            ts: e.ts,
          },
        ]
      }
      const realIdx = list.length - 1 - idx
      const next = [...list]
      next[realIdx] = {
        ...next[realIdx],
        status: String(p.status ?? ''),
        durationMs: Number(p.durationMs ?? 0),
        error: p.error ? String(p.error) : undefined,
        completed: true,
      }
      return next
    }
    case 'ERROR':
      return [
        ...list,
        {
          key: `err-${e.seq}`,
          seq: e.seq,
          kind: 'error',
          label: String(p.message ?? '出错了'),
          completed: true,
          ts: e.ts,
        },
      ]
    case 'ABORTED':
      return [
        ...list,
        {
          key: `abort-${e.seq}`,
          seq: e.seq,
          kind: 'aborted',
          label: '已中止',
          completed: true,
          ts: e.ts,
        },
      ]

    /* ===== 多 Agent 协作事件（标准模式自动升级时出现）===== */

    case 'COLLAB_DECISION':
      // 判定为协作时先给一行说明，用户才知道「为什么这次不只一个 Agent」
      return [
        ...list,
        {
          key: `collab-${e.seq}`,
          seq: e.seq,
          kind: 'collab',
          label: String(p.reason ?? '这个任务需要多个专家协作'),
          status: String(p.mode ?? ''),
          completed: true,
          ts: e.ts,
        },
      ]

    case 'AGENT_START':
      return [
        ...list,
        {
          key: `agent-${e.seq}`,
          seq: e.seq,
          kind: 'agent',
          agent: String(p.agent ?? ''),
          label: String(p.displayName ?? p.agent ?? ''),
          completed: false,
          ts: e.ts,
        },
      ]

    case 'AGENT_END': {
      const agent = String(p.agent ?? '')
      // 与 TOOL_END 同样的配对策略：从后往前找最近的、同名且未完成的专家行
      const idx = [...list].reverse().findIndex((x) => x.kind === 'agent' && x.agent === agent && !x.completed)
      if (idx === -1) return list
      const realIdx = list.length - 1 - idx
      const next = [...list]
      next[realIdx] = {
        ...next[realIdx],
        status: String(p.status ?? ''),
        durationMs: typeof p.durationMs === 'number' ? p.durationMs : undefined,
        completed: true,
      }
      return next
    }

    case 'REVIEW_RESULT':
      return [
        ...list,
        {
          key: `review-${e.seq}`,
          seq: e.seq,
          kind: 'review',
          agent: 'reviewer',
          label: String(p.verdict ?? ''),
          verdict: String(p.verdict ?? ''),
          reason: String(p.reason ?? ''),
          completed: true,
          ts: e.ts,
        },
      ]

    default:
      return list
  }
}

/**
 * 由 toolCalls 摘要派生历史时间轴（V26）。
 *
 * 为什么不用 /agent/streams/{id} 补拉：消息里持久化的 toolCalls 摘要
 * 与 agent_tool_call 流水同源（都是编排器写入），内容等价；
 * 本地派生零请求、对 V26 之前的旧消息也生效。
 * THINKING 行是一次性的过渡态，不参与历史恢复。
 */
export function timelineFromToolCalls(toolCalls: AgentToolCallEntry[]): TimelineItem[] {
  return (toolCalls ?? []).map((t, i) => ({
    key: `tool-${i + 1}`,
    seq: i + 1,
    kind: 'tool' as const,
    tool: t.tool,
    label: t.label || t.tool,
    status: t.status,
    durationMs: t.durationMs ?? 0,
    error: t.error,
    completed: true,
    ts: 0,
  }))
}

/* ============================================================ 字符流批量落地 */

/**
 * 字符流缓冲（MESSAGE_DELTA）。
 *
 * <p><b>为什么必须缓冲</b>：后端按 24 字符 / 60ms 合并后才推，但一条长回复仍有几十条
 * delta；每条都 `set` 一次会整条消息列表重渲染 + Markdown 重解析，
 * 在长思维链叠加时肉眼可见地卡。这里按帧合并：**每帧最多一次状态写入**。
 *
 * <p>跨 round 必须先落地：round 变化表示"重新生成一段"（重试 +1000 / 降级 +2000），
 * 若把两个 round 的片段并进同一个缓冲，重置语义就丢了 —— 会拼出半截旧文 + 半截新文。
 */
let deltaBuffer: { streamId: string; round: number; text: string } | null = null
let deltaFrame = 0

const scheduleFrame = (cb: () => void): number =>
  typeof requestAnimationFrame === 'function'
    ? requestAnimationFrame(cb)
    : (setTimeout(cb, 16) as unknown as number)

const cancelFrame = (id: number): void => {
  if (typeof cancelAnimationFrame === 'function') cancelAnimationFrame(id)
  else clearTimeout(id as unknown as ReturnType<typeof setTimeout>)
}

/**
 * 把缓冲的字符流一次性写进"进行中"的那条回复。
 *
 * <p>导出有两个用途：①测试里不必等帧；②收官事件（MESSAGE_DONE/ERROR/ABORTED）
 * 到达时先同步落地，否则最后一段正文可能还没 flush 就被收官收走。
 */
export function flushStreamDeltas(): void {
  const buffered = deltaBuffer
  deltaBuffer = null
  if (deltaFrame) {
    cancelFrame(deltaFrame)
    deltaFrame = 0
  }
  if (!buffered || !buffered.text) return
  useAgentStore.setState((s) => {
    const list = [...s.outcomes]
    const idx = list.length - 1
    // REST 返回已用权威全文覆盖过（streaming=false）→ 这段缓冲作废，别追加到终稿后面
    if (idx < 0 || !list[idx].streaming || s.activeStreamId !== buffered.streamId) return {}
    list[idx] = { ...list[idx], reply: list[idx].reply + buffered.text }
    return { outcomes: list }
  })
}

/** 收下一个 MESSAGE_DELTA（按帧合并；跨 round 先落地） */
function acceptStreamDelta(e: AgentStreamEvent): void {
  const round = Number(e.payload?.['round'] ?? 0)
  const text = String(e.payload?.['text'] ?? '')
  if (!text) return
  const state = useAgentStore.getState()
  if (state.deltaRound !== round) {
    flushStreamDeltas()
    useAgentStore.setState((s) => {
      const list = [...s.outcomes]
      const idx = list.length - 1
      if (idx >= 0) list[idx] = { ...list[idx], reply: '' }
      return { outcomes: list, timeline: sealThinking(s.timeline), deltaRound: round, activeStreamId: e.streamId, thinking: true }
    })
  } else if (state.timeline.some(item => item.kind === 'thinking' && !item.completed)) {
    // MESSAGE_DELTA bypasses the ordinary event reducer. Seal the preceding
    // reasoning on its first text chunk as well, before the answer is painted.
    useAgentStore.setState(s => ({timeline:sealThinking(s.timeline)}))
  }
  deltaBuffer = { streamId: e.streamId, round, text: (deltaBuffer?.round === round ? deltaBuffer.text : '') + text }
  if (!deltaFrame) {
    deltaFrame = scheduleFrame(() => {
      deltaFrame = 0
      flushStreamDeltas()
    })
  }
}

export const useAgentStore = create<AgentState>()((set, get) => ({
  conversations: [],
  conversationsLoading: false,
  currentConversationId: null,
  outcomes: [],
  messagesLoading: false,
  userTexts: [],
  timeline: [],
  activeStreamId: null,
  thinking: false,
  deltaRound: null,
  mode: 'standard',
  pendingRefs: [],
  userRefs: [],
  pendingPlan: null,
  pendingTaskCount: 0,

  setMode: async (m) => {
    if (get().mode === m) return
    // 只改档位，**不动**已渲染的清单卡：清单是后端持久化的真实任务，
    // 只读下把卡藏掉、切回来又恢复会让人以为任务丢了。
    // 可确认性由卡片自己按 readonly 禁用（见 LingsiActionCard）。
    set({ mode: m })
  },

  autoCollab: true,
  setAutoCollab: (v) => set({ autoCollab: v }),

  addRef: (r) =>
    set((s) => {
      // 同一实体重复点选只保留一条：chip 上看着才不会像出了 bug
      if (s.pendingRefs.some((x) => x.type === r.type && String(x.id) === String(r.id))) return {}
      // 与后端 ChatRefsResolver.MAX_REFS 对齐
      if (s.pendingRefs.length >= 5) return {}
      return { pendingRefs: [...s.pendingRefs, r] }
    }),

  removeRef: (r) =>
    set((s) => ({
      pendingRefs: s.pendingRefs.filter(
        (x) => !(x.type === r.type && String(x.id) === String(r.id)),
      ),
    })),

  fetchConversations: async () => {
    const version = requestVersion
    set({ conversationsLoading: true })
    try {
      const res = await api.listConversations(1, 50)
      if (version !== requestVersion) return
      set({
        conversations: res?.records ?? [],
        pendingTaskCount: res?.pendingTaskCount ?? 0,
      })
    } catch {
      // http 拦截器已提示
    } finally {
      if (version === requestVersion) set({ conversationsLoading: false })
    }
  },

  selectConversation: async (id) => {
    const version = ++requestVersion
    if (get().activeStreamId) stoppedStreams.add(get().activeStreamId!)
    flushStreamDeltas()
    set({
      currentConversationId: id,
      conversationsLoading: false,
      thinking: false,
      activeStreamId: null,
      outcomes: [],
      timeline: [],
      pendingPlan: null,
      userTexts: [],
      userRefs: [],
      deltaRound: null,
      // 切会话顺手清掉待附加引用：它们是针对"接下来要问什么"选的，跨会话残留只会误导
      pendingRefs: [],
      messagesLoading: id != null,
    })
    if (id == null) return
    set({ messagesLoading: true })
    try {
      const res = await api.listMessages(id, 1, 100)
      if (version !== requestVersion || get().currentConversationId !== id) return
      const outcomes: OutcomeMessage[] = []
      const users: string[] = []
      const userRefs: AgentChatRef[][] = []
      for (const m of res?.records ?? []) {
        if (m.role === 'USER') {
          // 用户提问按出现顺序与 assistant 回复一一对齐（每轮 user → assistant）
          users.push(m.content)
          // 附加上下文随 refs_json 落库（UI v2 `+`），历史里照样能看出当时选了哪个实体
          userRefs.push(
            (m.references ?? []).map((r) => ({ type: r.type, id: r.id ?? '', label: r.label })),
          )
          continue
        }
        if (m.role !== 'ASSISTANT') continue
        outcomes.push({
          messageId: m.id,
          streamId: m.streamId,
          reply: m.content,
          toolCalls: m.toolCalls ?? [],
          // V26 历史恢复：确认卡/表单卡/引用由后端按 stream_id 挂回，不再硬置 null
          plan: m.plan ?? null,
          form: m.form ?? null,
          references: m.references ?? [],
          // V27：依据随消息水合。后端已对它们做过回源复核（被收回权限的会带 revoked）
          citations: m.citations ?? [],
          artifacts: sanitizeArtifacts(m.artifacts),
          provider: m.provider ?? 'stub',
          degraded: (m.provider ?? '') === 'stub',
          durationMs: m.durationMs ?? 0,
          // V35：水合时把思维链一起回放 —— 只派生 toolCalls 的话，
          // 刷新/切会话后过程区会缺掉"思考"那一半（V26 的老行为）
          thinking: m.thinking ?? [],
          // V36：旁白也回放（按 ts 与推理段合并排序），否则少了「正在理解你的问题…」这些行
          thinkingNotes: m.thinkingNotes ?? [],
          timeline: timelineFromMessage({
            thinking: m.thinking,
            thinkingNotes: m.thinkingNotes,
            toolCalls: m.toolCalls ?? [],
          }),
          streaming: false,
          notice: m.taskResult ? noticeFromStatus(m.taskResult.status, m.taskResult) : undefined,
        })
      }
      set({ outcomes, userTexts: users, userRefs })
    } catch {
      // http 拦截器已提示
    } finally {
      if (version === requestVersion && get().currentConversationId === id) set({ messagesLoading: false })
    }
  },

  removeConversation: async (id) => {
    await api.deleteConversation(id)
    set((s) => ({
      conversations: s.conversations.filter((c) => c.id !== id),
      currentConversationId: s.currentConversationId === id ? null : s.currentConversationId,
      outcomes: s.currentConversationId === id ? [] : s.outcomes,
      timeline: s.currentConversationId === id ? [] : s.timeline,
      userTexts: s.currentConversationId === id ? [] : s.userTexts,
    }))
  },

  sendMessage: async (text) => {
    const content = text.trim()
    if (!content) return
    if (get().thinking) return
    const version = ++requestVersion

    // 附加上下文必须在 set 之前取走：set 里会清空 pendingRefs，之后再读就是空数组
    const refs = get().pendingRefs

    // 立刻清空过程区并进入"思考中"——本地先给即时反馈，
    // 真正的 THINKING 事件随后到达（TTFE P95 < 800ms）
    // 上一轮若还有未落地的字符流缓冲，先落地再开新轮（否则那半句话会写进新轮的回复）
    flushStreamDeltas()

    set((s) => ({
      timeline: [],
      pendingPlan: null,
      thinking: true,
      activeStreamId: null,
      deltaRound: null,
      // 用户提问与占位消息在「同一次 set」写入（单一数据源，下标一一对齐）：
      // 此前用户文案放在页面本地 state、占位消息放在 store，两条更新互相竞态，
      // 表现为"刚发出的消息不显示，刷新（走服务端 userTexts 水合）后才出现"。
      userTexts: [...s.userTexts, content],
      // 本轮实际附带的引用跟着落位，userRefs 与 userTexts 下标保持一致
      userRefs: [...s.userRefs, refs],
      pendingRefs: [],
      // 乐观插入一条"进行中"的结果占位
      outcomes: [
        ...s.outcomes,
        {
          messageId: null,
          streamId: null,
          reply: '',
          toolCalls: [],
          plan: null,
          form: null,
          references: [],
          citations: [],
          artifacts: [],
          provider: '',
          degraded: false,
          durationMs: 0,
          thinking: [],
          thinkingNotes: [],
          timeline: [],
          streaming: true,
        },
      ],
    }))

    try {
      const res = await api.agentChat(
        content, get().currentConversationId, get().mode, refs, get().autoCollab,
      )
      if (version !== requestVersion) return
      set((s) => {
        // 用真实结果替换最后那条占位
        const list = [...s.outcomes]
        const placeholderIdx = list.length - 1
        if (placeholderIdx >= 0 && list[placeholderIdx].status !== 'stopped') {
          list[placeholderIdx] = {
            messageId: res.messageId,
            streamId: res.streamId,
            reply: res.reply,
            toolCalls: res.toolCalls ?? [],
            plan: res.plan ?? null,
            form: res.form ?? null,
            references: res.references ?? [],
            // V27：REST 返回是权威值（WS 的过程事件里也可能带，但以这里为准）
            citations: res.citations ?? [],
            // 一轮 chat 永远不会直接产出 artifacts（写类工具此时只出 plan），
            // 真正有产物的是确认/异步完成那一步
            artifacts: [],
            provider: res.provider,
            degraded: res.degraded,
            durationMs: res.durationMs,
            // V35：思维链随 /chat 一起回来。此刻它还是"当前轮"，下一轮发起时它会随
            // 这条消息变成历史轮次 —— timeline 必须**同时**含推理与工具，
            // 否则切换到历史形态的瞬间思维链就没了（V26 的老行为）
            thinking: res.thinking ?? [],
            // V36：编排旁白同样随 /chat 回来，历史回放按 ts 与推理段合并排序
            thinkingNotes: res.thinkingNotes ?? [],
            timeline: timelineFromMessage({
              thinking: res.thinking,
              thinkingNotes: res.thinkingNotes,
              toolCalls: res.toolCalls ?? [],
            }),
            streaming: false,
          }
        }
        // 多轮「接续」（用户反馈 item 3）：本轮出了新清单 → 会话里旧的确认卡原地退役。
        // 后端创建新清单时已把旧任务置 CANCELLED；这里让旧卡立刻显式变成"已被取代"，
        // 避免"首轮确认按钮还在、但上下文已经走了"的错位感（会话中始终只有一张可确认卡）。
        if (res.plan) {
          const newSummary = res.plan.summary
          for (let i = 0; i < list.length - 1; i += 1) {
            const o = list[i]
            if (o.plan) {
              list[i] = {
                ...o,
                plan: null,
                notice: {
                  level: 'info',
                  text: `已被新的清单「${newSummary}」取代，未执行`,
                  taskId: o.plan.planId,
                },
              }
            }
          }
        }
        return {
          outcomes: list,
          thinking: false,
          activeStreamId: null,
          currentConversationId: res.conversationId,
          pendingPlan: res.plan ?? null,
          pendingTaskCount: res.pendingTaskCount ?? s.pendingTaskCount,
        }
      })      // 新会话首次问答后刷新列表（标题已确定）
      void get().fetchConversations()
    } catch {
      if (version !== requestVersion) return
      set((s) => {
        const list = [...s.outcomes]
        const idx = list.length - 1
        if (idx >= 0 && list[idx].streaming) {
          list[idx] = { ...list[idx], streaming: false, status:'failed', reply: '请求失败，请稍后重试。' }
        }
        return { outcomes: list, thinking: false }
      })
    }
  },

  stop: async () => {
    const streamId = get().activeStreamId
    if (!streamId) {
      return
    }
    flushStreamDeltas()
    requestVersion++
    stoppedStreams.add(streamId)
    if (stoppedStreams.size > 256) stoppedStreams.delete(stoppedStreams.values().next().value!)
    set(s => ({ thinking:false, activeStreamId:null, outcomes:s.outcomes.map((o,i) => i===s.outcomes.length-1 ? {...o,streaming:false,status:'stopped',reply:o.reply || '任务已停止，未继续生成内容。'}:o) }))
    try { await api.agentAbort(streamId) } catch { /* local stop remains effective */ }
  },

  applyStreamEvent: (e) => {
    if (stoppedStreams.has(e.streamId)) return
    const current = get()
    if (!current.outcomes.at(-1)?.streaming) return
    if (current.outcomes.some(o => o.streamId === e.streamId && !o.streaming)) return
    if (current.activeStreamId && current.activeStreamId !== e.streamId) return
    // 收官/中止类事件：先把字符流缓冲同步落地，否则最后一段正文会丢
    if (e.type === 'MESSAGE_DONE' || e.type === 'ERROR' || e.type === 'ABORTED') flushStreamDeltas()
    // 字符流走按帧合并的旁路（不进入下面的 set，避免一个 token 一次全列表重渲染）
    if (e.type === 'MESSAGE_DELTA') {
      acceptStreamDelta(e)
      return
    }
    set((s) => {
      /*
        协作事件的归属校验（C 批次）。
        过程事件按**用户**广播（后端 publish(username, ...)），所以别人/后台发起的协作会把
        AGENT_START 之类事件推到这个用户身上。若不加判断，这些事件会串进当前正在看的会话时间轴。
        判定依据用 conversationId 而不是 streamId：前者在发请求时就知道，
        后者要等 REST 返回，而事件可能先到（两者时序不保证）。
        后端已在这几类事件的 payload 上统一带 conversationId（见 AgentStreamEvent.withConversation）。
      */
      if (COLLAB_EVENT_TYPES.has(e.type)) {
        const evConv = e.payload?.['conversationId']
        if (evConv != null && s.currentConversationId != null
            && Number(evConv) !== Number(s.currentConversationId)) {
          return {}
        }
      }

      /*
        归属过滤（按 streamId）：过程事件按**用户**广播，且一个用户可能同时有
        别的轮次在跑（后台任务、另一个标签页确认后的补推）。带 streamId 且不属于
        当前这一轮的事件，只写进**那一轮消息自带的时间轴**，不碰实时区 ——
        否则上一轮的迟到事件会串进当前这轮的思维链。

        判据用"是否属于最后一条 outcome"，不引入第二个状态源：每轮的时间轴
        就存在它自己的 outcome.timeline 上（历史轮次读它，实时轮次读全局 timeline）。
      */
      const ownerIdx = e.streamId ? s.outcomes.findIndex((o) => o.streamId === e.streamId) : -1
      const liveIdx = s.outcomes.length - 1
      if (ownerIdx >= 0 && ownerIdx !== liveIdx) {
        const list = [...s.outcomes]
        const owned = list[ownerIdx].timeline
        const mergedOwned = mergeTimeline(owned, e)
        list[ownerIdx] = {
          ...list[ownerIdx],
          timeline: SEAL_THINKING_EVENTS.has(e.type) ? sealThinking(mergedOwned) : mergedOwned,
        }
        return { outcomes: list } as AgentState
      }

      const merged = mergeTimeline(s.timeline, e)
      const next: Partial<AgentState> = {
        // 推理段在该轮的推理结束时封口：模型一旦开始调工具/出正文，这一轮就不再"推理中"。
        // 只在真正有待封口的段时才重建数组，避免每个字符事件都刷新整条时间轴
        timeline: SEAL_THINKING_EVENTS.has(e.type) ? sealThinking(merged) : merged,
      }
      if (e.type === 'THINKING' || e.type === 'THINKING_DELTA' || e.type === 'TOOL_START') {
        next.activeStreamId = e.streamId
        next.thinking = true
      }
      if (e.type === 'MESSAGE_DONE' || e.type === 'ERROR' || e.type === 'ABORTED') {
        next.thinking = false
        next.deltaRound = null
      }
      // V27：依据随收官事件一起下发，用户不必等 REST 返回就能看到出处
      if (e.type === 'MESSAGE_DONE') {
        const cits = e.payload?.['citations']
        {
          const list = [...(next.outcomes ?? s.outcomes)]
          const idx = list.length - 1
          if (idx >= 0) {
            list[idx] = { ...list[idx],
              reply: typeof e.payload?.reply === 'string' ? e.payload.reply : list[idx].reply,
              streaming: false,
              citations: Array.isArray(cits) ? cits as AgentCitation[] : [],
            }
            next.outcomes = list
          }
        }
      }
      if (e.type === 'PLAN_READY' && e.payload) {
        // PLAN_READY 的 payload 即 plan.toJson()
        next.pendingPlan = e.payload as unknown as AgentActionPlan
      }
      // 字符流（MESSAGE_DELTA）在进入本 updater 之前已被 acceptStreamDelta 批量接管
      // （见文件上方的 deltaBuffer / flushStreamDeltas）
      return next as AgentState
    })
  },

  /**
   * 任务执行完成（/user/queue/agent，AGENT_TASK_DONE）。
   *
   * 体验约定（用户反馈）：**不再弹通知**，执行结果就落在对话框里。
   * - 清单卡还在 → 原地变成结果块（P4「已执行态原地变结果卡」，不留可点的僵尸按钮）
   * - 卡片不在（异步完成 / 历史轮次）→ 追加一条结果消息
   * taskId 去重：确认按钮的 REST 返回也会写同一条结果，避免重复出现两块。
   */
  applyTaskDone: (payload) => {
    const taskId = Number(payload['taskId'] ?? 0)
    const notice = noticeFromStatus(String(payload['status'] ?? ''), {
      message: payload['message'],
      summary: payload['summary'],
      affectedCount: payload['affectedCount'],
      failedCount: payload['failedCount'],
      artifacts: payload['artifacts'],
      detail: payload['detail'],
      taskId: taskId > 0 ? taskId : undefined,
      skipped: Array.isArray(payload['skipped']) ? payload['skipped'] as AgentSkippedItem[] : [],
    })
    set((s) => {
      // 已由 confirmPlan 的 REST 返回写入过同一任务的结果 → 不重复
      if (taskId > 0 && s.outcomes.some((o) => o.notice?.taskId === taskId)) return {}

      // 跨会话事件不落当前消息流（后端 AGENT_TASK_DONE 已带 conversationId）。
      // 典型场景：开了两个标签页，A 页确认的任务在 B 页也会收到同一事件 ——
      // 若不过滤，B 页会凭空多出一条"执行完成"的孤儿结果消息，且它没有任何用户提问，
      // userTexts 对齐也会被破坏。只刷计数与列表，不动消息流。
      // conversationId 缺失（旧后端）时保持旧行为，宁可在错误会话里多一条提示，也别吞掉真实结果。
      const eventConvId = payload['conversationId'] == null ? null : Number(payload['conversationId'])
      const belongsHere =
        eventConvId == null || !Number.isFinite(eventConvId) || s.currentConversationId === eventConvId
      if (!belongsHere) {
        return { pendingPlan: null, pendingTaskCount: Math.max(0, s.pendingTaskCount - 1) }
      }

      const hasCard = s.outcomes.some((o) => o.plan && o.plan.planId === taskId)
      const outcomes = hasCard
        ? s.outcomes.map((o) =>
            o.plan && o.plan.planId === taskId
              ? { ...o, plan: null, notice, artifacts: notice.artifacts ?? [] }
              : o,
          )
        : [
            ...s.outcomes,
            { ...emptyOutcome(), provider: 'system', notice, artifacts: notice.artifacts ?? [] },
          ]
      return {
        outcomes,
        // 追加型结果消息没有对应的用户提问，补空串保持 userTexts 与 outcomes 下标对齐
        userTexts: hasCard ? s.userTexts : [...s.userTexts, ''],
        // userRefs 与 userTexts 严格同步 —— 少推一个，后面所有轮次的引用就整体错位
        userRefs: hasCard ? s.userRefs : [...s.userRefs, []],
        pendingPlan: null,
        pendingTaskCount: Math.max(0, s.pendingTaskCount - 1),
      }
    })
    void get().fetchConversations()
  },

  confirmPlan: async (planId, confirmToken) => {
    // V26：令牌随卡传入而非读全局 pendingPlan——历史恢复的确认卡（msg.plan）也要能确认
    // mode 一并带上：只读档位下后端会在可见性断言处拦下（前端也已禁用按钮，这里是第二道）
    const task = await api.confirmAgentTask(planId, confirmToken, get().mode)
    const r = (task?.result ?? {}) as Record<string, unknown>
    const notice = noticeFromStatus(String(task?.status ?? ''), {
      summary: task?.summary,
      message: r['message'],
      affectedCount: r['affectedCount'],
      failedCount: r['failedCount'],
      // 产物优先取顶层 artifacts（与 WS 同构）；老库任务只有 result.references，交给 sanitize 兜底
      artifacts: (task?.artifacts?.length ? task.artifacts : r['references']) ?? [],
      detail: r['detail'],
      taskId: planId,
      skipped: Array.isArray(r['skipped']) ? r['skipped'] as AgentSkippedItem[] : get().outcomes.find(o => o.plan?.planId === planId)?.plan?.skipped,
    })
    // 原位把确认卡换成结果块：既回答"确认后到底做了什么"，也不留下还能再点的僵尸按钮
    set((s) => ({
      outcomes: s.outcomes.map((o) =>
        o.plan && o.plan.planId === planId
          ? { ...o, plan: null, notice, artifacts: notice.artifacts ?? [] }
          : o,
      ),
      pendingPlan: null,
      pendingTaskCount: Math.max(0, s.pendingTaskCount - 1),
    }))
    void get().fetchConversations()
  },

  cancelPlan: async (planId) => {
    await api.cancelAgentTask(planId)
    set((s) => ({
      outcomes: s.outcomes.map((o) =>
        o.plan && o.plan.planId === planId
          ? { ...o, plan: null, notice: { level: 'info', text: '已取消该操作，未执行写入', taskId: planId, skipped: o.plan.skipped } }
          : o,
      ),
      pendingPlan: null,
      pendingTaskCount: Math.max(0, s.pendingTaskCount - 1),
    }))
    void get().fetchConversations()
  },

  dismissPlan: () => set({ pendingPlan: null }),

  submitForm: async (taskId, values) => {
    const res = await api.submitTaskInput(taskId, values, get().mode)
    set((s) => {
      const list = s.outcomes.map((o) => {
        if (o.form?.taskId !== taskId) return o
        if (res.status === 'PLAN' && res.plan) {
          // 参数已齐：表单卡原位变为确认卡（走既有 D2 门禁）
          return { ...o, form: null, plan: res.plan }
        }
        if (res.status === 'FORM' && res.form) {
          return { ...o, form: { ...res.form, taskId } }
        }
        if (res.status === 'CANCELLED') {
          return { ...o, form: null }
        }
        return o
      })
      const next: Partial<AgentState> = { outcomes: list }
      if (res.status === 'PLAN' && res.plan) {
        next.pendingPlan = res.plan
        next.pendingTaskCount = s.pendingTaskCount + 1
      }
      return next as AgentState
    })
    if (res.status === 'PLAN' || res.status === 'CANCELLED') void get().fetchConversations()
    return res
  },

  reset: () => {
    requestVersion++
    flushStreamDeltas()
    stoppedStreams.clear()
    set({
      conversations: [],
      conversationsLoading: false,
      currentConversationId: null,
      outcomes: [],
      messagesLoading: false,
      userTexts: [],
      timeline: [],
      activeStreamId: null,
      thinking: false,
      deltaRound: null,
      mode: 'standard',
      pendingRefs: [],
      userRefs: [],
      pendingPlan: null,
      pendingTaskCount: 0,
    })
  },
}))
