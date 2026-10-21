import ActivityEditorDrawer from './ActivityEditorDrawer'
import '@/styles/activity-v6.css'
import { useEffect, useRef, useState } from 'react'
import {
  App,
  Button,
  Drawer,
  Dropdown,
  Grid,
  Input,
  Segmented,
  Pagination,
  Progress,
  Spin,
  Empty,
  Select,
  Space,
  Tag,
} from 'antd'
import type { MenuProps } from 'antd'
import {
  DeleteOutlined,
  EditOutlined,
  EllipsisOutlined,
  EyeOutlined,
  FilterOutlined,
  PlusOutlined,
} from '@ant-design/icons'
import { useLocation, useNavigate } from 'react-router-dom'
import GlassCard from '@/components/glass/GlassCard'
import GlassTable from '@/components/glass/GlassTable'
import PageHeader from '@/components/glass/PageHeader'
import StatusTag from '@/components/glass/StatusTag'
import {
  listActivities,
  listActivityOrganizers,
  changeActivityStatus,
  deleteActivity,
} from '@/api/activity'
import type { ActivityVO } from '@/api/activity'
import {
  ACTIVITY_STATUS_LABEL,
  ACTIVITY_STATUS_OPTIONS,
  ACTIVITY_TYPE_MAP,
  ACTIVITY_TYPE_OPTIONS,
} from '@/api/activityStatus'
import { useHasPerm } from '@/components/glass/RequireRole'
import { getDashboard, type ActivityStats } from '@/api/dashboard'

// 活动状态 → 推进后状态（状态机：ASSIGNED→PLANNING→PLAN_REVIEW→EXECUTING→FINISHED→ARCHIVED）
const NEXT_STATUS: Record<string, string> = {
  ASSIGNED: 'PLANNING',
  PLANNING: 'PLAN_REVIEW',
  PLAN_REVIEW: 'EXECUTING',
  EXECUTING: 'FINISHED',
  FINISHED: 'ARCHIVED',
}

// 活动类型中文展示（与 ActivityEdit/ActivityDetail 共用 ACTIVITY_TYPE_MAP 唯一来源）
const TYPE_MAP = ACTIVITY_TYPE_MAP
const TYPE_OPTIONS = ACTIVITY_TYPE_OPTIONS

type ActivityRecord = ActivityVO & { key: number }

