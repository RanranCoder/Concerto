import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert, Button, Descriptions, Drawer, Empty, Form, Input, Modal, Select, Space,
  Spin, Table, Tabs, Tag, Tooltip, message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import {
  kbChunks, kbChunksOf, kbCreateDocument, kbRefreshSnapshots, kbReindex, kbRetrievals,
  kbSnapshotSpecs, kbSnapshots, kbSources, kbStats,
} from '@/api/knowledge'
import type {
  KbRetrievalLogVO, KbSnapshotSpecVO, KbSnapshotVO, KnowledgeChunkVO,
  KnowledgeSourceVO, KnowledgeStatsVO,
} from '@/api/knowledge'
import { useAuthStore } from '@/stores/auth'

/** 后端 LocalDateTime 序列化成 `2026-09-14T20:13:45`；只展示到分钟 */
function fmt(iso: string | null | undefined, withTime = true): string {
  if (!iso) return '—'
  const t = iso.replace('T', ' ')
  return withTime ? t.slice(0, 16) : t.slice(0, 10)
}

/** 相对时间：知识库"更新于…"必须让人一眼看出新鲜度（最终一致是明说的，不是藏起来的） */
function ago(iso: string | null | undefined): string {
  if (!iso) return '尚未索引'
  const ms = Date.now() - new Date(iso.replace(' ', 'T')).getTime()
  if (Number.isNaN(ms)) return '—'
  const min = Math.floor(ms / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const h = Math.floor(min / 60)
  if (h < 24) return `${h} 小时前`
  return `${Math.floor(h / 24)} 天前`
}

const SKIP_LABEL: Record<string, { text: string; color: string }> = {
  NO_HIT: { text: '没有依据', color: 'default' },
  BELOW_THRESHOLD: { text: '相关度过低', color: 'orange' },
  NO_CITATION: { text: '未标注出处', color: 'gold' },
}

/**
 * 知识库（灵思「对话」档位的可检索视图）。
 *
 * <h3>两个 Tab 回答两个不同的问题</h3>
 * <ol>
 *   <li><b>内容台账</b> —— "Agent 现在能检索到什么"。以**分块**为单位，
 *       因为检索命中的单元就是分块，用户想核对的也是分块。</li>
 *   <li><b>检索记录</b> —— "谁问过什么、为什么答不上"。
 *       `命中/放行/拦下` 三个计数合起来才能解释一次问答的结果：
 *       命中很多但放行 0 条 = 有内容但你没权限；命中 0 条 = 语料不足。</li>
 * </ol>
 *
 * <h3>为什么这个页面必须存在</h3>
 * 知识库把散落在各业务表的正文做成了**可检索副本**。一旦有了副本，
 * 就必须能回答"库里有什么"和"谁看到过什么" —— 否则出了越权或答错都无从追溯，
 * 也没法判断该补语料还是该修索引。
 */
export default function KnowledgeBase() {
  const perms = useAuthStore((s) => s.user?.permissions) ?? []
  const canManage = perms.includes('kb:manage')

  const [tab, setTab] = useState('ledger')

  const [stats, setStats] = useState<KnowledgeStatsVO | null>(null)
  const [sources, setSources] = useState<KnowledgeSourceVO[]>([])
  const [loadingStats, setLoadingStats] = useState(false)

  const loadOverview = useCallback(async () => {
    setLoadingStats(true)
    try {
      const [s, src] = await Promise.all([kbStats(), kbSources()])
      setStats(s)
      setSources(src ?? [])
    } catch {
      /* http 拦截器已提示 */
    } finally {
      setLoadingStats(false)
    }
  }, [])

  useEffect(() => {
    void loadOverview()
  }, [loadOverview])

  const sourceOptions = useMemo(
    () => sources.map((s) => ({ value: s.sourceType, label: `${s.label}（${s.chunkCount} 条）` })),
    [sources],
  )

  return (
    <div className="page-container">
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 16 }}>
        <div>
          <h2 style={{ margin: 0 }}>知识库</h2>
          <div style={{ marginTop: 4, fontSize: 13, color: 'var(--text-secondary)' }}>
            灵思「对话」档位能检索到的全部资料。内容来自平台各业务数据与人工录入，
            均按来源各自的可见范围过滤。
          </div>
        </div>
        <Space>
          <Button onClick={() => void loadOverview()} loading={loadingStats}>
            刷新
          </Button>
          {canManage && (
            <Tooltip title="遍历全部知识源重建索引。日常由变更事件自动增量更新，这里是兜底与修复入口">
              <Button
                type="primary"
                onClick={async () => {
                  const hide = message.loading('正在重建索引…', 0)
                  try {
                    const r = await kbReindex()
                    message.success(`重建完成，当前共 ${r.totalChunks} 个分块`)
                    await loadOverview()
                  } catch {
                    /* 拦截器已提示 */
                  } finally {
                    hide()
                  }
                }}
              >
                重建索引
              </Button>
            </Tooltip>
          )}
        </Space>
      </div>

      {/* ---------- 覆盖统计 ---------- */}
      <div style={{ marginTop: 16 }}>
        {stats && (
          <Alert
            type={stats.fulltextAvailable ? 'success' : 'warning'}
            showIcon
            message={
              <span>
                检索模式：<b>{stats.retriever}</b>
                <span style={{ marginLeft: 12, color: 'var(--text-secondary)' }}>
                  知识库更新于 {ago(stats.lastIndexedAt)}
                  {stats.staleMinutes != null && `（${stats.staleMinutes} 分钟前）`}
                </span>
              </span>
            }
            description={
              <span>
                {stats.searchModeNote}
                {!stats.fulltextAvailable && ' —— 已降级为模糊匹配，检索会比全文索引慢。'}
              </span>
            }
            style={{ marginBottom: 12 }}
          />
        )}

        {stats && (
          <Descriptions bordered size="small" column={4}>
            <Descriptions.Item label="知识源">{sources.length} 类</Descriptions.Item>
            <Descriptions.Item label="人工文档">{stats.documentCount}</Descriptions.Item>
            <Descriptions.Item label="检索分块">{stats.chunkCount}</Descriptions.Item>
            <Descriptions.Item label="事实快照">
              {stats.snapshotCount ?? 0}
              {stats.snapshotSpecCount != null && stats.snapshotSpecCount > 0 && (
                <span style={{ marginLeft: 6, color: 'var(--text-tertiary)', fontSize: 13 }}>
                  来自 {stats.snapshotSpecCount} 个模板
                </span>
              )}
            </Descriptions.Item>
            <Descriptions.Item label="累计检索" span={2}>
              {stats.retrievalTotal}
              {stats.retrievalNoHit > 0 && (
                <span style={{ marginLeft: 6, color: 'var(--text-tertiary)', fontSize: 13 }}>
                  其中无依据 {stats.retrievalNoHit}
                </span>
              )}
            </Descriptions.Item>
            <Descriptions.Item label="数据截止" span={2}>
              {stats.snapshotLatestAsOf ? fmt(stats.snapshotLatestAsOf) : '—'}
            </Descriptions.Item>
            {/* filtered > 0 是索引期权限字段偏松的体检信号；正常应为 0 */}
            {stats.retrievalFiltered > 0 && (
              <Descriptions.Item label="权限复核拦截" span={4}>
                <Tag color="orange">
                  有 {stats.retrievalFiltered} 次检索出现"命中但无权查看"
                </Tag>
                <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
                  这是正常的兜底行为（索引粗筛偏宽、复核拦下）。若持续增长，
                  说明某些源的可见性配置需要收紧。
                </span>
              </Descriptions.Item>
            )}
          </Descriptions>
        )}
      </div>

      <Tabs
        activeKey={tab}
        onChange={setTab}
        style={{ marginTop: 16 }}
        items={[
          {
            key: 'ledger',
            label: '内容台账',
            children: (
              <LedgerTab
                sourceOptions={sourceOptions}
                canManage={canManage}
                onChanged={loadOverview}
                sources={sources}
              />
            ),
          },
          {
            key: 'retrieval',
            label: '检索记录',
            children: <RetrievalTab />,
          },
          {
            key: 'snapshot',
            label: '事实快照',
            children: <SnapshotTab canManage={canManage} onChanged={loadOverview} />,
          },
        ]}
      />
    </div>
  )
}

