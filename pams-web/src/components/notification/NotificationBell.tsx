import { Badge, Popover, List, Button, Spin, Typography, Space } from 'antd'
import { BellOutlined, CheckOutlined } from '@ant-design/icons'
import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useNotificationStore } from '@/stores/notification'
import type { NotificationVO } from '@/api/notification'
import dayjs from 'dayjs'
import { notificationRoute } from '@/utils/notificationRoute'

const { Text } = Typography

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

  const getNotificationIcon = (type: string) => {
    switch (type) {
      case 'TASK_ASSIGNED': return '📋'
      case 'PLAN_SUBMITTED': return '📝'
      case 'PLAN_APPROVED': return '✅'
      case 'PLAN_REJECTED': return '❌'
      default: return '🔔'
    }
  }

  const content = (
    <div style={{ width: 360, maxHeight: 400, overflow: 'auto' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8, padding: '0 12px' }}>
        <Text strong>通知</Text>
        <Space size={0}>
          {unreadCount > 0 && (
            <Button type="link" size="small" icon={<CheckOutlined />} onClick={handleMarkAll}>
              全部已读
            </Button>
          )}
          <Button type="link" size="small" onClick={goToAll}>
            查看全部
          </Button>
        </Space>
      </div>
      {loading ? (
        <div style={{ textAlign: 'center', padding: 20 }}><Spin /></div>
      ) : notifications.length === 0 ? (
        <div style={{ textAlign: 'center', padding: 20, color: 'var(--text-secondary)' }}>
          暂无通知
        </div>
      ) : (
        <List
          dataSource={notifications.slice(0, 20)}
          renderItem={(n: NotificationVO) => (
            <List.Item
              onClick={() => handleClick(n)}
              style={{
                cursor: 'pointer',
                padding: '8px 12px',
                backgroundColor: n.read ? 'transparent' : 'var(--canvas)',
                borderRadius: 6,
              }}
            >
              <Space direction="vertical" size={2} style={{ width: '100%' }}>
                <Space>
                  <span>{getNotificationIcon(n.type)}</span>
                  <Text strong={!n.read} style={{ fontSize: 14 }}>{n.title}</Text>
                  {!n.read && (
                    <span style={{ width: 6, height: 6, borderRadius: '50%', background: 'var(--brand)', display: 'inline-block' }} />
                  )}
                </Space>
                <Text type="secondary" style={{ fontSize: 13 }} ellipsis={{ tooltip: n.content }}>
                  {n.content}
                </Text>
                <Text type="secondary" style={{ fontSize: 13 }}>
                  {dayjs(n.createdAt).format('MM-DD HH:mm')}
                </Text>
              </Space>
            </List.Item>
          )}
        />
      )}
    </div>
  )

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
        <BellOutlined style={{ fontSize: 16, cursor: 'pointer', color: 'var(--text-primary)' }} />
      </Badge>
    </Popover>
  )
}
