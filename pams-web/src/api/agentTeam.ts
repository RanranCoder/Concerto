import { get, post } from './http'

export interface AgentDefinitionVO {
  id: number
  name: string
  displayName: string
  description: string | null
  enabled: number
  sortOrder: number
}

export interface PlanItem { id: number; agent: string; task: string }
export interface Finding { agent: string; summary: string | null; confidence: number | null }
export interface Artifact { agent: string; type: string; contentRef: string }
export interface Decision { by: string; verdict: string; reason: string }

export interface Blackboard {
  goal: string | null
  mode: string | null
  plan: PlanItem[]
  findings: Finding[]
  artifacts: Artifact[]
  decisions: Decision[]
  openQuestions: string[]
}

export interface AgentStepVO {
  id: number
  agentName: string
  seq: number
  parentStepId: number | null
  status: string
  durationMs: number | null
  output: string | null
}

export interface AgentRunDetail {
  id: number
  conversationId: number | null
  goal: string | null
  mode: string | null
  status: string
  startedAt: string | null
  finishedAt: string | null
  blackboard: Blackboard | null
  steps: AgentStepVO[]
  streamId?: string
}

export type CollaborationMode = 'P1' | 'P2' | 'P3' | 'P4'

/** 已注册的 Agent 定义（专家团队）。 */
export const listAgentDefinitions = () => get<AgentDefinitionVO[]>('/agent/team/agents')

/** 只做任务分解（预览协作计划，不落库）。 */
export const planTeam = (goal: string) => post<Blackboard>('/agent/team/plan', { goal })

/** 执行一次协作 Run。 */
export const runTeam = (goal: string, mode: CollaborationMode = 'P1') =>
  post<AgentRunDetail>('/agent/team/run', { goal, mode })

/** Run 详情（含步骤与黑板）。 */
export const getTeamRun = (id: number) => get<AgentRunDetail>(`/agent/team/runs/${id}`)
