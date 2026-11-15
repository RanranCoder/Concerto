import { useCallback, useEffect, useRef, useState } from 'react'
import { Alert, Button, Drawer, Input, Spin, Switch } from 'antd'
import { deleteMemory, listMemories, memoryEnabled, memoryMetrics, saveMemory } from '@/api/agentMemory'
import type { AgentMemory, MemoryMetrics } from '@/api/agentMemory'
import { useAuthStore } from '@/stores/auth'
import { Caret, Lock, Memory, Plus, Reload, Search } from './LingsiIcons'
import './LingsiMemoryDrawer.css'

const statusNames = { ACTIVE: '已生效', PENDING: '待确认', INVALID: '来源已失效', EXPIRED: '已过期' }
const filters = [
  { value: 'current', label: '当前' },
  { value: 'PENDING', label: '待确认' },
  { value: 'ACTIVE', label: '已生效' },
  { value: 'all', label: '全部状态' },
] as const
const remainingDays = (row: AgentMemory) => Math.max(1, Math.min(365, Math.ceil((new Date(row.expiresAt).getTime() - Date.now()) / 86400000)))
const errorText = (error: unknown) => error instanceof Error ? error.message : '操作未完成，请重试'

export default function LingsiMemoryDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const owner = useAuthStore(state => `${state.user?.id}:${state.user?.orgId}`)
  return (
    <Drawer
      title={<h1 className="memory-title"><Memory size={20} />长期记忆</h1>}
      open={open}
      onClose={onClose}
      width={700}
      rootClassName="lingsi-memory-drawer"
    >
      {open && <MemorySheet key={owner} />}
    </Drawer>
  )
}

