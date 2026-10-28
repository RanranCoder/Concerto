import { useEffect } from 'react'
import { App } from 'antd'
import { fetchEventSource } from '@microsoft/fetch-event-source'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notification'
import { refreshAuthorization } from './useAuthorizationSync'

class StopStream extends Error {}

export function useNotificationStream() {
  const token = useAuthStore(s => s.token)
  const { notification } = App.useApp()
  useEffect(() => {
    if (!token) return
    let stopped = false, healthy = false, baseline = false, attempt = 0, lastEvent = Date.now()
    let controller: AbortController | undefined
    let retryTimer: ReturnType<typeof setTimeout> | undefined
    let refreshTimer: ReturnType<typeof setTimeout> | undefined
    const refresh = (toast = false) => {
      if (refreshTimer) clearTimeout(refreshTimer)
      refreshTimer = setTimeout(() => {
        if (stopped || useAuthStore.getState().token !== token) return
        const allowToast = toast && baseline && document.hasFocus()
        void useNotificationStore.getState().refresh().then(items => {
          baseline = true
          if (!allowToast || stopped) return
          for (const n of items.slice(0, 3)) notification.info({ key: `notification-${n.id}`, message: n.title, description: n.content, duration: 3 })
        }).catch(() => {})
      }, 150)
    }
    const stopConnection = () => { controller?.abort(); healthy = false; if (retryTimer) clearTimeout(retryTimer) }
    const connect = () => {
      if (stopped || document.hidden || !navigator.onLine) return
      controller = new AbortController()
      const current = controller
      lastEvent = Date.now()
      let delay = Math.min(30_000, 1000 * 2 ** Math.min(attempt++, 5)) * (0.8 + Math.random() * 0.4)
      void fetchEventSource('/api/notifications/stream', {
        method: 'GET', headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
        signal: current.signal, openWhenHidden: true,
        onopen: async response => {
          if (response.status === 401) { useAuthStore.getState().logout(); throw new StopStream() }
          if (response.status === 403) { void refreshAuthorization().catch(() => {}); throw new StopStream() }
          if (response.status === 429) {
            const header = response.headers.get('Retry-After')
            const seconds = Number(header)
            delay = header ? Math.max(1000, Number.isFinite(seconds) ? seconds * 1000 : Date.parse(header) - Date.now()) : 30_000
          }
          if (!response.ok || !response.headers.get('content-type')?.includes('text/event-stream')) throw new Error('SSE unavailable')
        },
        onmessage: event => {
          const data = JSON.parse(event.data) as { protocolVersion?: number }
          if (data.protocolVersion !== 1) throw new StopStream()
          lastEvent = Date.now()
          if (event.event === 'ready') { healthy = true; baseline = false; refresh() }
          else if (event.event === 'inbox.changed') refresh(true)
          if (healthy && Date.now() - startedAt > 30_000) attempt = 0
        },
        onclose: () => { throw new Error('SSE closed') },
        onerror: error => { throw error }, // One reconnect owner: this hook.
      }).catch(error => {
        healthy = false
        if (error instanceof StopStream) { stopped = true; return }
      }).finally(() => {
        if (!stopped && !current.signal.aborted && !document.hidden && navigator.onLine) retryTimer = setTimeout(connect, delay)
      })
      const startedAt = Date.now()
    }
    const resume = () => { stopConnection(); if (!document.hidden && navigator.onLine) { refresh(); connect() } }
    const watchdog = setInterval(() => {
      if (healthy && Date.now() - lastEvent > 45_000) { stopConnection(); connect() }
    }, 5000)
    const fallback = setInterval(() => { if (!healthy && !document.hidden && navigator.onLine) refresh() }, 60_000)
    document.addEventListener('visibilitychange', resume)
    window.addEventListener('online', resume)
    window.addEventListener('offline', stopConnection)
    window.addEventListener('focus', resume)
    refresh(); connect()
    return () => {
      stopped = true; stopConnection(); clearInterval(watchdog); clearInterval(fallback)
      if (refreshTimer) clearTimeout(refreshTimer)
      document.removeEventListener('visibilitychange', resume)
      window.removeEventListener('online', resume); window.removeEventListener('offline', stopConnection); window.removeEventListener('focus', resume)
    }
  }, [token, notification])
}
