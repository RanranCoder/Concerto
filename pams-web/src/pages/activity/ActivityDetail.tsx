import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import {
  AutoComplete,
  Button,
  Descriptions,
  Empty,
  Form,
  Input,
  message,
  Popconfirm,
  Select,
  Space,
  Spin,
  Tabs,
  Tag,
  Tooltip,
} from 'antd'
import {
  ArrowLeftOutlined,
  ArrowRightOutlined,
  BarChartOutlined,
  DeleteOutlined,
  EditOutlined,
  PaperClipOutlined,
  PlusOutlined,
  SendOutlined,
} from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import dayjs from 'dayjs'
import PageHeader from '@/components/glass/PageHeader'
import GlassCard from '@/components/glass/GlassCard'
import GlassModal from '@/components/glass/GlassModal'
import GlassTable from '@/components/glass/GlassTable'
import StatusTag from '@/components/glass/StatusTag'
import {
  changeActivityStatus,
  getActivityDetail,
  type ActivityAgendaVO,
  type ActivityDetail,
  type ActivityPlanVO,
  type ActivityVO,
  type ScoreRecordVO,
  type ScoreRuleVO,
  type SeatMapVO,
} from '@/api/activity'
import {
  createPlan,
  reviewPlan,
  submitPlan,
  updatePlan,
  type PlanSave,
} from '@/api/plan'
import { createAgenda, deleteAgenda, listAgendas, updateAgenda } from '@/api/agenda'
import { createSeat, deleteSeat, listSeats, updateSeat } from '@/api/seat'
import { countSignins, createSignin, deleteSignin, listSignins, type SigninVO } from '@/api/signin'
import { useAuthStore } from '@/stores/auth'

// 活动状态机（与后端 ActivityStatus 一致）
const STATUS_ORDER = ['ASSIGNED', 'PLANNING', 'PLAN_REVIEW', 'EXECUTING', 'FINISHED', 'ARCHIVED']
const STATUS_TEXT: Record<string, string> = {
  ASSIGNED: '已下达',
  PLANNING: '排期中',
  PLAN_REVIEW: '策划审核',
  EXECUTING: '执行中',
  FINISHED: '已完成',
  ARCHIVED: '已归档',
}

const PLAN_STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿',
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
}

const SIGN_TYPE_TEXT: Record<string, string> = { MANUAL: '手动', SCAN: '扫码' }

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

/** 策划书 flow/budget 字段：可能是 JSON 数组，也可能是纯文本 */
function parseJsonField(s: string | null | undefined): unknown {
  if (!s) return null
  const t = s.trim()
  if (!t) return null
  if (t.startsWith('[') || t.startsWith('{')) {
    try {
      return JSON.parse(t)
    } catch {
      return null
    }
  }
  return null
}

function FieldBlock({ label, value }: { label: string; value: unknown }) {
  if (value == null || (typeof value === 'string' && !value.trim())) return null
  return (
    <div style={{ marginBottom: 16 }}>
      <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-red)', marginBottom: 6 }}>{label}</div>
      <div style={{ whiteSpace: 'pre-wrap', color: 'var(--color-text)', lineHeight: 1.8 }}>{String(value)}</div>
    </div>
  )
}

/** 流程/预算：JSON 数组按条目展示，否则按纯文本 */
function FlowBudgetBlock({ label, value }: { label: string; value: string | null }) {
  if (!value) return null
  const parsed = parseJsonField(value)
  if (Array.isArray(parsed)) {
    return (
      <div style={{ marginBottom: 16 }}>
        <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-red)', marginBottom: 6 }}>{label}</div>
        {parsed.map((item, i) => (
          <div key={i} style={{ marginBottom: 4, color: 'var(--color-text)', lineHeight: 1.7 }}>
            {typeof item === 'string'
              ? `• ${item}`
              : Object.entries(item as Record<string, unknown>)
                  .map(([k, v]) => `${k}: ${String(v)}`)
                  .join('　')}
          </div>
        ))}
      </div>
    )
  }
  return <FieldBlock label={label} value={value} />
}

// ==================== 策划书 Tab ====================

