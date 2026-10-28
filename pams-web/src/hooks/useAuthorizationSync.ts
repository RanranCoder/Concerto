import { useEffect } from 'react'
import { meApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

let refreshing: Promise<void> | undefined
export function refreshAuthorization() {
  const token = useAuthStore.getState().token
  if (!token) return Promise.resolve()
  refreshing ??= meApi()
    .then((user) => {
      if (useAuthStore.getState().token === token)
        useAuthStore.getState().refreshUser(user)
    })
    .finally(() => {
      refreshing = undefined
    })
  return refreshing
}

export function useAuthorizationSync() {
  const token = useAuthStore((state) => state.token)
  useEffect(() => {
    if (!token) return
    const refresh = () => {
      if (document.visibilityState !== 'hidden')
        void refreshAuthorization().catch(() => {})
    }
    const timer = window.setInterval(refresh, 30_000)
    window.addEventListener('focus', refresh)
    document.addEventListener('visibilitychange', refresh)
    return () => {
      window.clearInterval(timer)
      window.removeEventListener('focus', refresh)
      document.removeEventListener('visibilitychange', refresh)
    }
  }, [token])
}
