import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Button, Empty, Pagination, Select, Skeleton } from 'antd'
import { errorText, LoadError } from '@/components/admin/AdminSurface'
import FormSurface from '@/components/form/FormSurface'
import { get } from '@/api/http'
import type { PageResult } from '@/api/types'
import './pack-workspace.css'

export type Choice = { id: number; name: string; studentNo?: string; branchName?: string; startDate?: string }

export function usePackQuery<T>(url: string, page?: number, status?: string, generation = 0) {
  const [state, setState] = useState<{ key: string; data?: T; error?: string }>({ key: '' })
  const [revision, setRevision] = useState(0)
  const key = JSON.stringify([url, page, status, revision, generation])
  useEffect(() => {
    let active = true
    get<T>(url, { page, status }).then(data => {
      if (active) setState({ key, data })
    }).catch(error => {
      if (active) setState({ key, error: errorText(error, '记录加载失败，请重试。') })
    })
    return () => { active = false }
  }, [url, page, status, key])
  return { data: state.key === key ? state.data : undefined, error: state.key === key ? state.error : undefined,
    reload: () => setRevision(value => value + 1) }
}

export function RecordList<T extends { id: number }>({ result, error, reload, page, onPage, empty, render }: {
  result?: PageResult<T>; error?: string; reload: () => void; page: number; onPage: (page: number) => void
  empty: string; render: (record: T) => ReactNode
}) {
  if (error) return <LoadError message={error} retry={reload} />
  if (!result) return <Skeleton title={false} paragraph={{ rows: 4 }} />
  return <>
    {result.records.length ? <ul className="pack-rows">{result.records.map(record => <li key={record.id}>{render(record)}</li>)}</ul>
      : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={empty} />}
    {result.total > result.size && <div className="pack-pagination"><Pagination current={page} total={result.total}
      pageSize={result.size} onChange={onPage} showSizeChanger={false} responsive /></div>}
  </>
}

export function ChoiceSelect({ url, value, onChange, initial, placeholder, ...props }: {
  url: string; value?: number; onChange?: (value: number) => void; initial?: Choice; placeholder: string; id?: string; disabled?: boolean
}) {
  const [keyword, setKeyword] = useState('')
  const [choices, setChoices] = useState<Choice[]>(initial ? [initial] : [])
  const [chosen, setChosen] = useState(initial)
  const [loading, setLoading] = useState(false)
  const [failed, setFailed] = useState(false)
  const [revision, retry] = useState(0)
  useEffect(() => {
    let active = true
    const timer = window.setTimeout(() => {
      setLoading(true)
      setFailed(false)
      get<Choice[]>(url, { keyword }).then(rows => { if (active) setChoices(rows) })
        .catch(() => { if (active) { setFailed(true); setChoices([]) } })
        .finally(() => { if (active) setLoading(false) })
    }, 180)
    return () => { active = false; window.clearTimeout(timer) }
  }, [url, keyword, revision])
  const options = new Map(choices.map(row => [row.id, row]))
  if (chosen && !options.has(chosen.id)) options.set(chosen.id, chosen)
  return <Select {...props} value={value} onChange={id => { setChosen(options.get(id)); onChange?.(id) }}
    showSearch filterOption={false} onSearch={setKeyword} loading={loading} placeholder={placeholder}
    notFoundContent={failed ? <Button size="small" onClick={() => retry(v => v + 1)}>加载失败，重试</Button> : loading ? '正在查询…' : '未找到匹配记录'}
    options={[...options.values()].map(row => ({ value: row.id,
      label: [row.name, row.studentNo, row.branchName, row.startDate].filter(Boolean).join(' · ') }))} />
}

export function PackDialog({ title, action = '保存', onClose, onSave, children }: {
  title: string; action?: string; onClose: () => void; onSave: () => Promise<void>; children?: ReactNode
}) {
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const busy = useRef(false)
  async function save() {
    if (busy.current) return
    busy.current = true
    setSaving(true)
    setError('')
    try { await onSave(); onClose() }
    catch (err) { if (!(err && typeof err === 'object' && 'errorFields' in err)) setError(errorText(err, '保存失败，请重试。')) }
    finally { busy.current = false; setSaving(false) }
  }
  return <FormSurface open mode="drawer" title={title} onOk={save} onCancel={saving ? undefined : onClose}
    confirmLoading={saving} closable={!saving} keyboard={!saving} cancelButtonProps={{ disabled: saving, autoInsertSpace: false }}
    okButtonProps={{ autoInsertSpace: false }} okText={action} width={520}>
    {error && <p className="pack-error" role="alert">{error}</p>}
    <fieldset className="pack-fields" disabled={saving}>{children}</fieldset>
  </FormSurface>
}

export function dateLabel(value?: string | null) { return value ? value.replace('T', ' ').slice(0, 16) : '—' }

export function downloadText(filename: string, body: string, type = 'text/plain') {
  const href = URL.createObjectURL(new Blob(['\uFEFF', body], { type: `${type};charset=utf-8` }))
  const anchor = document.createElement('a')
  anchor.href = href
  anchor.download = filename
  anchor.click()
  window.setTimeout(() => URL.revokeObjectURL(href), 1000)
}