/* ========================================================================== 内容台账 */

function LedgerTab({
  sourceOptions, canManage, onChanged, sources,
}: {
  sourceOptions: { value: string; label: string }[]
  canManage: boolean
  onChanged: () => Promise<void>
  sources: KnowledgeSourceVO[]
}) {
  const [rows, setRows] = useState<KnowledgeChunkVO[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(20)
  const [sourceType, setSourceType] = useState<string | undefined>()
  const [keyword, setKeyword] = useState('')
  const [loading, setLoading] = useState(false)

  const [detail, setDetail] = useState<KnowledgeChunkVO[] | null>(null)
  const [detailTitle, setDetailTitle] = useState('')
  const [expanded, setExpanded] = useState<Record<string, KnowledgeChunkVO[]>>({})

  const [createOpen, setCreateOpen] = useState(false)
  const [form] = Form.useForm()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await kbChunks({ sourceType, keyword: keyword || undefined, page, size })
      setRows(res.records ?? [])
      setTotal(res.total ?? 0)
    } catch {
      /* 拦截器已提示 */
    } finally {
      setLoading(false)
    }
  }, [sourceType, keyword, page, size])

  useEffect(() => {
    void load()
  }, [load])

  const sourceLabel = (t: string) => sources.find((s) => s.sourceType === t)?.label ?? t

  const columns: ColumnsType<KnowledgeChunkVO> = [
    {
      title: '来源',
      dataIndex: 'sourceType',
      width: 110,
      render: (t: string) => <Tag style={{ margin: 0 }}>{sourceLabel(t)}</Tag>,
    },
    {
      title: '标题 / 路径',
      key: 'title',
      render: (_, r) => (
        <div style={{ minWidth: 0 }}>
          <div style={{ fontSize: 14 }}>{r.title || '（无标题）'}</div>
          {r.path && (
            <div style={{ fontSize: 13, color: 'var(--text-tertiary)' }}>{r.path}</div>
          )}
        </div>
      ),
    },
    {
      title: '层级',
      dataIndex: 'tier',
      width: 90,
      render: (t: string) =>
        t === 'T2' ? (
          <Tooltip title="字段级来源：只参与标题匹配，不参与正文打分（避免稀释相关性）">
            <Tag color="default">仅标题</Tag>
          </Tooltip>
        ) : (
          <Tag color="blue">全文</Tag>
        ),
    },
    {
      title: '数据截止',
      dataIndex: 'asOf',
      width: 110,
      render: (v: string | null) =>
        v ? <Tag color="orange">截至 {fmt(v, false)}</Tag> : <span style={{ opacity: 0.4 }}>—</span>,
    },
    {
      title: '字数',
      dataIndex: 'tokenLen',
      width: 70,
      render: (v: number | null) => v ?? '—',
    },
    {
      title: '索引时间',
      dataIndex: 'indexedAt',
      width: 150,
      render: (v: string | null) => (
        <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{fmt(v)}</span>
      ),
    },
    {
      title: '片段',
      dataIndex: 'snippet',
      render: (v: string) => (
        <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{v}</span>
      ),
    },
  ]

  return (
    <>
      <Space style={{ marginBottom: 12 }} wrap>
        <Select
          allowClear
          placeholder="全部来源"
          style={{ width: 200 }}
          options={sourceOptions}
          value={sourceType}
          onChange={(v) => {
            setPage(1)
            setSourceType(v)
          }}
        />
        <Input.Search
          allowClear
          placeholder="搜索标题 / 正文 / 标题路径"
          style={{ width: 280 }}
          onSearch={(v) => {
            setPage(1)
            setKeyword(v)
          }}
        />
        {canManage && (
          <Button onClick={() => setCreateOpen(true)}>录入制度文档</Button>
        )}
        <span style={{ fontSize: 13, color: 'var(--text-tertiary)' }}>
          共 {total} 个分块
        </span>
      </Space>

      <Table<KnowledgeChunkVO>
        rowKey="id"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={rows}
        expandable={{
          // 展开时才去拉全文：列表接口只给摘要，避免一次传输几百 KB
          onExpand: async (isOpen, r) => {
            const key = `${r.sourceType}-${r.sourceId}`
            if (!isOpen) return
            if (expanded[key]) return
            try {
              const list = await kbChunksOf(r.sourceType, r.sourceId)
              setExpanded((s) => ({ ...s, [key]: list }))
            } catch {
              /* 拦截器已提示 */
            }
          },
          expandedRowRender: (r) => {
            const key = `${r.sourceType}-${r.sourceId}`
            const list = expanded[key]
            if (!list) return <Spin size="small" />
            return (
              <div style={{ maxHeight: 320, overflow: 'auto', fontSize: 13, lineHeight: 1.7 }}>
                <div style={{ marginBottom: 8, color: 'var(--text-secondary)' }}>
                  该来源共产出 {list.length} 个分块。点「查看」在右侧通读单个分块原文。
                </div>
                {list.map((c) => (
                  <div
                    key={c.id}
                    style={{
                      padding: '8px 10px',
                      borderBottom: '1px solid var(--border)',
                      display: 'flex',
                      gap: 10,
                    }}
                  >
                    <span style={{ color: 'var(--text-tertiary)', flexShrink: 0 }}>
                      #{c.seq}
                    </span>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      {c.path && (
                        <div style={{ color: 'var(--text-tertiary)', fontSize: 13 }}>
                          {c.path}
                        </div>
                      )}
                      <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                        {c.content ?? c.snippet}
                      </div>
                    </div>
                    <Button
                      size="small"
                      type="link"
                      onClick={() => {
                        setDetailTitle(`${r.title ?? ''}${c.path ? ` · ${c.path}` : ''}`)
                        setDetail([c])
                      }}
                    >
                      查看
                    </Button>
                  </div>
                ))}
              </div>
            )
          },
        }}
        pagination={{
          current: page,
          pageSize: size,
          total,
          showSizeChanger: true,
          onChange: (p, s) => {
            setPage(p)
            setSize(s)
          },
        }}
      />

      <Drawer
        title={detailTitle}
        open={!!detail}
        onClose={() => setDetail(null)}
        width={620}
      >
        {detail?.map((c) => (
          <div key={c.id} style={{ whiteSpace: 'pre-wrap', lineHeight: 1.8, fontSize: 14 }}>
            {c.content ?? c.snippet}
          </div>
        ))}
      </Drawer>

      <Modal
        title="录入制度文档"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        okText="保存并建立索引"
        width={720}
        onOk={async () => {
          const v = await form.validateFields()
          try {
            await kbCreateDocument({
              title: v.title,
              docType: v.docType ?? 'REGULATION',
              category: v.category,
              body: v.body,
              summary: v.summary,
              version: v.version,
              visibility: v.visibility ?? 'ALL',
              status: v.status ?? 'PUBLISHED',
            })
            message.success('已保存，并已建立索引')
            setCreateOpen(false)
            form.resetFields()
            setPage(1)
            await load()
            await onChanged()
          } catch {
            /* 拦截器已提示 */
          }
        }}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="正文里请保留条款编号（如「第二十六条 …」）"
          description="分块按编号切分，引用里的出处路径（如「第三章 第二十六条」）就来自这里。编号缺失时出处只能给到文档级。"
        />
        <Form form={form} layout="vertical" initialValues={{ docType: 'REGULATION', visibility: 'ALL', status: 'PUBLISHED' }}>
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入标题' }]}>
            <Input maxLength={200} placeholder="如：中国共产党发展党员工作细则" />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="docType" label="类型" style={{ flex: 1 }}>
              <Select
                options={[
                  { value: 'REGULATION', label: '制度' },
                  { value: 'POLICY', label: '上级文件' },
                  { value: 'MANUAL', label: '操作指引' },
                  { value: 'FAQ', label: '常见问答' },
                ]}
              />
            </Form.Item>
            <Form.Item name="category" label="业务分类" style={{ flex: 1 }}>
              <Input placeholder="如：党员发展" />
            </Form.Item>
            <Form.Item name="visibility" label="可见范围" style={{ flex: 1 }}>
              <Select
                options={[
                  { value: 'ALL', label: '全员' },
                  { value: 'LEADER', label: '部长及以上' },
                ]}
              />
            </Form.Item>
          </Space>
          <Form.Item name="summary" label="摘要（可选）">
            <Input.TextArea rows={2} maxLength={500} placeholder="一句话说明这份文件管什么" />
          </Form.Item>
          <Form.Item name="body" label="正文" rules={[{ required: true, message: '请输入正文' }]}>
            <Input.TextArea rows={12} placeholder={'第三章 发展对象的确定\n第二十五条 党支部应当…\n第二十六条 入党介绍人应当…'} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}

