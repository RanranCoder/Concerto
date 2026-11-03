import { useEffect, useState } from 'react'
import { Alert, Button, Empty, Input, Select, Space } from 'antd'
import type { TableColumnsType } from 'antd'
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import FormSurface from '@/components/form/FormSurface'
import { AdminTable, InfoList, StatusTag, errorText } from '@/components/admin/AdminSurface'
import { kbRetrievals, kbSnapshots, kbSnapshotSpecs, type KbRetrievalLogVO, type KbSnapshotSpecVO, type KbSnapshotVO } from '@/api/knowledge'
import { fmt, scopeLabel, type EntryTarget } from './KnowledgeBase'

const labels: Record<string, string> = {NO_HIT: '没有依据', BELOW_THRESHOLD: '相关度过低', NO_CITATION: '未标注出处', SUCCESS: '已引用依据'}
const resultTag = (row: KbRetrievalLogVO) => <StatusTag tone={row.skipReason ? 'warning' : 'success'}>{labels[row.skipReason || 'SUCCESS'] || row.skipReason}</StatusTag>
function parseHits(value: string | null): {sourceType: string; sourceId: number; title?: string; path?: string; seq?: number}[] {
  try {const list: unknown = JSON.parse(value || '[]'); return Array.isArray(list) ? list.filter(item => item && typeof item.sourceType === 'string' && Number.isSafeInteger(item.sourceId) && item.sourceId > 0) : []} catch {return []}
}
function useKeyword() {
  const [keyword, setKeyword] = useState(''), [search, setSearch] = useState('')
  useEffect(() => {const timer = setTimeout(() => setSearch(keyword.trim()), 250); return () => clearTimeout(timer)}, [keyword])
  return {keyword, setKeyword, search}
}

