import { useEffect, useState } from 'react'
import { Alert, Button, Empty, Spin } from 'antd'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import { getPendingWork, type PendingWorkSummary } from '@/api/pendingWork'
import { useNotificationStore } from '@/stores/notification'
import { useAuthStore } from '@/stores/auth'
import { notificationRoute } from '@/utils/notificationRoute'

export default function PendingWorkList({ initial }: { initial?: PendingWorkSummary }) {
  const navigate = useNavigate()
  const revision = useNotificationStore(s => s.revision)
  const token = useAuthStore(s => s.token)
  const [data, setData] = useState<PendingWorkSummary | undefined>(initial)
  const [dataIdentity, setDataIdentity] = useState(token)
  const [loading, setLoading] = useState(!initial)
  const [error, setError] = useState(false)
  const [reload, setReload] = useState(0)
  useEffect(() => {
    let active = true
    const refresh = () => {
      if (document.hidden) return
      setError(false)
      void getPendingWork().then(result => { if (active && token === useAuthStore.getState().token) { setData(result); setDataIdentity(token) } })
        .catch(() => { if (active) setError(true) }).finally(() => { if (active) setLoading(false) })
    }
    refresh()
    const timer = setInterval(refresh, 60_000)
    document.addEventListener('visibilitychange', refresh)
    return () => { active = false; clearInterval(timer); document.removeEventListener('visibilitychange', refresh) }
  }, [revision, token, reload])
  if (error) return <Alert type="error" message="待办加载失败" action={<Button onClick={() => setReload(n => n + 1)}>重试</Button>} />
  if (loading || dataIdentity !== token) return <Spin />
  return <>
    <div className="business-section-head"><h3>待我处理</h3><span>{data?.total ?? 0} 项 · 逾期 {data?.overdue ?? 0} 项</span></div>
    {!data?.items.length ? <Empty description="暂无待处理事项" /> : <div className="workbench-tasks">{data.items.map(item => {
      const route = notificationRoute(item)
      return <div className="workbench-task" key={item.key}><div><strong>{item.title}</strong><span className={'tone-tag tone-' + (item.overdue ? 'danger' : 'warning')}>{item.action}</span></div>
        <small>{item.dueAt ? '截止 ' + dayjs(item.dueAt).format('MM-DD HH:mm') : '未设置办理期限'}</small>
        {route && <Button type="link" size="small" onClick={() => navigate(route)}>{item.action}</Button>}
      </div>
    })}</div>}
  </>
}
