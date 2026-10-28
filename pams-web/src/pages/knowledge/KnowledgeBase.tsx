import { useEffect, useRef, useState } from 'react'
import { App, Button, Dropdown, Empty, Space, Spin, Tabs } from 'antd'
import { BookOutlined, CheckOutlined, FolderOutlined, PlusOutlined, ReloadOutlined, SettingOutlined, WarningOutlined } from '@ant-design/icons'
import PageHeader from '@/components/glass/PageHeader'
import FormSurface from '@/components/form/FormSurface'
import { InfoList, LoadError, StatsStrip, StatusTag, errorText } from '@/components/admin/AdminSurface'
import { useAuthStore } from '@/stores/auth'
import { kbChunksOf, kbDocument, kbRefreshSnapshots, kbReindex, kbSources, kbStats, type KbDocumentVO, type KnowledgeChunkVO, type KnowledgeEntryVO, type KnowledgeSourceVO, type KnowledgeStatsVO } from '@/api/knowledge'
import KnowledgeEntries from './KnowledgeEntries'
import { RetrievalRecords, SnapshotRecords } from './KnowledgeRecords'
import KnowledgeDocumentEditor from './KnowledgeDocumentEditor'

export const fmt = (value?: string | null) => value ? value.replace('T', ' ').slice(0, 16) : '—'
export const scopeLabel = (value?: string | null) => value === 'LEADER' ? '部长及以上' : value === 'ALL' ? '全员可见' : '按来源权限'
export type EntryTarget = Pick<KnowledgeEntryVO, 'sourceType' | 'sourceId'> & Partial<KnowledgeEntryVO>

