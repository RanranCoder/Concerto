import { useEffect, useState } from 'react'
import { Alert, App, Button, Input, Space, Switch } from 'antd'
import GlassCard from '@/components/glass/GlassCard'
import { get, put } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

interface Policy { quietEnabled: boolean; quietStart: string; quietEnd: string; digestEnabled: boolean; digestTime: string; version: number }
interface Metrics { [key: string]: unknown }
export default function NotificationPolicyPanel() {
  const token = useAuthStore(s => s.token)
  const canEdit = useAuthStore(s => s.user?.managementCapabilities?.includes('org:manage') ?? false)
  const { message } = App.useApp()
  const [policy, setPolicy] = useState<Policy | null>(null)
  const [metrics, setMetrics] = useState<Metrics | null>(null)
  const [identity, setIdentity] = useState(token)
  const [error, setError] = useState(false)
  const [saving, setSaving] = useState(false)
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    let active = true
    setPolicy(null); setMetrics(null); setError(false)
    void Promise.all([get<Policy>('/notifications/operations/policy'), get<Metrics>('/notifications/operations/metrics')])
      .then(([p, m]) => { if(active) { setPolicy(p); setMetrics(m); setIdentity(token) } }).catch(() => { if(active) setError(true) })
    return () => { active = false }
  }, [token, revision])
  const shown = identity === token ? policy : null
  return <GlassCard style={{ padding: 16, marginBottom: 16 }}>
    <Space><h3>组织通知策略与指标</h3><Button onClick={() => setRevision(n => n + 1)}>刷新策略与指标</Button></Space>
    {error && <Alert type="error" message="策略或指标操作失败，请刷新后重试" />}
    {shown && <>
      <p>时区：Asia/Shanghai。资讯摘要与免打扰默认关闭，安全告知和业务办理通知保持即时。</p>
      <Space wrap style={{ display: 'flex', marginBottom: 12 }}>
        <span>资讯免打扰</span><Switch aria-label="资讯免打扰" disabled={!canEdit || saving} checked={shown.quietEnabled} onChange={quietEnabled => setPolicy({ ...shown, quietEnabled })} />
        <Input type="time" aria-label="免打扰开始" style={{ width: 130 }} disabled={!canEdit || saving} value={shown.quietStart.slice(0, 5)} onChange={e => setPolicy({ ...shown, quietStart: e.target.value })} />
        <span>至</span><Input type="time" aria-label="免打扰结束" style={{ width: 130 }} disabled={!canEdit || saving} value={shown.quietEnd.slice(0, 5)} onChange={e => setPolicy({ ...shown, quietEnd: e.target.value })} />
      </Space>
      <Space wrap style={{ display: 'flex', marginBottom: 12 }}>
        <span>每日资讯摘要</span><Switch aria-label="每日资讯摘要" disabled={!canEdit || saving} checked={shown.digestEnabled} onChange={digestEnabled => setPolicy({ ...shown, digestEnabled })} />
        <Input type="time" aria-label="摘要时间" style={{ width: 130 }} disabled={!canEdit || saving} value={shown.digestTime.slice(0, 5)} onChange={e => setPolicy({ ...shown, digestTime: e.target.value })} />
        {canEdit && <Button type="primary" loading={saving} onClick={async () => {
          setSaving(true)
          try { const next = await put<Policy>('/notifications/operations/policy', shown); if(token === useAuthStore.getState().token) { setPolicy(next); message.success('组织通知策略已保存') } }
          catch { if(token === useAuthStore.getState().token) setError(true) }
          finally { setSaving(false) }
        }}>保存组织通知策略</Button>}
      </Space>
      <p>开启摘要后，资讯类消息改为每日汇总提示；原消息、未读数与待办实时可见。摘要避开免打扰时段发送。</p>
    </>}
    {identity === token && metrics && <>
      {['outbox', 'delivery', 'reminder'].map(kind => {
        const m = metrics[kind] as Record<string, number | null> | undefined
        return m && <p key={kind}>{({ outbox: '收件箱生成', delivery: '在线唤醒', reminder: '定时提醒' })[kind]}：待处理 {m.pending} · 最老等待 {m.oldestPendingSeconds ?? '-'} 秒 · {kind === 'reminder' ? `记录失败 ${m.recordedFailureAttempts}` : `观测重试 ${m.observedRetryClaims}`} 次 · P95 {m.p95LatencyMs ?? '-'} ms（{m.latencySamples} 个样本）</p>
      })}
      <p>无接收人事实：{String(metrics.noRecipientFacts ?? '-')} · 全部受众已失去资格：{String(metrics.allRecipientsSkippedFacts ?? '-')} · 本实例 SSE 连接：{String(metrics.localSseConnections ?? '-')}</p>
      <p>静默入箱记录：{String(metrics.silentInboxRecords ?? '-')} · 已生成每日摘要：{String(metrics.digestMessages ?? '-')}</p>
      <p>{Array.isArray(metrics.mergeCounters) && (metrics.mergeCounters as { metric_key: string; total_value: number }[]).filter(m => ['fact_merge_hits', 'inbox_duplicate_hits'].includes(m.metric_key)).map(m => `${m.metric_key === 'fact_merge_hits' ? '事务内事实合并' : '个人收件箱重复命中'}：${m.total_value}`).join(' · ')}</p>
      <small>延迟使用最近最多 1000 个完成样本；观测重试从本批上线起累计，在线唤醒指标表示服务端排队结果。历史未记录完成时间的数据不计入样本。</small>
    </>}
  </GlassCard>
}
