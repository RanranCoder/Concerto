import { useEffect, useMemo, useState } from 'react'
import { App, Button, Empty, Input, Select, Spin, Tag } from 'antd'
import { ThunderboltOutlined } from '@ant-design/icons'
import { listAgentDefinitions } from '@/api/agentTeam'
import type { AgentDefinitionVO, AgentRunDetail, AgentStepVO, Blackboard, CollaborationMode } from '@/api/agentTeam'
import { useAgentTeamStore } from '@/stores/agentTeam'
import '@/styles/agent-team.css'

const AGENT_COLORS: Record<string, string> = {
  coordinator: '#c2220c', retrieval: '#4a6fa5', planning: '#5a7a5a',
  writing: '#8a6d3b', scheduling: '#5a6a7a', data: '#6a6a6a', reviewer: '#7a4a5a',
}
const AGENT_SHORT: Record<string, string> = {
  coordinator: '协', retrieval: '检', planning: '策', writing: '文',
  scheduling: '排', data: '事', reviewer: '审', generalist: '通',
}
const MODES: { value: CollaborationMode; label: string }[] = [
  { value: 'P1', label: 'P1 串行' }, { value: 'P2', label: 'P2 并行' },
  { value: 'P3', label: 'P3 反思' }, { value: 'P4', label: 'P4 委派' },
]

interface Invocation { name: string; label: string; status: string; summary?: string; durationMs?: number }
interface ProducerOut { agent?: string; text?: string | null; invocations?: Invocation[] }

function tryJson<T>(s: string | null | undefined): T | null {
  if (!s) return null
  try { return JSON.parse(s) as T } catch { return null }
}
const colorOf = (n: string) => AGENT_COLORS[n] ?? '#6a6a6a'
const shortOf = (n: string, fallback = '') => AGENT_SHORT[n] ?? (fallback || n).slice(0, 1)

export default function AgentTeam() {
  const { message } = App.useApp()
  const [agents, setAgents] = useState<AgentDefinitionVO[]>([])
  const [goal, setGoal] = useState('')
  const [mode, setMode] = useState<CollaborationMode>('P1')

  const { running, live, decisions, eventCount, run, streamId, start } = useAgentTeamStore()

  useEffect(() => { listAgentDefinitions().then(setAgents).catch(() => {}) }, [])
  useEffect(() => { useAgentTeamStore.getState().reset() }, [])

  const nameOf = (n: string) => agents.find((a) => a.name === n)?.displayName ?? n

  const onRun = async () => {
    if (!goal.trim()) { message.warning('请输入协作目标'); return }
    try { await start(goal.trim(), mode) } catch { message.error('发起协作失败') }
  }

  const liveCalls = useMemo(() => {
    const m: Record<string, number> = {}
    Object.values(live).forEach((a) => { m[a.name] = a.tools.length })
    return m
  }, [live])

  const statusOf = (n: string) => {
    if (running) {
      const l = live[n]
      if (!l) return { cls: 'idle', text: '空闲' }
      if (l.status === 'running') return { cls: 'run', text: '执行中' }
      return l.status === 'failed' ? { cls: 'warn', text: '失败' } : { cls: 'ok', text: '已完成' }
    }
    const steps = run?.steps.filter((s) => s.agentName === n) ?? []
    if (steps.some((s) => s.status === 'FAILED')) return { cls: 'warn', text: '失败' }
    if (steps.some((s) => s.status === 'SUCCESS')) return { cls: 'ok', text: '已完成' }
    return { cls: 'idle', text: '空闲' }
  }

  const showEmpty = !running && !run

  return (
    <div className="at-page">
      <div className="at-main">
        <div className="at-top">
          <Input className="at-input" placeholder="描述协作目标，如：给迎新晚会写一篇报道推文"
            value={goal} onChange={(e) => setGoal(e.target.value)} onPressEnter={onRun} disabled={running} />
          <Select value={mode} options={MODES} style={{ width: 108 }} onChange={setMode} disabled={running} />
          <Button type="primary" icon={<ThunderboltOutlined />} loading={running} onClick={onRun}
            disabled={running}>发起协作</Button>
          {running && <Tag color="processing">实时 · 已收 {eventCount} 个事件</Tag>}
        </div>

        <div className="at-stream">
          <div className="at-wrap">
            {showEmpty && <Empty style={{ marginTop: 80 }} description="输入目标后，协调者会拆解任务并委派专家团队协作" />}
            {running && <LiveView live={live} decisions={decisions} goal={goal} nameOf={nameOf} mode={mode} />}
            {!running && run && <RunView run={run} nameOf={nameOf} statusOf={statusOf} />}
          </div>
        </div>
      </div>

      <aside className="at-panel">
        <div className="at-ph">专家团队</div>
        <div className="at-team">
          {agents.map((a) => {
            const st = statusOf(a.name)
            const calls = running ? liveCalls[a.name] : undefined
            return (
              <div className="at-agent" key={a.name} style={{ opacity: st.cls === 'idle' ? 0.65 : 1 }}>
                <div className="at-av" style={{ background: colorOf(a.name) }}>{shortOf(a.name, a.displayName)}</div>
                <div className="info">
                  <div className="nm">{a.displayName}</div>
                  <div className="rl">{a.description}</div>
                </div>
                <span className={`at-badge ${st.cls}`}>{st.text}</span>
                {calls ? <span className="calls">{calls}</span> : null}
              </div>
            )
          })}
        </div>

        {streamId && <div className="at-ph">流 {streamId}</div>}
        {run?.blackboard && <Topology bb={run.blackboard} nameOf={nameOf} />}
        {run?.blackboard && <BlackboardView bb={run.blackboard} nameOf={nameOf} />}
      </aside>
    </div>
  )
}

