import { useEffect, useState } from 'react'
import { Button, DatePicker, Form, Input, message, Popconfirm, Select, Space } from 'antd'
import { DeleteOutlined, EditOutlined, EyeOutlined, PlusOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import dayjs, { type Dayjs } from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import GlassModal from '@/components/glass/GlassModal'
import GlassTable from '@/components/glass/GlassTable'
import PageHeader from '@/components/glass/PageHeader'
import StatusTag from '@/components/glass/StatusTag'
import { listActivities, getActivity, createActivity, updateActivity, changeActivityStatus, deleteActivity } from '@/api/activity'
import type { ActivitySave, ActivityVO } from '@/api/activity'
import { ACTIVITY_STATUS_LABEL, ACTIVITY_STATUS_OPTIONS } from '@/api/activityStatus'
import { useAuthStore } from '@/stores/auth'

// 活动状态 → 推进后状态（状态机：ASSIGNED→PLANNING→PLAN_REVIEW→EXECUTING→FINISHED→ARCHIVED）
const NEXT_STATUS: Record<string, string> = {
  ASSIGNED: 'PLANNING',
  PLANNING: 'PLAN_REVIEW',
  PLAN_REVIEW: 'EXECUTING',
  EXECUTING: 'FINISHED',
  FINISHED: 'ARCHIVED',
}

const TYPE_MAP: Record<string, string> = {
  PARTY_LESSON: '党课',
  DATE: '主题团日',
  PARTY_DAY: '主题党日',
  COMPETITION: '竞赛',
  VOLUNTEER: '志愿服务',
  LECTURE: '讲座',
  MEETING: '会议',
  OTHER: '其他',
}

const TYPE_OPTIONS = Object.entries(TYPE_MAP).map(([value, label]) => ({ value, label }))

type ActivityRecord = ActivityVO & { key: number }

interface FormValues extends Omit<ActivitySave, 'startDate' | 'endDate'> {
  range?: Dayjs[] | null
}

/** 编辑回填用 initialValues + key 重挂载（GlassModal destroyOnHidden 关闭即卸载，setFieldsValue 在挂载前调用会丢失，Task 21 同款修复） */
function toFormValues(detail?: ActivityVO & { description?: string }): FormValues | undefined {
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
    range: detail.startDate && detail.endDate ? [dayjs(detail.startDate), dayjs(detail.endDate)] : undefined,
  }
}

export default function ActivityList() {
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const isMinisterOrAbove = (user?.roleLevel ?? 0) >= 3

  const [data, setData] = useState<ActivityRecord[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(10)
  const [keyword, setKeyword] = useState('')
  const [status, setStatus] = useState<string | undefined>()
  const [type, setType] = useState<string | undefined>()
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<ActivityVO | null>(null)
  const [formInit, setFormInit] = useState<FormValues | undefined>(undefined)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<FormValues>()

  const fetchList = async () => {
    setLoading(true)
    try {
      const res = await listActivities({ keyword: keyword || undefined, status, type, page, size })
      setData((res.records ?? []).map((r) => ({ ...r, key: r.id })))
      setTotal(res.total)
    } catch {
      /* http 拦截已提示 */
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchList()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, size, keyword, status, type])

  const openCreate = () => {
    setEditing(null)
    setFormInit(undefined)
    setModalOpen(true)
  }

  const openEdit = async (record: ActivityVO) => {
    setEditing(record)
    try {
      // 列表 VO 不含 description，编辑时拉详情补全；回填交给 initialValues（挂载时生效）
      const detail = (await getActivity(record.id)) as ActivityVO & { description?: string }
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
      } else {
        await createActivity(payload)
        message.success('创建成功')
        setPage(1)
      }
      setModalOpen(false)
      fetchList()
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
    } catch {
      /* http 拦截已提示 */
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteActivity(id)
      message.success('已删除')
      fetchList()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const columns = [
    { title: '活动名称', dataIndex: 'name', key: 'name' },
    { title: '类型', dataIndex: 'type', key: 'type', render: (t: string) => TYPE_MAP[t] ?? t },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (s: string) => <StatusTag status={s} />,
    },
    {
      title: '时间',
      key: 'time',
      render: (_: unknown, r: ActivityRecord) =>
        r.startDate ? `${r.startDate} ~ ${r.endDate ?? ''}` : '-',
    },
    { title: '负责人', dataIndex: 'leader', key: 'leader', render: (v: string) => v || '-' },
    {
      title: '操作',
      key: 'action',
      render: (_: unknown, r: ActivityRecord) => (
        <Space size="small" wrap>
          <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => navigate(`/activities/${r.id}`)}>
            详情
          </Button>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(r)}>
            编辑
          </Button>
          {isMinisterOrAbove && NEXT_STATUS[r.status] && (
            <Button type="link" size="small" onClick={() => handleAdvance(r)}>
              推进状态
            </Button>
          )}
          {isMinisterOrAbove && (
            <Popconfirm title="确认删除该活动？" onConfirm={() => handleDelete(r.id)} okText="删除" cancelText="取消">
              <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                删除
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        title="活动管理"
        description="党建活动全流程管理"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新增活动
          </Button>
        }
      />

      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space wrap>
          <Input.Search
            placeholder="搜索活动名称 / 主题"
            allowClear
            style={{ width: 240 }}
            onSearch={(v) => {
              setKeyword(v)
              setPage(1)
            }}
          />
          <Select
            placeholder="状态筛选"
            allowClear
            options={ACTIVITY_STATUS_OPTIONS}
            style={{ width: 140 }}
            value={status}
            onChange={(v) => {
              setStatus(v)
              setPage(1)
            }}
          />
          <Select
            placeholder="类型筛选"
            allowClear
            options={TYPE_OPTIONS}
            style={{ width: 140 }}
            value={type}
            onChange={(v) => {
              setType(v)
              setPage(1)
            }}
          />
        </Space>
      </GlassCard>

      <GlassTable<ActivityRecord>
        columns={columns}
        dataSource={data}
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

      <GlassModal
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
          <Form.Item name="name" label="活动名称" rules={[{ required: true, message: '请输入活动名称' }]}>
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item name="theme" label="活动主题">
            <Input maxLength={200} />
          </Form.Item>
          <Form.Item name="type" label="活动类型">
            <Select options={TYPE_OPTIONS} />
          </Form.Item>
          <Form.Item name="range" label="起止日期">
            <DatePicker.RangePicker style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="location" label="活动地点">
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item name="organizer" label="组织单位">
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item name="leader" label="负责人">
            <Input maxLength={50} />
          </Form.Item>
          <Form.Item name="host" label="主持人">
            <Input maxLength={50} />
          </Form.Item>
          <Form.Item name="description" label="活动描述">
            <Input.TextArea rows={3} maxLength={1000} />
          </Form.Item>
        </Form>
      </GlassModal>
    </div>
  )
}
