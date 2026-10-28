// @vitest-environment jsdom
import { act, type ReactNode } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
vi.mock('react-router-dom', () => ({ useNavigate: () => vi.fn() }))
vi.mock('antd', () => ({
  Button: ({ children, onClick }: { children: ReactNode; onClick?: () => void }) => <button onClick={onClick}>{children}</button>,
  Empty: ({ description }: { description: string }) => <div>{description}</div>,
  Spin: () => <div>加载中</div>,
  Alert: ({ message, action }: { message: string; action: ReactNode }) => <div>{message}{action}</div>,
}))
vi.mock('@/api/pendingWork', () => ({ getPendingWork: vi.fn() }))
import { getPendingWork, type PendingWorkSummary } from '@/api/pendingWork'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notification'
import PendingWorkList from './PendingWorkList'

const summary: PendingWorkSummary = { total: 1, overdue: 0, counts: { PLAN_REVIEW: 1 }, items: [{ key: 'PLAN:1', type: 'PLAN_REVIEW', entityType: 'PLAN', entityId: 1, activityId: 7, title: '我的审核', action: '审核策划书', dueAt: null, overdue: false, progress: null, assignee: null }] }
let root: Root, host: HTMLDivElement
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  Object.defineProperty(document, 'hidden', { configurable: true, value: false })
  vi.resetAllMocks(); vi.useFakeTimers(); useAuthStore.setState({ token: 'account-a' })
  host = document.createElement('div'); root = createRoot(host)
})
afterEach(async () => { await act(async () => root.unmount()); vi.useRealTimers() })
it('refreshes from the shared invalidation signal', async () => {
  vi.mocked(getPendingWork).mockResolvedValue(summary)
  await act(async () => root.render(<PendingWorkList />))
  expect(host.textContent).toContain('我的审核')
  vi.mocked(getPendingWork).mockResolvedValue({ total: 0, overdue: 0, counts: {}, items: [] })
  await act(async () => useNotificationStore.setState({ revision: useNotificationStore.getState().revision + 1 }))
  expect(host.textContent).toContain('暂无待处理事项')
})
it('hides old work immediately when the account changes', async () => {
  vi.mocked(getPendingWork).mockResolvedValue(summary)
  await act(async () => root.render(<PendingWorkList />))
  vi.mocked(getPendingWork).mockImplementation(() => new Promise(() => {}))
  await act(async () => useAuthStore.setState({ token: 'account-b' }))
  expect(host.textContent).not.toContain('我的审核')
  expect(host.textContent).toContain('加载中')
})
it('allows a new account to retry after its first request fails without showing old work', async () => {
  vi.mocked(getPendingWork).mockResolvedValue(summary)
  await act(async () => root.render(<PendingWorkList />))
  vi.mocked(getPendingWork).mockRejectedValue(new Error('offline'))
  await act(async () => useAuthStore.setState({ token: 'account-b' }))
  expect(host.textContent).not.toContain('我的审核')
  expect(host.textContent).toContain('待办加载失败')
  vi.mocked(getPendingWork).mockResolvedValue({ total: 0, overdue: 0, counts: {}, items: [] })
  await act(async () => host.querySelector('button')!.click())
  expect(host.textContent).toContain('暂无待处理事项')
  expect(host.textContent).not.toContain('待办加载失败')
})
