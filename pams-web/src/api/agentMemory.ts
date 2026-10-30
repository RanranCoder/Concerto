import { get, post, put, del } from './http'

export interface AgentMemory {
  id: string; scope: string; key: string; content: string | null
  status: 'ACTIVE' | 'PENDING' | 'INVALID' | 'EXPIRED'; revision: number
  expiresAt: string; sourceConversationId?: number | null; sourceMessageId?: number | null
}
export interface MemoryMetrics {
  windowStartedAt: string; processLocal: boolean; summaryCalls: number; summaryFailures: number
  summaryMeanMs: number | null; summaryWrites: number; summaryCacheHits: number
  summaryRejected?: number; summaryFailureRate?: number | null
  summaryDiscarded: number; usageSamples: number; inputTokens: number | null; outputTokens: number | null
  memoryReads: number; memoryHits: number; selectedMemories: number
}
export const listMemories = () => get<{ enabled: boolean; records: AgentMemory[] }>('/agent/memories')
export const memoryMetrics = () => get<MemoryMetrics>('/agent/memories/metrics')
export const saveMemory = (body: { scope: string; key: string; content: string; consent: boolean; revision?: number; days: number }) => post<AgentMemory>('/agent/memories', body)
export const memoryEnabled = (enabled: boolean) => put<void>('/agent/memories/settings', { enabled })
export const deleteMemory = (row: AgentMemory) => del<void>(`/agent/memories/${encodeURIComponent(row.id)}`, { params: { revision: row.revision } })
