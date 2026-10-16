import type { AgentConversationStatus, AgentConversationVO } from '@/api/agent'

export const agentStatusLabels: Record<AgentConversationStatus, string> = {
  READY: '可继续', RUNNING: '进行中', WAITING_INPUT: '待补充', PENDING_CONFIRM: '待确认',
  COMPLETED: '已完成', SUCCESS: '已完成', FAILED: '执行失败', PARTIAL: '部分完成', CANCELLED: '已取消', STOPPED:'已停止',
}
export function conversationStatus(c: AgentConversationVO): AgentConversationStatus {
  return c.pendingTaskCount > 0 ? 'PENDING_CONFIRM' : c.status ?? 'READY'
}
export function isCompleted(status: AgentConversationStatus) {
  return status === 'COMPLETED' || status === 'SUCCESS'
}
export function taskDateGroup(iso: string, now = new Date()): '今天' | '昨天' | '更早' {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '更早'
  const today = new Date(now); today.setHours(0, 0, 0, 0)
  const yesterday = new Date(today); yesterday.setDate(yesterday.getDate() - 1)
  return date >= today ? '今天' : date >= yesterday ? '昨天' : '更早'
}
