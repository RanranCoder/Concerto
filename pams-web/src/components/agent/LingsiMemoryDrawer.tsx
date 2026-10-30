import { useCallback, useEffect, useRef, useState } from 'react'
import { Alert, Button, Drawer, Input, Spin, Switch } from 'antd'
import { deleteMemory, listMemories, memoryEnabled, memoryMetrics, saveMemory } from '@/api/agentMemory'
import type { AgentMemory, MemoryMetrics } from '@/api/agentMemory'
import './LingsiMemoryDrawer.css'

const statusNames = { ACTIVE: '已生效', PENDING: '待确认', INVALID: '来源已失效', EXPIRED: '已过期' }
const remainingDays = (row: AgentMemory) => Math.max(1, Math.min(365, Math.ceil((new Date(row.expiresAt).getTime() - Date.now()) / 86400000)))
const errorText = (error: unknown) => error instanceof Error ? error.message : '操作未完成，请重试'

export default function LingsiMemoryDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [data, setData] = useState<{ enabled: boolean; records: AgentMemory[] } | null>(null)
  const [metrics, setMetrics] = useState<MemoryMetrics | null>(null)
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState('current')
  const [editing, setEditing] = useState<AgentMemory | 'new' | null>(null)
  const [key, setKey] = useState('')
  const [content, setContent] = useState('')
  const [project, setProject] = useState('')
  const [days, setDays] = useState(90)
  const [removing, setRemoving] = useState<string | null>(null)
  const generation = useRef(0)
  const editor = useRef<HTMLFormElement>(null)
  useEffect(() => {
    if (!editing) return
    editor.current?.scrollIntoView({ block: 'start', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })
    editor.current?.querySelector<HTMLInputElement | HTMLTextAreaElement>('input:not(:disabled),textarea:not(:disabled)')?.focus({ preventScroll: true })
  }, [editing])
  const reload = useCallback(async () => {
    const ticket = ++generation.current
    setLoading(true)
    const [records, stats] = await Promise.allSettled([listMemories(), memoryMetrics()])
    if (ticket !== generation.current) return
    if (records.status === 'fulfilled') setData(records.value)
    else { setData(null); setError(errorText(records.reason)) }
    setMetrics(stats.status === 'fulfilled' ? stats.value : null)
    setLoading(false)
  }, [])
  useEffect(() => {
    if (!open) return
    setError(''); setNotice(''); setEditing(null); setRemoving(null)
    void reload()
    return () => { generation.current++ }
  }, [open, reload])
  const action = async (run: () => Promise<unknown>, success: string) => {
    setBusy(true); setError(''); setNotice('')
    try { await run(); setNotice(success); setEditing(null); setRemoving(null); await reload() }
    catch (e) { setError(errorText(e)); await reload() }
    finally { setBusy(false) }
  }
  const edit = (row: AgentMemory | 'new') => {
    setEditing(row); setNotice(''); setRemoving(null)
    setKey(row === 'new' ? '' : row.key); setContent(row === 'new' ? '' : row.content ?? '')
    setProject(row === 'new' || row.scope === 'USER' ? '' : row.scope.slice(8))
    setDays(row === 'new' ? 90 : remainingDays(row))
  }
  const rows = (data?.records ?? []).filter(row => {
    const matches = filter === 'all' || (filter === 'current' ? ['ACTIVE', 'PENDING'].includes(row.status) : row.status === filter)
    return matches && `${row.key} ${row.content ?? ''} ${row.scope}`.toLowerCase().includes(query.toLowerCase())
  })
  const pending = data?.records.filter(row => row.status === 'PENDING').length ?? 0
  return <Drawer title={<h1 className="memory-title">灵思 · 长期记忆</h1>} open={open} onClose={onClose} width={680} rootClassName="lingsi-memory-drawer">
    <section className="memory-sheet" data-ui="memory-settings">
      <div className="memory-setting-row">
        <div><h2>跨会话记住你的习惯</h2><p>仅当前组织内的你可用。当前要求优先，记忆不会代替操作确认。</p></div>
        <Switch aria-label="启用长期记忆" checked={data?.enabled ?? false} disabled={!data || busy || loading}
          onChange={enabled => void action(() => memoryEnabled(enabled), enabled ? '已开启长期记忆' : '已关闭，已有记忆保留供管理')} />
      </div>
      <div aria-live="polite">
        {error && <Alert type="error" message={error} description="版本冲突时请取消编辑，刷新后重新打开。" />}
        {notice && <p className="memory-notice" role="status">{notice}</p>}
      </div>
      {data && !data.enabled && <p className="memory-hint">已关闭：不会读取或新增记忆，仍可查看和删除已有记录。</p>}
      <div className="memory-toolbar">
        <div><strong>你的记忆</strong><span>{data ? `${data.records.length} / 100 条` : '尚未加载'}{pending > 0 ? ` · ${pending} 条待确认` : ''}</span></div>
        <div className="memory-actions"><Button onClick={() => { setError(''); void reload() }} disabled={busy} loading={loading}>刷新</Button>
          <Button onClick={() => edit('new')} disabled={!data?.enabled || busy}>添加记忆</Button></div>
      </div>
      {editing && <form ref={editor} className="memory-editor" aria-label="编辑长期记忆" onSubmit={e => {
        e.preventDefault()
        void action(() => saveMemory({ scope: project.trim() ? `PROJECT:${project.trim()}` : 'USER', key: key.trim(), content: content.trim(), consent: true,
          revision: editing === 'new' ? undefined : editing.revision, days }), '记忆已保存')
      }}>
        <h3>{editing === 'new' ? '添加记忆' : editing.status === 'PENDING' ? '核对候选记忆' : '编辑记忆'}</h3>
        <label htmlFor="memory-key">记忆名称</label><Input id="memory-key" value={key} onChange={e => setKey(e.target.value)} maxLength={80} required disabled={editing !== 'new' || busy} placeholder="例如：风格、语言、排班" />
        <label htmlFor="memory-project">项目范围（可选）</label><Input id="memory-project" value={project} onChange={e => setProject(e.target.value)} maxLength={80} disabled={editing !== 'new' || busy} placeholder="留空为个人偏好" />
        <p className="memory-hint">项目记忆只在问题带有「【项目:项目名】」时使用。</p>
        <label htmlFor="memory-content">记住什么</label><Input.TextArea id="memory-content" value={content} onChange={e => setContent(e.target.value)} required maxLength={600} rows={3} disabled={busy} />
        <label htmlFor="memory-days">有效期（从保存时起，天）</label><input id="memory-days" className="memory-days" type="number" min={1} max={365} required value={days} onChange={e => setDays(Number(e.target.value))} disabled={busy} />
        <div className="memory-actions"><Button onClick={() => setEditing(null)} disabled={busy}>取消编辑</Button><Button htmlType="submit" loading={busy} disabled={!data?.enabled}>保存并启用</Button></div>
      </form>}
      <div className="memory-filters"><Input aria-label="搜索记忆" value={query} onChange={e => setQuery(e.target.value)} placeholder="搜索名称、内容或项目" allowClear />
        <select aria-label="记忆状态" value={filter} onChange={e => setFilter(e.target.value)}><option value="current">当前可用与待确认</option><option value="PENDING">仅待确认</option><option value="ACTIVE">仅已生效</option><option value="all">全部状态</option></select></div>
      {loading && !data ? <div className="memory-empty"><Spin /><p>正在读取你的记忆…</p></div> : !data ? <div className="memory-empty"><p>记忆暂时无法读取</p><Button onClick={() => { setError(''); void reload() }}>重试</Button></div> : rows.length === 0 ?
        <div className="memory-empty"><h3>{query || filter !== 'current' ? '没有匹配的记忆' : '还没有记忆'}</h3><p>可以添加偏好，或在聊天中说「记住：风格=回答简洁」。</p></div> :
        <ul className="memory-list">{rows.map(row => <li key={row.id}>
          <div className="memory-row-heading"><h3>{row.key}</h3><span className={`memory-state memory-state-${row.status.toLowerCase()}`}>{statusNames[row.status]}</span></div>
          <p className="memory-content">{row.content ?? '正文已清除，不再用于回答。'}</p>
          <div className="memory-row-meta"><span>{row.scope === 'USER' ? '个人偏好' : `项目 · ${row.scope.slice(8)}`}</span><span>{new Date(row.expiresAt).toLocaleDateString('zh-CN')} 到期</span></div>
          <div className="memory-row-footer">
            <div className="memory-actions">{row.content && <Button type="text" disabled={!data.enabled || busy} onClick={() => edit(row)}>编辑</Button>}
              {row.status === 'PENDING' && row.content && <Button disabled={!data.enabled || busy} onClick={() => void action(() => saveMemory({ scope: row.scope, key: row.key, content: row.content!, consent: true, revision: row.revision, days: remainingDays(row) }), '候选记忆已确认')}>确认候选</Button>}
              <Button type="text" danger disabled={busy} onClick={() => setRemoving(row.id)}>删除</Button></div>
          </div>
          {removing === row.id && <div className="memory-delete-confirm" role="alert"><span>删除「{row.key}」？删除后新会话不再使用，旧聊天记录会保留。</span><div className="memory-actions"><Button onClick={() => setRemoving(null)} disabled={busy}>取消删除</Button><Button danger loading={busy} onClick={() => void action(() => deleteMemory(row), '已删除该项记忆')}>确认删除</Button></div></div>}
        </li>)}</ul>}
      <details className="memory-observations"><summary>本次运行的效果与用量</summary>
        <p className="memory-hint">仅当前账号，最多保留 24 小时；服务重启会清零。缺少供应商用量时显示「—」。</p>
        {metrics ? <dl>
          <div><dt>摘要调用 / 失败</dt><dd>{metrics.summaryCalls} / {metrics.summaryFailures + (metrics.summaryRejected ?? 0)}{metrics.summaryFailureRate != null ? `（${(metrics.summaryFailureRate * 100).toFixed(1)}%）` : ''}</dd></div>
          <div><dt>平均生成耗时</dt><dd>{metrics.summaryMeanMs == null ? '—' : `${Math.round(metrics.summaryMeanMs)} ms`}</dd></div>
          <div><dt>摘要成功写入 / 缓存命中</dt><dd>{metrics.summaryWrites} / {metrics.summaryCacheHits}</dd></div>
          <div><dt>记忆命中 / 读取</dt><dd>{metrics.memoryHits} / {metrics.memoryReads}</dd></div>
          <div><dt>摘要输入 / 输出 token</dt><dd>{metrics.inputTokens?.toLocaleString() ?? '—'} / {metrics.outputTokens?.toLocaleString() ?? '—'}</dd></div>
        </dl> : <p>统计暂不可用，记忆管理仍可使用。</p>}
      </details>
    </section>
  </Drawer>
}
