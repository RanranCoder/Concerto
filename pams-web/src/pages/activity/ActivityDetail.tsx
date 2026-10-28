import FormSurface from '@/components/form/FormSurface'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  App,
  AutoComplete,
  Button,
  Empty,
  Form,
  Input,
  InputNumber,
  Popconfirm,
  Radio,
  Space,
  Spin,
  Tabs,
} from 'antd'
import {
  ArrowLeftOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  PlusOutlined,
} from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import { SEAT_ZONE_COLORS } from '@/styles/categoryColors'
import PageHeader from '@/components/glass/PageHeader'
import GlassCard from '@/components/glass/GlassCard'
import StatusTag from '@/components/glass/StatusTag'
import { useHasPerm } from '@/components/glass/RequireRole'
import type { GanttTask } from '@/components/gantt/gantt.utils'
import {
  changeActivityStatus,
  getActivityDetail,
  type ActivityAgendaVO,
  type ActivityDetail,
  type ActivityVO,
  type SeatMapVO,
} from '@/api/activity'
import {
  ACTIVITY_STATUS_LABEL,
  ACTIVITY_STATUS_OPTIONS,
} from '@/api/activityStatus'
import { listMaterials, type MaterialVO } from '@/api/material'
import { listDepts } from '@/api/dept'
import {
  createAgenda,
  deleteAgenda,
  listAgendas,
  updateAgenda,
} from '@/api/agenda'
import { createSeat, deleteSeat, listSeats, updateSeat } from '@/api/seat'
import { agendaToDocx } from '@/components/word/agendaExport'
import SeatMapView from '@/components/seat/SeatMapView'
import SeatExcelEditor from '@/components/seat/SeatExcelEditor'
import SeatLayoutEditor from '@/components/seat/SeatLayoutEditor'
import { getActivitySeatLayout, type SeatLayoutVO } from '@/api/seatLayout'
import ScorePanel from './ScorePanel'
import SigninPanel from './SigninPanel'
import ActivityArticlesTab from './ActivityArticlesTab'
import PlanTab from './ActivityPlanTab'
import TaskPlanTab from './TaskPlanTab'
import type { TaskVO } from '@/api/task'
import ActivityOverview from './ActivityOverview'
import ActivityMaterialsTab from './ActivityMaterialsTab'
import ActivityEditorDrawer from './ActivityEditorDrawer'
import '@/styles/activity-v6.css'

// 活动状态机顺序（与 @/api/activityStatus 的 label 对应）
const STATUS_ORDER = ACTIVITY_STATUS_OPTIONS.map((o) => o.value)

// ==================== 座位表图例 ====================

/**
 * 座位类型默认色板：与座位表编辑器（SeatLayoutEditor 的 PRESET_COLORS）共用
 * styles/categoryColors.ts 的 SEAT_ZONE_COLORS，避免同一系统出现两套座位配色
 * （原实现这里是一套「国旗红系」8 色、编辑器是另一套 10 色，同一活动两处颜色对不上）。
 */
const SEAT_LEGEND_PALETTE = Object.values(SEAT_ZONE_COLORS)

const SEAT_LEGEND_KEY_PREFIX = 'pams_seat_legend_'

/** 从现有 seatType 列表按色板顺序自动分配默认图例（无本地缓存时） */
function buildDefaultLegend(types: string[]): Record<string, string> {
  const legend: Record<string, string> = {}
  const seen: string[] = []
  for (const t of types) {
    const type = t?.trim()
    if (!type || seen.includes(type)) continue
    seen.push(type)
    legend[type] =
      SEAT_LEGEND_PALETTE[(seen.length - 1) % SEAT_LEGEND_PALETTE.length]
  }
  return legend
}

// ==================== 议程 Tab ====================

