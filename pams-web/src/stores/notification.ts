import { create } from 'zustand'
import type { NotificationVO } from '@/api/notification'
import * as api from '@/api/notification'
import { useAuthStore } from './auth'

let requestVersion = 0
export const useNotificationStore = create<{
  unreadCount: number; notifications: NotificationVO[]; loading: boolean; revision: number
  refresh: () => Promise<NotificationVO[]>
  fetchUnreadCount: () => Promise<void>; fetchNotifications: () => Promise<void>
  markAsRead: (id: number) => Promise<void>; markAllAsRead: () => Promise<void>
  reset: () => void
}>()((set, get) => ({
  unreadCount: 0, notifications: [], loading: false, revision: 0,
  reset: () => { requestVersion++; set({ unreadCount: 0, notifications: [], loading: false, revision: get().revision + 1 }) },
  refresh: async () => {
    const token = useAuthStore.getState().token
    if (!token) return []
    const version = ++requestVersion
    set({ loading: true })
    try {
      const [list, count] = await Promise.all([api.listNotifications(), api.getUnreadCount()])
      if (useAuthStore.getState().token !== token || version !== requestVersion) return []
      const known = new Set(get().notifications.map(n => n.id))
      const next = list ?? []
      set({ notifications: next, unreadCount: count ?? 0, loading: false, revision: get().revision + 1 })
      return next.filter(n => !known.has(n.id) && !n.read && n.notify !== false)
    } finally {
      if (version === requestVersion && token === useAuthStore.getState().token) set({ loading: false })
    }
  },
  fetchUnreadCount: async () => { await get().refresh().catch(() => []) },
  fetchNotifications: async () => { await get().refresh().catch(() => []) },
  markAsRead: async id => { await api.markAsRead(id); await get().refresh() },
  markAllAsRead: async () => { await api.markAllAsRead(); await get().refresh() },
}))

useAuthStore.subscribe((state, previous) => {
  if (state.token !== previous.token || state.user?.id !== previous.user?.id) useNotificationStore.getState().reset()
})