const PLAN_FIELDS: Array<{ name: string; label: string; placeholder: string; textarea: boolean }> = [
  { name: 'background', label: '活动背景', placeholder: '活动的背景与缘由', textarea: true },
  { name: 'purpose', label: '活动目的', placeholder: '活动要达成的目的', textarea: true },
  { name: 'content', label: '活动内容', placeholder: '活动主要内容', textarea: true },
  { name: 'flow', label: '活动流程', placeholder: '流程，可填 JSON：[{"step":"...","detail":"..."}] 或纯文本', textarea: true },
  { name: 'notice', label: '注意事项', placeholder: '注意事项', textarea: true },
  { name: 'emergency', label: '应急预案', placeholder: '应急预案', textarea: true },
  { name: 'budget', label: '经费预算', placeholder: '预算，可填 JSON：[{"item":"...","quantity":1,"unitPrice":0,"totalPrice":0}] 或纯文本', textarea: true },
]

function PlanTab({
  activityId,
  plan,
  onChanged,
}: {
  activityId: number
  plan: { latest: ActivityPlanVO | null; status: string | null } | null
  onChanged: () => void
}) {
  const user = useAuthStore((s) => s.user)
  const isMinisterOrAbove = (user?.roleLevel ?? 0) >= 3
  const latest = plan?.latest ?? null
  const [modalOpen, setModalOpen] = useState(false)
  const [reviewOpen, setReviewOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm()
  const [reviewForm] = Form.useForm()

  const openCreate = () => {
    form.resetFields()
    setModalOpen(true)
  }

  const openEdit = (p: ActivityPlanVO) => {
    form.setFieldsValue({
      background: p.background ?? '',
      purpose: p.purpose ?? '',
      content: p.content ?? '',
      flow: p.flow ?? '',
      notice: p.notice ?? '',
      emergency: p.emergency ?? '',
      budget: p.budget ?? '',
    })
    setModalOpen(true)
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const payload: PlanSave = {
        activityId,
        background: values.background || null,
        purpose: values.purpose || null,
        content: values.content || null,
        flow: values.flow || null,
        notice: values.notice || null,
        emergency: values.emergency || null,
        budget: values.budget || null,
      }
      if (latest) {
        await updatePlan(latest.id, payload)
        message.success('策划书已保存')
      } else {
        await createPlan({ ...payload, version: 1 })
        message.success('策划书已创建')
      }
      setModalOpen(false)
      onChanged()
    } catch {
      /* 校验失败或 http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  const handleNewVersion = async () => {
    const next: PlanSave = {
      activityId,
      version: (latest?.version ?? 1) + 1,
      background: latest?.background ?? null,
      purpose: latest?.purpose ?? null,
      content: latest?.content ?? null,
      flow: latest?.flow ?? null,
      notice: latest?.notice ?? null,
      emergency: latest?.emergency ?? null,
      budget: latest?.budget ?? null,
    }
    try {
      await createPlan(next)
      message.success('已创建新版本')
      onChanged()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const handleSubmit = async () => {
    if (!latest) return
    try {
      await submitPlan(latest.id)
      message.success('已提交审核')
      onChanged()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const handleReview = async (approved: boolean) => {
    if (!latest) return
    const values = await reviewForm.validateFields()
    setSaving(true)
    try {
      await reviewPlan(latest.id, approved, values.comment || undefined)
      message.success(approved ? '已通过审核' : '已驳回')
      setReviewOpen(false)
      reviewForm.resetFields()
      onChanged()
    } catch {
      /* http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  if (!latest) {
    return (
      <div style={{ textAlign: 'center', padding: 32 }}>
        <Empty description="尚未编写策划书" />
        <Button type="primary" icon={<PlusOutlined />} style={{ marginTop: 8 }} onClick={openCreate}>
          创建策划书
        </Button>
        <GlassModal
          title="创建策划书"
          open={modalOpen}
          onCancel={() => setModalOpen(false)}
          onOk={handleSave}
          confirmLoading={saving}
          footer={
            <Space>
              <Button onClick={() => setModalOpen(false)}>取消</Button>
              <Button type="primary" loading={saving} onClick={handleSave}>
                保存
              </Button>
            </Space>
          }
        >
          <Form form={form} layout="vertical" preserve={false}>
            {PLAN_FIELDS.map((f) => (
              <Form.Item key={f.name} name={f.name} label={f.label}>
                <Input.TextArea rows={f.textarea ? 4 : 1} placeholder={f.placeholder} />
              </Form.Item>
            ))}
          </Form>
        </GlassModal>
      </div>
    )
  }

  return (
    <div>
      <GlassCard style={{ padding: 20 }}>
        <Space style={{ marginBottom: 16 }} wrap>
          <Tag color="red">版本 v{latest.version ?? 1}</Tag>
          <Tag>{PLAN_STATUS_TEXT[latest.status] ?? latest.status}</Tag>
          {latest.reviewComment && <Tag color="orange">审核意见：{latest.reviewComment}</Tag>}
          {latest.status === 'DRAFT' || latest.status === 'REJECTED' ? (
            <>
              <Button type="primary" icon={<EditOutlined />} onClick={() => openEdit(latest)}>
                编辑
              </Button>
              <Button icon={<SendOutlined />} onClick={handleSubmit}>
                提交审核
              </Button>
            </>
          ) : null}
          {latest.status === 'PENDING' && isMinisterOrAbove ? (
            <Space>
              <Button type="primary" onClick={() => setReviewOpen(true)}>
                审核
              </Button>
            </Space>
          ) : null}
          {latest.status === 'PENDING' && !isMinisterOrAbove ? (
            <Tag color="processing">等待部长审核</Tag>
          ) : null}
          {latest.status === 'APPROVED' ? (
            <>
              <Tag color="green">已通过</Tag>
              <Button onClick={handleNewVersion}>新建版本</Button>
            </>
          ) : null}
        </Space>

        <FieldBlock label="活动背景" value={latest.background} />
        <FieldBlock label="活动目的" value={latest.purpose} />
        <FieldBlock label="活动内容" value={latest.content} />
        <FlowBudgetBlock label="活动流程" value={latest.flow} />
        <FieldBlock label="注意事项" value={latest.notice} />
        <FieldBlock label="应急预案" value={latest.emergency} />
        <FlowBudgetBlock label="经费预算" value={latest.budget} />
      </GlassCard>

      {/* 编辑/创建弹窗 */}
      <GlassModal
        title={latest ? '编辑策划书' : '创建策划书'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setModalOpen(false)}>取消</Button>
            <Button type="primary" loading={saving} onClick={handleSave}>
              保存
            </Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical" preserve={false}>
          {PLAN_FIELDS.map((f) => (
            <Form.Item key={f.name} name={f.name} label={f.label}>
              <Input.TextArea rows={4} placeholder={f.placeholder} />
            </Form.Item>
          ))}
        </Form>
      </GlassModal>

      {/* 审核弹窗 */}
      <GlassModal
        title="审核策划书"
        open={reviewOpen}
        onCancel={() => setReviewOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setReviewOpen(false)}>取消</Button>
            <Button danger loading={saving} onClick={() => handleReview(false)}>
              驳回
            </Button>
            <Button type="primary" loading={saving} onClick={() => handleReview(true)}>
              通过
            </Button>
          </Space>
        }
      >
        <Form form={reviewForm} layout="vertical" preserve={false}>
          <Form.Item name="comment" label="审核意见">
            <Input.TextArea rows={3} placeholder="填写审核意见（可选）" />
          </Form.Item>
        </Form>
      </GlassModal>
    </div>
  )
}