/** 运行中的实时视图：按事件逐个点亮专家。 */
function LiveView({ live, decisions, goal, mode, nameOf }: {
  live: Record<string, { name: string; status: string; durationMs?: number; tools: { name: string; label: string; status: string; durationMs?: number }[] }>
  decisions: { verdict: string; reason: string }[]
  goal: string
  mode: string
  nameOf: (n: string) => string
}) {
  const order = ['coordinator', 'retrieval', 'planning', 'writing', 'scheduling', 'data', 'reviewer']
  const items = Object.values(live).sort((a, b) => order.indexOf(a.name) - order.indexOf(b.name))
  return (
    <>
      <div className="at-user"><div className="at-bubble">{goal}</div></div>
      <div className="at-block coord">
        <div className="at-who">
          <Spin size="small" />
          <b>协作进行中</b>
          <span className="at-sub">{mode} · 专家依次入场</span>
        </div>
      </div>
      {items.map((a) => (
        <div className="at-block" key={a.name}>
          <div className="at-who">
            <div className="at-av" style={{ background: colorOf(a.name) }}>{shortOf(a.name, nameOf(a.name))}</div>
            <b>{nameOf(a.name)}</b>
            <span className="at-sub">{a.durationMs != null ? `${a.durationMs}ms` : ''}</span>
            <span className={`at-badge ${a.status === 'running' ? 'run' : a.status === 'failed' ? 'warn' : 'ok'}`}>
              {a.status === 'running' ? '执行中' : a.status === 'failed' ? '失败' : '已完成'}
            </span>
          </div>
          {a.tools.map((t, i) => (
            <div className="at-tool" key={i}>
              <span className={`at-dot ${t.status === 'run' ? 'run' : t.status === 'OK' ? 'ok' : 'warn'}`} />
              {t.label} <code>{t.name}</code>
              {t.durationMs != null && <span className="dur">{t.durationMs}ms</span>}
            </div>
          ))}
        </div>
      ))}
      {decisions.map((d, i) => (
        <div className="at-block" key={`rv-${i}`}>
          <div className="at-who">
            <div className="at-av" style={{ background: colorOf('reviewer') }}>审</div>
            <b>{nameOf('reviewer')}</b>
            <span className={`at-badge ${d.verdict === 'PASS' ? 'ok' : 'warn'}`}>{d.verdict}</span>
          </div>
          <div className={`at-review ${d.verdict === 'PASS' ? 'pass' : ''}`}>
            <div className="rv-h">{d.verdict === 'PASS' ? '✓ PASS · 通过' : '↻ REVISE · 需修订'}</div>
            <ul><li>{d.reason}</li></ul>
          </div>
        </div>
      ))}
    </>
  )
}

