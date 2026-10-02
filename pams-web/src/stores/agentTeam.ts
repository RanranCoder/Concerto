import { create } from 'zustand'
import { getTeamRun, runTeam } from '@/api/agentTeam'
import type { AgentRunDetail, CollaborationMode, TeamStreamEvent } from '@/api/agentTeam'

export interface LiveTool { name: string; label: string; status: string; durationMs?: number }
export interface LiveAgent { name: string; status: 'running' | 'done' | 'failed'; durationMs?: number; tools: LiveTool[] }
export interface LiveDecision { verdict: string; reason: string }

interface TeamState {
  streamId: string | null
  runId: number | null
  goal: string
  mode: CollaborationMode
  running: boolean
  live: Record<string, LiveAgent>
  decisions: LiveDecision[]
  eventCount: number
  run: AgentRunDetail | null

  start: (goal: string, mode: CollaborationMode) => Promise<void>
  applyEvent: (e: TeamStreamEvent) => void
  reset: () => void
}

const empty = {
  streamId: null as string | null,
  runId: null as number | null,
  goal: '',
  mode: 'P1' as CollaborationMode,
  running: false,
  live: {} as Record<string, LiveAgent>,
  decisions: [] as LiveDecision[],
  eventCount: 0,
  run: null as AgentRunDetail | null,
}

export const useAgentTeamStore = create<TeamState>((set, get) => ({
  ...empty,

  start: async (goal, mode) => {
    set({ ...empty, goal, mode, running: true })
    try {
      const res = await runTeam(goal, mode)
      set({ runId: res.runId, streamId: res.streamId })
    } catch {
      set({ running: false })
      throw new Error('发起协作失败')
    }
  },

  applyEvent: (e) => {
    const st = get()
    if (!st.running) return
    // 竞态兜底：HTTP 响应未回、streamId 未知时，先采纳首个事件带过来的 streamId
    if (st.streamId && e.streamId !== st.streamId) return
    if (!st.streamId && e.streamId) set({ streamId: e.streamId })

    const live = { ...st.live }
    const p = e.payload ?? {}
    const agent = String(p.agent ?? '')

    switch (e.type) {
      case 'AGENT_START':
        if (agent) live[agent] = { name: agent, status: 'running', tools: [] }
        break
      case 'AGENT_END':
        if (agent && live[agent]) {
          live[agent] = {
            ...live[agent],
            status: p.status === 'SUCCESS' ? 'done' : 'failed',
            durationMs: typeof p.durationMs === 'number' ? p.durationMs : undefined,
          }
        }
        break
      case 'TOOL_START':
        if (agent && live[agent]) {
          live[agent] = {
            ...live[agent],
            tools: [...live[agent].tools, {
              name: String(p.tool ?? ''),
              label: String(p.label ?? p.tool ?? ''),
              status: 'run',
            }],
          }
        }
        break
      case 'TOOL_END':
        if (agent && live[agent]) {
          const tools = [...live[agent].tools]
          const idx = tools.map((t) => t.name).lastIndexOf(String(p.tool ?? ''))
          if (idx >= 0) {
            tools[idx] = {
              ...tools[idx],
              status: String(p.status ?? 'OK'),
              durationMs: typeof p.durationMs === 'number' ? p.durationMs : undefined,
            }
          }
          live[agent] = { ...live[agent], tools }
        }
        break
      case 'REVIEW_RESULT':
        set({ decisions: [...st.decisions, { verdict: String(p.verdict ?? ''), reason: String(p.reason ?? '') }] })
        break
      case 'RUN_DONE': {
        const runId = Number(p.runId ?? st.runId)
        set({ running: false })
        if (runId) {
          getTeamRun(runId).then((d) => set({ run: d })).catch(() => {})
        }
        break
      }
      default:
        break
    }
    set({ live, eventCount: st.eventCount + 1 })
  },

  reset: () => set({ ...empty }),
}))