export default function ActivityList() {
  const { message, modal } = App.useApp()
  const navigate = useNavigate()
  const location = useLocation()
  const hasPerm = useHasPerm()
  const screens = Grid.useBreakpoint()
  const compactFilters = screens.md === false

  const [data, setData] = useState<ActivityRecord[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(10)
  const [keyword, setKeyword] = useState('')
  const [keywordDraft, setKeywordDraft] = useState('')
  const [status, setStatus] = useState<string | undefined>()
  const [stats, setStats] = useState<ActivityStats | null>(null)
  const [density, setDensity] = useState<'middle' | 'small'>('middle')
  const [type, setType] = useState<string | undefined>()
  const [organizer, setOrganizer] = useState<string>()
  const [organizers, setOrganizers] = useState<string[]>([])
  const [dateOrder, setDateOrder] = useState('desc')
  const [failed, setFailed] = useState(false)
  const [filterDrawerOpen, setFilterDrawerOpen] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<ActivityVO | null>(null)
  const request = useRef(0)
  useEffect(
    () => () => {
      request.current++
    },
    [],
  )

  const fetchList = async () => {
    const current = ++request.current
    setLoading(true)
    try {
      const res = await listActivities({
        keyword: keyword || undefined,
        status,
        type,
        organizer,
        dateOrder,
        page,
        size,
      })
      if (current !== request.current) return
      setData((res.records ?? []).map((r) => ({ ...r, key: r.id })))
      setTotal(res.total)
      setFailed(false)
    } catch {
      if (current === request.current) setFailed(true)
    } finally {
      if (current === request.current) setLoading(false)
    }
  }

  const refreshSummary = () =>
    getDashboard()
      .then((dashboard) => setStats(dashboard.activityStats))
      .catch(() => {})
  useEffect(() => {
    listActivityOrganizers()
      .then(setOrganizers)
      .catch(() => {})
    void refreshSummary()
  }, [])
  useEffect(() => {
    if (location.state?.create && hasPerm('activity:create')) {
      setEditing(null)
      setModalOpen(true)
      navigate(location.pathname, { replace: true, state: null })
    }
  }, [location.state, location.pathname, navigate, hasPerm])

  useEffect(() => {
    fetchList()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, size, keyword, status, type, organizer, dateOrder])

  const activeFilterCount =
    Number(Boolean(keyword)) +
    Number(Boolean(status)) +
    Number(Boolean(type)) +
    Number(Boolean(organizer))
  const applyKeyword = () => {
    setKeyword(keywordDraft.trim())
    setPage(1)
    if (compactFilters) setFilterDrawerOpen(false)
  }
  const clearFilters = () => {
    setKeyword('')
    setKeywordDraft('')
    setStatus(undefined)
    setType(undefined)
    setOrganizer(undefined)
    setPage(1)
    setFilterDrawerOpen(false)
  }

  const openCreate = () => {
    setEditing(null)
    setModalOpen(true)
  }

  const openEdit = (record: ActivityVO) => {
    setEditing(record)
    setModalOpen(true)
  }

  const handleAdvance = async (record: ActivityVO) => {
    const next = NEXT_STATUS[record.status]
    if (!next) return
    try {
      await changeActivityStatus(record.id, next)
      message.success(`已推进到「${ACTIVITY_STATUS_LABEL[next]}」`)
      fetchList()
      void refreshSummary()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteActivity(id)
      message.success('已删除')
      void refreshSummary()
      // L9 fix: 若当前页只剩一条且非第一页，回退到上一页
      if (data.length === 1 && page > 1) {
        setPage(page - 1) // useEffect 触发 fetchList
      } else {
        fetchList()
      }
    } catch {
      /* http 拦截已提示 */
    }
  }

  const filterControls = (
    <div className="activity-filter-controls">
      <Input.Search
        className="activity-filter-keyword"
        placeholder="搜索活动名称 / 主题 / 负责人"
        allowClear
        value={keywordDraft}
        onChange={(event) => {
          const value = event.target.value
          setKeywordDraft(value)
          if (!value && keyword) {
            setKeyword('')
            setPage(1)
          }
        }}
        onSearch={applyKeyword}
      />
      <Select
        className="activity-filter-select"
        placeholder="类型筛选"
        allowClear
        options={TYPE_OPTIONS}
        value={type}
        onChange={(value) => {
          setType(value)
          setPage(1)
        }}
      />
      <Select
        className="activity-filter-select"
        aria-label="组织单位"
        placeholder="全部组织单位"
        allowClear
        showSearch
        options={organizers.map((value) => ({ value, label: value }))}
        value={organizer}
        onChange={(value) => {
          setOrganizer(value)
          setPage(1)
        }}
      />
    </div>
  )

  const columns = [
    {
      title: '活动名称',
      dataIndex: 'name',
      key: 'name',
      width: 320,
      render: (name: string, record: ActivityRecord) => (
        <>
          <Button
            type="link"
            className="activity-name-link"
            onClick={() => navigate(`/activities/${record.id}`)}
          >
            {name}
          </Button>
          <span className="business-table-meta">
            {record.organizer ||
              record.location ||
              record.theme ||
              '地点与组织单位待补充'}
          </span>
        </>
      ),
    },
    {
      title: '类型',
      dataIndex: 'type',
      key: 'type',
      render: (t: string) => TYPE_MAP[t] ?? t,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (s: string) => <StatusTag status={s} />,
    },
    {
      title: (
        <Button
          type="text"
          aria-label="按活动日期排序"
          onClick={() => {
            setDateOrder(dateOrder === 'asc' ? 'desc' : 'asc')
            setPage(1)
          }}
        >
          活动日期 {dateOrder === 'asc' ? '↑' : '↓'}
        </Button>
      ),
      key: 'time',
      width: 240,
      align: 'right' as const,
      onCell: () => ({
        style: { fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' },
      }),
      render: (_: unknown, r: ActivityRecord) =>
        r.startDate
          ? r.endDate && r.endDate !== r.startDate
            ? `${r.startDate} ~ ${r.endDate}`
            : r.startDate
          : '-',
    },
    {
      title: '任务进度',
      key: 'tasks',
      width: 120,
      render: (_: unknown, r: ActivityRecord) => (
        <div>
          {r.taskCount == null ? (
            '—'
          ) : (
            <>
              <span>
                {r.completedTaskCount ?? 0} / {r.taskCount}
              </span>
              <Progress
                percent={
                  r.taskCount
                    ? Math.round(
                        ((r.completedTaskCount ?? 0) / r.taskCount) * 100,
                      )
                    : 0
                }
                showInfo={false}
                size="small"
              />
            </>
          )}
        </div>
      ),
    },
    {
      title: '材料',
      dataIndex: 'materialCount',
      key: 'materials',
      render: (v?: number) => (v == null ? '—' : v + ' 份'),
    },
    {
      title: '负责人',
      dataIndex: 'leader',
      key: 'leader',
      render: (v: string) => v || '-',
    },
    {
      title: '操作',
      key: 'action',
      align: 'right' as const,
      width: 144,
      fixed: 'right' as const,
      render: (_: unknown, r: ActivityRecord) => {
        const items: MenuProps['items'] = []
        if (
          hasPerm('activity:edit') &&
          !['FINISHED', 'ARCHIVED'].includes(r.status)
        )
          items.push({ key: 'edit', icon: <EditOutlined />, label: '编辑' })
        if (hasPerm('activity:edit') && NEXT_STATUS[r.status]) {
          items.push({
            key: 'advance',
            label: `推进至${ACTIVITY_STATUS_LABEL[NEXT_STATUS[r.status]]}`,
          })
        }
        if (hasPerm('activity:delete')) {
          items.push({
            key: 'delete',
            icon: <DeleteOutlined />,
            label: '删除',
            danger: true,
          })
        }

        return (
          <Space size="small" wrap={false}>
            <Button
              aria-label="详情"
              type="link"
              size="small"
              icon={<EyeOutlined />}
              onClick={() => navigate(`/activities/${r.id}`)}
            >
              详情
            </Button>
            {items.length > 0 && (
              <Dropdown
                trigger={['click']}
                placement="bottomRight"
                menu={{
                  items,
                  onClick: ({ key }) => {
                    if (key === 'edit') void openEdit(r)
                    if (key === 'advance') void handleAdvance(r)
                    if (key === 'delete') {
                      modal.confirm({
                        title: '确认删除该活动？',
                        content: `删除后「${r.name}」将无法恢复。`,
                        okText: '删除',
                        cancelText: '取消',
                        okButtonProps: { danger: true },
                        onOk: () => handleDelete(r.id),
                      })
                    }
                  },
                }}
              >
                <Button
                  type="text"
                  size="small"
                  icon={<EllipsisOutlined />}
                  aria-label={`更多操作：${r.name}`}
                />
              </Dropdown>
            )}
          </Space>
        )
      },
    },
  ]

  return (
    <div className="activity-list-page">
      <PageHeader
        title="活动管理"
        description="跟进策划、审核、执行与归档"
        extra={
          hasPerm('activity:create') && (
            <Button
              aria-label="新增活动"
              type="primary"
              icon={<PlusOutlined />}
              onClick={openCreate}
            >
              新增活动
            </Button>
          )
        }
      />

      <section className="business-summary" aria-label="组织活动概览">
        {[
          [
            '全部活动',
            stats
              ? Object.values(stats).reduce(
                  (sum, count) => sum + (Number(count) || 0),
                  0,
                )
              : null,
            '组织活动总数',
          ],
          ['策划审核', stats?.PLAN_REVIEW, '需要处理'],
          ['执行中', stats?.EXECUTING, '持续跟进'],
          ['已归档', stats?.ARCHIVED, '材料可查'],
        ].map(([label, value, note], index) => (
          <div
            role="button"
            tabIndex={0}
            onClick={() => {
              setStatus(
                [undefined, 'PLAN_REVIEW', 'EXECUTING', 'ARCHIVED'][index],
              )
              setPage(1)
            }}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault()
                setStatus(
                  [undefined, 'PLAN_REVIEW', 'EXECUTING', 'ARCHIVED'][index],
                )
                setPage(1)
              }
            }}
            className={
              index === 1 ? 'business-metric attention' : 'business-metric'
            }
            key={String(label)}
          >
            <span>{label}</span>
            <div>
              <strong>{value ?? '—'}</strong>
              <small>{note}</small>
            </div>
          </div>
        ))}
      </section>

      <GlassCard className="business-activity-surface">
        <div
          className="business-status-tabs"
          role="group"
          aria-label="活动状态筛选"
        >
          {[
            { value: undefined, label: '全部活动' },
            ...ACTIVITY_STATUS_OPTIONS,
          ].map((option) => (
            <button
              key={option.value ?? 'all'}
              type="button"
              aria-pressed={status === option.value}
              onClick={() => {
                setStatus(option.value)
                setPage(1)
              }}
            >
              {option.label}{' '}
              <small>
                {stats
                  ? option.value
                    ? stats[option.value as keyof ActivityStats]
                    : Object.values(stats).reduce(
                        (sum, value) => sum + value,
                        0,
                      )
                  : '—'}
              </small>
            </button>
          ))}
        </div>
        <div className="business-table-toolbar">
          {compactFilters ? (
            <>
              <div className="activity-filter-summary">
                <Button
                  icon={<FilterOutlined />}
                  onClick={() => setFilterDrawerOpen(true)}
                >
                  筛选{activeFilterCount > 0 ? `（${activeFilterCount}）` : ''}
                </Button>
                {keyword && (
                  <Tag
                    closable
                    onClose={(event) => {
                      event.preventDefault()
                      setKeyword('')
                      setKeywordDraft('')
                      setPage(1)
                    }}
                  >
                    关键词：{keyword}
                  </Tag>
                )}
                {status && (
                  <Tag
                    closable
                    onClose={(event) => {
                      event.preventDefault()
                      setStatus(undefined)
                      setPage(1)
                    }}
                  >
                    {ACTIVITY_STATUS_LABEL[status] ?? status}
                  </Tag>
                )}
                {type && (
                  <Tag
                    closable
                    onClose={(event) => {
                      event.preventDefault()
                      setType(undefined)
                      setPage(1)
                    }}
                  >
                    {TYPE_MAP[type] ?? type}
                  </Tag>
                )}
                {activeFilterCount > 0 && (
                  <Button type="link" onClick={clearFilters}>
                    清除
                  </Button>
                )}
              </div>
              <Drawer
                className="activity-filter-drawer"
                title="筛选活动"
                placement="right"
                open={filterDrawerOpen}
                onClose={() => setFilterDrawerOpen(false)}
                footer={
                  <Space
                    style={{ display: 'flex', justifyContent: 'space-between' }}
                  >
                    <Button onClick={clearFilters}>清除条件</Button>
                    <Button
                      type="primary"
                      onClick={() => setFilterDrawerOpen(false)}
                    >
                      完成
                    </Button>
                  </Space>
                }
              >
                {filterControls}
              </Drawer>
            </>
          ) : (
            <div className="activity-filter-desktop">
              {filterControls}
              <Button
                type="link"
                disabled={activeFilterCount === 0}
                onClick={clearFilters}
              >
                清除筛选
              </Button>
            </div>
          )}
          <Segmented
            className="business-density"
            size="small"
            aria-label="表格密度"
            options={[
              { label: '标准', value: 'middle' },
              { label: '紧凑', value: 'small' },
            ]}
            value={density}
            onChange={(value) => setDensity(value as 'middle' | 'small')}
          />
        </div>

        {failed && (
          <p className="activity-inline-note error" role="alert">
            活动列表读取失败。
            <Button type="link" onClick={fetchList}>
              重新加载
            </Button>
          </p>
        )}
        {compactFilters ? (
          <Spin spinning={loading}>
            <div className="activity-mobile-list">
              {data.length ? (
                data.map((r) => (
                  <article key={r.id}>
                    <header>
                      <Button
                        type="link"
                        onClick={() => navigate(`/activities/${r.id}`)}
                      >
                        {r.name}
                      </Button>
                      <StatusTag status={r.status} />
                    </header>
                    <p>
                      {r.organizer || '组织单位待定'} /{' '}
                      {r.location || '地点待定'}
                    </p>
                    <dl>
                      <div>
                        <dt>负责人</dt>
                        <dd>{r.leader || '未设置'}</dd>
                      </div>
                      <div>
                        <dt>活动日期</dt>
                        <dd>
                          {r.startDate || '待定'}
                          {r.endDate && r.endDate !== r.startDate
                            ? ' ~ ' + r.endDate
                            : ''}
                        </dd>
                      </div>
                      <div>
                        <dt>任务进度</dt>
                        <dd>
                          {r.taskCount == null
                            ? '—'
                            : (r.completedTaskCount ?? 0) + ' / ' + r.taskCount}
                        </dd>
                      </div>
                      <div>
                        <dt>关联材料</dt>
                        <dd>
                          {r.materialCount == null
                            ? '—'
                            : r.materialCount + ' 份'}
                        </dd>
                      </div>
                    </dl>
                    <Space>
                      <Button
                        type="link"
                        onClick={() => navigate(`/activities/${r.id}`)}
                      >
                        查看详情
                      </Button>
                      {hasPerm('activity:edit') && (
                        <Button type="link" onClick={() => openEdit(r)}>
                          编辑活动
                        </Button>
                      )}
                    </Space>
                  </article>
                ))
              ) : (
                <Empty description="没有匹配的活动，请调整筛选条件。" />
              )}
            </div>
            <Pagination
              simple
              current={page}
              pageSize={size}
              total={total}
              onChange={setPage}
            />
          </Spin>
        ) : (
          <>
            <GlassTable<ActivityRecord>
              size={density}
              columns={columns}
              dataSource={data}
              scroll={{ x: 1000 }}
              loading={loading}
              pagination={{
                current: page,
                pageSize: size,
                total,
                showSizeChanger: true,
                showTotal: (t) => `共 ${t} 条`,
                onChange: (p, s) => {
                  setPage(p)
                  setSize(s)
                },
              }}
            />
          </>
        )}
      </GlassCard>

      <ActivityEditorDrawer
        open={modalOpen}
        activityId={editing?.id}
        onClose={() => setModalOpen(false)}
        onSaved={() => {
          if (page === 1) void fetchList()
          else setPage(1)
          void refreshSummary()
          listActivityOrganizers()
            .then(setOrganizers)
            .catch(() => {})
        }}
      />
    </div>
  )
}
