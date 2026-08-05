import { get, put } from './http'

export interface NotificationVO {
  id: number
  type: string
  title: string
  content: string
  entityType: string | null
  entityId: number | null
  senderName: string | null
  read: boolean
  createdAt: string
}

export const listNotifications = () => get<NotificationVO[]>('/notifications')
export const getUnreadCount = () => get<number>('/notifications/unread-count')
export const markAsRead = (id: number) => put<void>(`/notifications/${id}/read`)
export const markAllAsRead = () => put<void>('/notifications/read-all')
