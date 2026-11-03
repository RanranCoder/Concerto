import { Badge, Popover, Button, Spin, Space } from 'antd'
import { TYPE_LABEL } from './notificationLabels'
import '@/styles/notification-v6.css'
import { BellOutlined, CheckOutlined } from '@ant-design/icons'
import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useNotificationStore } from '@/stores/notification'
import type { NotificationVO } from '@/api/notification'
import dayjs from 'dayjs'
import { notificationRoute } from '@/utils/notificationRoute'


export default function NotificationBell() {
  const [open, setOpen] = useState(false)
  const navigate = useNavigate()
  const { unreadCount, notifications, loading, fetchNotifications, fetchUnreadCount, markAsRead, markAllAsRead } =
    useNotificationStore()

  useEffect(() => {
    fetchUnreadCount()
  }, [fetchUnreadCount])

  const handleOpenChange = useCallback(
    (visible: boolean) => {
      setOpen(visible)
      if (visible) {
        fetchNotifications()
      }
    },
    [fetchNotifications],
  )

  const goToAll = useCallback(() => {
    setOpen(false)
    navigate('/notifications')
  }, [navigate])

  const handleClick = useCallback(
    async (n: NotificationVO) => {
      if (!n.read) {
        try { await markAsRead(n.id) } catch { return }
      }
      setOpen(false)
      const route = notificationRoute(n)
      if (route) navigate(route)
    },
    [markAsRead, navigate],
  )

  const handleMarkAll = useCallback(async () => {
    try { await markAllAsRead() } catch { /* API interceptor reports the failure. */ }
  }, [markAllAsRead])

  const content = <div className="notification-popover">
    <div className="notification-popover-head"><strong>通知</strong><Space size={0}>
      {unreadCount > 0 && <Button type="link" size="small" icon={<CheckOutlined />} onClick={handleMarkAll}>全部已读</Button>}
      <Button type="link" size="small" onClick={goToAll}>查看全部</Button>
    </Space></div>
    <Spin spinning={loading}><div className="notification-popover-list">
      {!notifications.length ? <div className="notification-empty">暂无通知</div> : notifications.slice(0, 20).map(n => <button type="button" key={n.id} className={'notification-preview' + (!n.read ? ' is-unread' : '')} onClick={() => handleClick(n)}>
        <span className="notification-title"><span className="notification-dot" /><strong>{n.title}</strong>{n.priority === 'URGENT' && <span className="tone-tag tone-danger">紧急</span>}</span>
        <span className="notification-summary">{n.content}</span><span className="notification-meta"><span>{TYPE_LABEL[n.type] ?? n.type}</span><span>{n.senderName || '系统'}</span><time dateTime={n.createdAt}>{dayjs(n.createdAt).format('MM-DD HH:mm')}</time></span>
      </button>)}
    </div></Spin>
  </div>

  return (
    <Popover
      content={content}
      trigger="click"
      open={open}
      onOpenChange={handleOpenChange}
      placement="bottomRight"
      arrow={false}
    >
      <Badge count={unreadCount} size="small" offset={[-2, 2]}>
        <Button type="text" aria-label="打开通知" aria-expanded={open} icon={<BellOutlined />} />
      </Badge>
    </Popover>
  )
}
