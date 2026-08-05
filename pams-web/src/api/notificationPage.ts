import { get, put } from './http'
import type { PageResult } from './types'

export interface NotificationPageVO {
  id: number
  type: string
  title: string
  content: string
  entityType: string | null
  entityId: number | null
  senderName: string | null
  priority: string
  read: boolean
  createdAt: string
}

export interface NotificationPreferenceVO {
  type: string
  enabled: boolean
  system: boolean
}

export const listNotificationsPage = (params: { type?: string; page?: number; size?: number }) =>
  get<PageResult<NotificationPageVO>>('/notifications/page', params)
export const markNotificationAsRead = (id: number) => put<void>(`/notifications/${id}/read`)
export const markAllNotificationsAsRead = () => put<void>('/notifications/read-all')
export const getNotificationPreferences = () => get<NotificationPreferenceVO[]>('/notifications/preferences')
export const saveNotificationPreferences = (prefs: NotificationPreferenceVO[]) =>
  put<void>('/notifications/preferences', prefs)