function RunView({ run, nameOf, statusOf }: {
  run: AgentRunDetail
  nameOf: (n: string) => string
  statusOf: (n: string) => { cls: string; text: string }
}) {
  const steps = run.steps
  const coordinator = steps.find((s) => s.agentName === 'coordinator')
  const plan = tryJson<{ id: number; agent: string; task: string }[]>(coordinator?.output)
  const rest = steps.filter((s) => s !== coordinator)

  return (
    <>
      <div className="at-user"><div className="at-bubble">{run.goal}</div></div>
      {coordinator && (
        <div className="at-block coord">
          <div className="at-who">
            <div className="at-av" style={{ background: colorOf('coordinator') }}>协</div>
            <b>{nameOf('coordinator')}</b>
            <span className="at-sub">规划 · Run #{run.id} · {run.mode}</span>
            <span className={`at-badge ${statusOf('coordinator').cls}`}>{statusOf('coordinator').text}</span>
          </div>
          <div className="at-dim">拆成 {plan?.length ?? 0} 步协作计划并委派给专家：</div>
          {plan && plan.length > 0 && (
            <div className="at-plan">
              <div className="at-plan-h">协作计划 · 黑板已记录</div>
              {plan.map((p, i) => (
                <div className="at-plan-step" key={p.id}>
                  <div className="n">{i + 1}</div><div className="agent">{nameOf(p.agent)}</div>
                  <div className="task">{p.task}</div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
      {rest.map((s) => <StepBlock key={s.id} step={s} nameOf={nameOf} />)}
      {run.blackboard && (
        <div className="at-block coord">
          <div className="at-who">
            <div className="at-av" style={{ background: colorOf('coordinator') }}>协</div>
            <b>汇总</b><span className="at-sub">Run #{run.id} · {run.status}</span>
          </div>
          <div className="at-result">
            <div className="rt"><b>协作完成</b>
              <div>共 {run.steps.length} 个步骤 · {run.blackboard.artifacts.length} 项产物</div>
            </div>
          </div>
        </div>
      )}
    </>
  )
}

function StepBlock({ step, nameOf }: { step: AgentStepVO; nameOf: (n: string) => string }) {
  const badge = step.status === 'SUCCESS' ? 'ok' : step.status === 'FAILED' ? 'warn' : 'run'
  const badgeText = step.status === 'SUCCESS' ? '已完成' : step.status === 'FAILED' ? '失败' : '执行中'
  if (step.agentName === 'reviewer') {
    const rv = tryJson<{ verdict: string; reason: string }>(step.output)
    const pass = rv?.verdict === 'PASS'
    return (
      <div className="at-block">
        <div className="at-who">
          <div className="at-av" style={{ background: colorOf('reviewer') }}>审</div>
          <b>{nameOf('reviewer')}</b><span className="at-sub">委派自 · 协调者</span>
          <span className={`at-badge ${pass ? 'ok' : 'warn'}`}>{rv?.verdict ?? badgeText}</span>
        </div>
        <div className={`at-review ${pass ? 'pass' : ''}`}>
          <div className="rv-h">{pass ? '✓ PASS · 通过' : '↻ REVISE · 需修订'}</div>
          <ul><li>{rv?.reason ?? '—'}</li></ul>
        </div>
      </div>
    )
  }
  const out = tryJson<ProducerOut>(step.output)
  return (
    <div className="at-block">
      <div className="at-who">
        <div className="at-av" style={{ background: colorOf(step.agentName) }}>
          {shortOf(step.agentName, nameOf(step.agentName))}
        </div>
        <b>{nameOf(step.agentName)}</b><span className="at-sub">委派自 · 协调者</span>
        <span className={`at-badge ${badge}`}>{badgeText}</span>
      </div>
      {out?.invocations?.map((inv, i) => (
        <div className="at-tool" key={i}>
          <span className={`at-dot ${inv.status === 'OK' ? 'ok' : 'warn'}`} />
          {inv.label} <code>{inv.name}</code>
          {inv.durationMs != null && <span className="dur">{inv.durationMs}ms</span>}
        </div>
      ))}
      {out?.text && <div className="at-dim" style={{ marginTop: 4 }}>{out.text}</div>}
      {!out?.text && (!out?.invocations || out.invocations.length === 0) && (
        <div className="at-dim" style={{ marginTop: 4 }}>（未产生工具调用）</div>
      )}
    </div>
  )
}

function Topology({ bb, nameOf }: { bb: Blackboard; nameOf: (n: string) => string }) {
  const agents = Array.from(new Set(bb.plan.map((p) => p.agent)))
  const hasRevise = bb.decisions.some((d) => d.verdict === 'REVISE')
  const gap = agents.length > 0 ? 296 / agents.length : 296
  return (
    <>
      <div className="at-ph">协作拓扑</div>
      <div className="at-topo">
        <svg viewBox="0 0 296 170" width="100%" height="170">
          <defs>
            <marker id="atArrow" markerWidth="7" markerHeight="7" refX="6" refY="3" orient="auto">
              <path d="M0,0 L6,3 L0,6 z" fill="#c9ced6" />
            </marker>
          </defs>
          <rect x="112" y="8" width="72" height="30" rx="8" fill="#fff1ee" stroke="#DE2910" />
          <text x="148" y="27" textAnchor="middle" fontSize="11" fill="#c2220c">协调者</text>
          {agents.map((a, i) => {
            const cx = gap * i + gap / 2
            return (
              <g key={a}>
                <path d={`M148 38 C 148 62, ${cx} 70, ${cx} 92`} stroke="#c9ced6" fill="none" markerEnd="url(#atArrow)" />
                <rect x={cx - 36} y="94" width="72" height="28" rx="8" fill="#fff" stroke="#e8eaed" />
                <text x={cx} y="112" textAnchor="middle" fontSize="10.5" fill="#1f2328">{nameOf(a)}</text>
              </g>
            )
          })}
          {hasRevise && (
            <text x="148" y="150" textAnchor="middle" fontSize="9.5" fill="#a9701a">含反思回路（REVISE → 修订）</text>
          )}
        </svg>
      </div>
    </>
  )
}

function BlackboardView({ bb, nameOf }: { bb: Blackboard; nameOf: (n: string) => string }) {
  return (
    <>
      <div className="at-ph">黑板 · Blackboard</div>
      <div className="at-bb">
        <h4>目标</h4><div className="ev">{bb.goal}</div>
        <h4 style={{ marginTop: 8 }}>证据 · Findings</h4>
        {bb.findings.length === 0 && <div className="ev">（暂无）</div>}
        {bb.findings.map((f, i) => <div className="ev" key={i}>{nameOf(f.agent)}：{f.summary}</div>)}
        <h4 style={{ marginTop: 8 }}>裁决 · Decisions</h4>
        {bb.decisions.length === 0 && <div className="ev">（暂无）</div>}
        {bb.decisions.map((d, i) => <div className="ev" key={i}>{nameOf(d.by)}：{d.verdict}（{d.reason}）</div>)}
      </div>
    </>
  )
}
