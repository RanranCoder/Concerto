import { describe, expect, it } from 'vitest'
import type { AgentStreamEvent } from '@/api/agent'
import { flushStreamDeltas, useAgentStore, type OutcomeMessage } from '@/stores/agent'
import {
  formatDuration,
  groupTimeline,
  hasTeamPanel,
  summarizeTimeline,
  thinkingHeadline,
  timelineFromMessage,
  totalDurationMs,
} from './thinking'

/**
 * 思维链展示的纯逻辑回归。
 *
 * <p>为什么值得单测：这些规则过去都是散在组件里的"肉眼约定"，
 * 结果就是同一个数字在摘要里写秒、在行内写毫秒，标题与正文各写一份。
 * 把它们抽成纯函数后，口径不一致会直接变成失败用例，而不是用户读到一屏不一致的数字。
 */

describe('formatDuration：同一区块只允许一种耗时口径', () => {
  it('小于 1s 用毫秒，1s 以上用秒（一位小数）', () => {
    expect(formatDuration(320)).toBe('320ms')
    expect(formatDuration(999)).toBe('999ms')
    expect(formatDuration(1000)).toBe('1.0s')
    expect(formatDuration(3450)).toBe('3.5s')
  })

  it('超过 1 分钟用 m+s，避免出现 123.4s 这种要心算的写法', () => {
    expect(formatDuration(62000)).toBe('1m2s')
  })

  it('空值/0/负数一律返回空串（调用方据此不渲染，而不是渲染 0ms）', () => {
    expect(formatDuration(0)).toBe('')
    expect(formatDuration(undefined)).toBe('')
    expect(formatDuration(null)).toBe('')
    expect(formatDuration(-5)).toBe('')
  })
})

describe('thinkingHeadline：推理原文 → 折叠标题', () => {
  it('取首个非空行，去掉 markdown 记号与序号', () => {
    expect(thinkingHeadline('1. **先确认活动时间**\n再排班')).toBe('先确认活动时间')
    expect(thinkingHeadline('### 检查成员名单')).toBe('检查成员名单')
  })

  it('空文本回退到固定文案，不返回空标题', () => {
    expect(thinkingHeadline('')).toBe('模型推理')
    expect(thinkingHeadline('\n  \n')).toBe('模型推理')
  })

  it('过长时截断并加省略号', () => {
    const long = '甲'.repeat(100)
    expect(thinkingHeadline(long, 10)).toBe(`${'甲'.repeat(10)}…`)
  })
})

describe('totalDurationMs：优先真实墙钟跨度', () => {
  it('有时间戳时用首末跨度（含工具等待），而不是各步耗时之和', () => {
    const items = [
      { key: 'a', seq: 1, kind: 'thinking' as const, label: 'x', completed: true, ts: 1000 },
      { key: 'b', seq: 2, kind: 'tool' as const, label: 'y', completed: true, ts: 4000, durationMs: 100 },
    ]
    expect(totalDurationMs(items)).toBe(3000)
  })

  it('没有时间戳时退化为各步耗时之和（历史回放的老数据）', () => {
    const items = [
      { key: 'a', seq: 1, kind: 'tool' as const, label: 'x', completed: true, ts: 0, durationMs: 200 },
      { key: 'b', seq: 2, kind: 'tool' as const, label: 'y', completed: true, ts: 0, durationMs: 300 },
    ]
    expect(totalDurationMs(items)).toBe(500)
  })
})

describe('summarizeTimeline：折叠态一眼可读', () => {
  const thinking = {
    key: 't1', seq: 1, kind: 'thinking' as const, label: '先确认活动', detail: '先确认活动', completed: true, ts: 0,
  }
  const tool = {
    key: 'k1', seq: 2, kind: 'tool' as const, label: '查询活动', completed: true, ts: 0, durationMs: 500,
  }

  it('进行中显示"正在推理 + 当段标题"，而不是干巴巴的"正在思考"', () => {
    expect(summarizeTimeline([thinking], true)).toBe('正在推理 · 先确认活动')
  })

  it('结束后给出推理段数 / 能力数 / 总耗时', () => {
    expect(summarizeTimeline([thinking, tool])).toBe('已完成 · 推理 1 段 · 调用 1 项能力 · 耗时 500ms')
  })

  it('有失败项时明确说明异常（不自动折叠的前提）', () => {
    expect(summarizeTimeline([thinking, { ...tool, error: '超时' }])).toBe('过程中出现异常')
  })

  it('空列表进行中不崩（首字延迟窗口）', () => {
    expect(summarizeTimeline([], true)).toBe('正在思考…')
  })
})

