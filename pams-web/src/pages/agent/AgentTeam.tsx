import { useEffect, useMemo, useState } from 'react'
import { App, Button, Empty, Input, Select, Space, Tag } from 'antd'
import { ThunderboltOutlined } from '@ant-design/icons'
import { listAgentDefinitions, runTeam } from '@/api/agentTeam'
import type { AgentDefinitionVO, AgentRunDetail, AgentStepVO, Blackboard, CollaborationMode } from '@/api/agentTeam'
import '@/styles/agent-team.css'

const AGENT_COLORS: Record<string, string> = {
  coordinator: '#c2220c',
  retrieval: '#4a6fa5',
  planning: '#5a7a5a',
  writing: '#8a6d3b',
  scheduling: '#5a6a7a',
  data: '#6a6a6a',
  reviewer: '#7a4a5a',
}
const AGENT_SHORT: Record<string, string> = {
  coordinator: '协', retrieval: '检', planning: '策', writing: '文',
  scheduling: '排', data: '事', reviewer: '审',
}

const MODES: { value: CollaborationMode; label: string }[] = [
  { value: 'P1', label: 'P1 串行' },
  { value: 'P2', label: 'P2 并行' },
  { value: 'P3', label: 'P3 反思' },
  { value: 'P4', label: 'P4 委派' },
]

interface Invocation { name: string; label: string; status: string; summary?: string; durationMs?: number }
interface ProducerOut { agent?: string; text?: string | null; invocations?: Invocation[] }

function tryJson<T>(s: string | null | undefined): T | null {
  if (!s) return null
  try { return JSON.parse(s) as T } catch { return null }
}

