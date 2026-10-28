import { beforeEach, describe, expect, it, vi } from 'vitest'
vi.mock('@/api/notification', () => ({ listNotifications: vi.fn(), getUnreadCount: vi.fn(), markAsRead: vi.fn(), markAllAsRead: vi.fn() }))
import * as api from '@/api/notification'
import { useAuthStore } from './auth'
import { useNotificationStore } from './notification'

describe('notification reconciliation', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    useAuthStore.setState({ token: 'account-a' })
    useNotificationStore.getState().reset()
  })
  it('rejects an in-flight response after account switching', async () => {
    let resolve!: (value: never[]) => void
    vi.mocked(api.listNotifications).mockImplementation(() => new Promise(r => { resolve = r }))
    vi.mocked(api.getUnreadCount).mockResolvedValue(9)
    const pending = useNotificationStore.getState().refresh()
    useAuthStore.setState({ token: 'account-b' })
    resolve([]); await pending
    expect(useNotificationStore.getState().unreadCount).toBe(0)
  })
  it('repeated read uses server counts rather than subtracting twice', async () => {
    vi.mocked(api.markAsRead).mockResolvedValue(undefined)
    vi.mocked(api.listNotifications).mockResolvedValue([])
    vi.mocked(api.getUnreadCount).mockResolvedValue(4)
    await useNotificationStore.getState().markAsRead(1)
    await useNotificationStore.getState().markAsRead(1)
    expect(useNotificationStore.getState().unreadCount).toBe(4)
  })
})
