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
  AgentToolCallEntry,
  TaskInputResult,
} from '@/api/agent'
import * as api from '@/api/agent'

/** 过程区时间轴的一行（由 AgentStreamEvent 归并而成） */
export interface TimelineItem {
  /** TOOL_START 先入，TOOL_END 到位后合并为 completed */
  key: string
  seq: number
  kind: 'thinking' | 'tool' | 'plan' | 'error' | 'aborted'
  tool?: string
  label: string
  status?: string
  durationMs?: number
  error?: string
  note?: string
  completed: boolean
  ts: number
}

/** 结果区的一条助手消息 */
export interface OutcomeMessage {
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
 * - THINKING → 追加一行
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
    set({ conversationsLoading: true })
    try {
      const res = await api.listConversations(1, 50)
      set({
        conversations: res?.records ?? [],
        pendingTaskCount: res?.pendingTaskCount ?? 0,
      })
    } catch {
      // http 拦截器已提示
    } finally {
      set({ conversationsLoading: false })
    }
  },

  selectConversation: async (id) => {
    set({
      currentConversationId: id,
      outcomes: [],
      timeline: [],
      pendingPlan: null,
      userTexts: [],
      userRefs: [],
      deltaRound: null,
      // 切会话顺手清掉待附加引用：它们是针对"接下来要问什么"选的，跨会话残留只会误导
      pendingRefs: [],
    })
    if (id == null) return
    set({ messagesLoading: true })
    try {
      const res = await api.listMessages(id, 1, 100)
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
          timeline: timelineFromToolCalls(m.toolCalls ?? []),
          streaming: false,
        })
      }
      set({ outcomes, userTexts: users, userRefs })
    } catch {
      // http 拦截器已提示
    } finally {
      set({ messagesLoading: false })
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

    // 附加上下文必须在 set 之前取走：set 里会清空 pendingRefs，之后再读就是空数组
    const refs = get().pendingRefs

    // 立刻清空过程区并进入"思考中"——本地先给即时反馈，
    // 真正的 THINKING 事件随后到达（TTFE P95 < 800ms）
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
          timeline: [],
          streaming: true,
        },
      ],
    }))

    try {
      const res = await api.agentChat(content, get().currentConversationId, get().mode, refs)
      set((s) => {
        // 用真实结果替换最后那条占位
        const list = [...s.outcomes]
        const placeholderIdx = list.length - 1
        if (placeholderIdx >= 0 && list[placeholderIdx].streaming) {
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
            // 本轮自己的时间轴由 toolCalls 派生：下一轮发起后它成为"历史轮次"仍有过程区
            timeline: timelineFromToolCalls(res.toolCalls ?? []),
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
      if (!get().currentConversationId || get().conversations.every((c) => c.id !== res.conversationId)) {
        void get().fetchConversations()
      }
    } catch {
      set((s) => {
        const list = [...s.outcomes]
        const idx = list.length - 1
        if (idx >= 0 && list[idx].streaming) {
          list[idx] = { ...list[idx], streaming: false, reply: '请求失败，请稍后重试。' }
        }
        return { outcomes: list, thinking: false }
      })
    }
  },

  stop: async () => {
    const streamId = get().activeStreamId
    if (!streamId) {
      set({ thinking: false })
      return
    }
    try {
      await api.agentAbort(streamId)
    } catch {
      // 中止失败也让前端恢复可交互状态
    }
    set({ thinking: false })
  },

  applyStreamEvent: (e) => {
    set((s) => {
      const next: Partial<AgentState> = {
        timeline: mergeTimeline(s.timeline, e),
      }
      if (e.type === 'THINKING' || e.type === 'TOOL_START') {
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
        if (Array.isArray(cits) && cits.length > 0) {
          const list = [...(next.outcomes ?? s.outcomes)]
          const idx = list.length - 1
          if (idx >= 0) {
            list[idx] = { ...list[idx], citations: cits as AgentCitation[] }
            next.outcomes = list
          }
        }
      }
      if (e.type === 'PLAN_READY' && e.payload) {
        // PLAN_READY 的 payload 即 plan.toJson()
        next.pendingPlan = e.payload as unknown as AgentActionPlan
      }
      // 字符流（UI v2）：增量写进最后那条"进行中"的占位回复。
      // REST 返回会用权威全文覆盖它，这里只是让用户在等待期就能读到内容。
      if (e.type === 'MESSAGE_DELTA') {
        const round = Number(e.payload?.['round'] ?? 0)
        const text = String(e.payload?.['text'] ?? '')
        if (text) {
          const reset = s.deltaRound !== round
          const list = [...s.outcomes]
          const idx = list.length - 1
          if (idx >= 0 && list[idx].streaming) {
            list[idx] = { ...list[idx], reply: (reset ? '' : list[idx].reply) + text }
            next.outcomes = list
          }
          next.deltaRound = round
          next.activeStreamId = e.streamId
          next.thinking = true
        }
      }
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
  },

  cancelPlan: async (planId) => {
    await api.cancelAgentTask(planId)
    set((s) => ({
      outcomes: s.outcomes.map((o) =>
        o.plan && o.plan.planId === planId
          ? { ...o, plan: null, notice: { level: 'info', text: '已取消该操作', taskId: planId } }
          : o,
      ),
      pendingPlan: null,
      pendingTaskCount: Math.max(0, s.pendingTaskCount - 1),
    }))
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
    return res
  },

  reset: () =>
    set({
      conversations: [],
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
    }),
}))