export default function AgentTeam() {
  const { message } = App.useApp()
  const [agents, setAgents] = useState<AgentDefinitionVO[]>([])
  const [goal, setGoal] = useState('')
  const [mode, setMode] = useState<CollaborationMode>('P1')
  const [run, setRun] = useState<AgentRunDetail | null>(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    listAgentDefinitions().then(setAgents).catch(() => {})
  }, [])

  const onRun = async () => {
    if (!goal.trim()) { message.warning('请输入协作目标'); return }
    setLoading(true)
    try {
      const res = await runTeam(goal.trim(), mode)
      setRun(res)
    } catch { /* http 拦截已提示 */ } finally { setLoading(false) }
  }

  const nameOf = (n: string) => agents.find((a) => a.name === n)?.displayName ?? n
  const roleOf = (n: string) => agents.find((a) => a.name === n)?.description ?? ''

  const callsByAgent = useMemo(() => {
    const m: Record<string, number> = {}
    run?.steps.forEach((s) => {
      const out = tryJson<ProducerOut>(s.output)
      if (out?.invocations) m[s.agentName] = (m[s.agentName] ?? 0) + out.invocations.length
    })
    return m
  }, [run])

  const statusOf = (n: string) => {
    const steps = run?.steps.filter((s) => s.agentName === n) ?? []
    if (steps.some((s) => s.status === 'FAILED')) return { cls: 'warn', text: '失败' }
    if (steps.some((s) => s.status === 'SUCCESS')) return { cls: 'ok', text: '已完成' }
    return { cls: 'idle', text: '空闲' }
  }

  return (
    <div className="at-page">
      <div className="at-main">
        <div className="at-top">
          <Input
            className="at-input"
            placeholder="描述协作目标，如：给迎新晚会写一篇报道推文"
            value={goal}
            onChange={(e) => setGoal(e.target.value)}
            onPressEnter={onRun}
          />
          <Select value={mode} options={MODES} style={{ width: 108 }} onChange={setMode} />
          <Button type="primary" icon={<ThunderboltOutlined />} loading={loading} onClick={onRun}>
            发起协作
          </Button>
        </div>

        <div className="at-stream">
          <div className="at-wrap">
            {!run && (
              <Empty style={{ marginTop: 80 }} description="输入目标后，协调者会拆解任务并委派专家团队协作" />
            )}
            {run && <RunView run={run} nameOf={nameOf} statusOf={statusOf} />}
          </div>
        </div>
      </div>

      {/* 右栏 */}
      <aside className="at-panel">
        <div className="at-ph">专家团队</div>
        <div className="at-team">
          {agents.map((a) => {
            const st = statusOf(a.name)
            return (
              <div className="at-agent" key={a.name} style={{ opacity: st.cls === 'idle' ? 0.65 : 1 }}>
                <div className="at-av" style={{ background: AGENT_COLORS[a.name] ?? '#6a6a6a' }}>
                  {AGENT_SHORT[a.name] ?? a.displayName.slice(0, 1)}
                </div>
                <div className="info">
                  <div className="nm">{a.displayName}</div>
                  <div className="rl">{a.description}</div>
                </div>
                <span className={`at-badge ${st.cls}`}>{st.text}</span>
                {callsByAgent[a.name] ? <span className="calls">{callsByAgent[a.name]}</span> : null}
              </div>
            )
          })}
        </div>

        {run?.blackboard && <Topology bb={run.blackboard} nameOf={nameOf} />}
        {run?.blackboard && <BlackboardView bb={run.blackboard} nameOf={nameOf} />}
      </aside>
    </div>
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
            <div className="at-av" style={{ background: AGENT_COLORS.coordinator }}>协</div>
            <b>{nameOf('coordinator')}</b>
            <span className="at-sub">规划 · Run #{run.id} · {run.mode}</span>
            <span className={`at-badge ${statusOf('coordinator').cls}`}>{statusOf('coordinator').text}</span>
          </div>
          <div className="at-dim">我理解目标后，拆成 {plan?.length ?? 0} 步协作计划并委派给专家：</div>
          {plan && plan.length > 0 && (
            <div className="at-plan">
              <div className="at-plan-h">协作计划 · 黑板已记录</div>
              {plan.map((p, i) => (
                <div className="at-plan-step" key={p.id}>
                  <div className="n">{i + 1}</div>
                  <div className="agent">{nameOf(p.agent)}</div>
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
            <div className="at-av" style={{ background: AGENT_COLORS.coordinator }}>协</div>
            <b>汇总</b><span className="at-sub">Run #{run.id} · {run.status}</span>
          </div>
          <div className="at-result">
            <div className="rt">
              <b>协作完成</b>
              <div>共 {run.steps.length} 个步骤 · {run.blackboard.artifacts.length} 项产物</div>
            </div>
          </div>
        </div>
      )}
    </>
  )
}

function StepBlock({ step, nameOf }: { step: AgentStepVO; nameOf: (n: string) => string }) {
  const color = AGENT_COLORS[step.agentName] ?? '#6a6a6a'
  const short = AGENT_SHORT[step.agentName] ?? nameOf(step.agentName).slice(0, 1)
  const badge = step.status === 'SUCCESS' ? 'ok' : step.status === 'FAILED' ? 'warn' : 'run'
  const badgeText = step.status === 'SUCCESS' ? '已完成' : step.status === 'FAILED' ? '失败' : '执行中'

  if (step.agentName === 'reviewer') {
    const rv = tryJson<{ verdict: string; reason: string }>(step.output)
    const pass = rv?.verdict === 'PASS'
    return (
      <div className="at-block">
        <div className="at-who">
          <div className="at-av" style={{ background: color }}>{short}</div>
          <b>{nameOf('reviewer')}</b>
          <span className="at-sub">委派自 · 协调者</span>
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
        <div className="at-av" style={{ background: color }}>{short}</div>
        <b>{nameOf(step.agentName)}</b>
        <span className="at-sub">委派自 · 协调者</span>
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
  const w = 296
  const gap = agents.length > 0 ? w / agents.length : w
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
          <g>
            <rect x="112" y="8" width="72" height="30" rx="8" fill="#fff1ee" stroke="#DE2910" />
            <text x="148" y="27" textAnchor="middle" fontSize="11" fill="#c2220c">协调者</text>
          </g>
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
            <text x="148" y="150" textAnchor="middle" fontSize="9.5" fill="#a9701a">
              含反思回路（REVISE → 修订）
            </text>
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
        <h4>目标</h4>
        <div className="ev">{bb.goal}</div>
        <h4 style={{ marginTop: 8 }}>证据 · Findings</h4>
        {bb.findings.length === 0 && <div className="ev">（暂无）</div>}
        {bb.findings.map((f, i) => (
          <div className="ev" key={i}>{nameOf(f.agent)}：{f.summary}</div>
        ))}
        <h4 style={{ marginTop: 8 }}>裁决 · Decisions</h4>
        {bb.decisions.length === 0 && <div className="ev">（暂无）</div>}
        {bb.decisions.map((d, i) => (
          <div className="ev" key={i}>{nameOf(d.by)}：{d.verdict}（{d.reason}）</div>
        ))}
      </div>
    </>
  )
}