export function RetrievalRecords({refresh, onOpen, sourceName}: {refresh: number; onOpen: (target: EntryTarget) => void; sourceName: (type: string) => string}) {
  const navigate = useNavigate(), {keyword, setKeyword, search} = useKeyword()
  const [skipReason, setSkipReason] = useState<string>(), [paging, setPaging] = useState({page: 1, size: 10})
  const [rows, setRows] = useState<KbRetrievalLogVO[]>([]), [total, setTotal] = useState(0), [loading, setLoading] = useState(true)
  const [error, setError] = useState(''), [retry, setRetry] = useState(0), [detail, setDetail] = useState<KbRetrievalLogVO>()
  useEffect(() => {setPaging(p => ({...p, page: 1}))}, [search, skipReason])
  useEffect(() => {
    let active = true; setLoading(true); setError('')
    kbRetrievals({keyword: search || undefined, skipReason, ...paging}).then(result => {if (active) {setRows(result.records); setTotal(result.total)}})
      .catch(err => {if (active) setError(errorText(err, '检索记录加载失败，请重试。'))}).finally(() => {if (active) setLoading(false)})
    return () => {active = false}
  }, [search, skipReason, paging, refresh, retry])
  const columns: TableColumnsType<KbRetrievalLogVO> = [
    {title: '提问内容', dataIndex: 'queryText', width: '40%', render: (text: string) => <strong>{text}</strong>},
    {title: '检索结果', key: 'result', render: (_, row) => resultTag(row)},
    {title: '可用 / 命中', key: 'counts', render: (_, row) => `${row.allowedCount} / ${row.hitCount}`},
    {title: '时间', dataIndex: 'createdAt', render: fmt},
    {title: '操作', key: 'actions', align: 'right', render: (_, row) => <Button type="link" onClick={() => setDetail(row)}>查看详情</Button>},
  ]
  const hits = detail ? parseHits(detail.hitsJson) : []
  const reasons: Record<string, string> = {NO_HIT: '当前可见资料中没有命中内容。可补充相关制度、指引，或尝试更具体的提问。', BELOW_THRESHOLD: '命中的资料与提问相关度不足，没有采用弱相关内容生成答案。', NO_CITATION: '生成结果缺少可核验的出处，系统未直接展示答案。'}
  return <><div className="admin-toolbar"><Input prefix={<SearchOutlined aria-hidden="true" />} aria-label="搜索我的提问" placeholder="搜索我的提问" value={keyword} allowClear onChange={e => setKeyword(e.target.value)} /><Select aria-label="检索结果" placeholder="全部结果" allowClear value={skipReason} options={Object.entries(labels).map(([value, label]) => ({value, label}))} onChange={setSkipReason} /><Button type="link" onClick={() => {setKeyword(''); setSkipReason(undefined)}}>重置</Button></div>
    <p className="admin-pane-note">仅展示当前账号的检索记录；结果统计对应提问当时，引用详情按当前来源权限重新校验。</p>
    <AdminTable rows={rows} total={total} {...paging} loading={loading} error={error} retry={() => setRetry(v => v + 1)} onPage={(page, size) => setPaging({page, size})} columns={columns} renderMobile={row => <><strong>{row.queryText}</strong><div className="admin-mobile-meta">{resultTag(row)}<small>可用 {row.allowedCount} / 命中 {row.hitCount}</small></div><div className="admin-mobile-actions"><small>{fmt(row.createdAt)}</small><Button type="link" onClick={() => setDetail(row)}>查看详情</Button></div></>} empty={<Empty description="没有匹配的检索记录" />} />
    <p className="admin-table-note">命中内容经权限复核后成为可用依据；回答仍需通过相关度与出处检查。</p>
    <FormSurface mode="drawer" title="检索记录详情" description={fmt(detail?.createdAt)} open={!!detail} onCancel={() => setDetail(undefined)} footer={<Space><Button onClick={() => setDetail(undefined)}>关闭</Button><Button type="primary" onClick={() => navigate('/agent')}>进入concerto</Button></Space>}>
      {detail && <><article className="admin-article">{detail.queryText}</article><h3 className="admin-section-title">{resultTag(detail)}</h3><p className="admin-note">{detail.skipReason ? reasons[detail.skipReason] || detail.skipReason : '回答通过相关度与出处检查，可按引用定位核对原文。'}</p>
        <h3 className="admin-section-title">依据筛选过程</h3><div className="admin-log-flow"><div>命中内容<strong>{detail.hitCount}</strong></div><div>权限复核排除<strong>{detail.filteredCount}</strong></div><div>记录时可用<strong>{detail.allowedCount}</strong></div></div>
        <p className="admin-note">被排除或已无访问权的资料不展示标题和正文。</p><h3 className="admin-section-title">当前可查看的引用</h3>
        {hits.length ? hits.map((hit, index) => <button className="admin-hit" key={index} onClick={() => {setDetail(undefined); onOpen({sourceType: hit.sourceType, sourceId: hit.sourceId, title: hit.title})}}><strong>{hit.title || sourceName(hit.sourceType)}</strong><small>{hit.path || `分块 ${hit.seq ?? ''}`}</small></button>) : <Empty description="暂无当前可查看的引用" />}
        <InfoList items={[{label: '检索耗时', value: detail.scanMs == null ? '—' : `${detail.scanMs} ms`}, {label: '回答模式', value: detail.degraded ? '规则模式' : detail.provider || '—'}]} /></>}
    </FormSurface>
  </>
}