function MemorySheet() {
  const [data, setData] = useState<Awaited<ReturnType<typeof listMemories>> | null>(null)
  const [metrics, setMetrics] = useState<MemoryMetrics | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState<(typeof filters)[number]['value']>('current')
  const [editing, setEditing] = useState<AgentMemory | 'new' | null>(null)
  const [removing, setRemoving] = useState<string | null>(null)
  const generation = useRef(0)

  const reload = useCallback(async () => {
    const ticket = ++generation.current
    setLoading(true)
    void memoryMetrics().then(
      stats => { if (ticket === generation.current) setMetrics(stats) },
      () => { if (ticket === generation.current) setMetrics(null) },
    )
    try {
      const records = await listMemories()
      if (ticket === generation.current) setData(records)
    } catch (e) {
      if (ticket === generation.current) {
        setData(null)
        setError(errorText(e))
      }
    } finally {
      if (ticket === generation.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    void reload()
    return () => { generation.current++ }
  }, [reload])

  async function action(run: () => Promise<unknown>, success: string) {
    const ticket = generation.current
    setBusy(true)
    setError('')
    setNotice('')
    try {
      await run()
      if (ticket !== generation.current) return
      setNotice(success)
      setEditing(null)
      setRemoving(null)
    } catch (e) {
      if (ticket !== generation.current) return
      setError(errorText(e))
    }
    setBusy(false)
    void reload()
  }

  function startEditing(row: AgentMemory | 'new') {
    setEditing(row)
    setError('')
    setNotice('')
    setRemoving(null)
  }

  function refresh() {
    setError('')
    void reload()
  }

  const records = data?.records ?? []
  const pending = records.filter(row => row.status === 'PENDING').length
  const keyword = query.trim().toLowerCase()
  const rows = records.filter(row => {
    const matches = filter === 'all' || (filter === 'current' ? ['ACTIVE', 'PENDING'].includes(row.status) : row.status === filter)
    return matches && `${row.key} ${row.content ?? ''} ${row.scope}`.toLowerCase().includes(keyword)
  })
  const enabled = data?.enabled ?? false
  const locked = loading || busy

  return (
    <div className="memory-sheet" data-ui="memory-settings">
      <section className="memory-setting" aria-labelledby="memory-setting-title">
        <div>
          <h2 id="memory-setting-title">跨会话记忆</h2>
          <p>{data && !enabled ? '灵思已暂停检索和保存记忆。你仍可查看或删除已有条目。' : '灵思会在后续对话中参考相关偏好，以你当次的要求为准。'}</p>
        </div>
        <div className="memory-switch">
          <span aria-hidden="true">{data ? (enabled ? '已开启' : '已关闭') : '—'}</span>
          <Switch aria-label="启用长期记忆" checked={enabled} disabled={!data || locked}
            onChange={next => void action(() => memoryEnabled(next), next ? '长期记忆已开启' : '长期记忆已关闭')} />
        </div>
        <p className="memory-private"><Lock size={14} />仅当前组织内的你可管理和使用</p>
      </section>

      {error && <Alert className="memory-feedback" type="error" showIcon message={error} />}
      <div role="status" className="memory-notice">{notice}</div>

      {editing && <MemoryEditor
        record={editing}
        locked={locked}
        enabled={enabled}
        onCancel={() => { setEditing(null); setError('') }}
        onSave={values => void action(() => saveMemory(values), editing !== 'new' && editing.status === 'PENDING' ? '候选记忆已确认' : '记忆已保存')}
      />}

      <section aria-labelledby="memory-library-title" hidden={!!editing}>
        <div className="memory-library-head">
          <div>
            <h2 id="memory-library-title">记忆清单</h2>
            {data && <span className="memory-count">已保存 {records.length} / 100 条</span>}
          </div>
          <Button type="primary" icon={<Plus />} disabled={!enabled || locked} onClick={() => startEditing('new')}>添加记忆</Button>
        </div>

        <div className="memory-filters">
          <Input aria-label="搜索记忆" value={query} onChange={e => setQuery(e.target.value)} placeholder="搜索名称、内容或项目" prefix={<Search />} allowClear />
          <Button title="刷新记忆" aria-label="刷新记忆" icon={<Reload />} onClick={refresh} loading={loading} disabled={busy} />
        </div>
        <div className="memory-status-filters" role="group" aria-label="记忆状态">
          {filters.map(item => (
            <button type="button" key={item.value} aria-pressed={filter === item.value} onClick={() => { setFilter(item.value); setRemoving(null) }}>
              {item.value === 'ACTIVE' && data && !enabled ? '已暂停' : item.label}
              {item.value === 'PENDING' && pending > 0 && <span>{pending}</span>}
            </button>
          ))}
        </div>

        {loading && !data ? (
          <div className="memory-empty" role="status"><Spin /><p>正在读取你的记忆…</p></div>
        ) : !data ? (
          <div className="memory-empty"><h3>记忆暂时无法读取</h3><Button onClick={refresh}>重试</Button></div>
        ) : rows.length === 0 ? (
          <div className="memory-empty">
            <Memory size={24} />
            <h3>{keyword || filter !== 'current' || records.length > 0 ? '没有匹配的记忆' : '还没有记忆'}</h3>
            {keyword || filter !== 'current' || records.length > 0
              ? <p>可修改搜索条件或查看全部状态。</p>
              : enabled && <p>你也可以在对话中输入「记住：风格=回答简洁」。</p>}
          </div>
        ) : (
          <ul className="memory-list">{rows.map(row => (
            <li key={row.id}>
              <div className="memory-row-body">
                <div className="memory-row-heading">
                  <h3>{row.key}</h3>
                  <span className={`memory-state memory-state-${row.status.toLowerCase()}`} data-paused={!enabled && row.status === 'ACTIVE'}>
                    {!enabled && row.status === 'ACTIVE' ? '已暂停' : statusNames[row.status]}
                  </span>
                </div>
                <p className="memory-content">{row.content ?? '正文已清除'}</p>
                <div className="memory-row-meta">
                  <span>{row.scope === 'USER' ? '个人偏好' : `项目：${row.scope.slice(8)}`}</span>
                  <span>{row.sourceConversationId != null ? '来自对话' : '独立保存'}</span>
                  <time dateTime={row.expiresAt}>{new Date(row.expiresAt).toLocaleDateString('zh-CN')} {row.status === 'EXPIRED' ? '已到期' : '到期'}</time>
                </div>
              </div>
              <div className="memory-row-actions">
                {row.content && <Button type="text" disabled={!enabled || locked} onClick={() => startEditing(row)}>编辑</Button>}
                {row.status === 'PENDING' && row.content != null && (
                  <Button disabled={!enabled || locked} onClick={() => {
                    const { scope, key, content, revision } = row
                    if (content != null) void action(() => saveMemory({ scope, key, content, consent: true, revision, days: remainingDays(row) }), '候选记忆已确认')
                  }}>确认候选</Button>
                )}
                <Button type="text" disabled={locked} onClick={() => setRemoving(row.id)}>删除</Button>
              </div>
              {removing === row.id && (
                <div className="memory-delete-confirm" role="alert">
                  <span>删除「{row.key}」？此操作会移除该条长期记忆，相关对话历史会保留。</span>
                  <div className="memory-actions">
                    <Button onClick={() => setRemoving(null)} disabled={locked}>取消删除</Button>
                    <Button danger loading={busy} disabled={loading} onClick={() => void action(() => deleteMemory(row), '已删除该项记忆')}>确认删除</Button>
                  </div>
                </div>
              )}
            </li>
          ))}</ul>
        )}

        <details className="memory-observations">
          <summary><Caret size={14} />记忆使用统计</summary>
          <p className="memory-hint">仅统计当前账号在本服务进程中的活动，最多保留 24 小时。</p>
          {metrics ? <dl>
            <div><dt>记忆命中 / 读取请求</dt><dd>{metrics.memoryHits} / {metrics.memoryReads}</dd></div>
            <div><dt>摘要调用 / 失败次数</dt><dd>{metrics.summaryCalls} / {metrics.summaryFailures + (metrics.summaryRejected ?? 0)}{metrics.summaryFailureRate != null ? `（${(metrics.summaryFailureRate * 100).toFixed(1)}%）` : ''}</dd></div>
            <div><dt>平均摘要耗时</dt><dd>{metrics.summaryMeanMs == null ? '—' : `${Math.round(metrics.summaryMeanMs)} ms`}</dd></div>
            <div><dt>摘要写入 / 缓存命中次数</dt><dd>{metrics.summaryWrites} / {metrics.summaryCacheHits}</dd></div>
            <div><dt>摘要输入 / 输出 token</dt><dd>{metrics.inputTokens?.toLocaleString() ?? '—'} / {metrics.outputTokens?.toLocaleString() ?? '—'}</dd></div>
          </dl> : <p>统计暂不可用</p>}
        </details>
      </section>
    </div>
  )
}

function MemoryEditor({ record, locked, enabled, onCancel, onSave }: {
  record: AgentMemory | 'new'
  locked: boolean
  enabled: boolean
  onCancel: () => void
  onSave: (values: Parameters<typeof saveMemory>[0]) => void
}) {
  const isNew = record === 'new'
  const [key, setKey] = useState(isNew ? '' : record.key)
  const [content, setContent] = useState(isNew ? '' : record.content ?? '')
  const [project, setProject] = useState(isNew || record.scope === 'USER' ? '' : record.scope.slice(8))
  const [days, setDays] = useState(isNew ? '90' : String(remainingDays(record)))
  const form = useRef<HTMLFormElement>(null)

  useEffect(() => {
    form.current?.scrollIntoView({ block: 'nearest' })
    form.current?.querySelector<HTMLInputElement | HTMLTextAreaElement>('input:not(:disabled), textarea')?.focus({ preventScroll: true })
  }, [])

  return (
    <form ref={form} className="memory-editor" aria-label="编辑长期记忆" onSubmit={e => {
      e.preventDefault()
      if (!enabled || locked || !key.trim() || !content.trim()) return
      onSave({
        scope: project.trim() ? `PROJECT:${project.trim()}` : 'USER',
        key: key.trim(),
        content: content.trim(),
        consent: true,
        revision: isNew ? undefined : record.revision,
        days: Number(days),
      })
    }}>
      <h2>{isNew ? '添加记忆' : record.status === 'PENDING' ? '核对候选记忆' : '编辑记忆'}</h2>
      <div className="memory-editor-columns">
        <div className="memory-field">
          <label htmlFor="memory-key">记忆名称</label>
          <Input id="memory-key" value={key} onChange={e => setKey(e.target.value)} maxLength={80} required disabled={!isNew || locked} placeholder="例如：风格、语言、排班" />
        </div>
        <div className="memory-field">
          <label htmlFor="memory-project">项目范围（可选）</label>
          <Input id="memory-project" aria-describedby="memory-project-hint" value={project} onChange={e => setProject(e.target.value)} maxLength={80} disabled={!isNew || locked} placeholder="留空为个人偏好" />
        </div>
        <p id="memory-project-hint" className="memory-hint">项目记忆在对话中带有「【项目:项目名】」时匹配。自定义个人偏好按记忆名称匹配。</p>
      </div>
      <div className="memory-field">
        <label htmlFor="memory-content">记住什么</label>
        <Input.TextArea id="memory-content" value={content} onChange={e => setContent(e.target.value)} required maxLength={600} rows={4} showCount disabled={locked} />
      </div>
      <div className="memory-field memory-expiry">
        <label htmlFor="memory-days">有效期（从保存时起，天）</label>
        <Input id="memory-days" type="number" min={1} max={365} required value={days} onChange={e => setDays(e.target.value)} disabled={locked} />
      </div>
      <div className="memory-actions">
        <Button onClick={onCancel} disabled={locked}>取消编辑</Button>
        <Button type="primary" htmlType="submit" disabled={!enabled || locked || !key.trim() || !content.trim()}>保存并启用</Button>
      </div>
    </form>
  )
}
