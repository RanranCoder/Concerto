import { useMemo, useState } from 'react'
import type { TimelineItem } from '@/stores/agent'
import { agentColorOf, agentShortOf } from '@/styles/categoryColors'
import { Caret, Sparkles } from './LingsiIcons'

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

  const { agents, mode, reason, reviews } = useMemo(() => {
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
        seen.set(it.agent, it)
      } else if (it.kind === 'review') {
        rv.push(it)
      }
    }
    return { agents: Array.from(seen.values()), mode: collabMode, reason: collabReason, reviews: rv }
  }, [items])

  // 没有协作事件 → 这一轮是单 Agent，整块不渲染（不占位、不打扰）
  if (agents.length === 0 && reviews.length === 0) return null

  const open = manual ?? Boolean(active)
  const done = agents.filter((a) => a.completed && a.status !== 'FAILED').length
  const failed = agents.filter((a) => a.status === 'FAILED').length
  const lastReview = reviews[reviews.length - 1]

  const summary = (() => {
    const parts: string[] = [`${agents.length} 位专家协作`]
    if (mode) parts.push(mode)
    if (active) parts.push(`已完成 ${done}/${agents.length}`)
    else if (failed) parts.push(`${failed} 位失败`)
    if (lastReview) parts.push(lastReview.verdict === 'PASS' ? '审查通过' : '审查要求修订')
    return parts.join(' · ')
  })()

  return (
    <div className="lingsi-team">
      <span
        className={`lingsi-team-head${active ? ' lingsi-team-head-live' : ''}`}
        onClick={() => setManual(!open)}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') setManual(!open)
        }}
      >
        <Sparkles size={13} />
        <Caret size={13} open={open} />
        <span>{summary}</span>
      </span>

      {open && (
        <div className="lingsi-team-body">
          {reason && <div className="lingsi-team-reason">判定为协作：{reason}</div>}
          <div className="lingsi-team-list">
            {agents.map((a) => {
              const st = !a.completed ? 'run' : a.status === 'FAILED' ? 'warn' : 'ok'
              const text = !a.completed ? '执行中' : a.status === 'FAILED' ? '失败' : '已完成'
              return (
                <div className="lingsi-team-agent" key={a.key}>
                  <span className="lingsi-team-av" style={{ background: agentColorOf(a.agent ?? '') }}>
                    {agentShortOf(a.agent ?? '', a.label)}
                  </span>
                  <span className="lingsi-team-name">{a.label}</span>
                  {typeof a.durationMs === 'number' && a.durationMs > 0 && (
                    <span className="lingsi-team-dur">{a.durationMs}ms</span>
                  )}
                  <span className={`tone-tag tone-${st === 'ok' ? 'success' : st === 'warn' ? 'warning' : 'processing'}`}>
                    {text}
                  </span>
                </div>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}
