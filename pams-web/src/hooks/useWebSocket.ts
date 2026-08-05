import { useEffect } from 'react'
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { notification } from 'antd'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notification'
import type { NotificationVO } from '@/api/notification'

export function useWebSocket() {
  const token = useAuthStore((s) => s.token)
  const addRealtime = useNotificationStore((s) => s.addRealtimeNotification)
  const setUnreadCount = useNotificationStore((s) => s.setUnreadCount)
  useEffect(() => {
    if (!token) return

    const client = new Client({
      webSocketFactory: () => new SockJS('/ws'),
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: ((attempt: number) => Math.min(5000 * Math.pow(2, attempt), 60_000)) as unknown as number,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        console.log('[WS] 已连接')
        client.subscribe('/user/queue/notifications', (msg) => {
          try {
            const data = JSON.parse(msg.body)
            if (data.type === 'NEW_NOTIFICATION') {
              setUnreadCount(data.unreadCount ?? 0)
              useNotificationStore.getState().fetchNotifications()
              notification.info({
                message: data.title ?? '新通知',
                description: data.content,
                placement: 'topRight',
                duration: 3,
              })
            } else {
              const n = data as NotificationVO
              addRealtime(n)
              notification.info({
                message: n.title,
                description: n.content,
                placement: 'topRight',
                duration: 3,
              })
            }
          } catch (e) {
            console.error('[WS] 解析通知消息失败', e)
          }
        })
      },
      onDisconnect: () => {
        console.log('[WS] 已断开')
      },
      onStompError: (frame) => {
        console.error('[WS] STOMP 错误', frame.headers['message'])
      },
    })

    client.activate()

    return () => {
      client.deactivate()
    }
  }, [token, addRealtime, setUnreadCount])
}
