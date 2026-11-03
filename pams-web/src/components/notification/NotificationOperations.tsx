import { useEffect, useState } from 'react'
import { Alert, Button, Space, Switch } from 'antd'
import GlassCard from '@/components/glass/GlassCard'
import GlassTable from '@/components/glass/GlassTable'
import { get, post, put } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import NotificationPolicyPanel from './NotificationPolicyPanel'

interface Metrics { states: { status: string; count: number }[]; oldestPendingAt: string | null; retried: number; failures: { id: number; status: string; attempts: number; last_error: string | null; created_at: string }[] }
export default function NotificationOperations() {
  const token = useAuthStore(s => s.token)
  const [data, setData] = useState<Record<string, Metrics>>({})
  const [identity, setIdentity] = useState(token)
  const [error, setError] = useState(false)
  const [loading, setLoading] = useState(true)
  const [revision, setRevision] = useState(0)
  const [audit, setAudit] = useState<Record<string, unknown>[]>([])
  const [incidents, setIncidents] = useState<Record<string, unknown>[]>([])
  const [subscribed, setSubscribed] = useState(false)
  const [savingSubscription, setSavingSubscription] = useState(false)
  useEffect(() => {
    let active = true
    setData({}); setAudit([]); setIncidents([]); setSubscribed(false); setLoading(true); setError(false)
    void Promise.all([get<Record<string, Metrics>>('/notifications/operations'), get<Record<string, unknown>[]>('/notifications/operations/audit'), get<Record<string, unknown>[]>('/notifications/operations/incidents'), get<{ enabled: boolean }>('/notifications/operations/subscription')])
      .then(([result, auditRows, incidentRows, subscription]) => { if(active) { setData(result); setAudit(auditRows); setIncidents(incidentRows); setSubscribed(subscription.enabled === true); setIdentity(token) } }).catch(() => { if(active) setError(true) }).finally(() => { if(active) setLoading(false) })
    return () => { active = false }
  }, [token, revision])
  return <>
    <NotificationPolicyPanel />
    <Space wrap className="notification-ops-toolbar"><Button onClick={() => setRevision(n => n + 1)} loading={loading}>刷新管线状态</Button><span>仅显示当前组织的处理状态与错误类别</span></Space>
    <GlassCard style={{ padding: 16, marginBottom: 16 }}><Space><span>订阅本人维护告警</span><Switch aria-label="订阅本人维护告警" checked={identity === token && subscribed} disabled={loading || identity !== token} loading={savingSubscription} onChange={async enabled => {
      setSavingSubscription(true)
      try { const result = await put<{ enabled: boolean }>('/notifications/operations/subscription', { enabled }); if(token === useAuthStore.getState().token) setSubscribed(result.enabled) }
      catch { if(token === useAuthStore.getState().token) setError(true) }
      finally { setSavingSubscription(false) }
    }} /></Space><p>默认关闭。开启后，同类故障每天最多通知一次；后续次数继续在台账中更新。即时提醒遵循通知偏好。</p></GlassCard>
    {error && <Alert type="error" message="管线状态加载失败，请刷新重试" />}
    {Object.entries(identity === token ? data : {}).map(([table, metrics]) => <GlassCard key={table} style={{ padding: 16, marginBottom: 16 }}>
      <h3>{{ notification_outbox: '收件箱生成', notification_delivery: '在线唤醒', notification_reminder: '定时提醒' }[table] ?? table}</h3>
      <div className="notification-pipeline-states">{metrics.states.length ? metrics.states.map(s => <div key={s.status}><span>{({ PENDING: '待处理', PROCESSING: '处理中', SENT: '已发送', DELIVERED: '已送达', DONE: '已完成', FAILED: '失败', DEAD: '终止', RETRY: '待重试', SKIPPED: '已跳过', CANCELLED: '已取消' } as Record<string, string>)[s.status] ?? s.status}</span><strong>{s.count}</strong></div>) : <span>暂无记录</span>}</div>
      <p>最早待处理：{metrics.oldestPendingAt ?? '无'} · 已重试：{metrics.retried}</p>
      <GlassTable rowKey="id" dataSource={metrics.failures} pagination={false} locale={{ emptyText: '暂无失败记录' }} columns={[
        { title: '记录 ID', dataIndex: 'id' }, { title: '尝试次数', dataIndex: 'attempts' }, { title: '错误类别', dataIndex: 'last_error' },
        { title: '操作', render: (_: unknown, row: { id: number }) => <Button onClick={async () => {
          try { await post(`/notifications/operations/${table.replace('notification_', '')}/${row.id}/retry`); setRevision(n => n + 1) }
          catch { setError(true) }
        }}>重新入队</Button> },
      ]} />
    </GlassCard>)}
    {identity === token && <>
      <GlassCard style={{ padding: 16, marginBottom: 16 }}><h3>知识库维护故障</h3><GlassTable rowKey="id" dataSource={incidents} pagination={false} columns={[
        { title: '类别', dataIndex: 'category' }, { title: '日期', dataIndex: 'window_date' }, { title: '发生次数', dataIndex: 'occurrences' }, { title: '最近发生', dataIndex: 'last_seen_at' },
      ]} locale={{ emptyText: '暂无维护故障' }} /></GlassCard>
      <GlassCard style={{ padding: 16 }}><h3>重新入队操作记录</h3><GlassTable rowKey="id" dataSource={audit} pagination={false} columns={[
        { title: '操作账号 ID', dataIndex: 'actor_id' }, { title: '管线', dataIndex: 'pipeline_kind' }, { title: '记录 ID', dataIndex: 'record_id' }, { title: '原尝试次数', dataIndex: 'previous_attempts' }, { title: '操作时间', dataIndex: 'created_at' },
      ]} locale={{ emptyText: '暂无重新入队操作' }} /></GlassCard>
    </>}
  </>
}
