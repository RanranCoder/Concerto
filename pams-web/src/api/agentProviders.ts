import { del, get, post, put } from './http'

export type AiProtocol = 'openai_chat_completions' | 'openai_responses'
export interface AgentModelStatus {
  configured: boolean; displayName: string; protocol: string; modelId: string
  source: 'organization' | 'deployment' | 'rules'
}
export const getAgentModelStatus = () => get<AgentModelStatus>('/agent/model-status')
export interface ProviderView {
  id: number; version: number; displayName: string; protocol: AiProtocol; baseUrl: string; modelId: string
  summaryEnabled: boolean; hasApiKey: boolean; testStatus: string; testedAt: string | null; testSummary: boolean
}
export interface ProviderDraft {
  id?: number; version?: number; displayName: string; protocol: AiProtocol; baseUrl: string; modelId: string
  apiKey?: string; summaryEnabled: boolean; testTicket?: string
}
export interface ProviderListing {
  items: ProviderView[]; defaultId: number | null; defaultVersion: number; deploymentProtocol: AiProtocol
  deploymentModel: string; deploymentAvailable: boolean; encryptionReady: boolean
}
export interface ProviderTest { success: boolean; message: string; durationMs: number; summaryAvailable: boolean; ticket: string | null }
const root = '/agent/providers'
export const listProviders = () => get<ProviderListing>(root)
export const saveProvider = (draft: ProviderDraft) => draft.id
  ? put<ProviderView>(`${root}/${draft.id}`, draft) : post<ProviderView>(root, draft)
export const testProvider = (draft: ProviderDraft) => post<ProviderTest>(`${root}/test`, draft, { timeout: 100000 })
export const activateProvider = (item: ProviderView, defaultVersion: number) => post<void>(`${root}/${item.id}/activate`, { version: item.version, defaultVersion })
export const resetProvider = (defaultVersion: number) => del<void>(`${root}/default`, { params: { defaultVersion } })
export const deleteProvider = (item: ProviderView) => del<void>(`${root}/${item.id}`, { params: { version: item.version } })
