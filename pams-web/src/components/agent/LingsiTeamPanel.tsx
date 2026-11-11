import { useId, useMemo, useState } from 'react'
import type { TimelineItem } from '@/stores/agent'
import { agentColorOf, agentShortOf } from '@/styles/categoryColors'
import { formatDuration, hasTeamPanel } from '@/utils/thinking'
import { Caret, Sparkles } from './LingsiIcons'
import { runStatusLabels, statusTone } from './LingsiRunOverview'

interface Props {
  items: TimelineItem[]
  /** 该轮仍在进行 */
  active?: boolean
}

/**
 * 协作团队面板（原 `/agent/team` 独立页的拓扑与团队列表，内联进对话流）。
 *
 * <p>为什么内联而不是独立页：协作是灵思在标准模式下**自动选择的执行策略**，
 * 不是用户要主动去的一个功能。用户关心的是「它派了几个专家、各自在做什么」，
 * 这个信息放在过程区旁边最省一次跳转。
 *
 * <p>数据来源：过程区时间轴里的 `collab` / `agent` / `review` 行，
 * 与 `mergeTimeline` 同源，因此刷新后由历史 toolCalls 回放时也一致。
 */
export default function LingsiTeamPanel({ items, active }: Props) {
  const [manual, setManual] = useState<boolean | null>(null)
  const bodyId = useId()

  const { agents, reason, reviews } = useMemo(() => {
    const seen = new Map<string, TimelineItem>()
    let collabMode = ''
    let collabReason = ''
    const rv: TimelineItem[] = []
    for (const it of items) {
      if (it.kind === 'collab') {
        collabMode = it.status ?? ''
        collabReason = it.label
      } else if (it.kind === 'agent' && it.agent) {
        // 同一专家可能出现多轮：保留最后一条状态（后面的覆盖前面的）
        seen.set(it.key, it)
      } else if (it.kind === 'review') {
        rv.push(it)
      }
    }
    return { agents: Array.from(seen.values()), mode: collabMode, reason: collabReason, reviews: rv }
  }, [items])

  // 没有协作事件 → 这一轮是单 Agent，整块不渲染（不占位、不打扰）。
  // 判据用 utils/thinking.ts 的 hasTeamPanel：过程区要靠**同一个**判据决定
  // 是否把 agent/collab 行让给本面板渲染 —— 两处各写一份必然漂移
  // （一边藏了、一边没渲染 → 信息直接消失）
  if (!hasTeamPanel(items)) return null

  const waiting = agents.some(a => a.status?.startsWith('WAITING_'))
  const open = manual ?? Boolean(active || waiting || agents.some(a => ['FAILED', 'PARTIAL'].includes(a.status ?? '')))
  const done = agents.filter((a) => a.completed && a.status === 'SUCCESS').length
  const failed = agents.filter((a) => a.status === 'FAILED').length
  const lastReview = reviews[reviews.length - 1]

  const summary = (() => {
    const parts: string[] = ['协作进展', `${new Set(agents.map(a => a.agent)).size} 位专家`, `${agents.length} 项执行记录`]
    if (waiting) parts.push('需要你处理')
    if (active) parts.push(`已完成 ${done}/${agents.length}`)
    else if (failed) parts.push(`${failed} 项失败`)
    if (lastReview) parts.push(lastReview.verdict === 'PASS' ? '基础检查已完成' : '审查要求修订')
    return parts.join(' / ')
  })()

  return (
    <div className="lingsi-team">
      <button
        type="button"
        className={`lingsi-team-head${active ? ' lingsi-team-head-live' : ''}`}
        onClick={() => setManual(!open)}
        aria-expanded={open}
        aria-controls={bodyId}
      >
        <Sparkles size={13} />
        <Caret size={13} open={open} />
        <span>{summary}</span>
      </button>

      <div className="pams-collapse" data-open={open} aria-hidden={!open} {...(!open ? { inert: '' } : {})}>
        <div className="pams-collapse-inner">
        <div className="lingsi-team-body" id={bodyId}>
          {reason && <div className="lingsi-team-reason">{reason}</div>}
          <div className="lingsi-team-list">
            {agents.map((a) => {
              const status = a.status || (a.completed ? 'UNKNOWN' : active ? 'RUNNING' : 'UNKNOWN')
              const text = runStatusLabels[status] ?? '待核对'
              return (
                <div className="lingsi-team-agent" key={a.key}>
                  <span className="lingsi-team-av" style={{ background: agentColorOf(a.agent ?? '') }}>
                    {agentShortOf(a.agent ?? '', a.label)}
                  </span>
                  <span className="lingsi-team-name">{a.label}</span>
                  {typeof a.durationMs === 'number' && a.durationMs > 0 && (
                    <span className="lingsi-team-dur">{formatDuration(a.durationMs)}</span>
                  )}
                  <span className={`lingsi-run-state is-${statusTone(status)}`}>
                    {text}
                  </span>
                </div>
              )
            })}
          </div>
        </div>
        </div>
      </div>
    </div>
  )
}