export default function KnowledgeBase() {
  const { message, modal } = App.useApp()
  const canManage = useAuthStore(s => s.user?.permissions.includes('kb:document:manage')) ?? false
  const canIndex = useAuthStore(s => s.user?.permissions.includes('kb:index:manage')) ?? false
  const [tab, setTab] = useState('entries'), [source, setSource] = useState<string>()
  const [stats, setStats] = useState<KnowledgeStatsVO | null>(null), [sources, setSources] = useState<KnowledgeSourceVO[]>([])
  const [loading, setLoading] = useState(true), [overviewError, setOverviewError] = useState(''), [refresh, setRefresh] = useState(0)
  const [maintenance, setMaintenance] = useState(false), running = useRef(false)
  const [sourceInfo, setSourceInfo] = useState(false), [target, setTarget] = useState<EntryTarget>()
  const [chunks, setChunks] = useState<KnowledgeChunkVO[]>([]), [detailDoc, setDetailDoc] = useState<KbDocumentVO>()
  const [detailLoading, setDetailLoading] = useState(false), [detailError, setDetailError] = useState(''), [detailRefresh, setDetailRefresh] = useState(0)
  const [editor, setEditor] = useState<{document?: KbDocumentVO}>()
  useEffect(() => {
    let active = true; setLoading(true)
    Promise.allSettled([kbStats(), kbSources()]).then(([summary, catalog]) => {
      if (!active) return
      setStats(summary.status === 'fulfilled' ? summary.value : null)
      setSources(catalog.status === 'fulfilled' ? catalog.value : [])
      setOverviewError(summary.status === 'rejected' || catalog.status === 'rejected' ? '知识库统计或来源加载失败，资料列表仍可独立查看。' : '')
    }).finally(() => {if (active) setLoading(false)})
    return () => {active = false}
  }, [refresh])
  useEffect(() => {
    if (!target) return
    let active = true; setChunks([]); setDetailDoc(undefined); setDetailError(''); setDetailLoading(true)
    Promise.all([kbChunksOf(target.sourceType, target.sourceId), target.sourceType === 'manual' ? kbDocument(target.sourceId) : Promise.resolve(undefined)])
      .then(([list, document]) => {if (active) {setChunks(list); setDetailDoc(document)}})
      .catch(err => {if (active) setDetailError(errorText(err, '资料详情加载失败，请重试。'))})
      .finally(() => {if (active) setDetailLoading(false)})
    return () => {active = false}
  }, [target, detailRefresh])
  const reload = () => setRefresh(v => v + 1)
  const sourceName = (type: string) => sources.find(s => s.sourceType === type)?.label || type
  const maintain = (kind: 'index' | 'snapshots') => modal.confirm({
    title: kind === 'index' ? '重建全部检索索引？' : '刷新业务快照？',
    content: kind === 'index' ? '日常资料会自动更新。重建用于修复检索副本，不修改来源正文或访问权限。' : '重新计算业务汇总，并清理历史快照；数据截至时间将在完成后更新。',
    okText: kind === 'index' ? '开始重建' : '刷新快照', cancelText: '取消',
    onOk: async () => {
      if (running.current) return
      running.current = true; setMaintenance(true)
      try {
        if (kind === 'index') {const result = await kbReindex(); message.success(`重建完成，共 ${result.totalChunks} 个分块`)}
        else {const result = await kbRefreshSnapshots(); message.success(`已刷新 ${result.totalWritten} 份快照`); setTab('snapshots')}
        reload()
      } catch (err) {message.error(errorText(err, '维护操作未完成，请重试。')); throw err}
      finally {running.current = false; setMaintenance(false)}
    },
  })
  return <div className="admin-v6 knowledge-v6">
    <PageHeader title="知识库" description="管理灵思可引用的资料，核对内容来源、数据时效与检索结果。" extra={<>
      <Button icon={<ReloadOutlined aria-hidden="true" />} loading={loading} onClick={reload}>刷新</Button>
      {canIndex && <Dropdown trigger={['click']} menu={{items: [{key: 'index', label: '重建全部检索索引'}, {key: 'snapshots', label: '刷新业务快照'}], onClick: ({key}) => maintain(key as 'index' | 'snapshots')}}><Button icon={<SettingOutlined aria-hidden="true" />} loading={maintenance}>维护工具</Button></Dropdown>}{canManage && <Button type="primary" icon={<PlusOutlined aria-hidden="true" />} onClick={() => setEditor({})}>录入文档</Button>}
    </>} />
    {overviewError && <LoadError message={overviewError} retry={reload} />}
    <div className={`admin-context ${stats && !stats.fulltextAvailable ? 'warning' : ''}`}>
      <span>{stats ? stats.fulltextAvailable ? <CheckOutlined aria-hidden="true" /> : <WarningOutlined aria-hidden="true" /> : <BookOutlined aria-hidden="true" />}<strong>{stats ? stats.fulltextAvailable ? '全文检索可用' : '检索已降级为模糊匹配' : loading ? '正在读取检索状态' : '检索状态暂不可用'}</strong><small>{stats?.fulltextAvailable ? '资料随业务变更自动更新' : stats?.searchModeNote}</small></span>
      <span><small>最近成功更新 {fmt(stats?.lastIndexedAt)}</small><Button type="link" onClick={() => setSourceInfo(true)}>查看知识源</Button></span>
    </div>
    <StatsStrip loading={loading} items={[
      {label: '资料条目', value: stats?.entryCount ?? '—', note: '按当前账号可见范围统计'},
      {label: '检索分块', value: stats?.chunkCount ?? '—', note: `来自 ${sources.length} 类知识源`},
      {label: '事实快照', value: stats?.snapshotCount ?? '—', note: `最新数据截至 ${fmt(stats?.snapshotLatestAsOf)}`},
      {label: '我的未答复记录', value: stats?.retrievalUnanswered ?? '—', note: `累计 ${stats?.retrievalTotal ?? '—'} 次检索`, warning: true},
    ]} />
    <div className="admin-workbench"><aside className="admin-rail" aria-label="知识来源"><h3>按来源浏览</h3>
      <button aria-pressed={!source} onClick={() => {setSource(undefined); setTab('entries')}}><BookOutlined aria-hidden="true" />全部资料<span>{stats?.entryCount ?? '—'}</span></button>
      {sources.map(item => <button key={item.sourceType} aria-pressed={source === item.sourceType} onClick={() => {setSource(item.sourceType); setTab('entries')}}><FolderOutlined aria-hidden="true" />{item.label}<span>{item.documentCount}</span></button>)}
      <p>来源权限同步生效。资料数量不包含当前账号无权访问的内容。</p><Button type="link" onClick={() => setSourceInfo(true)}>了解可见范围</Button>
    </aside><section className="admin-workspace"><Tabs activeKey={tab} onChange={setTab} items={[{key: 'entries', label: '内容台账'}, {key: 'snapshots', label: '事实快照'}, {key: 'retrievals', label: <>检索记录 <small>我的</small></>}]} />
      {tab === 'entries' && <KnowledgeEntries source={source} refresh={refresh} onOpen={setTarget} onResetSource={() => setSource(undefined)} canManage={canManage} onCreate={() => setEditor({})} />}
      {tab === 'snapshots' && <SnapshotRecords refresh={refresh} canManage={canIndex} onRefresh={() => maintain('snapshots')} />}
      {tab === 'retrievals' && <RetrievalRecords refresh={refresh} onOpen={setTarget} sourceName={sourceName} />}
    </section></div>
    <FormSurface mode="drawer" title="知识来源与可见范围" description="来源决定收录内容、检索方式与访问边界。" open={sourceInfo} onCancel={() => setSourceInfo(false)} footer={<Button onClick={() => setSourceInfo(false)}>关闭</Button>}>
      {sources.length ? sources.map(item => <section className="admin-source-note" key={item.sourceType}><h3>{item.label} <StatusTag tone={item.tier === 'T1' ? 'brand' : 'neutral'}>{item.tier === 'T1' ? '全文检索' : '标题匹配'}</StatusTag></h3><p>{item.description}</p><small>{item.documentCount} 条可见资料 · {item.chunkCount} 个分块 · 按来源权限访问</small></section>) : <Empty description={loading ? '正在读取来源' : '暂无来源信息'} />}
      <p className="admin-note">全员可见指本组织内符合授权的用户。业务资料在来源页面维护，知识库沿用其访问约束。</p>
    </FormSurface>
    <FormSurface mode="drawer" title={target?.title || '资料详情'} description="核对来源、正文与引用定位。" open={!!target} onCancel={() => setTarget(undefined)} footer={<Space><Button onClick={() => setTarget(undefined)}>关闭</Button>{canManage && detailDoc && <Button type="primary" onClick={() => {setEditor({document: detailDoc}); setTarget(undefined)}}>编辑文档</Button>}</Space>}>
      {detailError ? <LoadError message={detailError} retry={() => setDetailRefresh(v => v + 1)} /> : <Spin spinning={detailLoading}>
        <InfoList items={[{label: '资料来源', value: target && sourceName(target.sourceType)}, {label: '可见范围', value: target?.sourceType === 'manual' ? scopeLabel(detailDoc?.visibility) : '按来源权限'}, {label: '检索方式', value: (target?.tier || chunks[0]?.tier) === 'T2' ? '标题匹配' : '全文检索'}, {label: '版本', value: detailDoc?.version}, {label: '最近索引', value: fmt(chunks[0]?.indexedAt)}]} />
        {(target?.asOf || chunks[0]?.asOf) && <div className="admin-cutoff">数据截至 {fmt(target?.asOf || chunks[0]?.asOf)}，不代表此刻实时数据。</div>}
        <h3 className="admin-section-title">正文与引用定位 <small>{chunks.length} 个分块</small></h3>
        {!detailLoading && !chunks.length && <Empty description="该资料暂无可检索分块" />}
        {chunks.map(chunk => <article className="admin-chunk" key={chunk.id}><h4>{chunk.path || '文档正文'}</h4><small>分块 {chunk.seq} · {chunk.title}</small><p>{chunk.content ?? chunk.snippet}</p></article>)}
        {target?.tier === 'T2' && <p className="admin-note">此来源仅检索标题和元数据，附件正文不会成为回答依据。</p>}
        {target?.link?.startsWith('/') && !target.link.startsWith('//') && <Button type="link" href={target.link}>查看来源资料</Button>}
      </Spin>}
    </FormSurface>
    {editor && <KnowledgeDocumentEditor document={editor.document} onClose={() => setEditor(undefined)} onSaved={() => {setEditor(undefined); reload(); setTab('entries'); setSource(undefined)}} />}
  </div>
}
