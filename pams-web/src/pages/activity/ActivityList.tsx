import FormSurface, { FormSection } from '@/components/form/FormSurface'
import { useEffect, useState } from 'react'
import {
  App,
  Button,
  DatePicker,
  Drawer,
  Dropdown,
  Form,
  Grid,
  Input,
  Segmented,
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
import dayjs, { type Dayjs } from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import GlassTable from '@/components/glass/GlassTable'
import PageHeader from '@/components/glass/PageHeader'
import StatusTag from '@/components/glass/StatusTag'
import {
  listActivities,
  getActivity,
  createActivity,
  updateActivity,
  changeActivityStatus,
  deleteActivity,
} from '@/api/activity'
import type { ActivitySave, ActivityVO } from '@/api/activity'
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

interface FormValues extends Omit<ActivitySave, 'startDate' | 'endDate'> {
  range?: Dayjs[] | null
}

/** 编辑回填用 initialValues + key 重挂载（GlassModal destroyOnHidden 关闭即卸载，setFieldsValue 在挂载前调用会丢失，Task 21 同款修复） */
function toFormValues(
  detail?: ActivityVO & { description?: string },
): FormValues | undefined {
  if (!detail) return undefined
  return {
    name: detail.name,
    theme: detail.theme,
    type: detail.type,
    location: detail.location,
    organizer: detail.organizer,
    host: detail.host,
    leader: detail.leader,
    description: detail.description ?? '',
    range:
      detail.startDate && detail.endDate
        ? [dayjs(detail.startDate), dayjs(detail.endDate)]
        : undefined,
  }
}

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
  const [filterDrawerOpen, setFilterDrawerOpen] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<ActivityVO | null>(null)
  const [formInit, setFormInit] = useState<FormValues | undefined>(undefined)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<FormValues>()

  const fetchList = async () => {
    setLoading(true)
    try {
      const res = await listActivities({
        keyword: keyword || undefined,
        status,
        type,
        page,
        size,
      })
      setData((res.records ?? []).map((r) => ({ ...r, key: r.id })))
      setTotal(res.total)
    } catch {
      /* http 拦截已提示 */
    } finally {
      setLoading(false)
    }
  }

  const refreshSummary = () =>
    getDashboard()
      .then((dashboard) => setStats(dashboard.activityStats))
      .catch(() => {})
  useEffect(() => {
    void refreshSummary()
  }, [])
  useEffect(() => {
    if (location.state?.create && hasPerm('activity:create')) {
      setEditing(null)
      setFormInit(undefined)
      setModalOpen(true)
      navigate(location.pathname, { replace: true, state: null })
    }
  }, [location.state, location.pathname, navigate, hasPerm])

  useEffect(() => {
    fetchList()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, size, keyword, status, type])

  const activeFilterCount =
    Number(Boolean(keyword)) + Number(Boolean(status)) + Number(Boolean(type))
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
    setPage(1)
    setFilterDrawerOpen(false)
  }

  const openCreate = () => {
    setEditing(null)
    setFormInit(undefined)
    setModalOpen(true)
  }

  const openEdit = async (record: ActivityVO) => {
    setEditing(record)
    try {
      // 列表 VO 不含 description，编辑时拉详情补全；回填交给 initialValues（挂载时生效）
      const detail = (await getActivity(record.id)) as ActivityVO & {
        description?: string
      }
      setFormInit(toFormValues(detail))
      setModalOpen(true)
    } catch {
      /* http 拦截已提示 */
    }
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const [start, end] = values.range ?? [null, null]
      const payload: ActivitySave = {
        name: values.name,
        theme: values.theme,
        type: values.type,
        startDate: start ? start.format('YYYY-MM-DD') : null,
        endDate: end ? end.format('YYYY-MM-DD') : null,
        location: values.location,
        organizer: values.organizer,
        host: values.host,
        leader: values.leader,
        description: values.description,
      }
      if (editing) {
        await updateActivity(editing.id, payload)
        message.success('保存成功')
        fetchList()
      } else {
        await createActivity(payload)
        message.success('创建成功')
        // E2E 发现的回归：page 已是 1 时 setPage(1) 不触发 effect，列表不刷新——当前页为 1 时手动刷新
        if (page === 1) fetchList()
        else setPage(1) // 翻页场景由 useEffect 触发刷新
      }
      setModalOpen(false)
      void refreshSummary()
    } catch {
      /* 校验失败或 http 拦截已提示 */
    } finally {
      setSaving(false)
    }
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
        placeholder="搜索活动名称 / 主题"
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
        placeholder="状态筛选"
        allowClear
        options={ACTIVITY_STATUS_OPTIONS}
        value={status}
        onChange={(value) => {
          setStatus(value)
          setPage(1)
        }}
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
          <span className="business-table-name">{name}</span>
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
      title: '时间',
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
        if (hasPerm('activity:edit'))
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
    <div>
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
              {option.label}
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
      </GlassCard>

      <FormSurface
        mode="drawer"
        description="设置活动信息、时间安排与负责人。"
        title={editing ? '编辑活动' : '新增活动'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={handleSave}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
        footer={
          <Space>
            <Button onClick={() => setModalOpen(false)}>取消</Button>
            <Button type="primary" loading={saving} onClick={handleSave}>
              保存
            </Button>
          </Space>
        }
      >
        <Form
          form={form}
          layout="vertical"
          preserve={false}
          key={editing ? `edit-${editing.id}` : 'create'}
          initialValues={formInit ?? { type: 'OTHER' }}
        >
          <FormSection title="活动信息">
            <Form.Item
              name="name"
              label="活动名称"
              rules={[{ required: true, message: '请输入活动名称' }]}
            >
              <Input maxLength={100} />
            </Form.Item>
            <Form.Item name="theme" label="活动主题">
              <Input maxLength={200} />
            </Form.Item>
            <Form.Item name="type" label="活动类型">
              <Select options={TYPE_OPTIONS} />
            </Form.Item>
            <Form.Item name="description" label="活动描述">
              <Input.TextArea rows={3} maxLength={1000} />
            </Form.Item>
          </FormSection>
          <FormSection title="时间与地点">
            <Form.Item name="range" label="起止日期">
              <DatePicker.RangePicker style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="location" label="活动地点">
              <Input maxLength={100} />
            </Form.Item>
          </FormSection>
          <FormSection title="组织与分工">
            <Form.Item name="organizer" label="组织单位">
              <Input maxLength={100} />
            </Form.Item>
            <Form.Item name="leader" label="负责人">
              <Input maxLength={50} />
            </Form.Item>
            <Form.Item name="host" label="主持人">
              <Input maxLength={50} />
            </Form.Item>
          </FormSection>
        </Form>
      </FormSurface>
    </div>
  )
}
