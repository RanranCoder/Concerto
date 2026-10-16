import type { AgentThinkingNote, AgentThinkingStep, AgentToolCallEntry } from '@/api/agent'
import type { TimelineItem } from '@/stores/agent'

/**
 * 思维链展示的纯函数层。
 *
 * <p>为什么不写在组件里：这些规则（耗时口径、折叠标题、分段、回放顺序、去重判据）
 * 全部是**可单测的纯逻辑**，而它们正是"同一屏里数字/格式各不相同"这类问题的根源 ——
 * 放在组件里就只能靠肉眼看，放在这里可以断言。
 */

/**
 * 耗时格式化 —— **同一过程区只允许一种口径**。
 *
 * <p>此前折叠摘要用秒（{@code 耗时 3.4s}）、行内用毫秒原文（{@code 1234ms}）、
 * 团队面板又是毫秒：同一个数字三种写法，用户要心算。
 * 规则：<1s 用毫秒（短步骤看得到差别），<60s 用秒一位小数，更长用 m+s。
 */
export function formatDuration(ms?: number | null): string {
  if (ms == null || !Number.isFinite(ms) || ms <= 0) return ''
  if (ms < 1000) return `${Math.round(ms)}ms`
  const seconds = ms / 1000
  if (seconds < 60) return `${seconds.toFixed(1)}s`
  const minutes = Math.floor(seconds / 60)
  const rest = Math.round(seconds - minutes * 60)
  return `${minutes}m${rest}s`
}

/**
 * 推理原文 → 折叠态标题。
 *
 * <p>模型的推理常常以换行/markdown 记号开头（{@code "1. **先确认**…"}），
 * 直接截前 40 字会显示成一堆符号，所以先去记号再取首个非空行。
 */