describe('groupTimeline：按 LLM 轮次分段', () => {
  it('同轮推理归一段，之后的动作跟在该段内', () => {
    const items = [
      { key: 't0', seq: 1, kind: 'thinking' as const, round: 0, label: '想第一次', detail: '想第一次', completed: true, ts: 0 },
      { key: 'k0', seq: 2, kind: 'tool' as const, label: '查名单', completed: true, ts: 0 },
      { key: 't1', seq: 3, kind: 'thinking' as const, round: 1, label: '想第二次', detail: '想第二次', completed: true, ts: 0 },
    ]
    const groups = groupTimeline(items)
    expect(groups).toHaveLength(2)
    expect(groups[0].round).toBe(0)
    expect(groups[0].items.map((i) => i.key)).toEqual(['t0', 'k0'])
    expect(groups[1].items.map((i) => i.key)).toEqual(['t1'])
  })
})

describe('timelineFromMessage：历史回放同时含推理与工具', () => {
  it('思维链段按 round 排序在前，工具行随后', () => {
    const built = timelineFromMessage({
      thinking: [
        { round: 1, text: '第二段推理', durationMs: 20 },
        { round: 0, text: '第一段推理', durationMs: 10 },
      ],
      toolCalls: [{ tool: 'query', label: '查询活动', status: 'SUCCESS', durationMs: 30, write: false, sensitivity: 'NORMAL' }],
    })
    expect(built.map((i) => i.key)).toEqual(['think-0-1', 'think-1-2', 'tool-3'])
    expect(built[0].detail).toBe('第一段推理')
    expect(built[1].label).toBe('第二段推理')
    expect(built[2].kind).toBe('tool')
  })

  it('老数据（没有 thinking）行为不变：只有工具行', () => {
    const built = timelineFromMessage({ toolCalls: [{ tool: 'x', label: 'X', status: 'SUCCESS', durationMs: 5, write: false, sensitivity: 'NORMAL' }] })
    expect(built).toHaveLength(1)
    expect(built[0].kind).toBe('tool')
  })

  /* ------------------------------- V36：编排旁白 + 入参下钻 ------------------------------- */

  it('旁白与推理段按 ts 还原真实交错顺序（旁白 → 推理 → 旁白）', () => {
    const built = timelineFromMessage({
      thinkingNotes: [
        { note: '正在理解你的问题…', ts: 100, durationMs: 50 },
        { note: '已找到 2 条相关依据，正在整理…', ts: 300 },
      ],
      thinking: [{ round: 0, text: '先确认活动时间', startedAt: 200, durationMs: 40 }],
      toolCalls: null,
    })
    expect(built.map((i) => i.label)).toEqual([
      '正在理解你的问题…',
      '先确认活动时间',
      '已找到 2 条相关依据，正在整理…',
    ])
    expect(built[0].detail, '旁白没有推理原文 —— 前端据此渲染成不可展开的一行').toBeUndefined()
    expect(built[0].durationMs).toBe(50)
    expect(built[2].durationMs, '最后一条旁白没有 durationMs，不渲染耗时 0ms').toBe(0)
  })

  it('没有时间戳的老数据保持插入顺序（稳定排序）', () => {
    const built = timelineFromMessage({
      thinking: [{ round: 0, text: '第一段' }, { round: 1, text: '第二段' }],
    })
    expect(built.map((i) => i.label)).toEqual(['第一段', '第二段'])
  })

  it('入参下钻只认后端给的 argsJson：null 就是不可下钻', () => {
    const built = timelineFromMessage({
      toolCalls: [
        { tool: 'query', label: '查询活动', status: 'SUCCESS', durationMs: 10, write: false, sensitivity: 'NORMAL', argsJson: '{"activityId":7}' },
        { tool: 'queryParty', label: '查党员', status: 'SUCCESS', durationMs: 10, write: false, sensitivity: 'SENSITIVE', argsJson: null },
      ],
    })
    expect(built[0].argsJson).toBe('{"activityId":7}')
    expect(built[1].argsJson, 'sensitive 级后端给 null → 前端不猜、不给入口').toBeUndefined()
  })

  it('hasTeamPanel 与团队面板渲染条件同源（否则过程区会把信息藏掉）', () => {
    const agent = { key: 'a', seq: 1, kind: 'agent' as const, agent: 'retrieval', label: '检索专家', completed: true, ts: 0 }
    expect(hasTeamPanel([agent])).toBe(true)
    expect(hasTeamPanel([{ key: 'r', seq: 2, kind: 'review' as const, verdict: 'PASS', label: 'PASS', completed: true, ts: 0 }])).toBe(true)
    expect(hasTeamPanel([{ key: 't', seq: 3, kind: 'thinking' as const, label: '推理', detail: '推理', completed: true, ts: 0 }])).toBe(false)
  })

  it('groupTimeline 用"有没有 detail"区分编排旁白与模型推理段', () => {
    const groups = groupTimeline([
      { key: 'n0', seq: 1, kind: 'thinking' as const, label: '正在理解你的问题…', completed: true, ts: 0 },
      { key: 't0', seq: 2, kind: 'thinking' as const, round: 0, label: '想', detail: '想', completed: true, ts: 0 },
      { key: 'k0', seq: 3, kind: 'tool' as const, label: '查名单', completed: true, ts: 0 },
      { key: 'n1', seq: 4, kind: 'thinking' as const, label: '已找到 2 条相关依据，正在整理…', completed: true, ts: 0 },
    ])
    expect(groups.map((g) => g.kind)).toEqual(['narration', 'reasoning', 'narration'])
    expect(groups[1].items.map((i) => i.key), '工具行跟随其所属的推理段').toEqual(['t0', 'k0'])
  })
})

