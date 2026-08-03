import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  AutoComplete,
  Button,
  Descriptions,
  Empty,
  Form,
  Input,
  message,
  Popconfirm,
  Radio,
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
  DownloadOutlined,
  EditOutlined,
  PaperClipOutlined,
  PlusOutlined,
  SendOutlined,
  UploadOutlined,
} from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import PageHeader from '@/components/glass/PageHeader'
import GlassCard from '@/components/glass/GlassCard'
import GlassModal from '@/components/glass/GlassModal'
import GlassTable from '@/components/glass/GlassTable'
import StatusTag from '@/components/glass/StatusTag'
import GanttChart from '@/components/gantt/GanttChart'
import type { GanttTask } from '@/components/gantt/gantt.utils'
import {
  changeActivityStatus,
  getActivityDetail,
  type ActivityAgendaVO,
  type ActivityDetail,
  type ActivityPlanVO,
  type ActivityVO,
  type SeatMapVO,
} from '@/api/activity'
import { ACTIVITY_STATUS_LABEL, ACTIVITY_STATUS_OPTIONS } from '@/api/activityStatus'
import { listMaterials, MATERIAL_BIZ_TYPE_MAP, type MaterialVO } from '@/api/material'
import { downloadFile } from '@/api/file'
import {
  createPlan,
  reviewPlan,
  submitPlan,
  updatePlan,
  type PlanSave,
} from '@/api/plan'
import { createAgenda, deleteAgenda, listAgendas, updateAgenda } from '@/api/agenda'
import { createSeat, deleteSeat, listSeats, updateSeat } from '@/api/seat'
import { useAuthStore } from '@/stores/auth'
import WordEditor from '@/components/word/WordEditor'
import WordPreview from '@/components/word/WordPreview'
import { agendaToDocx, docxToPlan, planToDocx, type PlanFields, type PlanMeta } from '@/components/word/planTemplate'
import SeatMapView from '@/components/seat/SeatMapView'
import SeatExcelEditor from '@/components/seat/SeatExcelEditor'
import ScorePanel from './ScorePanel'
import SigninPanel from './SigninPanel'

// 活动状态机顺序（与 @/api/activityStatus 的 label 对应）
const STATUS_ORDER = ACTIVITY_STATUS_OPTIONS.map((o) => o.value)

const PLAN_STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿',
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
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

// ==================== 座位表图例 ====================

/** 座位类型默认色板：国旗红系 + 灰阶 + 强调色（按列表顺序逐个分配） */
const SEAT_LEGEND_PALETTE = [
  '#DE2910', // 国旗红（领导席/主席台）
  '#C0392B', // 深红
  '#E67E22', // 橙（嘉宾席）
  '#E9C46A', // 金黄（礼仪/引导）
  '#2A9D8F', // 青绿（签到/工作组）
  '#457B9D', // 深蓝（媒体/摄影）
  '#6C757D', // 中灰（观众席）
  '#8C9AAB', // 浅灰（机动座）
]

const SEAT_LEGEND_KEY_PREFIX = 'pams_seat_legend_'

