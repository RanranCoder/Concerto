import { useId, useState } from 'react'
import type { AgentRunView } from '@/api/agent'
import type { TimelineItem } from '@/stores/agent'
import { hasTeamPanel } from '@/utils/thinking'
import { Caret, Check, Cross, Thinking } from './LingsiIcons'

export const runStatusLabels: Record<string, string> = {
  QUEUED: '等待执行', RUNNING: '执行中', WAITING_INPUT: '待补充信息', WAITING_CONFIRM: '待确认',
  STOPPING: '正在停止', REVIEW_REQUIRED: '待修订', NEEDS_RECONCILE: '结果待核对',
  SUCCESS: '已完成', FAILED: '失败', PARTIAL: '部分完成', CANCELLED: '已停止',
}
export const specialistNames: Record<string, string> = {
  retrieval: '检索专家', planning: '策划专家', writing: '写作专家', scheduling: '排班专家',
  data: '事务专家', reviewer: '审查专家', generalist: '综合助手', coordinator: '协调助手',
}
export function statusTone(status: string) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'danger'
  if (['WAITING_INPUT', 'WAITING_CONFIRM', 'REVIEW_REQUIRED', 'NEEDS_RECONCILE', 'PARTIAL'].includes(status)) return 'warning'
  if (['RUNNING', 'STOPPING'].includes(status)) return 'processing'
  return 'neutral'
}

/** Snapshot-derived overview. Counts represent completed tasks, never elapsed progress. */
export default function LingsiRunOverview({ run, items = [], active, onInspect }: {
  run?: AgentRunView; items?: TimelineItem[]; active?: boolean; onInspect?: () => void
}) {
  const [expanded, setExpanded] = useState<boolean | null>(null)
  const id = useId()
  // Live events and durable snapshots share the same shell. An event record is
  // not a planned task: never invent a total, dependency or durable node ID.
  const nodes = run?.nodes ?? Array.from(new Map(items.filter(i => i.kind === 'agent' && i.agent)
    .map(i => [i.key, i])).values()).map(i => ({
      id: i.key, workItemId: i.key, agent: i.agent!, task: i.label, dependsOn: [] as number[],
      status: i.status || (!i.completed && active ? 'RUNNING' : 'UNKNOWN'),
    }))
  const status = run?.status ?? (active ? 'RUNNING' :
    nodes.find(n => ['FAILED', 'PARTIAL', 'WAITING_INPUT', 'WAITING_CONFIRM', 'REVIEW_REQUIRED'].includes(n.status))?.status ?? 'UNKNOWN')
  const done = nodes.filter(n => n.status === 'SUCCESS').length
  const running = nodes.filter(n => n.status === 'RUNNING').length
  const attention = nodes.filter(n => statusTone(n.status) === 'warning' || n.status === 'FAILED').length
  const open = expanded ?? !['SUCCESS', 'CANCELLED'].includes(status)
  const specialists = new Set(nodes.map(n => n.agent)).size
  if (!run && !hasTeamPanel(items)) return null
  return <section className="lingsi-run-overview" aria-label="协作概览">
    <header>
      <button type="button" className="lingsi-run-toggle" aria-expanded={open} aria-controls={id} onClick={() => setExpanded(!open)}>
        <Caret size={14} open={open} /><strong>协作进展</strong>
        <span className={`lingsi-run-state is-${statusTone(status)}`}>{runStatusLabels[status] ?? (run ? status : '等待状态同步')}</span>
      </button>
      {run && onInspect && <button type="button" className="lingsi-run-inspect" onClick={onInspect}>{attention ? '查看待处理事项' : '查看任务详情'}<span aria-hidden="true"> →</span></button>}
    </header>
    <div className="lingsi-run-counts" role="status">
      <span>{specialists} 位专家</span>{run ? <span>已完成 <b>{done}/{nodes.length}</b> 项任务</span> : <span>已收到 {nodes.length} 项执行记录</span>}
      {running > 0 && <span>{running} 项执行中</span>}
      {attention > 0 && <span className="lingsi-run-attention">{attention} 项需关注</span>}
    </div>
    <div id={id} hidden={!open}>
      {nodes.length === 0 ? <p className="lingsi-run-empty">正在同步任务分工…</p> : <ol className="lingsi-run-nodes">
        {nodes.map(n => {
          const dependencies = n.dependsOn.map(id => nodes.find(other => other.id === id))
          const ready = dependencies.every(dep => dep?.status === 'SUCCESS')
          return <li key={n.workItemId}>
            <span className={`lingsi-run-marker is-${statusTone(n.status)}`} aria-hidden="true">{n.status === 'SUCCESS' ? <Check size={14} /> : n.status === 'FAILED' ? <Cross size={14} /> : <Thinking size={14} />}</span>
            <div className="lingsi-run-task"><strong>{n.task.split('\n')[0]}</strong>
              <small>{specialistNames[n.agent] ?? n.agent}</small>
              {n.dependsOn.length > 0 && <small className="lingsi-run-dependency">{ready ? '前置任务已完成' : '等待前置任务'}：{dependencies.map((dep, i) => dep?.task.split('\n')[0] || `步骤 ${n.dependsOn[i]}`).join('、')}</small>}
            </div>
            <span className={`lingsi-run-state is-${statusTone(n.status)}`}>{n.status === 'QUEUED' && !ready ? '等待上游' : runStatusLabels[n.status] ?? (n.status === 'UNKNOWN' ? '待核对' : n.status)}</span>
          </li>
        })}
      </ol>}
    </div>
  </section>
}