/* ------------------------------- 三类行按真实时间交错（V36 收尾） ------------------------------- */

describe('timelineFromMessage：工具行 / 推理段 / 旁白行按 ts 交错', () => {
  const tool = (label: string, ts?: number) => ({
    tool: label,
    label,
    status: 'SUCCESS',
    durationMs: 10,
    write: false,
    sensitivity: 'NORMAL',
    ts,
  })

  it('三类行乱序输入 → 输出按各自时间戳交错（不再按类别分组拼接）', () => {
    const built = timelineFromMessage({
      thinkingNotes: [{ note: '正在理解你的问题…', ts: 300 }],
      thinking: [{ round: 0, text: '第 0 段推理', startedAt: 200 }],
      toolCalls: [tool('查询活动', 100)],
    })
    expect(built.map((i) => i.label)).toEqual(['查询活动', '第 0 段推理', '正在理解你的问题…'])
    expect(built.map((i) => i.kind)).toEqual(['tool', 'thinking', 'thinking'])
  })

  it('工具行缺 ts（本次契约之前落库的老数据）→ 回落到分组顺序，不破坏推理段位置', () => {
    const built = timelineFromMessage({
      thinkingNotes: [{ note: '正在理解你的问题…', ts: 100 }],
      thinking: [{ round: 0, text: '推理原文', startedAt: 200 }],
      toolCalls: [tool('查询活动')],
    })
    expect(built.map((i) => i.label)).toEqual(['正在理解你的问题…', '推理原文', '查询活动'])
  })

  it('三类都没有时间戳 → 完全保持改动前的分组顺序（老消息零变化）', () => {
    const built = timelineFromMessage({
      thinkingNotes: [{ note: '旁白', ts: 0 }],
      thinking: [{ round: 1, text: '第二段' }, { round: 0, text: '第一段' }],
      toolCalls: [tool('查询活动')],
    })
    expect(built.map((i) => i.label)).toEqual(['旁白', '第一段', '第二段', '查询活动'])
  })

  it('同一毫秒内保持基线相对顺序（刷新前后不跳）', () => {
    const built = timelineFromMessage({
      thinkingNotes: [
        { note: '旁白甲', ts: 1000 },
        { note: '旁白乙', ts: 1000 },
      ],
      toolCalls: [tool('查询活动', 1000)],
    })
    expect(built.map((i) => i.label)).toEqual(['旁白甲', '旁白乙', '查询活动'])
  })

  it('同类内混有/缺 ts：缺的那条紧跟自己类别的前一个已知时间点', () => {
    const built = timelineFromMessage({
      thinking: [{ round: 0, text: '推理', startedAt: 100 }],
      toolCalls: [tool('工具甲', 200), tool('工具乙'), tool('工具丙', 300)],
    })
    expect(built.map((i) => i.label)).toEqual(['推理', '工具甲', '工具乙', '工具丙'])
  })

  it('交错后 totalDurationMs 用真实墙钟跨度（不再只算工具耗时之和）', () => {
    const built = timelineFromMessage({
      thinkingNotes: [{ note: '正在理解你的问题…', ts: 1000 }],
      thinking: [{ round: 0, text: '推理', startedAt: 2000 }],
      toolCalls: [tool('查询活动', 3000)],
    })
    expect(totalDurationMs(built)).toBe(2000)
  })
})

