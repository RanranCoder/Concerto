import { useRef, useState } from 'react'
import type { AgentActionPlan, AgentRunView, TaskInputResult } from '@/api/agent'
import { retryAgentNode, reviseAgentNode, resumeAgentRun, cancelAgentRun } from '@/api/agent'
import { useAgentStore } from '@/stores/agent'
import LingsiActionCard from './LingsiActionCard'
import LingsiFormCard from './LingsiFormCard'
import LingsiArtifactCard from './LingsiArtifactCard'

const labels: Record<string, string> = {
  QUEUED: '等待上游', RUNNING: '处理中', WAITING_INPUT: '待补充信息', WAITING_CONFIRM: '待确认',
  STOPPING: '正在停止', REVIEW_REQUIRED: '需修订审查问题', NEEDS_RECONCILE: '执行结果待核对',
  SUCCESS: '已完成', FAILED: '失败', PARTIAL: '部分完成', CANCELLED: '已取消',
}
const names: Record<string,string> = { retrieval:'检索', planning:'策划', writing:'写作', scheduling:'排班', data:'事务', reviewer:'审查', generalist:'通用' }
interface Props {
  run: AgentRunView
  readonly?: boolean
  busy?: boolean
  onConfirm: (plan: AgentActionPlan) => void
  onCancel: (plan: AgentActionPlan) => void
  onSubmit: (id: number, values: Record<string, string>) => Promise<TaskInputResult>
}
/** One durable node per row; each pending action retains its own confirmation and result. */
export default function LingsiWorkItems({ run, readonly, busy, onConfirm, onCancel, onSubmit }: Props) {
  const stepsRef=useRef<HTMLDetailsElement>(null)
  const revisionsRef=useRef<Record<number,HTMLDetailsElement | null>>({})
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const refresh = useAgentStore(s => s.refreshRun)
  const perform = async (fn: () => Promise<unknown>) => {
    setPending(true); setError('')
    try { await fn(); await refresh(run.id) }
    catch { setError('操作未完成，请核对当前任务状态后重试。') }
    finally { setPending(false) }
  }
  return <section className="lingsi-work-items" aria-label="协作任务">
    <details ref={stepsRef}>
      <summary>协作任务， {labels[run.status] ?? run.status} · {run.nodes.filter(n => n.status === 'SUCCESS').length}/{run.nodes.length} 步完成</summary>
      <ol>
        {run.nodes.map(n => {
          const action = run.actions.find(a => a.workItemId === n.workItemId && a.revision === n.revision)
          const retryable = ['FAILED', 'PARTIAL'].includes(n.status) && !action
          const revisable = !['RUNNING','STOPPING','NEEDS_RECONCILE'].includes(run.status) && !run.cancelRequested && (!action || ['PENDING_CONFIRM', 'WAITING_INPUT', 'CANCELLED', 'REVIEW_REQUIRED'].includes(action.status))
          return <li key={n.workItemId}>
            <div><strong>步骤 {n.id}， {names[n.agent] ?? n.agent}</strong><span>{labels[n.status] ?? n.status}</span></div>
            <small>{n.dependsOn.length ? `依赖步骤 ${n.dependsOn.join('、')}` : '可独立执行'}，第 {n.revision} 版，已尝试 {n.attempt} 次</small>
            {retryable && <button type="button" disabled={readonly || pending || busy} onClick={() => void perform(() => retryAgentNode(run.id, n.workItemId))}>重试此步骤</button>}
            {revisable && <details ref={element=>{revisionsRef.current[n.workItemId]=element}}><summary>修订此步骤</summary><form onSubmit={e => {
              e.preventDefault()
              const instruction = String(new FormData(e.currentTarget).get('instruction') ?? '').trim()
              if (instruction) void perform(() => reviseAgentNode(run.id, n.workItemId, n.revision, instruction))
            }}>
              <label>任务说明<textarea name="instruction" defaultValue={n.task} required maxLength={16000} disabled={readonly || pending} /></label>
              <small>修订后将重新执行此步骤及依赖它的后续步骤，旧的待确认清单会失效。已提交业务动作的分支须先核对结果。</small>
              <button type="submit" disabled={readonly || pending || busy}>保存修订并继续</button>
            </form></details>}
          </li>
        })}
      </ol>
      {!run.cancelRequested && !['SUCCESS','CANCELLED','NEEDS_RECONCILE'].includes(run.status) && <button type="button" disabled={readonly || pending || busy} onClick={() => void perform(() => resumeAgentRun(run.id))}>检查并继续可执行步骤</button>}
    </details>
    {!run.cancelRequested && ['RUNNING','WAITING_CONFIRM','WAITING_INPUT','REVIEW_REQUIRED'].includes(run.status) && <button type="button" disabled={readonly || pending || busy} onClick={() => void perform(() => cancelAgentRun(run.id))}>停止此协作任务</button>}
    {run.status==='STOPPING' && <p role="status">正在停止，等待已开始的业务动作返回真实结果。</p>}
    {run.status==='NEEDS_RECONCILE' && <p role="status">业务提交被中断，结果需要在业务页面核对。系统不会自动重复提交。</p>}
    {Object.entries(run.reviews ?? {}).map(([id,review]) => review.status==='NOT_REVIEWED'?null:<section key={id} className="lingsi-review" aria-label="内容审查">
      <strong>{({BLOCKED:'内容有需要修订的问题',CHECKED:'已完成自动审查，请确认正文',UNVERIFIED:'内容尚未核验，请人工核对正文'} as Record<string,string>)[review.status] ?? review.status}</strong>
      {review.status==='BLOCKED' && <button type="button" disabled={readonly || pending || busy || run.cancelRequested} onClick={()=>{
        if(stepsRef.current) stepsRef.current.open=true
        const editor=revisionsRef.current[Number(id)]
        if(editor) {editor.open=true;editor.querySelector('textarea')?.focus()}
      }}>修改任务说明</button>}
      {review.issues.map(issue=><div key={issue.id}>
        <p>{issue.severity==='BLOCKING'?'需修订':'建议核对'}：{issue.location}，第 {issue.revision} 版，第 {issue.attempt} 次执行</p>
        <blockquote>{issue.quote}</blockquote><p>{issue.reason}（依据：{issue.sourceRef==='task'?'任务要求':issue.sourceRef.startsWith('step:')?`上游步骤 ${issue.sourceRef.slice(5)}`:issue.sourceRef}）</p>
      </div>)}
    </section>)}
    {error && <p role="alert">{error}</p>}
    {run.actions.map(action => <div key={action.id}>
      {action.status === 'PENDING_CONFIRM' && action.plan && <LingsiActionCard plan={action.plan} readonly={readonly} busy={busy || pending} onConfirm={() => onConfirm(action.plan!)} onCancel={() => onCancel(action.plan!)} />}
      {action.status === 'REVIEW_REQUIRED' && action.plan?.preview && <details><summary>查看待修订正文</summary><div className="lingsi-review-preview">{action.plan.preview}</div></details>}
      {action.status === 'WAITING_INPUT' && action.form && <LingsiFormCard form={action.form} inline readonly={readonly} busy={busy || pending} onSubmit={values => onSubmit(action.id, values)} />}
      {!['PENDING_CONFIRM', 'WAITING_INPUT'].includes(action.status) && <div className={`lingsi-notice lingsi-notice-${action.status === 'SUCCESS' ? 'success' : action.status === 'FAILED' ? 'failed' : 'info'}`}>
        {action.summary}， {labels[action.status] ?? action.status}{action.result?.message ? `：${String(action.result.message)}` : ''}
      </div>}
      {!!action.artifacts?.length && <LingsiArtifactCard artifacts={action.artifacts} />}
    </div>)}
  </section>
}