/** 从现有 seatType 列表按色板顺序自动分配默认图例（无本地缓存时） */
function buildDefaultLegend(types: string[]): Record<string, string> {
  const legend: Record<string, string> = {}
  const seen: string[] = []
  for (const t of types) {
    const type = t?.trim()
    if (!type || seen.includes(type)) continue
    seen.push(type)
    legend[type] = SEAT_LEGEND_PALETTE[(seen.length - 1) % SEAT_LEGEND_PALETTE.length]
  }
  return legend
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
  activity,
  plan,
  onChanged,
}: {
  activityId: number
  activity?: ActivityVO & { targetAudience?: string; description?: string }
  plan: { latest: ActivityPlanVO | null; status: string | null } | null
  onChanged: () => void
}) {
  const user = useAuthStore((s) => s.user)
  const isMinisterOrAbove = (user?.roleLevel ?? 0) >= 3
  const latest = plan?.latest ?? null
  const [modalOpen, setModalOpen] = useState(false)
  const [reviewOpen, setReviewOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [importing, setImporting] = useState(false)
  const [mode, setMode] = useState<'preview' | 'edit'>('preview')
  const [planFields, setPlanFields] = useState<PlanFields | null>(null)
  const [formInit, setFormInit] = useState<Record<string, string>>()
  const [form] = Form.useForm()
  const [reviewForm] = Form.useForm()
  const fileInputRef = useRef<HTMLInputElement>(null)

  const toPlanFields = (vo: ActivityPlanVO): PlanFields => ({
    background: vo.background ?? '',
    purpose: vo.purpose ?? '',
    content: vo.content ?? '',
    flow: vo.flow ?? '',
    notice: vo.notice ?? '',
    emergency: vo.emergency ?? '',
    budget: vo.budget ?? '',
  })

  /** 当前可用的 7 字段：编辑态 planFields 优先，否则回退到后端 latest */
  const fields: PlanFields = planFields ?? (latest ? toPlanFields(latest) : { background: '', purpose: '', content: '', flow: '', notice: '', emergency: '', budget: '' })

  const planMeta: PlanMeta = useMemo(
    () => ({
      name: activity?.name,
      theme: activity?.theme,
      orgName: activity?.organizer || '信息工程学院党建办公室',
      time: activity?.startDate ? `${activity.startDate}${activity.endDate ? ` ~ ${activity.endDate}` : ''}` : '',
      location: activity?.location ?? '',
      organizer: activity?.organizer ?? '',
      target: activity?.targetAudience ?? '',
      endDate: activity?.endDate ?? undefined,
    }),
    [activity],
  )

  const openCreate = () => {
    // GlassModal destroyOnHidden 关闭即卸载，回填用 initialValues（挂载时生效）
    setFormInit(undefined)
    setModalOpen(true)
  }

  const openEdit = (p: ActivityPlanVO) => {
    setFormInit({
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

  /** 导出 docx：planToDocx 动态 import docx 库，下载标准策划书 */
  const handleExport = async () => {
    try {
      const blob = await planToDocx(fields, planMeta)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `策划书_${activity?.name ?? '活动'}.docx`
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      URL.revokeObjectURL(url)
      message.success('策划书已导出')
    } catch {
      message.error('导出失败，请稍后重试')
    }
  }

  /** 导入 docx：mammoth 提取字段回填，编辑态直接可见，预览态进入编辑 */
  const handleImportFile = async (file: File) => {
    setImporting(true)
    try {
      const parsed = await docxToPlan(file)
      const next = { ...fields, ...parsed }
      setPlanFields(next)
      setMode('edit')
      message.success(`已导入，填充 ${Object.keys(parsed).length} 个章节字段`)
    } catch {
      message.error('导入失败，请确认文件为 .docx 格式')
    } finally {
      setImporting(false)
    }
  }

  /** 保存：把 planFields 写入后端（updatePlan/createPlan），已审核通过则新建版本，成功后刷新并回到预览 */
  const handleSaveFields = async () => {
    if (!fields) return
    setSaving(true)
    try {
      const payload: PlanSave = {
        activityId,
        background: fields.background?.trim() ? fields.background : null,
        purpose: fields.purpose?.trim() ? fields.purpose : null,
        content: fields.content?.trim() ? fields.content : null,
        flow: fields.flow?.trim() ? fields.flow : null,
        notice: fields.notice?.trim() ? fields.notice : null,
        emergency: fields.emergency?.trim() ? fields.emergency : null,
        budget: fields.budget?.trim() ? fields.budget : null,
      }
      // 已审核通过的策划书后端不允许修改，编辑保存时自动升为新版本
      if (latest && latest.status === 'APPROVED') {
        await createPlan({ ...payload, version: (latest.version ?? 1) + 1 })
        message.success('已基于当前内容创建新版本')
      } else if (latest) {
        await updatePlan(latest.id, payload)
        message.success('策划书已保存')
      } else {
        await createPlan({ ...payload, version: 1 })
        message.success('策划书已创建')
      }
      setPlanFields(null) // 清空编辑缓冲，避免旧内容残留
      setMode('preview')
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
              <Button icon={<EditOutlined />} onClick={() => openEdit(latest)}>
                字段编辑
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

        {/* Word 形态工具栏：预览/编辑切换 + 导入/导出/保存 */}
        <Space style={{ marginBottom: 16 }} wrap>
          <Radio.Group value={mode} onChange={(e) => setMode(e.target.value)}>
            <Radio.Button value="preview">Word 预览</Radio.Button>
            <Radio.Button value="edit">编辑</Radio.Button>
          </Radio.Group>
          {mode === 'edit' ? (
            <>
              <Button loading={importing} icon={<UploadOutlined />} onClick={() => fileInputRef.current?.click()}>
                导入 docx
              </Button>
              <Button icon={<DownloadOutlined />} onClick={handleExport}>
                导出 docx
              </Button>
              <Button type="primary" loading={saving} onClick={handleSaveFields}>
                保存
              </Button>
            </>
          ) : (
            <Button icon={<DownloadOutlined />} onClick={handleExport}>
              导出 docx
            </Button>
          )}
        </Space>

        {/* Word 形态：预览（A4 只读渲染）/ 编辑（contenteditable） */}
        {mode === 'edit' ? (
          <WordEditor value={fields} onChange={setPlanFields} meta={planMeta} />
        ) : (
          <WordPreview plan={fields} meta={planMeta} />
        )}

        {/* 活动流程字段不在 12 章模板中，Word 预览/导出不含流程，引导用字段编辑 */}
        <div style={{ marginTop: 12, fontSize: 12, color: 'var(--color-text-secondary)' }}>
          提示：活动流程字段未在 Word 展示，如需查看/编辑请用「字段编辑」。
        </div>

        {/* 隐藏的文件选择：导入 docx */}
        <input
          ref={fileInputRef}
          type="file"
          accept=".docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
          style={{ display: 'none' }}
          onChange={(e) => {
            const f = e.target.files?.[0]
            if (f) handleImportFile(f)
            e.target.value = ''
          }}
        />
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
        <Form
          form={form}
          layout="vertical"
          preserve={false}
          key={latest ? `plan-edit-${latest.id}` : 'plan-create'}
          initialValues={formInit}
        >
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
  const [formInit, setFormInit] = useState<Record<string, unknown>>()
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
    const maxStep = list.reduce((a, x) => Math.max(a, x.stepNo), 0)
    // GlassModal destroyOnHidden 关闭即卸载，回填用 initialValues（挂载时生效）
    setFormInit({ stepNo: maxStep + 1 })
    setModalOpen(true)
  }

  const openEdit = (a: ActivityAgendaVO) => {
    setEditing(a)
    setFormInit({ stepNo: a.stepNo, title: a.title, remark: a.remark ?? '' })
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

  /** 导出议程表 docx：按 stepNo 排序生成编号列表，动态 import docx 后下载 */
  const handleExport = async () => {
    if (list.length === 0) {
      message.info('暂无议程可导出')
      return
    }
    try {
      const sorted = [...list].sort((a, b) => a.stepNo - b.stepNo)
      const blob = await agendaToDocx(sorted)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = '活动议程表.docx'
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      URL.revokeObjectURL(url)
      message.success('议程表已导出')
    } catch {
      message.error('导出失败，请稍后重试')
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
        <Button icon={<DownloadOutlined />} onClick={handleExport} disabled={list.length === 0}>
          导出议程表
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
        <Form
          form={form}
          layout="vertical"
          preserve={false}
          key={editing ? `agenda-edit-${editing.id}` : 'agenda-create'}
          initialValues={formInit}
        >
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
  const [view, setView] = useState<'matrix' | 'excel'>('matrix')
  // 图例：localStorage pams_seat_legend_{activityId} 缓存优先；无缓存则等 seats 加载后按现有 seatType 自动分配默认色
  const [legend, setLegend] = useState<Record<string, string>>(() => {
    try {
      const cached = localStorage.getItem(`${SEAT_LEGEND_KEY_PREFIX}${activityId}`)
      if (cached) {
        const parsed = JSON.parse(cached)
        if (parsed && typeof parsed === 'object') return parsed as Record<string, string>
      }
    } catch {
      /* 缓存损坏时回退默认 */
    }
    return {}
  })
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<SeatMapVO | null>(null)
  const [formInit, setFormInit] = useState<Record<string, unknown>>()
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

  /** 全部座位（跨 zone 展平），供矩阵视图与 Excel 编辑共用 */
  const allSeats = useMemo<SeatMapVO[]>(() => Object.values(zones).flat(), [zones])

  /** 现有所有 seatType（去重有序） */
  const seatTypes = useMemo(() => {
    const seen: string[] = []
    for (const s of allSeats) {
      const t = s.seatType?.trim()
      if (t && !seen.includes(t)) seen.push(t)
    }
    return seen
  }, [allSeats])

  /** 无本地缓存时，seats 加载后按现有 seatType 自动分配默认色 */
  useEffect(() => {
    setLegend((prev) => {
      if (Object.keys(prev).length > 0) return prev // 已有图例（缓存或用户配置）不动
      const next = buildDefaultLegend(seatTypes)
      return Object.keys(next).length ? next : prev
    })
  }, [seatTypes])

  const handleLegendChange = (next: Record<string, string>) => {
    setLegend(next)
    try {
      localStorage.setItem(`${SEAT_LEGEND_KEY_PREFIX}${activityId}`, JSON.stringify(next))
    } catch {
      /* localStorage 不可用（隐私模式等）忽略 */
    }
  }

  /** Excel 编辑回传：按 zone 重新分组刷新 zones（矩阵视图实时反映编辑） */
  const handleExcelChange = (next: SeatMapVO[]) => {
    const grouped: Record<string, SeatMapVO[]> = {}
    for (const s of next) {
      const zone = s.zone?.trim() || '未分区'
      ;(grouped[zone] ??= []).push(s)
    }
    setZones(grouped)
  }

  const zoneEntries = Object.entries(zones)
  const zoneOptions = zoneEntries.map(([z]) => ({ value: z, label: z }))

  const openCreate = () => {
    setEditing(null)
    // GlassModal destroyOnHidden 关闭即卸载，回填用 initialValues（挂载时生效）
    setFormInit(zoneEntries.length ? { zone: zoneEntries[0][0] } : {})
    setModalOpen(true)
  }

  const openEdit = (s: SeatMapVO) => {
    setEditing(s)
    setFormInit({
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
      <Space style={{ marginBottom: 12 }} wrap>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新增座位
        </Button>
        <Radio.Group value={view} onChange={(e) => setView(e.target.value)}>
          <Radio.Button value="matrix">图表视图</Radio.Button>
          <Radio.Button value="excel">Excel 编辑</Radio.Button>
        </Radio.Group>
      </Space>

      {view === 'matrix' ? (
        <SeatMapView seats={allSeats} legend={legend} onSelect={openEdit} />
      ) : (
        <SeatExcelEditor seats={allSeats} legend={legend} onChangeLegend={handleLegendChange} onChangeSeats={handleExcelChange} />
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
        <Form
          form={form}
          layout="vertical"
          preserve={false}
          key={editing ? `seat-edit-${editing.id}` : 'seat-create'}
          initialValues={formInit}
        >
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

// ==================== 页面 ====================

export default function ActivityDetail() {
  const { id } = useParams()
  const activityId = Number(id)
  const navigate = useNavigate()
  const [detail, setDetail] = useState<ActivityDetail | null>(null)
  const [loading, setLoading] = useState(false)
  const [changing, setChanging] = useState(false)
  const [materials, setMaterials] = useState<MaterialVO[]>([])

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

  const fetchMaterials = useCallback(async () => {
    try {
      const page = await listMaterials({ activityId, page: 1, size: 50 })
      setMaterials(page.records)
    } catch {
      /* http 拦截已提示 */
    }
  }, [activityId])
  useEffect(() => {
    fetchMaterials()
  }, [fetchMaterials])

  /** 甘特图只读预览：detail.tasks 为后端 Task 实体（deptId 无 deptName），映射到组件所需形状 */
  const ganttPreviewTasks = useMemo<GanttTask[]>(() => {
    return (detail?.tasks ?? [])
      .filter((t): t is Record<string, unknown> => !!t && typeof t === 'object')
      .map((t) => ({
        id: Number(t.id),
        name: String(t.name ?? ''),
        startDate: String(t.startDate ?? ''),
        endDate: String(t.endDate ?? ''),
        dependsOn: t.dependsOn != null ? Number(t.dependsOn) : null,
        deptName: t.deptName != null ? String(t.deptName) : undefined,
        isMilestone: t.isMilestone === 1,
        progress: t.progress != null ? Number(t.progress) : 0,
        assignee: t.assignee != null ? String(t.assignee) : undefined,
      }))
  }, [detail?.tasks])

  const handleStatusChange = async (target: string) => {
    setChanging(true)
    try {
      await changeActivityStatus(activityId, target)
      message.success(`已变更为「${ACTIVITY_STATUS_LABEL[target]}」`)
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
                  回退到「{ACTIVITY_STATUS_LABEL[prevStatus]}」
                </Button>
              )}
              {nextStatus && (
                <Button
                  loading={changing}
                  type="primary"
                  icon={<ArrowRightOutlined />}
                  onClick={() => handleStatusChange(nextStatus)}
                >
                  推进到「{ACTIVITY_STATUS_LABEL[nextStatus]}」
                </Button>
              )}
              {!prevStatus && !nextStatus && <Tag>已归档，无后续状态</Tag>}
            </Space>
          </div>

          <div style={{ marginTop: 20, paddingTop: 16, borderTop: '1px solid var(--glass-border)' }}>
            <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-red)', marginBottom: 6 }}>
              任务甘特图预览
              <Button type="link" size="small" onClick={() => navigate(`/activities/${activityId}/gantt`)}>
                查看完整甘特图 →
              </Button>
            </div>
            {/* 内嵌只读甘特图：tasks 来自 detail.tasks（聚合接口已返回），onEdit 空操作保持只读 */}
            <GanttChart tasks={ganttPreviewTasks} onUpdate={() => {}} onEdit={() => {}} />
          </div>

          <div style={{ marginTop: 20 }}>
            <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-red)', marginBottom: 6 }}>
              关联文件（{materials.length}）
            </div>
            {materials.length === 0 ? (
              <div style={{ color: 'var(--color-text-secondary)', fontSize: 13 }}>暂无关联文件，可在材料库上传</div>
            ) : (
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                {materials.map((m) => (
                  <Tag key={m.id} color="red" style={{ cursor: 'pointer' }} onClick={() => m.fileId && downloadFile(m.fileId)}>
                    {MATERIAL_BIZ_TYPE_MAP[m.bizType] ?? m.bizType} · {m.name}
                  </Tag>
                ))}
              </div>
            )}
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
      children: detail ? <PlanTab activityId={activityId} activity={detail.activity} plan={detail.plan} onChanged={fetchDetail} /> : null,
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
      children: <ScorePanel activityId={activityId} />,
    },
    {
      key: 'signin',
      label: '签到',
      children: <SigninPanel activityId={activityId} />,
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
            {activity && (
              <Button type="primary" icon={<EditOutlined />} onClick={() => navigate(`/activities/${activityId}/edit`)}>
                编辑
              </Button>
            )}
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