export function SnapshotRecords({refresh, canManage, onRefresh}: {refresh: number; canManage: boolean; onRefresh: () => void}) {
  const {keyword, setKeyword, search} = useKeyword()
  const [specs, setSpecs] = useState<KbSnapshotSpecVO[]>([]), [specCode, setSpecCode] = useState<string>()
  const [paging, setPaging] = useState({page: 1, size: 10}), [rows, setRows] = useState<KbSnapshotVO[]>([]), [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true), [error, setError] = useState(''), [specError, setSpecError] = useState(''), [retry, setRetry] = useState(0)
  const [detail, setDetail] = useState<KbSnapshotVO>(), [showSpecs, setShowSpecs] = useState(false)
  useEffect(() => {let active = true; kbSnapshotSpecs().then(list => {if (active) {setSpecs(list); setSpecError('')}}).catch(() => {if (active) setSpecError('快照模板读取失败，仍可浏览快照。')}); return () => {active = false}}, [refresh, retry])
  useEffect(() => {setPaging(p => ({...p, page: 1}))}, [search, specCode])
  useEffect(() => {
    let active = true; setLoading(true); setError('')
    kbSnapshots({specCode, keyword: search || undefined, ...paging}).then(result => {if (active) {setRows(result.records); setTotal(result.total)}})
      .catch(err => {if (active) setError(errorText(err, '事实快照加载失败，请重试。'))}).finally(() => {if (active) setLoading(false)})
    return () => {active = false}
  }, [search, specCode, paging, refresh, retry])
  const specName = (code: string) => specs.find(s => s.code === code)?.titleTemplate.replace('%s', '').trim() || code
  const columns: TableColumnsType<KbSnapshotVO> = [
    {title: '快照名称', key: 'title', width: '35%', render: (_, row) => <button className="admin-record-title" onClick={() => setDetail(row)}><span><strong>{row.title}</strong><small>{specName(row.specCode)} · 仅汇总计数</small></span></button>},
    {title: '数据截至', key: 'asOf', render: (_, row) => <><StatusTag tone="brand">{fmt(row.asOf)}</StatusTag><small className="admin-cell-note">生成于 {fmt(row.generatedAt)}</small></>},
    {title: '可见范围', dataIndex: 'visibility', render: scopeLabel},
    {title: '更新频率', dataIndex: 'refreshHint', render: (v: string | null) => v || '—'},
    {title: '操作', key: 'actions', align: 'right', render: (_, row) => <Button type="link" onClick={() => setDetail(row)}>查看快照</Button>},
  ]
  return <>{specError && <div className="admin-pane-note"><Alert type="warning" message={specError} action={<Button size="small" onClick={() => setRetry(v => v + 1)}>重试</Button>} /></div>}
    <div className="admin-toolbar"><Input prefix={<SearchOutlined aria-hidden="true" />} aria-label="搜索快照名称" placeholder="搜索快照名称" value={keyword} allowClear onChange={e => setKeyword(e.target.value)} /><Select aria-label="快照模板" value={specCode} allowClear placeholder="全部模板" options={specs.map(s => ({value: s.code, label: specName(s.code)}))} onChange={setSpecCode} /><Button type="link" onClick={() => setShowSpecs(true)}>查看模板说明</Button>{canManage && <Button icon={<ReloadOutlined aria-hidden="true" />} onClick={onRefresh}>刷新快照</Button>}</div>
    <p className="admin-pane-note">按日生成的业务汇总。数字对应“数据截至”时间，最新情况请以业务页面为准。</p>
    <AdminTable rows={rows} total={total} {...paging} loading={loading} error={error} retry={() => setRetry(v => v + 1)} onPage={(page, size) => setPaging({page, size})} columns={columns} renderMobile={row => <><strong>{row.title}</strong><div className="admin-mobile-meta"><StatusTag tone="brand">截至 {fmt(row.asOf)}</StatusTag><StatusTag>{scopeLabel(row.visibility)}</StatusTag></div><div className="admin-mobile-actions"><small>{row.refreshHint || '—'}</small><Button type="link" onClick={() => setDetail(row)}>查看快照</Button></div></>} empty={<Empty description="没有匹配的事实快照" />} />
    <p className="admin-table-note">快照沿用来源访问权限，不包含成员个人明细。</p>
    <FormSurface mode="drawer" title={detail?.title || '事实快照'} open={!!detail} onCancel={() => setDetail(undefined)} footer={<Button onClick={() => setDetail(undefined)}>关闭</Button>}>
      {detail && <><div className="admin-cutoff">数据截至 {fmt(detail.asOf)}，不代表此刻实时数据。</div><InfoList items={[{label: '生成时间', value: fmt(detail.generatedAt)}, {label: '可见范围', value: scopeLabel(detail.visibility)}, {label: '更新频率', value: detail.refreshHint}]} /><h3 className="admin-section-title">汇总内容</h3><article className="admin-article">{detail.content}</article><p className="admin-note">{detail.description}</p></>}
    </FormSurface>
    <FormSurface mode="drawer" title="事实快照模板" description="了解系统收录哪些汇总数据、给谁看、何时更新。" open={showSpecs} onCancel={() => setShowSpecs(false)} footer={<Button onClick={() => setShowSpecs(false)}>关闭</Button>}>
      {specs.map(spec => <section className="admin-source-note" key={spec.code}><h3>{specName(spec.code)}</h3><p>{spec.description}</p><small>{spec.visibilityLabel} · {spec.refreshHint} · 覆盖 {spec.scopeCount} 个作用域</small></section>)}
    </FormSurface>
  </>
}