/* ========================================================================== 检索记录 */

function RetrievalTab() {
  const [rows, setRows] = useState<KbRetrievalLogVO[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(20)
  const [skipReason, setSkipReason] = useState<string | undefined>()
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await kbRetrievals({ skipReason, page, size })
      setRows(res.records ?? [])
      setTotal(res.total ?? 0)
    } catch {
      /* 拦截器已提示 */
    } finally {
      setLoading(false)
    }
  }, [skipReason, page, size])

  useEffect(() => {
    void load()
  }, [load])

  const parseHits = (json: string | null): Record<string, unknown>[] => {
    if (!json) return []
    try {
      const v = JSON.parse(json)
      return Array.isArray(v) ? v : []
    } catch {
      return []
    }
  }

  const columns: ColumnsType<KbRetrievalLogVO> = [
    {
      title: '时间',
      dataIndex: 'createdAt',
      width: 150,
      render: (v: string) => <span style={{ fontSize: 13 }}>{fmt(v)}</span>,
    },
    {
      title: '问题',
      dataIndex: 'queryText',
      render: (v: string) => <span style={{ fontSize: 13 }}>{v}</span>,
    },
    {
      title: '命中 / 放行 / 拦下',
      key: 'counts',
      width: 160,
      render: (_, r) => (
        <span style={{ fontSize: 13 }}>
          <span>{r.hitCount}</span>
          <span style={{ color: 'var(--text-tertiary)' }}> / </span>
          <span>{r.allowedCount}</span>
          <span style={{ color: 'var(--text-tertiary)' }}> / </span>
          <span style={{ color: r.filteredCount > 0 ? 'var(--ls-warn)' : 'inherit' }}>
            {r.filteredCount}
          </span>
        </span>
      ),
    },
    {
      title: '耗时',
      dataIndex: 'scanMs',
      width: 80,
      render: (v: number | null) => (v == null ? '—' : `${v}ms`),
    },
    {
      title: '结果',
      key: 'result',
      width: 120,
      render: (_, r) => {
        if (!r.skipReason) return <Tag color="green">正常作答</Tag>
        const s = SKIP_LABEL[r.skipReason] ?? { text: r.skipReason, color: 'default' }
        return <Tag color={s.color}>{s.text}</Tag>
      },
    },
    {
      title: '模型',
      dataIndex: 'provider',
      width: 110,
      render: (v: string | null, r) =>
        r.degraded ? <Tag color="orange">规则模式</Tag> : <span style={{ fontSize: 13 }}>{v ?? '—'}</span>,
    },
  ]

  return (
    <>
      <Space style={{ marginBottom: 12 }} wrap>
        <Select
          allowClear
          placeholder="全部结果"
          style={{ width: 180 }}
          value={skipReason}
          onChange={(v) => {
            setPage(1)
            setSkipReason(v)
          }}
          options={[
            { value: 'NO_HIT', label: '没有依据' },
            { value: 'BELOW_THRESHOLD', label: '相关度过低' },
            { value: 'NO_CITATION', label: '未标注出处' },
          ]}
        />
        <Button onClick={() => void load()} loading={loading}>
          刷新
        </Button>
        <span style={{ fontSize: 13, color: 'var(--text-tertiary)' }}>
          共 {total} 条。命中/放行/拦下三个数字合起来才能解释一次问答：
          命中很多但放行 0 条 = 有内容但当前账号无权查看。
        </span>
      </Space>

      <Table<KbRetrievalLogVO>
        rowKey="id"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={rows}
        locale={{ emptyText: <Empty description="还没有检索记录" /> }}
        expandable={{
          expandedRowRender: (r) => {
            const hits = parseHits(r.hitsJson)
            return (
              <div style={{ fontSize: 13 }}>
                <div style={{ marginBottom: 6, color: 'var(--text-secondary)' }}>
                  放行的 {hits.length} 条依据（进入模型上下文的部分）
                </div>
                {hits.length === 0 ? (
                  <span style={{ color: 'var(--text-tertiary)' }}>无</span>
                ) : (
                  hits.map((h, i) => (
                    <div key={i} style={{ padding: '3px 0' }}>
                      <Tag style={{ marginRight: 6 }}>{String(h['sourceType'])}</Tag>
                      #{String(h['seq'])}
                      {h['path'] ? ` · ${String(h['path'])}` : ''}
                      <span style={{ color: 'var(--text-tertiary)' }}>
                        {' '}
                        相关度 {Number(h['score'] ?? 0).toFixed(3)}
                      </span>
                    </div>
                  ))
                )}
                {r.filteredCount > 0 && (
                  <Alert
                    style={{ marginTop: 10 }}
                    type="warning"
                    showIcon
                    message={`另有 ${r.filteredCount} 条命中被权限复核拦下`}
                    description="这些内容确实存在于知识库，但当前账号的可见范围不包含它们。对提问者是「没有依据」，对运维是「配置可能需要收紧」。"
                  />
                )}
              </div>
            )
          },
        }}
        pagination={{
          current: page,
          pageSize: size,
          total,
          showSizeChanger: true,
          onChange: (p, s) => {
            setPage(p)
            setSize(s)
          },
        }}
      />
    </>
  )
}