/* ------------------------------- 字符流按帧合并（V36 性能） ------------------------------- */

describe('MESSAGE_DELTA 批量落地', () => {
  const ev = (seq: number, type: string, payload: Record<string, unknown>): AgentStreamEvent =>
    ({ streamId: 's1', seq, type, payload, ts: 1000 + seq } as AgentStreamEvent)

  const streamingOutcome = (): OutcomeMessage =>
    ({
      messageId: null,
      streamId: 's1',
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
    }) as unknown as OutcomeMessage

  it('不逐条写状态：先缓冲，flush 后一次写入', () => {
    useAgentStore.setState({ outcomes: [streamingOutcome()], deltaRound: null, timeline: [] })
    const { applyStreamEvent } = useAgentStore.getState()
    applyStreamEvent(ev(1, 'MESSAGE_DELTA', { round: 0, text: '前半' }))
    applyStreamEvent(ev(2, 'MESSAGE_DELTA', { round: 0, text: '后半' }))
    expect(useAgentStore.getState().outcomes[0].reply, '一帧之内不写状态').toBe('')

    flushStreamDeltas()
    expect(useAgentStore.getState().outcomes[0].reply).toBe('前半后半')
  })

  it('换 round 先落地再重置：重试语义不会被跨帧合并破坏', () => {
    useAgentStore.setState({ outcomes: [streamingOutcome()], deltaRound: null, timeline: [] })
    const { applyStreamEvent } = useAgentStore.getState()
    applyStreamEvent(ev(1, 'MESSAGE_DELTA', { round: 0, text: '半截旧文' }))
    applyStreamEvent(ev(2, 'MESSAGE_DELTA', { round: 1000, text: '重试后的完整文' }))
    flushStreamDeltas()
    expect(useAgentStore.getState().outcomes[0].reply, 'round 变化即清空重拼').toBe('重试后的完整文')
  })
})

describe('applyStreamEvent：推理增量拼进同一段', () => {
  const ev = (seq: number, type: string, payload: Record<string, unknown>): AgentStreamEvent =>
    ({ streamId: 's1', seq, type, payload, ts: 1000 + seq } as AgentStreamEvent)

  it('同 round 的多个片段合成一段（而不是每个片段一行）', () => {
    useAgentStore.setState({ timeline: [] })
    const { applyStreamEvent } = useAgentStore.getState()
    applyStreamEvent(ev(1, 'THINKING_DELTA', { round: 0, text: '先看看' }))
    applyStreamEvent(ev(2, 'THINKING_DELTA', { round: 0, text: '活动的时间' }))

    const timeline = useAgentStore.getState().timeline
    expect(timeline).toHaveLength(1)
    expect(timeline[0].detail).toBe('先看看活动的时间')
    expect(timeline[0].label).toBe('先看看活动的时间')
    expect(timeline[0].completed).toBe(false)
  })

  it('换了 round 就新起一段，并且工具事件把上一段封口', () => {
    useAgentStore.setState({ timeline: [] })
    const { applyStreamEvent } = useAgentStore.getState()
    applyStreamEvent(ev(1, 'THINKING_DELTA', { round: 0, text: '第一段' }))
    applyStreamEvent(ev(2, 'TOOL_START', { tool: 'query', label: '查询活动' }))
    applyStreamEvent(ev(3, 'THINKING_DELTA', { round: 1, text: '第二段' }))

    const timeline = useAgentStore.getState().timeline
    expect(timeline.filter((i) => i.kind === 'thinking')).toHaveLength(2)
    expect(timeline[0].completed).toBe(true)
    expect(timeline[2].completed).toBe(false)
  })

  it('THINKING 编排旁白与 THINKING_DELTA 模型推理是两行、两种语义', () => {
    useAgentStore.setState({ timeline: [] })
    const { applyStreamEvent } = useAgentStore.getState()
    applyStreamEvent(ev(1, 'THINKING', { note: '正在理解你的问题…' }))
    applyStreamEvent(ev(2, 'THINKING_DELTA', { round: 0, text: '模型在想' }))

    const timeline = useAgentStore.getState().timeline
    expect(timeline[0].label).toBe('正在理解你的问题…')
    expect(timeline[0].detail).toBeUndefined()
    expect(timeline[1].detail).toBe('模型在想')
  })
})
