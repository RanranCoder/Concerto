import { get, post } from './http'

export interface BenchRow {
  id: number
  batchId: string | null
  taskCode: string
  taskText: string
  arm: string
  mode: string | null
  status: string
  steps: number
  agents: number
  toolCalls: number
  artifacts: number
  reviewPass: number | null
  durationMs: number | null
  metricsJson?: string | null
  createdAt: string | null
}

export interface ArmSummary {
  samples: number
  completionRate: number | null
  runSuccessRate: number
  waiting: number
  p50DurationMs: number | null
  p95DurationMs: number | null
  qualityVerdict: string
  avgSteps: number
  avgAgents: number
  avgToolCalls: number
  avgArtifacts: number
  avgDurationMs: number | null
  reviewPassRate?: number | null
}

export interface BenchResult {
  batchId: string
  tasks: number
  rows: BenchRow[]
  summary: Record<string, ArmSummary>
}

export interface BenchTask { code: string; text: string }

export const listBenchTasks = () => get<BenchTask[]>('/agent/bench/tasks')
// Opt-in evaluation runs several bounded samples sequentially; ordinary API requests retain their timeout.
export const runBench = (modes?: string[]) => post<BenchResult>('/agent/bench/run', { modes }, { timeout: 30 * 60_000 })
export const listBenchResults = () => get<BenchRow[]>('/agent/bench/results')
