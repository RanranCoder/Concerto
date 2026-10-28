import { get } from './http'
export interface PendingWorkItem {
  key: string; type: string; entityType: string; entityId: number; activityId: number | null
  title: string; action: string; dueAt: string | null; overdue: boolean; progress: number | null; assignee: string | null
}
export interface PendingWorkSummary { total: number; overdue: number; counts: Record<string, number>; items: PendingWorkItem[] }
export const getPendingWork = () => get<PendingWorkSummary>('/notifications/pending')
