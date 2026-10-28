import { useCallback, useEffect, useState } from 'react'
import { App, Button, Empty, Select, Space, Spin, Switch, Tag } from 'antd'
import { CheckOutlined, ReloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { useNavigate } from 'react-router-dom'
import { useNotificationStore } from '@/stores/notification'
import { useAuthStore } from '@/stores/auth'
import { notificationRoute } from '@/utils/notificationRoute'
import GlassCard from '@/components/glass/GlassCard'
import GlassTable from '@/components/glass/GlassTable'
import PageHeader from '@/components/glass/PageHeader'
import {
  getNotificationPreferences,
  listNotificationsPage,
  saveNotificationPreferences,
} from '@/api/notificationPage'

// 对照后端 NotificationType 枚举：TASK_ASSIGNED, PLAN_SUBMITTED, PLAN_APPROVED, PLAN_REJECTED, SYSTEM, PLAN_MODIFIED, NEWS_UPLOADED, SIGNIN_ROSTER_UPLOADED, SIGNIN_COMPLETED, ARTICLE_SUBMITTED, ARTICLE_ASSIGNED, ARTICLE_APPROVED, ARTICLE_REJECTED, ARTICLE_PUBLISHED, ARTICLE_DEADLINE_REMINDER, ANNOUNCEMENT
const TYPE_LABEL: Record<string, string> = {
  TASK_ASSIGNED: '任务',
  TASK_CHANGED: '任务变更',
  TASK_COMPLETED: '任务完成',
  SCHEDULE_PUBLISHED: '排班发布',
  SCHEDULE_CHANGED: '排班调整',
  SCHEDULE_CANCELLED: '排班取消',
  PLAN_WITHDRAWN: '策划书撤回',
  COLLABORATION: '协作提醒',
  PLAN_SUBMITTED: '策划书',
  PLAN_APPROVED: '策划书',
  PLAN_REJECTED: '策划书',
  PLAN_MODIFIED: '策划书',
  SYSTEM: '系统',
  NEWS_UPLOADED: '新媒体',
  SIGNIN_ROSTER_UPLOADED: '签到',
  SIGNIN_COMPLETED: '签到',
  ARTICLE_SUBMITTED: '推文',
  ARTICLE_ASSIGNED: '推文',
  ARTICLE_APPROVED: '推文',
  ARTICLE_REJECTED: '推文',
  ARTICLE_PUBLISHED: '推文',
  ARTICLE_DEADLINE_REMINDER: '推文',
  ANNOUNCEMENT: '公告',
}

interface NotificationRow {
  id: number
  type: string
  title: string
  content: string
  entityType: string | null
  entityId: number | null
  activityId?: number | null
  actionState?: string | null
  senderName: string | null
  priority: string
  read: boolean
  createdAt: string
  key: number
}

export default function NotificationList() {
  const navigate = useNavigate()
  const revision = useNotificationStore(s => s.revision)
  const { message } = App.useApp()
  const [typeFilter, setTypeFilter] = useState<string>()
  const [data, setData] = useState<NotificationRow[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(10)
  const [loading, setLoading] = useState(false)
  const [prefs, setPrefs] = useState<Array<{ type: string; enabled: boolean; system: boolean }>>([])
  const [prefLoading, setPrefLoading] = useState(false)

  const fetchList = useCallback(() => {
    const token = useAuthStore.getState().token
    setLoading(true)
    listNotificationsPage({ type: typeFilter, page, size })
      .then((res) => {
        if (token !== useAuthStore.getState().token) return
        setData((res.records ?? []).map((r) => ({ ...r, key: r.id })))
        setTotal(res.total)
      })
      .catch(() => {
        /* 拦截已提示 */
      })
      .finally(() => setLoading(false))
  }, [typeFilter, page, size])

  useEffect(() => {
    fetchList()
  }, [fetchList, revision])

  const fetchPrefs = useCallback(() => {
    getNotificationPreferences()
      .then((res) => setPrefs(res ?? []))
      .catch(() => {
        /* 拦截已提示 */
      })
  }, [])

  useEffect(() => {
    fetchPrefs()
  }, [fetchPrefs])

  const handleMarkRead = async (id: number) => {
    try {
      await useNotificationStore.getState().markAsRead(id)
    } catch {
      /* 拦截已提示 */
    }
  }

  const handleMarkAll = async () => {
    try {
      await useNotificationStore.getState().markAllAsRead()
      message.success('已全部标记为已读')
      fetchList()
    } catch {
      /* 拦截已提示 */
    }
  }

  const togglePref = async (type: string, enabled: boolean) => {
    const next = prefs.map((p) => (p.type === type ? { ...p, enabled } : p))
    setPrefs(next)
    setPrefLoading(true)
    try {
      await saveNotificationPreferences(next)
      message.success('偏好已保存')
    } catch {
      /* 拦截已提示 */
      fetchPrefs()
    } finally {
      setPrefLoading(false)
    }
  }

  const columns = [
    {
      title: '类型',
      dataIndex: 'type',
      key: 'type',
      width: 90,
      render: (t: string) => <Tag color="blue">{TYPE_LABEL[t] ?? t}</Tag>,
    },
    {
      title: '标题',
      dataIndex: 'title',
      key: 'title',
      ellipsis: true,
      render: (v: string, r: NotificationRow) => (
        <Space size={4}>
          {!r.read && <span style={{ display: 'inline-block', width: 8, height: 8, borderRadius: 4, background: 'var(--brand)' }} />}
          {r.priority === 'URGENT' && <Tag color="red">紧急</Tag>}
          <span style={{ fontWeight: r.read ? 'calc(400 - var(--font-weight-compensation))' : 'calc(600 - var(--font-weight-compensation))' }}>{v}</span>
        </Space>
      ),
    },
    { title: '内容', dataIndex: 'content', key: 'content', ellipsis: true },
    { title: '发送人', dataIndex: 'senderName', key: 'senderName', width: 110, render: (v: string | null) => v || '系统' },
    {
      title: '时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      align: 'right' as const,
      onCell: () => ({ style: { fontVariantNumeric: 'tabular-nums' } }),
      render: (t: string) => (t ? dayjs(t).format('YYYY-MM-DD HH:mm') : '-'),
    },
    {
      title: '操作',
      key: 'action',
      align: 'right' as const,
      width: 100,
      render: (_: unknown, r: NotificationRow) => (
        <Space>
          {notificationRoute(r) && <Button type="link" size="small" onClick={() => {
            if (!r.read) void handleMarkRead(r.id)
            navigate(notificationRoute(r)!)
          }}>{r.actionState === 'open' ? (r.type === 'PLAN_SUBMITTED' || r.type === 'ARTICLE_SUBMITTED' ? '去审核' : '去处理') : '查看'}</Button>}
          {r.read ? '已读' : <Button type="link" size="small" onClick={() => handleMarkRead(r.id)}>标记已读</Button>}
        </Space>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        title="通知中心"
        description="系统内所有协作消息：策划书、签到、新媒体、排班、素拓、党务等"
        extra={
          <Button icon={<CheckOutlined />} onClick={handleMarkAll}>
            全部已读
          </Button>
        }
      />

      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space wrap>
          <Select
            placeholder="按类型筛选"
            allowClear
            style={{ width: 160 }}
            value={typeFilter}
            onChange={setTypeFilter}
            options={Object.entries(TYPE_LABEL).map(([v, l]) => ({ value: v, label: l }))}
          />
          <Button icon={<ReloadOutlined />} onClick={fetchList}>
            刷新
          </Button>
        </Space>
      </GlassCard>

      <GlassTable<NotificationRow>
        columns={columns}
        dataSource={data}
        rowKey="id"
        loading={loading}
        locale={{ emptyText: <Empty description="暂无通知" /> }}
        pagination={{
          current: page,
          pageSize: size,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => {
            setPage(p)
            setSize(s)
          },
        }}
      />

      <GlassCard style={{ padding: 16, marginTop: 16 }}>
        <div style={{ fontWeight: 'calc(600 - var(--font-weight-compensation))', marginBottom: 12 }}>通知偏好设置</div>
        <Spin spinning={prefLoading}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 8 }}>
            {prefs.map((p) => (
              <div
                key={p.type}
                style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '8px 12px', border: '1px solid var(--border)', borderRadius: 8 }}
              >
                <span style={{ fontSize: 14 }}>
                  {TYPE_LABEL[p.type] ?? p.type}
                  {p.system && <Tag color="red" style={{ marginLeft: 6 }}>系统</Tag>}
                </span>
                <Switch size="small" checked={p.enabled} disabled={p.system} onChange={(v) => togglePref(p.type, v)} />
              </div>
            ))}
          </div>
          <div style={{ marginTop: 8, color: 'var(--text-secondary)', fontSize: 13 }}>
            系统级通知（如密码重置）不可关闭
          </div>
        </Spin>
      </GlassCard>
    </div>
  )
}