function AgendaTab({ activityId }: { activityId: number }) {
  const hasPerm = useHasPerm()
  const { message } = App.useApp()
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
    let values
    try {
      values = await form.validateFields()
    } catch {
      return
    }
    setSaving(true)
    try {
      const payload = {
        activityId,
        stepNo: values.stepNo,
        title: values.title,
        remark: values.remark || null,
      }
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

  return (
    <div>
      <Space style={{ marginBottom: 12 }}>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={openCreate}
          disabled={!hasPerm('agenda:manage')}
        >
          新增议程
        </Button>
        <Button
          icon={<DownloadOutlined />}
          onClick={handleExport}
          disabled={list.length === 0}
        >
          导出议程表
        </Button>
      </Space>
      <GlassCard className="activity-agenda-panel">
        <header className="activity-panel-head">
          <div>
            <h2>活动议程</h2>
            <p>{list.length} 个环节 / 按步骤顺序执行</p>
          </div>
        </header>
        {list.length ? (
          <ol className="activity-agenda-list">
            {list.map((item) => (
              <li key={item.id}>
                <span>{item.stepNo}</span>
                <div>
                  <h3>{item.title}</h3>
                  <p>{item.remark || '暂无补充说明'}</p>
                </div>
                <Space>
                  <Button
                    type="link"
                    disabled={!hasPerm('agenda:manage')}
                    onClick={() => openEdit(item)}
                  >
                    编辑
                  </Button>
                  <Popconfirm
                    disabled={!hasPerm('agenda:manage')}
                    title="删除该议程环节？"
                    onConfirm={() => handleDelete(item.id)}
                  >
                    <Button
                      type="link"
                      danger
                      disabled={!hasPerm('agenda:manage')}
                    >
                      删除
                    </Button>
                  </Popconfirm>
                </Space>
              </li>
            ))}
          </ol>
        ) : (
          <Empty description="尚未安排议程，添加环节后明确执行顺序。" />
        )}
      </GlassCard>
      <FormSurface
        mode="drawer"
        description="填写步骤顺序、标题与补充说明。"
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
          <Form.Item
            name="stepNo"
            label="步骤序号"
            rules={[{ required: true, message: '请输入步骤序号' }]}
          >
            <InputNumber min={1} precision={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            name="title"
            label="标题"
            rules={[{ required: true, message: '请输入标题' }]}
          >
            <Input maxLength={200} />
          </Form.Item>
          <Form.Item name="remark" label="说明">
            <Input.TextArea rows={3} maxLength={500} />
          </Form.Item>
        </Form>
      </FormSurface>
    </div>
  )
}

// ==================== 座位表 Tab ====================

function SeatTab({ activityId }: { activityId: number }) {
  const { message } = App.useApp()
  const hasPerm = useHasPerm()
  const [zones, setZones] = useState<Record<string, SeatMapVO[]>>({})
  const [view, setView] = useState<'visual' | 'matrix' | 'excel'>('matrix')
  const [layout, setLayout] = useState<SeatLayoutVO | null>(null)
  const [layoutLoaded, setLayoutLoaded] = useState(false)
  // 图例：localStorage pams_seat_legend_{activityId} 缓存优先；无缓存则等 seats 加载后按现有 seatType 自动分配默认色
  const [legend, setLegend] = useState<Record<string, string>>(() => {
    try {
      const cached = localStorage.getItem(
        `${SEAT_LEGEND_KEY_PREFIX}${activityId}`,
      )
      if (cached) {
        const parsed = JSON.parse(cached)
        if (parsed && typeof parsed === 'object') {
          const clean: Record<string, string> = {}
          for (const [k, v] of Object.entries(
            parsed as Record<string, unknown>,
          )) {
            if (typeof v === 'string') clean[k] = v // 过滤非字符串值，防 ColorPicker value={123}
          }
          return clean
        }
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

  const fetchLayout = useCallback(async () => {
    try {
      setLayout(await getActivitySeatLayout(activityId))
    } catch {
      /* http 拦截已提示 */
    } finally {
      setLayoutLoaded(true)
    }
  }, [activityId])

  useEffect(() => {
    fetchZones()
    fetchLayout()
  }, [fetchZones, fetchLayout])

  /** 全部座位（跨 zone 展平），供矩阵视图与 Excel 编辑共用 */
  const allSeats = useMemo<SeatMapVO[]>(
    () => Object.values(zones).flat(),
    [zones],
  )

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
      localStorage.setItem(
        `${SEAT_LEGEND_KEY_PREFIX}${activityId}`,
        JSON.stringify(next),
      )
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
    let values
    try {
      values = await form.validateFields()
    } catch {
      return
    }
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
      {/* F6 座位表双轨收敛：已存在可视化布局（seat_layout）时旧版座位表（seat_map）转只读归档；
          仅有旧数据时提示迁移，写入口统一收敛到可视化编辑 */}
      {layout && allSeats.length > 0 && (
        <div
          style={{
            marginBottom: 12,
            padding: '4px 12px',
            borderLeft: '3px solid var(--brand)',
            background: 'var(--soft)',
            fontSize: 13,
            color: 'var(--text-secondary)',
          }}
        >
          该活动已启用可视化座位表（新版），下方旧版座位表转为只读归档；编辑请在「可视化编辑」中进行。
        </div>
      )}
      {!layout && allSeats.length > 0 && (
        <div
          style={{
            marginBottom: 12,
            padding: '4px 12px',
            borderLeft: '3px solid var(--warn)',
            background: 'var(--soft)',
            fontSize: 13,
            color: 'var(--text-secondary)',
          }}
        >
          检测到旧版座位表数据（{allSeats.length}{' '}
          个座位）。建议切换到「可视化编辑」重建并保存新布局，旧版编辑入口将在新布局创建后转为只读。
        </div>
      )}
      <Space style={{ marginBottom: 12 }} wrap>
        {!layout && hasPerm('seat:edit') && (
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新增座位
          </Button>
        )}
        <Radio.Group value={view} onChange={(e) => setView(e.target.value)}>
          {hasPerm('seat:edit') && (
            <Radio.Button value="visual">可视化编辑</Radio.Button>
          )}
          <Radio.Button value="matrix">图表视图</Radio.Button>
          <Radio.Button
            value="excel"
            disabled={!!layout || !hasPerm('seat:edit')}
          >
            Excel 编辑{layout ? '（已只读）' : ''}
          </Radio.Button>
        </Radio.Group>
      </Space>

      {view === 'visual' ? (
        layoutLoaded ? (
          <SeatLayoutEditor
            value={layout}
            activityId={activityId}
            onSaved={fetchLayout}
            readOnly={!hasPerm('seat:edit')}
          />
        ) : (
          <div style={{ textAlign: 'center', padding: 40 }}>
            <Spin />
          </div>
        )
      ) : view === 'matrix' && layout ? (
        <div className="activity-seat-pane">
          <SeatLayoutEditor
            value={layout}
            activityId={activityId}
            readOnly
            onSaved={fetchLayout}
          />
        </div>
      ) : view === 'matrix' ? (
        <SeatMapView
          seats={allSeats}
          legend={legend}
          onSelect={layout || !hasPerm('seat:edit') ? undefined : openEdit}
        />
      ) : layout ? (
        <div
          style={{
            textAlign: 'center',
            padding: 40,
            color: 'var(--text-secondary)',
          }}
        >
          旧版 Excel
          编辑已锁定：该活动已启用可视化座位表，请使用「可视化编辑」。
        </div>
      ) : (
        <SeatExcelEditor
          seats={allSeats}
          legend={legend}
          onChangeLegend={handleLegendChange}
          onChangeSeats={handleExcelChange}
        />
      )}

      <FormSurface
        mode="drawer"
        description="指定座位位置、就座人和座位类型。"
        title={editing ? '编辑座位' : '新增座位'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        footer={
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
            }}
          >
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
          <Form.Item
            name="zone"
            label="区域"
            rules={[{ required: true, message: '请输入区域' }]}
          >
            <AutoComplete
              options={zoneOptions}
              placeholder="选择或输入区域"
              filterOption={(input, option) =>
                ((option?.value as string) ?? '').includes(input)
              }
            />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="rowNo" label="排" style={{ flex: 1 }}>
              <InputNumber min={1} precision={0} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="colNo" label="列" style={{ flex: 1 }}>
              <InputNumber min={1} precision={0} style={{ width: '100%' }} />
            </Form.Item>
          </Space>
          <Form.Item name="personName" label="就座人">
            <Input maxLength={50} />
          </Form.Item>
          <Form.Item name="seatType" label="座位类型">
            <Input maxLength={50} placeholder="如 领导席 / 观众席 / 礼仪" />
          </Form.Item>
        </Form>
      </FormSurface>
    </div>
  )
}

// ==================== 页面 ====================

export default function ActivityDetail({
  editOnOpen = false,
}: { editOnOpen?: boolean } = {}) {
  const { message } = App.useApp()
  const hasPerm = useHasPerm()
  const { id } = useParams()
  const activityId = Number(id)
  const navigate = useNavigate()
  const [detail, setDetail] = useState<ActivityDetail | null>(null)
  const [loading, setLoading] = useState(false)
  const [changing, setChanging] = useState(false)
  const [materials, setMaterials] = useState<MaterialVO[]>([])
  const [materialsTotal, setMaterialsTotal] = useState(0)
  const [activeTab, setActiveTab] = useState(() => {
    const key = new URLSearchParams(location.search).get('tab') || (hasPerm('task:view') ? 'gantt' : 'basic')
    return [
      'gantt',
      'basic',
      'materials',
      'plan',
      'agenda',
      'seat',
      'score',
      'signin',
      'articles',
    ].includes(key)
      ? key
      : 'basic'
  })
  const [editorOpen, setEditorOpen] = useState(editOnOpen)
  const [focusTaskId, setFocusTaskId] = useState<number>()
  const [failed, setFailed] = useState(false)
  const detailRequest = useRef(0),
    materialRequest = useRef(0)
  // 部门列表：后端 Task 只返回 deptId，需映射 deptName 供甘特图预览条着色（与甘特页同款处理）
  const [depts, setDepts] = useState<Array<{ id: number; name: string }>>([])

  const deptNameById = useMemo(() => {
    const m = new Map<number, string>()
    depts.forEach((d) => m.set(d.id, d.name))
    return m
  }, [depts])

  useEffect(() => {
    listDepts()
      .then((rows) => setDepts(rows ?? []))
      .catch(() => {})
  }, [])

  const fetchDetail = useCallback(async () => {
    if (!activityId) return
    const current = ++detailRequest.current
    setLoading(true)
    try {
      const result = await getActivityDetail(activityId)
      if (current !== detailRequest.current) return
      setDetail(result)
      setFailed(false)
    } catch {
      if (current === detailRequest.current) setFailed(true)
    } finally {
      if (current === detailRequest.current) setLoading(false)
    }
  }, [activityId])

  useEffect(() => {
    setDetail(null)
    void fetchDetail()
    return () => {
      detailRequest.current++
    }
  }, [fetchDetail])

  const fetchMaterials = useCallback(async () => {
    const current = ++materialRequest.current
    try {
      const page = await listMaterials({ activityId, page: 1, size: 50 })
      if (current !== materialRequest.current) return
      setMaterials(page.records)
      setMaterialsTotal(page.total)
    } catch {
      /* http 拦截已提示 */
    }
  }, [activityId])
  useEffect(() => {
    setMaterials([])
    setMaterialsTotal(0)
    void fetchMaterials()
    return () => {
      materialRequest.current++
    }
  }, [fetchMaterials])

  /** 甘特图只读预览：detail.tasks 为后端 Task 实体（deptId 无 deptName），映射到组件所需形状并用部门列表补 deptName 着色 */
  const ganttPreviewTasks = useMemo<GanttTask[]>(() => {
    return (detail?.tasks ?? [])
      .filter((t): t is Record<string, unknown> => !!t && typeof t === 'object')
      .map((t) => {
        const deptId = t.deptId != null ? Number(t.deptId) : null
        return {
          id: Number(t.id),
          name: String(t.name ?? ''),
          startDate: String(t.startDate ?? ''),
          endDate: String(t.endDate ?? ''),
          dependsOn: t.dependsOn != null ? Number(t.dependsOn) : null,
          deptId,
          deptName:
            deptId != null
              ? (deptNameById.get(deptId) ?? undefined)
              : undefined,
          isMilestone: t.isMilestone === 1,
          progress: t.progress != null ? Number(t.progress) : 0,
          assignee: t.assignee != null ? String(t.assignee) : undefined,
        }
      })
  }, [detail?.tasks, deptNameById])

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

  const activity:
    | (ActivityVO & { targetAudience?: string; description?: string })
    | undefined = detail?.activity
  const statusIdx = activity ? STATUS_ORDER.indexOf(activity.status) : -1
  const canEditActivity =
    hasPerm('activity:edit') &&
    !!activity &&
    !['FINISHED', 'ARCHIVED'].includes(activity.status)
  const nextStatus =
    statusIdx >= 0 && statusIdx < STATUS_ORDER.length - 1
      ? STATUS_ORDER[statusIdx + 1]
      : null
  const previousStatus =
    statusIdx > 0 && !['FINISHED', 'ARCHIVED'].includes(activity?.status ?? '')
      ? STATUS_ORDER[statusIdx - 1]
      : null
  const changeTab = (key: string) => {
    setActiveTab(key)
    const url = new URL(location.href)
    url.searchParams.set('tab', key)
    history.replaceState(null, '', url)
  }

  const tabItems = [
    {
      key: 'gantt',
      label: <span className="activity-task-plan-tab">任务计划</span>,
      children: detail ? <TaskPlanTab key={activityId} activity={detail.activity} active={activeTab === 'gantt'} onTab={changeTab} focusTaskId={focusTaskId} onTaskOpened={() => setFocusTaskId(undefined)} onChanged={(rows: TaskVO[]) => setDetail(current => current?.activity.id === activityId ? { ...current, tasks: rows } : current)} /> : null,
    },
    {
      key: 'basic',
      label: '基本信息',
      children: detail ? (
        <ActivityOverview
          detail={detail}
          materials={materials}
          tasks={ganttPreviewTasks}
          onTab={changeTab}
          onEdit={() => setEditorOpen(true)}
          canEdit={canEditActivity}
          canViewTasks={hasPerm('task:view')}
          onTaskPlan={taskId => { setFocusTaskId(taskId); changeTab('gantt') }}
        />
      ) : null,
    },
    {
      key: 'materials',
      label: (
        <>
          材料 <span className="activity-tab-count">{materialsTotal}</span>
        </>
      ),
      children: (
        <ActivityMaterialsTab
          activityId={activityId}
          onChanged={fetchMaterials}
        />
      ),
    },
    {
      key: 'plan',
      label: '策划书',
      children: detail ? (
        <PlanTab
          key={activityId}
          activityId={activityId}
          activity={detail.activity}
          plan={detail.plan}
          onChanged={fetchDetail}
        />
      ) : null,
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
      children: (
        <SigninPanel activityId={activityId} active={activeTab === 'signin'} />
      ),
    },
    {
      key: 'articles',
      label: '推文',
      children: (
        <ActivityArticlesTab activityId={activityId} activity={activity} />
      ),
    },
  ].filter(tab => {
    const permissions: Record<string,string> = { gantt:'task:view',materials:'material:view',plan:'plan:view',agenda:'agenda:view',seats:'seat:view',score:'score:view',signin:'checkin:view',articles:'content:view' }
    return !permissions[tab.key] || hasPerm(permissions[tab.key])
  })

  return (
    <div className="activity-detail-v6">
      <PageHeader
        title={activity ? activity.name : '活动详情'}
        description={
          activity ? (
            <span className="activity-detail-metadata">
              <span>
                {activity.startDate || '日期待定'}
                {activity.endDate && activity.endDate !== activity.startDate
                  ? ' ~ ' + activity.endDate
                  : ''}
              </span>
              <span>{activity.location || '地点待定'}</span>
              <span>
                {activity.organizer || '组织单位待定'} /{' '}
                {activity.leader || '负责人待定'}
              </span>
            </span>
          ) : (
            '加载中…'
          )
        }
        extra={
          <Space wrap>
            {activity && <StatusTag status={activity.status} />}
            {activity && hasPerm('schedule:view') && (
              <>
                <Button
                  onClick={() =>
                    navigate(
                      `/routine/activity-schedules?activityId=${activityId}`,
                    )
                  }
                >
                  岗位排班
                </Button>
                <Button
                  onClick={() =>
                    navigate(`/routine/attendance?activityId=${activityId}`)
                  }
                >
                  活动考勤
                </Button>
              </>
            )}
            {canEditActivity && (
              <Button
                aria-label="编辑活动"
                icon={<EditOutlined />}
                onClick={() => setEditorOpen(true)}
              >
                编辑
              </Button>
            )}
            {previousStatus && hasPerm('activity:transition') && (
              <Popconfirm
                title={`回退到「${ACTIVITY_STATUS_LABEL[previousStatus]}」？`}
                description="请确认当前活动的实际进度。"
                onConfirm={() => handleStatusChange(previousStatus)}
              >
                <Button loading={changing}>回退阶段</Button>
              </Popconfirm>
            )}
            {nextStatus && hasPerm('activity:transition') && (
              <Popconfirm
                title={`推进到「${ACTIVITY_STATUS_LABEL[nextStatus]}」？`}
                onConfirm={() => handleStatusChange(nextStatus)}
              >
                <Button type="primary" loading={changing}>
                  推进至{ACTIVITY_STATUS_LABEL[nextStatus]}
                </Button>
              </Popconfirm>
            )}
            <Button
              icon={<ArrowLeftOutlined />}
              onClick={() => navigate('/activities')}
            >
              返回列表
            </Button>
          </Space>
        }
      />
      {activity && (
        <ol className="activity-stage-strip" aria-label="活动推进阶段">
          {ACTIVITY_STATUS_OPTIONS.map((option, index) => (
            <li
              key={option.value}
              className={
                index < statusIdx
                  ? 'complete'
                  : index === statusIdx
                    ? 'current'
                    : ''
              }
              aria-current={index === statusIdx ? 'step' : undefined}
            >
              <span>{index < statusIdx ? '✓' : index + 1}</span>
              {option.label}
            </li>
          ))}
        </ol>
      )}
      {failed && (
        <p role="alert" className="activity-inline-note error">
          活动加载失败。
          <Button type="link" onClick={fetchDetail}>
            重新加载
          </Button>
        </p>
      )}
      <Spin spinning={loading}>
        <Tabs className="activity-detail-tabs" items={tabItems} activeKey={tabItems.some(tab => tab.key === activeTab) ? activeTab : 'basic'} onChange={changeTab} />
      </Spin>
      <ActivityEditorDrawer
        open={editorOpen}
        activityId={activityId}
        onClose={() => {
          setEditorOpen(false)
          if (editOnOpen)
            navigate(`/activities/${activityId}`, { replace: true })
        }}
        onSaved={fetchDetail}
      />
    </div>
  )
}