// ==================== 议程 Tab ====================

function AgendaTab({ activityId }: { activityId: number }) {
  const [list, setList] = useState<ActivityAgendaVO[]>([])
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<ActivityAgendaVO | null>(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm()

  const fetchList = useCallback(async () => {
    try {
      setList(await listAgendas(activityId))
    } catch {
      /* http 拦截已提示 */
    }
  }, [activityId])

  useEffect(() => {
    fetchList()
  }, [fetchList])

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    const maxStep = list.reduce((a, x) => Math.max(a, x.stepNo), 0)
    form.setFieldsValue({ stepNo: maxStep + 1 })
    setModalOpen(true)
  }

  const openEdit = (a: ActivityAgendaVO) => {
    setEditing(a)
    form.setFieldsValue({ stepNo: a.stepNo, title: a.title, remark: a.remark ?? '' })
    setModalOpen(true)
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const payload = { activityId, stepNo: values.stepNo, title: values.title, remark: values.remark || null }
      if (editing) {
        await updateAgenda(editing.id, payload)
        message.success('议程已更新')
      } else {
        await createAgenda(payload)
        message.success('议程已新增')
      }
      setModalOpen(false)
      fetchList()
    } catch {
      /* http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteAgenda(id)
      message.success('已删除')
      fetchList()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const columns = [
    { title: '步骤', dataIndex: 'stepNo', key: 'stepNo', width: 80 },
    { title: '标题', dataIndex: 'title', key: 'title' },
    { title: '说明', dataIndex: 'remark', key: 'remark', render: (v: string | null) => v || '-' },
    {
      title: '操作',
      key: 'action',
      width: 140,
      render: (_: unknown, r: ActivityAgendaVO) => (
        <Space size="small">
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(r)}>
            编辑
          </Button>
          <Popconfirm title="确认删除该议程？" onConfirm={() => handleDelete(r.id)} okText="删除" cancelText="取消">
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <Space style={{ marginBottom: 12 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新增议程
        </Button>
      </Space>
      <GlassTable<ActivityAgendaVO>
        columns={columns}
        dataSource={list.map((x) => ({ ...x, key: x.id }))}
        pagination={false}
        rowKey="id"
      />
      <GlassModal
        title={editing ? '编辑议程' : '新增议程'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setModalOpen(false)}>取消</Button>
            <Button type="primary" loading={saving} onClick={handleSave}>
              保存
            </Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item name="stepNo" label="步骤序号" rules={[{ required: true, message: '请输入步骤序号' }]}>
            <Input type="number" min={1} />
          </Form.Item>
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入标题' }]}>
            <Input maxLength={200} />
          </Form.Item>
          <Form.Item name="remark" label="说明">
            <Input.TextArea rows={3} maxLength={500} />
          </Form.Item>
        </Form>
      </GlassModal>
    </div>
  )
}

// ==================== 座位表 Tab ====================

function SeatTab({ activityId }: { activityId: number }) {
  const [zones, setZones] = useState<Record<string, SeatMapVO[]>>({})
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<SeatMapVO | null>(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm()

  const fetchZones = useCallback(async () => {
    try {
      setZones(await listSeats(activityId))
    } catch {
      /* http 拦截已提示 */
    }
  }, [activityId])

  useEffect(() => {
    fetchZones()
  }, [fetchZones])

  const zoneEntries = Object.entries(zones)
  const zoneOptions = zoneEntries.map(([z]) => ({ value: z, label: z }))

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    if (zoneEntries.length) form.setFieldsValue({ zone: zoneEntries[0][0] })
    setModalOpen(true)
  }

  const openEdit = (s: SeatMapVO) => {
    setEditing(s)
    form.setFieldsValue({
      zone: s.zone,
      rowNo: s.rowNo,
      colNo: s.colNo,
      personName: s.personName ?? '',
      seatType: s.seatType ?? '',
    })
    setModalOpen(true)
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const payload = {
        activityId,
        zone: values.zone,
        rowNo: values.rowNo ?? null,
        colNo: values.colNo ?? null,
        personName: values.personName || null,
        seatType: values.seatType || null,
      }
      if (editing) {
        await updateSeat(editing.id, payload)
        message.success('座位已更新')
      } else {
        await createSeat(payload)
        message.success('座位已新增')
      }
      setModalOpen(false)
      fetchZones()
    } catch {
      /* http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteSeat(id)
      message.success('已删除')
      fetchZones()
    } catch {
      /* http 拦截已提示 */
    }
  }

  return (
    <div>
      <Space style={{ marginBottom: 12 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新增座位
        </Button>
      </Space>
      {zoneEntries.length === 0 ? (
        <Empty description="尚未安排座位" />
      ) : (
        zoneEntries.map(([zone, seats]) => (
          <GlassCard key={zone} style={{ padding: 16, marginBottom: 12 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
              <div style={{ fontWeight: 600, color: 'var(--color-text)' }}>{zone}</div>
              <span style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>共 {seats.length} 座</span>
            </div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
              {seats.map((s) => (
                <Tooltip key={s.id} title={`${s.rowNo ?? '-'}排 ${s.colNo ?? '-'}列 · ${s.seatType ?? '普通'}`}>
                  <div
                    style={{
                      padding: '6px 10px',
                      borderRadius: 8,
                      border: '1px solid var(--glass-border)',
                      background: 'var(--glass-bg-strong)',
                      cursor: 'pointer',
                      fontSize: 12,
                      color: 'var(--color-text)',
                    }}
                    onClick={() => openEdit(s)}
                  >
                    {s.personName || `座位 ${s.id}`}
                    {s.personName ? <span style={{ color: 'var(--color-text-secondary)', marginLeft: 4 }}>{s.seatType ?? ''}</span> : null}
                  </div>
                </Tooltip>
              ))}
            </div>
          </GlassCard>
        ))
      )}

      <GlassModal
        title={editing ? '编辑座位' : '新增座位'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        footer={
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div>
              {editing && (
                <Popconfirm
                  title="确认删除该座位？"
                  onConfirm={() => {
                    setModalOpen(false)
                    handleDelete(editing.id)
                  }}
                  okText="删除"
                  cancelText="取消"
                >
                  <Button danger icon={<DeleteOutlined />}>
                    删除
                  </Button>
                </Popconfirm>
              )}
            </div>
            <Space>
              <Button onClick={() => setModalOpen(false)}>取消</Button>
              <Button type="primary" loading={saving} onClick={handleSave}>
                保存
              </Button>
            </Space>
          </div>
        }
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item name="zone" label="区域" rules={[{ required: true, message: '请输入区域' }]}>
            <AutoComplete options={zoneOptions} placeholder="选择或输入区域" filterOption={(input, option) => ((option?.value as string) ?? '').includes(input)} />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="rowNo" label="排" style={{ flex: 1 }}>
              <Input type="number" min={1} />
            </Form.Item>
            <Form.Item name="colNo" label="列" style={{ flex: 1 }}>
              <Input type="number" min={1} />
            </Form.Item>
          </Space>
          <Form.Item name="personName" label="就座人">
            <Input maxLength={50} />
          </Form.Item>
          <Form.Item name="seatType" label="座位类型">
            <Input maxLength={50} placeholder="如 领导席 / 观众席 / 礼仪" />
          </Form.Item>
        </Form>
      </GlassModal>
    </div>
  )
}

// ==================== 评分 Tab（基础版，Task 15 深化） ====================

function ScoreTab({ rules, records }: { rules: ScoreRuleVO[]; records: ScoreRecordVO[] }) {
  const columns = useMemo(() => {
    const base: Array<Record<string, unknown>> = [
      { title: '队名', dataIndex: 'teamName', key: 'teamName' },
      { title: '分组', dataIndex: 'groupName', key: 'groupName', render: (v: unknown) => (v as string) || '-' },
    ]
    rules.forEach((r) => {
      base.push({
        title: `${r.dimensionName} (${r.fullMarks})`,
        dataIndex: `dim_${r.id}`,
        key: `dim_${r.id}`,
        width: 90,
      })
    })
    base.push({ title: '总分', dataIndex: 'total', key: 'total', width: 80 })
    base.push({ title: '名次', dataIndex: 'rankNo', key: 'rankNo', width: 80, render: (v: unknown) => v ?? '-' })
    return base as ColumnsType<Record<string, unknown>>
  }, [rules])

  const rows = useMemo(
    () =>
      records.map((r) => {
        let dims: Record<string, number> = {}
        try {
          dims = r.dimensionScores ? (JSON.parse(r.dimensionScores) as Record<string, number>) : {}
        } catch {
          dims = {}
        }
        const row: Record<string, unknown> = {
          key: r.id,
          teamName: r.teamName,
          groupName: r.groupName ?? '',
          total: r.total,
          rankNo: r.rankNo,
        }
        rules.forEach((rule) => {
          row[`dim_${rule.id}`] = dims[String(rule.id)] ?? '-'
        })
        return row
      }),
    [records, rules],
  )

  return (
    <div>
      <Space style={{ marginBottom: 12 }} wrap>
        <Tag color="red">评分规则</Tag>
        {rules.length === 0 && <span style={{ color: 'var(--color-text-secondary)' }}>暂无评分规则</span>}
        {rules.map((r) => (
          <Tag key={r.id}>{r.dimensionName}（满分 {r.fullMarks}）</Tag>
        ))}
      </Space>
      <GlassTable columns={columns} dataSource={rows} pagination={false} rowKey="key" />
      <div style={{ marginTop: 8, fontSize: 12, color: 'var(--color-text-secondary)' }}>
        完整评分面板（录入规则/记录、自动合计、名次排序）在后续任务中实现。
      </div>
    </div>
  )
}

// ==================== 签到 Tab（基础版，Task 15 深化） ====================

function SigninTab({ activityId }: { activityId: number }) {
  const [list, setList] = useState<SigninVO[]>([])
  const [count, setCount] = useState(0)
  const [modalOpen, setModalOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm()

  const fetchData = useCallback(async () => {
    try {
      const [rows, c] = await Promise.all([listSignins(activityId), countSignins(activityId)])
      setList(rows ?? [])
      setCount(c ?? 0)
    } catch {
      /* http 拦截已提示 */
    }
  }, [activityId])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  const openCreate = () => {
    form.resetFields()
    setModalOpen(true)
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      await createSignin({
        activityId,
        name: values.name,
        studentNo: values.studentNo || null,
        className: values.className || null,
        identityType: values.identityType || null,
        signType: 'MANUAL',
        remark: values.remark || null,
      })
      message.success('已签到')
      setModalOpen(false)
      fetchData()
    } catch {
      /* http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteSignin(id)
      message.success('已删除')
      fetchData()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const columns = [
    { title: '姓名', dataIndex: 'name', key: 'name' },
    { title: '学号', dataIndex: 'studentNo', key: 'studentNo', render: (v: string | null) => v || '-' },
    { title: '班级', dataIndex: 'className', key: 'className', render: (v: string | null) => v || '-' },
    { title: '身份', dataIndex: 'identityType', key: 'identityType', render: (v: string | null) => v || '-' },
    { title: '方式', dataIndex: 'signType', key: 'signType', render: (v: string | null) => (v ? SIGN_TYPE_TEXT[v] ?? v : '-') },
    { title: '签到时间', dataIndex: 'signTime', key: 'signTime', render: (v: string | null) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-') },
    {
      title: '操作',
      key: 'action',
      width: 80,
      render: (_: unknown, r: SigninVO) => (
        <Popconfirm title="确认删除该签到记录？" onConfirm={() => handleDelete(r.id)} okText="删除" cancelText="取消">
          <Button type="link" size="small" danger icon={<DeleteOutlined />} />
        </Popconfirm>
      ),
    },
  ]

  return (
    <div>
      <GlassCard style={{ padding: 16, marginBottom: 12 }}>
        <Space wrap>
          <Tag color="red">已签到 {count} 人</Tag>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新增手动签到
          </Button>
        </Space>
      </GlassCard>
      <GlassTable<SigninVO>
        columns={columns}
        dataSource={list.map((x) => ({ ...x, key: x.id }))}
        pagination={false}
        rowKey="id"
      />
      <div style={{ marginTop: 8, fontSize: 12, color: 'var(--color-text-secondary)' }}>
        完整签到面板（扫码、名单导入、筛选统计）在后续任务中实现。
      </div>
      <GlassModal
        title="手动签到"
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setModalOpen(false)}>取消</Button>
            <Button type="primary" loading={saving} onClick={handleSave}>
              确认签到
            </Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item name="name" label="姓名" rules={[{ required: true, message: '请输入姓名' }]}>
            <Input maxLength={50} />
          </Form.Item>
          <Form.Item name="studentNo" label="学号">
            <Input maxLength={20} />
          </Form.Item>
          <Form.Item name="className" label="班级">
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item name="identityType" label="身份">
            <Select
              placeholder="选择身份"
              allowClear
              options={['党建干事', '发展对象', '预备党员', '入党积极分子'].map((v) => ({ value: v, label: v }))}
            />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} maxLength={200} />
          </Form.Item>
        </Form>
      </GlassModal>
    </div>
  )
}

// ==================== 页面 ====================

export default function ActivityDetail() {
  const { id } = useParams()
  const activityId = Number(id)
  const navigate = useNavigate()
  const [detail, setDetail] = useState<ActivityDetail | null>(null)
  const [loading, setLoading] = useState(false)
  const [changing, setChanging] = useState(false)

  const fetchDetail = useCallback(async () => {
    if (!activityId) return
    setLoading(true)
    try {
      setDetail(await getActivityDetail(activityId))
    } catch {
      /* http 拦截已提示 */
    } finally {
      setLoading(false)
    }
  }, [activityId])

  useEffect(() => {
    fetchDetail()
  }, [fetchDetail])

  const handleStatusChange = async (target: string) => {
    setChanging(true)
    try {
      await changeActivityStatus(activityId, target)
      message.success(`已变更为「${STATUS_TEXT[target]}」`)
      fetchDetail()
    } catch {
      /* http 拦截已提示 */
    } finally {
      setChanging(false)
    }
  }

  const activity: (ActivityVO & { targetAudience?: string; description?: string }) | undefined = detail?.activity
  const statusIdx = activity ? STATUS_ORDER.indexOf(activity.status) : -1
  const nextStatus = statusIdx >= 0 && statusIdx < STATUS_ORDER.length - 1 ? STATUS_ORDER[statusIdx + 1] : null
  const prevStatus = statusIdx > 0 ? STATUS_ORDER[statusIdx - 1] : null

  const tabItems = [
    {
      key: 'basic',
      label: '基本信息',
      children: activity ? (
        <GlassCard style={{ padding: 20 }}>
          <Descriptions
            column={{ xs: 1, sm: 2, md: 3 }}
            size="small"
            items={[
              { key: 'type', label: '类型', children: TYPE_MAP[activity.type] ?? activity.type },
              { key: 'status', label: '状态', children: <StatusTag status={activity.status} /> },
              { key: 'theme', label: '主题', children: activity.theme || '-' },
              {
                key: 'time',
                label: '时间',
                children: activity.startDate ? `${activity.startDate} ~ ${activity.endDate ?? ''}` : '-',
              },
              { key: 'location', label: '地点', children: activity.location || '-' },
              { key: 'organizer', label: '组织单位', children: activity.organizer || '-' },
              { key: 'host', label: '主持人', children: activity.host || '-' },
              { key: 'leader', label: '负责人', children: activity.leader || '-' },
              { key: 'target', label: '面向对象', children: activity.targetAudience || '-' },
            ]}
          />
          <div style={{ marginTop: 16 }}>
            <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-red)', marginBottom: 6 }}>活动描述</div>
            <div style={{ whiteSpace: 'pre-wrap', color: 'var(--color-text)', lineHeight: 1.8 }}>
              {activity.description || '暂无描述'}
            </div>
          </div>

          <div style={{ marginTop: 20, paddingTop: 16, borderTop: '1px solid var(--glass-border)' }}>
            <Space wrap>
              <span style={{ color: 'var(--color-text-secondary)', fontSize: 13 }}>状态操作：</span>
              {prevStatus && (
                <Button
                  loading={changing}
                  icon={<ArrowLeftOutlined />}
                  onClick={() => handleStatusChange(prevStatus)}
                >
                  回退到「{STATUS_TEXT[prevStatus]}」
                </Button>
              )}
              {nextStatus && (
                <Button
                  loading={changing}
                  type="primary"
                  icon={<ArrowRightOutlined />}
                  onClick={() => handleStatusChange(nextStatus)}
                >
                  推进到「{STATUS_TEXT[nextStatus]}」
                </Button>
              )}
              {!prevStatus && !nextStatus && <Tag>已归档，无后续状态</Tag>}
            </Space>
          </div>

          <div style={{ marginTop: 16 }}>
            <Space wrap>
              <Button icon={<BarChartOutlined />} onClick={() => navigate(`/activities/${activityId}/gantt`)}>
                查看任务甘特图
              </Button>
              <Tooltip title="文件关联将在档案模块（Task 23）提供">
                <Button icon={<PaperClipOutlined />}>关联文件</Button>
              </Tooltip>
            </Space>
          </div>
        </GlassCard>
      ) : null,
    },
    {
      key: 'plan',
      label: '策划书',
      children: detail ? <PlanTab activityId={activityId} plan={detail.plan} onChanged={fetchDetail} /> : null,
    },
    {
      key: 'agenda',
      label: '议程',
      children: <AgendaTab activityId={activityId} />,
    },
    {
      key: 'seat',
      label: '座位表',
      children: <SeatTab activityId={activityId} />,
    },
    {
      key: 'score',
      label: '评分',
      children: detail ? <ScoreTab rules={detail.score?.rules ?? []} records={detail.score?.records ?? []} /> : null,
    },
    {
      key: 'signin',
      label: '签到',
      children: <SigninTab activityId={activityId} />,
    },
  ]

  return (
    <div>
      <PageHeader
        title={activity ? activity.name : '活动详情'}
        description={
          activity
            ? `${TYPE_MAP[activity.type] ?? activity.type} · ${activity.startDate ?? ''}${activity.endDate ? ` ~ ${activity.endDate}` : ''}`
            : '加载中…'
        }
        extra={
          <Space>
            {activity && <StatusTag status={activity.status} />}
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/activities')}>
              返回列表
            </Button>
          </Space>
        }
      />
      <Spin spinning={loading}>
        <Tabs items={tabItems} />
      </Spin>
    </div>
  )
}
