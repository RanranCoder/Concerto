import { useEffect, useState } from 'react'
import { Alert, Button, Space } from 'antd'
import GlassCard from '@/components/glass/GlassCard'
import GlassTable from '@/components/glass/GlassTable'
import { get, post } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

interface Metrics { states: { status: string; count: number }[]; oldestPendingAt: string | null; retried: number; failures: { id: number; status: string; attempts: number; last_error: string | null; created_at: string }[] }
export default function NotificationOperations() {
  const token = useAuthStore(s => s.token)
  const [data, setData] = useState<Record<string, Metrics>>({})
  const [identity, setIdentity] = useState(token)
  const [error, setError] = useState(false)
  const [loading, setLoading] = useState(true)
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    let active = true
    setData({}); setLoading(true); setError(false)
    void get<Record<string, Metrics>>('/notifications/operations').then(result => { if(active) { setData(result); setIdentity(token) } }).catch(() => { if(active) setError(true) }).finally(() => { if(active) setLoading(false) })
    return () => { active = false }
  }, [token, revision])
  return <>
    <Space style={{ display: 'flex', marginBottom: 16 }}><Button onClick={() => setRevision(n => n + 1)} loading={loading}>刷新管线状态</Button><span>仅显示当前组织的处理状态与错误类别</span></Space>
    {error && <Alert type="error" message="管线状态加载失败，请刷新重试" />}
    {Object.entries(identity === token ? data : {}).map(([table, metrics]) => <GlassCard key={table} style={{ padding: 16, marginBottom: 16 }}>
      <h3>{{ notification_outbox: '收件箱生成', notification_delivery: '在线唤醒', notification_reminder: '定时提醒' }[table] ?? table}</h3>
      <p>{metrics.states.map(s => `${s.status}: ${s.count}`).join(' / ') || '暂无记录'}</p>
      <p>最早待处理：{metrics.oldestPendingAt ?? '无'} · 已重试：{metrics.retried}</p>
      <GlassTable rowKey="id" dataSource={metrics.failures} pagination={false} locale={{ emptyText: '暂无失败记录' }} columns={[
        { title: '记录 ID', dataIndex: 'id' }, { title: '尝试次数', dataIndex: 'attempts' }, { title: '错误类别', dataIndex: 'last_error' },
        { title: '操作', render: (_: unknown, row: { id: number }) => <Button onClick={async () => {
          try { await post(`/notifications/operations/${table.replace('notification_', '')}/${row.id}/retry`); setRevision(n => n + 1) }
          catch { setError(true) }
        }}>重新入队</Button> },
      ]} />
    </GlassCard>)}
  </>
}
