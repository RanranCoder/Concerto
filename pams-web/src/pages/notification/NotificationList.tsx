import { useCallback, useEffect, useState } from 'react'
import { Alert, App, Button, Empty, Pagination, Select, Segmented, Spin, Switch, Tag } from 'antd'
import PendingWorkList from '@/components/notification/PendingWorkList'
import NotificationOperations from '@/components/notification/NotificationOperations'
import { CheckOutlined, ReloadOutlined, SettingOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useNotificationStore } from '@/stores/notification'
import { useAuthStore } from '@/stores/auth'
import { notificationRoute } from '@/utils/notificationRoute'
import GlassCard from '@/components/glass/GlassCard'
import FormSurface from '@/components/form/FormSurface'
import { TYPE_LABEL, preferenceGroup } from '@/components/notification/notificationLabels'
import '@/styles/notification-v6.css'
import PageHeader from '@/components/glass/PageHeader'
import {
  getNotificationPreferences,
  listNotificationsPage,
  saveNotificationPreferences,
} from '@/api/notificationPage'

// 对照后端 NotificationType 枚举：TASK_ASSIGNED, PLAN_SUBMITTED, PLAN_APPROVED, PLAN_REJECTED, SYSTEM, PLAN_MODIFIED, NEWS_UPLOADED, SIGNIN_ROSTER_UPLOADED, SIGNIN_COMPLETED, ARTICLE_SUBMITTED, ARTICLE_ASSIGNED, ARTICLE_APPROVED, ARTICLE_REJECTED, ARTICLE_PUBLISHED, ARTICLE_DEADLINE_REMINDER, ANNOUNCEMENT

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
  const canOperate = useAuthStore(s => s.user?.managementCapabilities?.includes('org:audit:view') ?? false)
  const [searchParams, setSearchParams] = useSearchParams()
  const requestedView = searchParams.get('view')
  const view = requestedView === 'pending' || (requestedView === 'operations' && canOperate) ? requestedView : 'messages'
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [error, setError] = useState(false)
  const [prefsError, setPrefsError] = useState(false)
  const [prefsReady, setPrefsReady] = useState(false)
  const [markingAll, setMarkingAll] = useState(false)
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
    setError(false)
    let active = true
    listNotificationsPage({ type: typeFilter, page, size })
      .then((res) => {
        if (!active || token !== useAuthStore.getState().token) return
        setData((res.records ?? []).map((r) => ({ ...r, key: r.id })))
        setTotal(res.total)
      })
      .catch(() => { if (active) setError(true) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [typeFilter, page, size])

  useEffect(() => {
    return fetchList()
  }, [fetchList, revision])

  const fetchPrefs = useCallback(() => {
    setPrefsError(false)
    setPrefsReady(false)
    getNotificationPreferences()
      .then((res) => setPrefs(res ?? []))
      .catch(() => setPrefsError(true))
      .finally(() => setPrefsReady(true))
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

  return <div className="notification-page">
    <PageHeader title="通知中心" description="集中查看协作消息，及时处理相关事项" extra={<>
      <Button aria-label="通知设置" icon={<SettingOutlined />} onClick={() => setSettingsOpen(true)}>通知设置</Button>
      <Button icon={<CheckOutlined />} loading={markingAll} onClick={() => { setMarkingAll(true); void handleMarkAll().finally(() => setMarkingAll(false)) }}>全部已读</Button>
    </>} />
    <div className="notification-nav"><Segmented aria-label="通知视图" value={view} onChange={value => {
      const next = new URLSearchParams(searchParams)
      if (value === 'messages') next.delete('view'); else next.set('view', String(value))
      setSearchParams(next)
    }} options={[{ label: '消息记录', value: 'messages' }, { label: '待我处理', value: 'pending' }, ...(canOperate ? [{ label: '管线运维', value: 'operations' }] : [])]} /></div>
    {view === 'operations' && canOperate && <div className="notification-operations"><NotificationOperations /></div>}
    {view === 'pending' && <GlassCard className="notification-pending"><PendingWorkList /></GlassCard>}
    {view === 'messages' && <section className="notification-inbox" aria-label="消息记录">
      <div className="notification-toolbar"><Select aria-label="按类型筛选" placeholder="全部类型" allowClear value={typeFilter} onChange={value => { setTypeFilter(value); setPage(1) }} options={Object.entries(TYPE_LABEL).map(([value, label]) => ({ value, label }))} /><Button icon={<ReloadOutlined />} loading={loading} onClick={() => fetchList()}>刷新</Button></div>
      {error && <Alert type="error" showIcon message="消息加载失败，请重试" action={<Button onClick={() => fetchList()}>重试</Button>} />}
      <Spin spinning={loading}>
        {!data.length ? <div className="notification-empty"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={error ? '暂时无法显示消息' : typeFilter ? '没有匹配的通知' : '暂无通知'} /></div> : <ul className="notification-list">{data.map(row => {
          const route = notificationRoute(row)
          return <li key={row.id} className={'notification-row' + (!row.read ? ' is-unread' : '')}>
            <div className="notification-message"><div className="notification-title"><span className="notification-dot" aria-label={row.read ? '已读消息' : '未读消息'} /><h3>{row.title}</h3>{row.priority === 'URGENT' && <Tag color="error">紧急</Tag>}</div>
              <p className="notification-summary">{row.content}</p><div className="notification-meta"><span>{TYPE_LABEL[row.type] ?? row.type}</span><span>{row.senderName || '系统'}</span><time dateTime={row.createdAt}>{row.createdAt ? dayjs(row.createdAt).format('MM-DD HH:mm') : '—'}</time></div></div>
            <div className="notification-actions">{route && <Button type="link" onClick={async () => { if (!row.read) await handleMarkRead(row.id); navigate(route) }}>{row.actionState === 'open' ? (row.type === 'PLAN_SUBMITTED' || row.type === 'ARTICLE_SUBMITTED' ? '去审核' : '去处理') : '查看'}</Button>}{row.read ? <span className="notification-read">已读</span> : <Button type="text" onClick={() => handleMarkRead(row.id)}>标记已读</Button>}</div>
          </li>
        })}</ul>}
      </Spin>
      <div className="notification-pagination"><Pagination current={page} pageSize={size} total={total} showSizeChanger showTotal={t => `共 ${t} 条`} onChange={(p, s) => { setPage(p); setSize(s) }} /></div>
    </section>}
    <FormSurface mode="drawer" title="通知设置" description="选择需要接收的协作消息，修改后自动保存" open={settingsOpen} onCancel={() => setSettingsOpen(false)} width={520} footer={<Button aria-label="完成" onClick={() => setSettingsOpen(false)}>完成</Button>}>
      {prefsError ? <Alert type="error" message="通知设置加载失败" action={<Button onClick={fetchPrefs}>重试</Button>} /> : <Spin spinning={!prefsReady || prefLoading}>
        {prefsReady && !prefs.length && <Empty description="暂无可配置的通知" />}
        {['任务与活动', '新媒体与推文', '排班与签到', '组织与系统'].map(group => {
          const items = prefs.filter(p => preferenceGroup(p.type) === group)
          return items.length > 0 && <section className="notification-pref-group" key={group}><h3>{group}</h3>{items.map(p => <div className="notification-pref-row" key={p.type}><span>{TYPE_LABEL[p.type] ?? p.type}{p.system && <small>系统必收</small>}</span><Switch aria-label={TYPE_LABEL[p.type] ?? p.type} size="small" checked={p.enabled} disabled={p.system || prefLoading} onChange={v => togglePref(p.type, v)} /></div>)}</section>
        })}<p className="notification-pref-note">密码重置等系统级通知不可关闭。</p>
      </Spin>}
    </FormSurface>
  </div>
}
