import { useEffect } from 'react'
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { App } from 'antd'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notification'
import { useAgentStore } from '@/stores/agent'
import type { NotificationVO } from '@/api/notification'
import type { AgentStreamEvent } from '@/api/agent'

export function useWebSocket() {
  const { notification } = App.useApp()
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

        // 灵思过程事件流（D7）——厚载荷：携带工具名/耗时/状态，供前端渲染时间轴。
        // 与上面通知队列的"薄载荷 + 回拉 REST"策略有意不同。
        //
        // 协作内聚后：COLLAB_DECISION / AGENT_START / AGENT_END / REVIEW_RESULT 也走这条流、
        // 同一个 applyStreamEvent，由 mergeTimeline 并入过程区时间轴 ——
        // 因此不再需要把它另行转发给一个「协作页专用」的 store。
        client.subscribe('/user/queue/agent/stream', (msg) => {
          try {
            const event = JSON.parse(msg.body) as AgentStreamEvent
            useAgentStore.getState().applyStreamEvent(event)
          } catch (e) {
            console.error('[WS] 解析灵思过程事件失败', e)
          }
        })

        // 灵思写操作执行完成（确认后落库的结果）
        // 体验约定（用户反馈）：**不弹通知**——结果由 store 写进对话框（原地变结果块 / 追加结果消息），
        // 这里只做状态收敛（关卡片、刷新待确认计数），不再产生任何 corner 弹窗。
        client.subscribe('/user/queue/agent', (msg) => {
          try {
            const data = JSON.parse(msg.body) as Record<string, unknown>
            if (data.type === 'AGENT_TASK_DONE') {
              useAgentStore.getState().applyTaskDone(data)
            } else if (data.type === 'AGENT_RUN_UPDATED' && typeof data.runId === 'number') {
              const store=useAgentStore.getState()
              const known=store.outcomes.find(o=>o.run?.id===data.runId)?.run
              if (typeof data.eventSeq!=='number' || data.eventSeq>(known?.stateVersion ?? -1)) void store.refreshRun(data.runId)
            }
          } catch (e) {
            console.error('[WS] 解析灵思任务事件失败', e)
          }
        })
        void useAgentStore.getState().reconcileConnection()
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
  }, [token, addRealtime, setUnreadCount, notification])
}