export function thinkingHeadline(text: string, max = 42): string {
  const firstLine = (text ?? '')
    .split('\n')
    .map((line) => line.trim())
    .find((line) => line.length > 0)
  const plain = (firstLine ?? '')
    .replace(/^[#>*\-\d.\s]+/, '')
    .replace(/\*\*/g, '')
    .replace(/`/g, '')
    .trim()
  if (!plain) return '模型推理'
  return plain.length > max ? `${plain.slice(0, max)}…` : plain
}

/** 总耗时：优先用事件时间戳跨度（真实墙钟），没有时间戳时退化为各步耗时之和 */
export function totalDurationMs(items: TimelineItem[]): number {
  const stamped = items.filter((i) => i.ts > 0)
  if (stamped.length >= 2) {
    return Math.max(0, stamped[stamped.length - 1].ts - stamped[0].ts)
  }
  return items.reduce((sum, i) => sum + (i.durationMs ?? 0), 0)
}

/**
 * 团队面板是否会渲染。
 *
 * <p>判据与 `LingsiTeamPanel` 的渲染条件**必须同一份**（它也是"有专家或有审查才渲染"）：
 * 否则过程区会以为面板在、于是把 agent 行藏掉，而面板其实没渲染 —— 信息直接消失。
 * 两端都调这个函数，就不会出现这种漂移。
 */
export function hasTeamPanel(items: TimelineItem[]): boolean {
  const agents = new Set<string>()
  let reviews = 0
  for (const item of items) {
    if (item.kind === 'agent' && item.agent) agents.add(item.agent)
    else if (item.kind === 'review') reviews += 1
  }
  return agents.size > 0 || reviews > 0
}

export interface TimelineGroup {
  key: string
  /** 推理段的 LLM 轮次；旁白/执行段没有 */
  round?: number
  /**
   * 段类型 —— 决定小标题（编排 / 第 N 段推理 / 执行）。
   * 判据是**首行是否带推理原文**：模型推理必有 detail，编排旁白只有一句话。
   */
  kind: 'narration' | 'reasoning' | 'exec'
  items: TimelineItem[]
}

/**
 * 按"推理段"分组：一段推理 + 它之后产生的动作算一组。
 *
 * <p>为什么要分组：一次回答里 LLM 可能被调用多轮（调用工具后再想一次），
 * 平铺成一条长列表后，"哪几步属于同一次思考"完全看不出来。
 */
export function groupTimeline(items: TimelineItem[]): TimelineGroup[] {
  const groups: TimelineGroup[] = []
  for (const item of items) {
    const last = groups[groups.length - 1]
    if (item.kind === 'thinking') {
      const kind: TimelineGroup['kind'] = item.detail ? 'reasoning' : 'narration'
      const sameGroup =
        last != null &&
        last.kind === kind &&
        last.items.every((i) => i.kind === 'thinking') &&
        (kind !== 'reasoning' || last.round === item.round)
      if (sameGroup) {
        last.items.push(item)
      } else {
        groups.push({ key: `g-${item.key}`, round: item.round, kind, items: [item] })
      }
    } else if (last) {
      last.items.push(item)
    } else {
      groups.push({ key: `g-${item.key}`, kind: 'exec', items: [item] })
    }
  }
  return groups
}

/** 折叠态摘要（进行中含"正在…"，结束含推理段数/能力数/总耗时） */
export function summarizeTimeline(items: TimelineItem[], active?: boolean): string {
  const thinkingCount = items.filter((i) => i.kind === 'thinking' && i.detail).length
  const toolCount = items.filter((i) => i.kind === 'tool' || i.kind === 'agent').length
  const hasError = items.some((i) => i.kind === 'error' || i.error)

  if (active) {
    const last = items[items.length - 1]
    if (!last) return '正在思考…'
    if (last.kind === 'thinking') {
      return last.detail ? `正在推理 · ${last.label}` : last.label
    }
    if (last.error) return `执行出错 · ${last.label}`
    return last.completed ? last.label : `${last.label}…`
  }
  if (hasError) return '过程中出现异常'

  const parts: string[] = []
  if (thinkingCount) parts.push(`推理 ${thinkingCount} 段`)
  if (toolCount) parts.push(`调用 ${toolCount} 项能力`)
  const total = totalDurationMs(items)
  if (total) parts.push(`耗时 ${formatDuration(total)}`)
  return parts.length ? `已完成 · ${parts.join(' · ')}` : '已完成'
}

/** 交错排序的候选行：`own` 只参与内部排序，不写回 `item.ts` 之外的任何展示 */
interface StagedRow {
  item: TimelineItem
  /** 类别：旁白 / 模型推理 / 执行（工具等）。缺时间戳时按类别就近继承 */
  category: 'note' | 'reasoning' | 'exec'
  /** 自身时间戳（epoch ms）；0 表示这一行没有时间戳（老数据） */
  own: number
}

/**
 * 把三类行按真实时间交错（V36 收尾）。
 *
 * <p>规则（每条都对应一种真实数据形态）：
 * 1. **三类都没有时间戳** → 原样返回基线顺序，一个元素都不动（老消息零变化）
 * 2. 有自身时间戳 → 用自身时间戳
 * 3. 自身缺、同类有 → **继承同类最近的已知时间点**（先前向、再后向）：
 *    它因此落在同类相邻行旁边，而不会漂到别的类别之间。
 *    真实形态：V35 期的消息有推理段 `startedAt`、工具行还没有 `ts` → 工具仍排在推理之后
 * 4. 排序键 `(有效时间戳, 基线序号)`：**同一毫秒内保持基线相对顺序**，
 *    刷新前后不会跳；`Array.sort` 本身稳定，再显式用序号兜底，与引擎实现无关
 * 5. 有效时间戳仍为空（该类别整体没有时间戳）→ 排在所有已知时间点之后，
 *    与规则 1 的基线顺序一致
 */
function interleaveByTime(rows: StagedRow[]): StagedRow[] {
  if (!rows.some((row) => row.own > 0)) return rows

  const effective = rows.map((row) => row.own)

  // 先前向继承：同类最近的前一个已知时间点
  const prevOfCategory = new Map<StagedRow['category'], number>()
  rows.forEach((row, index) => {
    if (row.own > 0) prevOfCategory.set(row.category, row.own)
    else if (prevOfCategory.has(row.category)) effective[index] = prevOfCategory.get(row.category) as number
  })

  // 再后向继承：同类在它之后才有已知时间点（只补仍然为空的那些）
  const nextOfCategory = new Map<StagedRow['category'], number>()
  for (let index = rows.length - 1; index >= 0; index -= 1) {
    const row = rows[index]
    if (row.own > 0) nextOfCategory.set(row.category, row.own)
    else if (effective[index] === 0 && nextOfCategory.has(row.category)) {
      effective[index] = nextOfCategory.get(row.category) as number
    }
  }

  const order = rows.map((_, index) => index)
  order.sort((a, b) => {
    const keyA = effective[a] > 0 ? effective[a] : Number.MAX_SAFE_INTEGER
    const keyB = effective[b] > 0 ? effective[b] : Number.MAX_SAFE_INTEGER
    return keyA === keyB ? a - b : keyA - keyB
  })
  return order.map((index) => rows[index])
}

/**
 * 由落库数据回放一条消息的完整过程（V35 推理 + V36 旁白 + 工具行）。
 *
 * <p><b>顺序 = 真实时间交错</b>：三类行各有自己的时间戳
 * （旁白 `thinkingNotes[].ts`、推理 `thinking[].startedAt`、工具 `toolCalls[].ts`，
 * 都是服务端 epoch ms），按它们排序才是"刷新前后同一条时间轴"。
 *
 * <p><b>缺时间戳的行不会被丢掉、也不会乱序</b>：见 {@link interleaveByTime} 的五条规则 ——
 * 老消息（工具行没有 `ts`）回落到本次改动前的分组顺序（旁白 → 推理 → 工具），
 * 并且同毫秒内保持基线顺序稳定。
 */
export function timelineFromMessage(msg: {
  thinking?: AgentThinkingStep[] | null
  thinkingNotes?: AgentThinkingNote[] | null
  toolCalls?: AgentToolCallEntry[] | null
}): TimelineItem[] {
  let seq = 0
  /**
   * 基线顺序 = 本次改动前的分组顺序（旁白 → 推理 → 工具）。
   *
   * <p>它同时是**缺时间戳时的最终顺序**：老消息（工具行没有 `ts`、更早的连推理段都没有）
   * 必须一字不动地保持原样 —— 否则"升级一次前端，历史对话顺序全变了"。
   */
  const staged: StagedRow[] = []

  // 编排旁白：只有一句标签、没有 detail —— 与模型推理行在同一套渲染里天然区分
  for (const [index, note] of (msg.thinkingNotes ?? []).entries()) {
    const text = (note?.note ?? '').trim()
    if (!text) continue
    seq += 1
    staged.push({
      category: 'note',
      own: note.ts ?? 0,
      item: {
        key: `note-${index}-${seq}`,
        seq,
        kind: 'thinking',
        label: text,
        durationMs: note.durationMs ?? 0,
        completed: true,
        ts: note.ts ?? 0,
      },
    })
  }

  const steps = [...(msg.thinking ?? [])].sort((a, b) => (a.round ?? 0) - (b.round ?? 0))
  for (const step of steps) {
    const text = (step.text ?? '').trim()
    if (!text) continue
    seq += 1
    staged.push({
      category: 'reasoning',
      own: step.startedAt ?? 0,
      item: {
        key: `think-${step.round ?? 0}-${seq}`,
        seq,
        kind: 'thinking',
        round: step.round,
        label: thinkingHeadline(text),
        detail: text,
        durationMs: step.durationMs ?? 0,
        completed: true,
        ts: step.startedAt ?? 0,
      },
    })
  }

  for (const call of msg.toolCalls ?? []) {
    seq += 1
    staged.push({
      category: 'exec',
      // 后端给的是"调用开始时刻"（now - cost），不是落库时刻
      own: call.ts ?? 0,
      item: {
        key: `tool-${seq}`,
        seq,
        kind: 'tool',
        tool: call.tool,
        label: call.label || call.tool,
        status: call.status,
        durationMs: call.durationMs ?? 0,
        error: call.error,
        // 只有后端给了（normal 级）才有值；null/undefined 都表示不可下钻
        argsJson: call.argsJson ?? undefined,
        completed: true,
        ts: call.ts ?? 0,
      },
    })
  }

  const out = interleaveByTime(staged).map((row) => row.item)
  return out
}
