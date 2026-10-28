import { useWebSocket } from '@/hooks/useWebSocket'
import { useNotificationStream } from '@/hooks/useNotificationStream'

export function NotificationToast() {
  useWebSocket()
  useNotificationStream()
  return null
}
