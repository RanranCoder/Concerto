import { useEffect, useState } from 'react'
import { Button, Empty, Input, Select, Space } from 'antd'
import { FileTextOutlined, SearchOutlined } from '@ant-design/icons'
import type { TableColumnsType } from 'antd'
import { AdminTable, StatusTag, errorText } from '@/components/admin/AdminSurface'
import { kbEntries, type KnowledgeEntryVO, type KnowledgeTier } from '@/api/knowledge'
import { fmt, scopeLabel, type EntryTarget } from './KnowledgeBase'

export default function KnowledgeEntries({source, refresh, onOpen, onResetSource, canManage, onCreate}: {source?: string; refresh: number; onOpen: (row: EntryTarget) => void; onResetSource: () => void; canManage: boolean; onCreate: () => void}) {
  const [keyword, setKeyword] = useState(''), [search, setSearch] = useState('')
  const [tier, setTier] = useState<KnowledgeTier>(), [sort, setSort] = useState<'updated' | 'title'>('updated')
  const [paging, setPaging] = useState({page: 1, size: 10}), [rows, setRows] = useState<KnowledgeEntryVO[]>([]), [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true), [error, setError] = useState(''), [retry, setRetry] = useState(0)
  useEffect(() => {const timer = setTimeout(() => {setSearch(keyword.trim()); setPaging(p => ({...p, page: 1}))}, 250); return () => clearTimeout(timer)}, [keyword])
  useEffect(() => {setPaging(p => ({...p, page: 1}))}, [source])
  useEffect(() => {
    let active = true; setLoading(true); setError('')
    kbEntries({sourceType: source, keyword: search || undefined, tier, sort, ...paging})
      .then(result => {if (active) {setRows(result.records); setTotal(result.total); if (paging.page > 1 && !result.records.length) setPaging(p => ({...p, page: 1}))}})
      .catch(err => {if (active) {setRows([]); setError(errorText(err, '资料加载失败，请重试。'))}}).finally(() => {if (active) setLoading(false)})
    return () => {active = false}
  }, [source, search, tier, sort, paging, refresh, retry])
  const reset = () => {setKeyword(''); setSearch(''); setTier(undefined); setSort('updated'); setPaging(p => ({...p, page: 1})); onResetSource()}
  const name = (row: KnowledgeEntryVO) => <button className="admin-record-title" onClick={() => onOpen(row)}><span className="admin-file-icon"><FileTextOutlined aria-hidden="true" /></span><span><strong>{row.title || '未命名资料'}</strong><small>{row.sourceLabel} · {row.chunkCount} 个分块{row.path ? ` · ${row.path}` : ''}</small></span></button>
  const match = (row: KnowledgeEntryVO) => <StatusTag tone={row.tier === 'T1' ? 'brand' : 'neutral'}>{row.tier === 'T1' ? '全文检索' : '标题匹配'}</StatusTag>
  const scope = (row: KnowledgeEntryVO) => row.sourceType === 'manual' || row.sourceType === 'snapshot' ? scopeLabel(row.visibility) : '按来源权限'
  const columns: TableColumnsType<KnowledgeEntryVO> = [
    {title: '资料名称 / 来源', key: 'title', render: (_, row) => name(row), width: '42%'},
    {title: '检索方式', key: 'tier', render: (_, row) => <>{match(row)}<small className="admin-cell-note">{row.tier === 'T1' ? '正文可作为引用' : '只检索元数据'}</small></>},
    {title: '可见范围', key: 'visibility', render: (_, row) => <StatusTag>{scope(row)}</StatusTag>},
    {title: '更新于', dataIndex: 'indexedAt', render: (value: string) => fmt(value)},
    {title: '操作', key: 'actions', align: 'right', render: (_, row) => <Button type="link" onClick={() => onOpen(row)}>查看</Button>},
  ]
  return <><div className="admin-toolbar"><Input prefix={<SearchOutlined aria-hidden="true" />} allowClear aria-label="搜索标题、正文或条款" placeholder="搜索标题、正文或条款" value={keyword} onChange={e => setKeyword(e.target.value)} />
    <label>检索<Select aria-label="检索方式" value={tier} allowClear placeholder="全部方式" options={[{value: 'T1', label: '全文检索'}, {value: 'T2', label: '标题匹配'}]} onChange={value => {setTier(value); setPaging(p => ({...p, page: 1}))}} /></label>
    <label>排序<Select aria-label="排序方式" value={sort} options={[{value: 'updated', label: '最近更新'}, {value: 'title', label: '标题顺序'}]} onChange={value => {setSort(value); setPaging(p => ({...p, page: 1}))}} /></label><Button type="link" onClick={reset}>重置</Button></div>
    <AdminTable rows={rows} total={total} {...paging} loading={loading} error={error} retry={() => setRetry(v => v + 1)} onPage={(page, size) => setPaging({page, size})} columns={columns} renderMobile={row => <>{name(row)}<div className="admin-mobile-meta">{match(row)}<StatusTag>{scope(row)}</StatusTag></div><div className="admin-mobile-actions"><small>{fmt(row.indexedAt)}</small><Button type="link" onClick={() => onOpen(row)}>查看</Button></div></>} empty={<Empty description={search || source || tier ? '没有找到匹配资料' : '知识库还没有可见资料'}><Space><Button onClick={reset}>清除筛选</Button>{canManage && <Button type="primary" onClick={onCreate}>录入文档</Button>}</Space></Empty>} />
    <p className="admin-table-note">按资料聚合展示。详情中可核对全部检索分块；业务资料请回到来源页面维护。</p></>
}