/* ========================================================================== 事实快照 */

/**
 * 事实快照 Tab（D12 的 B 形态）。
 *
 * <h3>它回答的问题与另外两个 Tab 不同</h3>
 * <ul>
 *   <li>内容台账 —— "Agent 能检索到什么"（以分块为单位）</li>
 *   <li>检索记录 —— "谁问过什么、为什么答不上"</li>
 *   <li><b>事实快照</b> —— <b>"数据类的数字是怎么算出来的、截止到什么时候"</b></li>
 * </ul>
 *
 * <p>第三点必须单独一页，因为快照是<b>派生数据</b>：它由定时任务从业务表聚合而来，
 * 用户看到"本届成员共 12 人"时，需要能回答"这个 12 是什么时候算的、
 * 包不含往届、为什么和页面上看到的不一样" —— 这些答案都在这一页。
 *
 * <h3>asOf 必须显眼</h3>
 * 快照声明的是"某时刻的数字"。展示时把「数据截至」放在标题旁而不是折叠进详情，
 * 因为用户最容易犯的错就是把它当实时值读（方案 R16）。
 */
function SnapshotTab({ canManage, onChanged }: { canManage: boolean; onChanged: () => Promise<void> }) {
  const [specs, setSpecs] = useState<KbSnapshotSpecVO[]>([])
  const [rows, setRows] = useState<KbSnapshotVO[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(20)
  const [specCode, setSpecCode] = useState<string | undefined>()
  const [loading, setLoading] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [detail, setDetail] = useState<KbSnapshotVO | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [sp, list] = await Promise.all([kbSnapshotSpecs(), kbSnapshots({ specCode, page, size })])
      setSpecs(sp ?? [])
      setRows(list.records ?? [])
      setTotal(list.total ?? 0)
    } catch {
      /* 拦截器已提示 */
    } finally {
      setLoading(false)
    }
  }, [specCode, page, size])

  useEffect(() => {
    void load()
  }, [load])

  const specShort = (code: string) => {
    const s = specs.find((x) => x.code === code)
    if (!s) return code
    // 取模板标题里的主体（如「%s 成员构成」→「成员构成」）
    return s.titleTemplate.replace('%s', '').trim() || code
  }

  const columns: ColumnsType<KbSnapshotVO> = [
    {
      title: '标题',
      dataIndex: 'title',
      render: (v: string, r) => (
        <div style={{ minWidth: 0 }}>
          <div style={{ fontSize: 14 }}>{v}</div>
          {r.description && (
            <div style={{ fontSize: 13, color: 'var(--text-tertiary)' }}>{r.description}</div>
          )}
        </div>
      ),
    },
    {
      title: '模板',
      dataIndex: 'specCode',
      width: 130,
      render: (v: string) => <Tag style={{ margin: 0 }}>{specShort(v)}</Tag>,
    },
    {
      title: '可见范围',
      dataIndex: 'visibility',
      width: 110,
      render: (v: string) =>
        v === 'LEADER'
          ? <Tag color="orange">部长及以上</Tag>
          : <Tag color="blue">全员</Tag>,
    },
    {
      // 放在生成时间之前：这是用户最先要看的信息
      title: '数据截至',
      dataIndex: 'asOf',
      width: 130,
      render: (v: string) => (
        <Tag color="orange" style={{ margin: 0 }}>截至 {fmt(v, false)}</Tag>
      ),
    },
    {
      title: '生成时间',
      dataIndex: 'generatedAt',
      width: 150,
      render: (v: string | null) => (
        <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{fmt(v)}</span>
      ),
    },
    {
      title: '内容',
      dataIndex: 'content',
      render: (v: string) => (
        <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
          {v.length > 90 ? v.slice(0, 90) + '…' : v}
        </span>
      ),
    },
  ]

  return (
    <>
      {/* 模板说明卡：这是评审入口，必须让人不点开任何东西就看清"系统在自动收录什么" */}
      <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginBottom: 14 }}>
        {specs.map((s) => (
          <div
            key={s.code}
            style={{
              flex: '1 1 260px',
              minWidth: 240,
              padding: '10px 12px',
              border: '1px solid var(--border)',
              borderRadius: 8,
              background: 'var(--soft)',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
              <span style={{ fontSize: 13, fontWeight: 500 }}>{s.titleTemplate.replace('%s', '').trim()}</span>
              <Tag style={{ margin: 0, fontSize: 13 }}>
                {s.visibility === 'LEADER' ? '部长及以上' : '全员'}
              </Tag>
              {s.requiredPermission && (
                <Tag style={{ margin: 0, fontSize: 13 }}>{s.requiredPermission}</Tag>
              )}
            </div>
            <div style={{ fontSize: 13, lineHeight: 1.6, color: 'var(--text-secondary)' }}>
              {s.description}
            </div>
            <div style={{ marginTop: 4, fontSize: 13, color: 'var(--text-tertiary)' }}>
              {s.refreshHint} · 覆盖 {s.scopeCount} 个作用域
            </div>
          </div>
        ))}
      </div>

      <Space style={{ marginBottom: 12 }} wrap>
        <Select
          allowClear
          placeholder="全部模板"
          style={{ width: 200 }}
          value={specCode}
          onChange={(v) => {
            setPage(1)
            setSpecCode(v)
          }}
          options={specs.map((s) => ({ value: s.code, label: specShort(s.code) }))}
        />
        <Button onClick={() => void load()} loading={loading}>刷新</Button>
        {canManage && (
          <Tooltip title="重算全部快照。日常由定时任务按日生成；刚录完一批人想立刻能问到时用">
            <Button
              type="primary"
              loading={refreshing}
              onClick={async () => {
                setRefreshing(true)
                try {
                  const r = await kbRefreshSnapshots()
                  message.success(`已重算 ${r.totalWritten} 份快照，清理 ${r.pruned} 份历史版本`)
                  await load()
                  await onChanged()
                } catch {
                  /* 拦截器已提示 */
                } finally {
                  setRefreshing(false)
                }
              }}
            >
              重算快照
            </Button>
          </Tooltip>
        )}
        <span style={{ fontSize: 13, color: 'var(--text-tertiary)' }}>
          共 {total} 份。快照是聚合数字，<b>不含个人明细</b>；逐人数据请到成员管理页查看。
        </span>
      </Space>

      <Table<KbSnapshotVO>
        rowKey="id"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={rows}
        locale={{ emptyText: <Empty description="还没有生成快照" /> }}
        expandable={{
          // 内容只有一段，直接展开看全文 —— 它存在的意义就是让人核对数字
          expandedRowRender: (r) => (
            <div style={{ fontSize: 13, lineHeight: 1.8, whiteSpace: 'pre-wrap' }}>
              {r.content}
            </div>
          ),
        }}
        pagination={{
          current: page,
          pageSize: size,
          total,
          showSizeChanger: true,
          onChange: (p, s) => {
            setPage(p)
            setSize(s)
          },
        }}
      />

      <Drawer
        title={detail?.title}
        open={!!detail}
        onClose={() => setDetail(null)}
        width={560}
      >
        {detail && (
          <>
            <Descriptions bordered size="small" column={1} style={{ marginBottom: 14 }}>
              <Descriptions.Item label="数据截至">{fmt(detail.asOf)}</Descriptions.Item>
              <Descriptions.Item label="生成时间">{fmt(detail.generatedAt)}</Descriptions.Item>
              <Descriptions.Item label="可见范围">
                {detail.visibility === 'LEADER' ? '部长及以上' : '全员'}
                {detail.requiredPermission ? ` + ${detail.requiredPermission}` : ''}
              </Descriptions.Item>
            </Descriptions>
            <div style={{ fontSize: 14, lineHeight: 1.9, whiteSpace: 'pre-wrap' }}>
              {detail.content}
            </div>
          </>
        )}
      </Drawer>
    </>
  )
}
