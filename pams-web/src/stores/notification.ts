import { create } from 'zustand'
import type { NotificationVO } from '@/api/notification'
import * as api from '@/api/notification'

interface NotificationState {
  unreadCount: number
  notifications: NotificationVO[]
  loading: boolean
  fetchUnreadCount: () => Promise<void>
  fetchNotifications: () => Promise<void>
  markAsRead: (id: number) => Promise<void>
  markAllAsRead: () => Promise<void>
  addRealtimeNotification: (n: NotificationVO) => void
  setUnreadCount: (count: number) => void
}

export const useNotificationStore = create<NotificationState>()((set, _get) => ({
  unreadCount: 0,
  notifications: [],
  loading: false,

  fetchUnreadCount: async () => {
    try {
      const count = await api.getUnreadCount()
      set({ unreadCount: count ?? 0 })
    } catch {
      // http interceptor handles error
    }
  },

  fetchNotifications: async () => {
    set({ loading: true })
    try {
      const list = await api.listNotifications()
      set({ notifications: list ?? [] })
    } catch {
      // http interceptor handles error
    } finally {
      set({ loading: false })
    }
  },

  markAsRead: async (id: number) => {
    try {
      await api.markAsRead(id)
      set((state) => ({
        notifications: state.notifications.map((n) =>
          n.id === id ? { ...n, read: true } : n,
        ),
        unreadCount: Math.max(0, state.unreadCount - 1),
      }))
    } catch {
      // http interceptor handles error
    }
  },

  markAllAsRead: async () => {
    try {
      await api.markAllAsRead()
      set((state) => ({
        notifications: state.notifications.map((n) => ({ ...n, read: true })),
        unreadCount: 0,
      }))
    } catch {
      // http interceptor handles error
    }
  },

  addRealtimeNotification: (n: NotificationVO) => {
    set((state) => ({
      notifications: [n, ...state.notifications].slice(0, 50),
      unreadCount: state.unreadCount + 1,
    }))
  },

  setUnreadCount: (count: number) => set({ unreadCount: count }),
}))
