// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fetchEventSource } from '@microsoft/fetch-event-source'
vi.mock('@microsoft/fetch-event-source', () => ({ fetchEventSource: vi.fn(() => new Promise<void>(() => {})) }))
vi.mock('./useAuthorizationSync', () => ({ refreshAuthorization: vi.fn() }))
import { refreshAuthorization } from './useAuthorizationSync'
vi.mock('antd', () => { const notification = { info: vi.fn() }; return { App: { useApp: () => ({ notification }) } } })
import { useNotificationStream } from './useNotificationStream'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notification'

function Harness() { useNotificationStream(); return null }
let root: Root
beforeEach(async () => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
  Object.defineProperty(document, 'hidden', { configurable: true, value: false })
  vi.useFakeTimers(); vi.mocked(fetchEventSource).mockClear()
  vi.mocked(refreshAuthorization).mockReset().mockResolvedValue(undefined)
  vi.spyOn(useNotificationStore.getState(), 'refresh').mockResolvedValue([])
  useAuthStore.setState({ token: 'stream-token' })
  root = createRoot(document.createElement('div'))
  await act(async () => root.render(<Harness />))
})
afterEach(async () => { await act(async () => root.unmount()); vi.restoreAllMocks(); vi.useRealTimers() })

it('sends Bearer, reconciles ready/change and aborts on unmount', async () => {
  const [url, options] = vi.mocked(fetchEventSource).mock.calls[0]
  expect(url).toBe('/api/notifications/stream')
  expect(options?.headers).toMatchObject({ Authorization: 'Bearer stream-token' })
  await act(async () => {
    options?.onmessage?.({ event: 'ready', data: '{"protocolVersion":1}', id: '', retry: undefined })
    await vi.advanceTimersByTimeAsync(200)
  })
  const refresh = useNotificationStore.getState().refresh
  expect(refresh).toHaveBeenCalled()
  vi.mocked(refresh).mockClear()
  await act(async () => {
    options?.onmessage?.({ event: 'inbox.changed', data: '{"protocolVersion":1}', id: '', retry: undefined })
    await vi.advanceTimersByTimeAsync(200)
  })
  expect(refresh).toHaveBeenCalledTimes(1)
  await act(async () => root.unmount())
  expect(options?.signal?.aborted).toBe(true)
})

it('clears the session on a stream 401 rather than retrying the old token', async () => {
  vi.mocked(refreshAuthorization).mockRejectedValue({ response: { status: 401 } })
  const options = vi.mocked(fetchEventSource).mock.calls[0][1]
  await act(async () => {
    await expect(options?.onopen?.(new Response('', { status: 401 }))).rejects.toThrow()
  })
  expect(useAuthStore.getState().token).toBeNull()
})

it('keeps a valid REST session when the stream alone returns 401', async () => {
  const options = vi.mocked(fetchEventSource).mock.calls[0][1]
  await expect(options?.onopen?.(new Response('', { status: 401 }))).rejects.toThrow()
  expect(refreshAuthorization).toHaveBeenCalledOnce()
  expect(useAuthStore.getState().token).toBe('stream-token')
})

it('ignores a late 401 from an aborted stream after page navigation', async () => {
  const options = vi.mocked(fetchEventSource).mock.calls[0][1]
  await act(async () => root.unmount())
  expect(options?.signal?.aborted).toBe(true)
  await expect(options?.onopen?.(new Response('', { status: 401 }))).rejects.toThrow()
  expect(useAuthStore.getState().token).toBe('stream-token')
})
