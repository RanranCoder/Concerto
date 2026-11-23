import FormSurface, { FormSection } from '@/components/form/FormSurface'
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  App,
  Button,
  DatePicker,
  Empty,
  Alert,
  Form,
  Grid,
  Input,
  InputNumber,
  Select,
  Space,
  Table,
  Tabs,
  TimePicker,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import {
  PlusOutlined,
  QrcodeOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { QRCodeSVG } from 'qrcode.react'
import dayjs, { type Dayjs } from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import PageHeader from '@/components/glass/PageHeader'
import RoutineLayout, {
  RoutineNote,
  RoutineStatus,
  RoutineSummary,
} from '@/components/routine/RoutineLayout'
import PeopleFields from '@/components/routine/PeopleFields'
import { ScheduleDetail, ShiftPeople } from '@/components/routine/ScheduleViews'
import SigninFieldsConfig from '@/components/signin/SigninFieldsConfig'
import { listActivities, type ActivityVO } from '@/api/activity'
import { listDepts, type DeptVO } from '@/api/dept'
import { listUserOptions, type UserOptionVO } from '@/api/user'
import {
  listActivitySchedules,
  listSemesters,
  previewActivitySchedules,
  confirmActivitySchedules,
  createActivitySchedule,
  updateActivitySchedule,
  publishActivitySchedule,
  publishAllActivitySchedules,
  cancelActivitySchedule,
  regenerateActivitySchedule,
  deleteActivitySchedule,
  type GenerateScheduleRequest,
  type GenerationReview,
  type ActivityScheduleSave,
} from '@/api/activitySchedule'
import {
  closeActivitySignin,
  generateActivitySigninToken,
  getActivitySigninToken,
  signinTokenExpiresAt,
  getSigninFields,
  listSignins,
  type ActivitySigninToken,
  type SigninFieldConfigVO,
  type SigninVO,
} from '@/api/signin'
import type { ScheduleVO, SchedulePersonItem } from '@/api/schedule'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/api/http'
import { timeRange } from '@/utils/routine'

interface GenerateValues {
  activityMode: 'existing' | 'custom'
  activityId?: number
  customActivityName?: string
  dateRange: [Dayjs, Dayjs]
  timeRange: [Dayjs, Dayjs]
  deptIds: number[]
  peoplePerSlot?: number
  semester?: string
  semesterStartDate?: Dayjs
}
interface ManualValues {
  activityId: number
  scheduleDate: Dayjs
  timeRange: [Dayjs, Dayjs]
  location?: string
  note?: string
  persons: SchedulePersonItem[]
}
interface RegenerateValues {
  deptId?: number
  semester?: string
  semesterStartDate?: Dayjs
}
export default function ActivityScheduleList() {
  const { message } = App.useApp(),
    navigate = useNavigate(),
    [params, setParams] = useSearchParams()
  const permissions = useAuthStore((s) => s.user?.permissions) ?? [],
    canManage = permissions.includes('schedule:manage'),
    canGenerate = permissions.includes('schedule:generate'),
    canPublish = permissions.includes('schedule:publish'),
    canReadSignin = permissions.includes('checkin:view'),
    canViewAttendance = permissions.includes('attendance:view') || permissions.includes('attendance:self:view'),
    canManageSignin = permissions.includes('checkin:manage')
  const compact = !Grid.useBreakpoint().md
  const qrVersion = useRef(0)
  const [activityId, setActivityId] = useState<number | undefined>(
    Number(params.get('activityId')) || undefined,
  )
  const currentId = useRef(activityId)
  currentId.current = activityId
  const [activities, setActivities] = useState<ActivityVO[]>([]),
    [depts, setDepts] = useState<DeptVO[]>([]),
    [users, setUsers] = useState<UserOptionVO[]>([]),
    [semesters, setSemesters] = useState<string[]>([])
  const [rows, setRows] = useState<ScheduleVO[]>([]),
    [loading, setLoading] = useState(false),
    [revision, setRevision] = useState(0),
    [status, setStatus] = useState<string>(),
    [tab, setTab] = useState('shifts'),
    [busy, setBusy] = useState(false)
  const [detail, setDetail] = useState<ScheduleVO | null>(null),
    [manualEditing, setManualEditing] = useState<ScheduleVO | null>(null),
    [manualOpen, setManualOpen] = useState(false)
  const [genOpen, setGenOpen] = useState(false),
    [preview, setPreview] = useState<{ request: GenerateScheduleRequest; review: GenerationReview } | null>(null),
    [previewStale, setPreviewStale] = useState(false),
    [publishTarget, setPublishTarget] = useState<{
      activityId: number
      rows: ScheduleVO[]
      all: boolean
    } | null>(null),
    [cancelTarget, setCancelTarget] = useState<ScheduleVO | null>(null),
    [deleteTarget, setDeleteTarget] = useState<ScheduleVO | null>(null),
    [regenTarget, setRegenTarget] = useState<ScheduleVO | null>(null)
  const [genForm] = Form.useForm<GenerateValues>(),
    [manualForm] = Form.useForm<ManualValues>(),
    [regenForm] = Form.useForm<RegenerateValues>()
  const genMode = Form.useWatch('activityMode', genForm)
  const [qr, setQr] = useState<ActivitySigninToken | null>(null),
    [signRows, setSignRows] = useState<SigninVO[]>([]),
    [fields, setFields] = useState<SigninFieldConfigVO[]>([]),
    [signLoading, setSignLoading] = useState(false),
    [qrBusy, setQrBusy] = useState(false),
    [qrFailed, setQrFailed] = useState(false)
  const selected = activities.find((a) => a.id === activityId),
    drafts = rows.filter((s) => s.status === 'DRAFT'),
    shown = rows.filter((s) => !status || s.status === status)
  useEffect(() => {
    listActivities({ page: 1, size: 200 })
      .then((r) => {
        setActivities(r.records ?? [])
        if (!currentId.current && r.records.length)
          setActivityId(Math.max(...r.records.map((a) => a.id)))
      })
      .catch(() => {})
    listDepts()
      .then(setDepts)
      .catch(() => {})
    listUserOptions()
      .then(setUsers)
      .catch(() => {})
    listSemesters()
      .then(setSemesters)
      .catch(() => {})
  }, [])
  useEffect(() => {
    const id = Number(params.get('activityId'))
    if (id) setActivityId(id)
  }, [params])
  useEffect(() => {
    let current = true
    setRows([])
    if (!activityId) return
    setLoading(true)
    listActivitySchedules({ activityId })
      .then((r) => {
        if (current) setRows(r ?? [])
      })
      .catch(() => {})
      .finally(() => {
        if (current) setLoading(false)
      })
    return () => {
      current = false
    }
  }, [activityId, revision])
  useEffect(() => {
    ++qrVersion.current
    setQr(null)
    setQrFailed(false)
    setSignRows([])
    setFields([])
    setStatus(undefined)
    setDetail(null)
  }, [activityId])
  const refreshSigns = useCallback(async (id: number) => {
    setSignLoading(true)
    try {
      const [records, configs] = await Promise.all([
        listSignins(id),
        getSigninFields(id),
      ])
      if (currentId.current === id) {
        setSignRows(records ?? [])
        setFields(configs ?? [])
      }
    } catch {
    } finally {
      if (currentId.current === id) setSignLoading(false)
    }
  }, [])
  useEffect(() => {
    if (tab !== 'signin' || !activityId || !canReadSignin) return
    void refreshSigns(activityId)
    const timer = setInterval(() => {
      if (!document.hidden) void refreshSigns(activityId)
    }, 6000)
    return () => clearInterval(timer)
  }, [tab, activityId, canReadSignin, refreshSigns])
  useEffect(() => {
    if (tab !== 'signin' || !activityId || !canManageSignin) return
    let current = true
    const refresh = () => {
      const version = qrVersion.current
      void getActivitySigninToken(activityId).then((result) => {
        if (current && version === qrVersion.current) {
          setQr(result)
          setQrFailed(false)
        }
      }).catch(() => {
        if (current && version === qrVersion.current) setQrFailed(true)
      })
    }
    refresh()
    const timer = window.setInterval(refresh, 10_000)
    return () => {
      current = false
      window.clearInterval(timer)
    }
  }, [activityId, canManageSignin, tab])
  useEffect(() => {
    if (!qr) return
    const delay = Math.max(0, signinTokenExpiresAt(qr.expiresAt) - Date.now())
    const timer = window.setTimeout(() => {
      setQr((current) => current?.token === qr.token ? null : current)
    }, delay)
    return () => window.clearTimeout(timer)
  }, [qr])
  const reload = () => setRevision((n) => n + 1)
  const selectActivity = (id: number) => {
    setActivityId(id)
    const next = new URLSearchParams(params)
    next.set('activityId', String(id))
    setParams(next, { replace: true })
  }
  const mutate = async (fn: () => Promise<unknown>, text: string) => {
    setBusy(true)
    try {
      await fn()
      message.success(text)
      setPublishTarget(null)
      setCancelTarget(null)
      setDeleteTarget(null)
      setRegenTarget(null)
      setDetail(null)
      reload()
    } catch {
    } finally {
      setBusy(false)
    }
  }
  const previewGeneration = async (v: GenerateValues) => {
    if (v.timeRange[1].format('HH:mm:ss') <= v.timeRange[0].format('HH:mm:ss')) {
      message.warning('结束时间必须晚于开始时间')
      return
    }
    const request: GenerateScheduleRequest = {
      activityId: v.activityMode === 'existing' ? v.activityId : undefined,
      customActivityName: v.activityMode === 'custom' ? v.customActivityName?.trim() : undefined,
      startDate: v.dateRange[0].format('YYYY-MM-DD'),
      endDate: v.dateRange[1].format('YYYY-MM-DD'),
      startTime: v.timeRange[0].format('HH:mm:ss'),
      endTime: v.timeRange[1].format('HH:mm:ss'),
      deptIds: v.deptIds,
      peoplePerSlot: v.peoplePerSlot,
      semester: v.semester,
      semesterStartDate: v.semesterStartDate?.format('YYYY-MM-DD') ?? null,
    }
    setBusy(true)
    try {
      const review = await previewActivitySchedules(request)
      setPreviewStale(false)
      setPreview({ request, review })
    } catch {
    } finally {
      setBusy(false)
    }
  }
  const refreshGeneration = async () => {
    if (!preview) return
    setBusy(true)
    try {
      const review = await previewActivitySchedules(preview.request)
      setPreview({ request: preview.request, review })
      setPreviewStale(false)
    } catch {
    } finally {
      setBusy(false)
    }
  }
  const confirmGeneration = async () => {
    if (!preview || previewStale || !preview.review.draftCount) return
    setBusy(true)
    const { request, review } = preview
    try {
      const result = await confirmActivitySchedules(request, review.fingerprint)
      setPreview(null)
      setGenOpen(false)
      setStatus('DRAFT')
      message.success('已生成 ' + result.draftCount + ' 个草稿班次')
      reload()
      let targetId = request.activityId
      if (!targetId) {
        const updated = await listActivities({ page: 1, size: 200 })
        setActivities(updated.records ?? [])
        targetId = updated.records.find((a) => a.name === request.customActivityName)?.id
      }
      if (targetId) selectActivity(targetId)
    } catch (e) {
      if (e instanceof ApiError && e.code === 409) setPreviewStale(true)
      else if (typeof e === 'object' && e && 'response' in e &&
        (e.response as { status?: number } | undefined)?.status === 409) setPreviewStale(true)
    } finally {
      setBusy(false)
    }
  }
  const manual = async (v: ManualValues) => {
    if (!v.timeRange[1].isAfter(v.timeRange[0])) {
      message.warning('结束时间必须晚于开始时间')
      return
    }
    setBusy(true)
    const payload: ActivityScheduleSave = {
      activityId: v.activityId,
      scheduleDate: v.scheduleDate.format('YYYY-MM-DD'),
      startTime: v.timeRange[0].format('HH:mm:ss'),
      endTime: v.timeRange[1].format('HH:mm:ss'),
      location: v.location,
      note: v.note,
      persons: v.persons.map((p) => ({
        userId: p.userId,
        personName: p.personName.trim(),
        isPrimary: p.isPrimary ?? 0,
      })),
    }
    try {
      if (manualEditing) await updateActivitySchedule(manualEditing.id, payload)
      else await createActivitySchedule(payload)
      setManualOpen(false)
      selectActivity(v.activityId)
      reload()
      message.success(manualEditing ? '班次已保存' : '活动排班草稿已保存')
    } catch {
    } finally {
      setBusy(false)
    }
  }
  const edit = (s: ScheduleVO | null) => {
    setDetail(null)
    setManualEditing(s)
    setManualOpen(true)
  }
  const operateQr = async (close = false) => {
    if (!activityId) return
    const id = activityId
    ++qrVersion.current
    setQrBusy(true)
    try {
      if (close) {
        await closeActivitySignin(id)
        if (currentId.current === id) setQr(null)
        message.success('签到已关闭，当前码已作废')
      } else {
        const token = await generateActivitySigninToken(id)
        if (currentId.current === id) setQr(token)
        message.success('签到码已生成，刷新会使旧码作废')
      }
    } catch {
    } finally {
      ++qrVersion.current
      setQrBusy(false)
    }
  }
  const showPublish = (s?: ScheduleVO) => {
    if (!activityId) return
    const targetRows = s ? [s] : drafts
    if (targetRows.length)
      setPublishTarget({ activityId, rows: targetRows, all: !s })
  }
  const columns: ColumnsType<ScheduleVO> = [
    {
      title: '日期 / 节次',
      key: 'time',
      width: compact ? undefined : 190,
      render: (_, s) => (
        <>
          <strong>
            {s.scheduleDate || '未设置日期'}{' '}
            {s.scheduleDate ? dayjs(s.scheduleDate).format('ddd') : ''}
          </strong>
          <small>
            {timeRange(s)} {s.sessionName && `/ ${s.sessionName}`}
          </small>
          {compact && <RoutineStatus status={s.status} />}
        </>
      ),
    },
    {
      title: '值班人员',
      key: 'people',
      responsive: ['md'],
      render: (_, s) => (
        <>
          <ShiftPeople shift={s} />
          <small>{s.location || '地点未设置'}</small>
        </>
      ),
    },
    {
      title: '来源',
      key: 'source',
      responsive: ['lg'],
      width: 115,
      render: (_, s) => (
        <small>{s.source === 'AUTO' ? '无课表生成' : '人工排班'}</small>
      ),
    },
    {
      title: '状态',
      key: 'status',
      responsive: ['md'],
      width: 105,
      render: (_, s) => <RoutineStatus status={s.status} />,
    },
    {
      title: '操作',
      key: 'actions',
      width: compact ? 155 : 170,
      render: (_, s) => (
        <Space size={compact ? 2 : 8} wrap>
          <Button type="link" onClick={() => setDetail(s)}>
            查看
          </Button>
          {canPublish && s.status === 'DRAFT' && (
            <Button type="link" onClick={() => showPublish(s)}>
              发布
            </Button>
          )}
          {canPublish && s.status === 'PUBLISHED' && (
            <Button type="link" onClick={() => setCancelTarget(s)}>
              取消
            </Button>
          )}
          {canManage && (s.status !== 'PUBLISHED' || canPublish) && ['DRAFT', 'PUBLISHED'].includes(s.status ?? '') && (
            <Button type="link" onClick={() => edit(s)}>
              编辑
            </Button>
          )}
        </Space>
      ),
    },
  ]
  const shiftsPanel = (
    <GlassCard>
      <div className="routine-process">
        <span>
          <i>1</i>读取无课表
        </span>
        <hr />
        <span className="current">
          <i>2</i>复核草稿
        </span>
        <hr />
        <span>
          <i>3</i>发布班次
        </span>
        <hr />
        <span>
          <i>4</i>签到与考勤
        </span>
      </div>
      <div className="routine-toolbar">
        <label className="routine-filter">
          班次状态
          <Select
            aria-label="班次状态"
            value={status}
            onChange={setStatus}
            allowClear
            placeholder="全部状态"
            options={['DRAFT', 'PUBLISHED', 'ENDED', 'CANCELLED'].map((s) => ({
              value: s,
              label: {
                DRAFT: '草稿',
                PUBLISHED: '已发布',
                ENDED: '已结束',
                CANCELLED: '已取消',
              }[s],
            }))}
          />
        </label>
        <span className="routine-muted">按当前活动查看，避免跨活动误操作</span>
        <div className="routine-toolbar-actions">
          <Button
            type="link"
            disabled={!activityId || !canViewAttendance}
            onClick={() =>
              navigate(`/routine/attendance?activityId=${activityId}`)
            }
          >
            查看考勤
          </Button>
          {canPublish && (
            <Button
              type="primary"
              disabled={!activityId || !drafts.length || loading}
              onClick={() => showPublish()}
            >
              发布草稿（{drafts.length}）
            </Button>
          )}
        </div>
      </div>
      {drafts.length > 0 && (
        <div className="routine-scope">
          <span>{drafts.length} 个草稿班次尚未进入考勤，请核对人员与时段</span>
          <Button type="link" onClick={() => setStatus('DRAFT')}>
            复核草稿
          </Button>
        </div>
      )}
      <Table
        className="routine-data-table"
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={shown}
        scroll={compact ? undefined : { x: 780 }}
        pagination={{ pageSize: 15, showTotal: (n) => `共 ${n} 个班次` }}
        locale={{
          emptyText: (
            <Empty
              description={
                activityId ? '当前活动暂无匹配班次' : '先选择关联活动'
              }
            >
              {canGenerate && (
                <Button onClick={() => setGenOpen(true)}>生成排班</Button>
              )}
            </Empty>
          ),
        }}
      />
    </GlassCard>
  )
  const signinPanel = (
    <div className="routine-signin-workspace">
      <GlassCard className="routine-qr">
        <h3>活动签到码</h3>
        {qrFailed && <Alert showIcon type="error" message="签到状态暂时无法读取，页面将自动重试" />}
        {qr ? (
          <>
            <span className="tone-tag tone-success">签到已开启</span>
            <div className="routine-qr-box">
              <QRCodeSVG
                value={`${window.location.origin}/signin/${qr.token}`}
                size={182}
                level="M"
              />
            </div>
            <small>有效期至 {dayjs(qr.expiresAt).format('MM-DD HH:mm')}</small>
          </>
        ) : (
          !qrFailed && <Empty description="开启签到后生成活动统一签到码" />
        )}
        {canManageSignin && (
          <Space wrap>
            {qr ? (
              <>
                <Button
                  icon={<ReloadOutlined />}
                  loading={qrBusy}
                  onClick={() => operateQr()}
                >
                  刷新签到码
                </Button>
                <Button danger loading={qrBusy} onClick={() => operateQr(true)}>
                  关闭签到
                </Button>
              </>
            ) : (
              <Button
                type="primary"
                disabled={!activityId || qrFailed}
                loading={qrBusy}
                icon={<QrcodeOutlined />}
                onClick={() => operateQr()}
              >
                开启签到
              </Button>
            )}
          </Space>
        )}
        <RoutineNote>
          活动使用统一签到码；刷新后旧码作废。班次发布后才进入排班考勤。
        </RoutineNote>
      </GlassCard>
      <div>
        <GlassCard>
          <div className="business-section-head">
            <h3>签到记录</h3>
            <Button
              icon={<ReloadOutlined />}
              disabled={!activityId}
              onClick={() => activityId && refreshSigns(activityId)}
            >
              刷新
            </Button>
          </div>
          <Table<SigninVO>
            className="routine-data-table"
            rowKey="id"
            dataSource={signRows}
            loading={signLoading}
            scroll={{ x: 620 }}
            pagination={{ pageSize: 8 }}
            columns={[
              { title: '姓名', dataIndex: 'name' },
              {
                title: '学号',
                dataIndex: 'studentNo',
                render: (v) => v || '—',
              },
              {
                title: '签到时间',
                dataIndex: 'signTime',
                render: (v) => (v ? dayjs(v).format('MM-DD HH:mm') : '—'),
              },
              ...fields
                .filter((f) => !['姓名', '学号'].includes(f.fieldName))
                .map((f) => ({
                  title: f.fieldName,
                  key: f.id,
                  render: (_: unknown, r: SigninVO) => {
                    try {
                      return (
                        JSON.parse(r.fieldsJson || '{}')[f.fieldName] ?? '—'
                      )
                    } catch {
                      return '—'
                    }
                  },
                })),
            ]}
          />
        </GlassCard>
        {canManageSignin && activityId && (
          <GlassCard className="routine-pad" style={{ marginTop: 20 }}>
            <h3>签到字段配置</h3>
            <SigninFieldsConfig activityId={activityId} />
          </GlassCard>
        )}
      </div>
    </div>
  )
  return (
    <RoutineLayout>
      <PageHeader
        title="活动排班"
        extra={
          <>
            {canManage && (
              <Button
                icon={<PlusOutlined />}
                disabled={!activityId}
                onClick={() => edit(null)}
              >
                人工排班
              </Button>
            )}
            {canGenerate && (
              <Button
                type="primary"
                icon={<ThunderboltOutlined />}
                onClick={() => setGenOpen(true)}
              >
                生成排班
              </Button>
            )}
          </>
        }
      />
      <div className="routine-context">
        <div>
          <span className="tone-tag tone-brand">关联活动</span>
          <h3>{selected?.name || '请选择关联活动'}</h3>
          <p>
            {selected?.organizer}　{selected?.location}　
            {selected && (
              <Button
                type="link"
                onClick={() => navigate(`/activities/${selected.id}`)}
              >
                查看活动详情
              </Button>
            )}
          </p>
        </div>
        <Select
          aria-label="切换活动"
          showSearch
          optionFilterProp="label"
          value={activityId}
          placeholder="选择活动"
          onChange={selectActivity}
          options={activities.map((a) => ({ value: a.id, label: a.name }))}
        />
      </div>
      <RoutineSummary
        items={[
          { label: '活动班次', value: rows.length, note: '当前活动' },
          {
            label: '待发布草稿',
            value: drafts.length,
            note: '需要复核',
            attention: true,
          },
          {
            label: '已发布',
            value: rows.filter((s) => s.status === 'PUBLISHED').length,
            note: '进入考勤',
          },
          {
            label: '已结束',
            value: rows.filter((s) => s.status === 'ENDED').length,
            note: '可查看考勤',
          },
        ]}
      />
      <Tabs
        className="routine-tabs"
        activeKey={tab}
        onChange={setTab}
        items={[
          { key: 'shifts', label: '班次安排', children: shiftsPanel },
          ...(canReadSignin
            ? [{ key: 'signin', label: '签到现场', children: signinPanel }]
            : []),
        ]}
      />
      <RoutineNote>
        生成结果按节次分别选人。周末活动请使用人工排班；发布后自动建立待签到记录。
      </RoutineNote>
      <ScheduleDetail
        shift={detail}
        activities={activities}
        onClose={() => setDetail(null)}
        onEdit={
          canManage &&
          (detail?.status !== 'PUBLISHED' || canPublish) &&
          detail &&
          ['DRAFT', 'PUBLISHED'].includes(detail.status ?? '')
            ? edit
            : undefined
        }
        extra={
          <>
            {canGenerate && (detail?.status !== 'PUBLISHED' || canPublish) &&
              detail?.source === 'AUTO' &&
              ['DRAFT', 'PUBLISHED'].includes(detail.status ?? '') && (
                <Button
                  onClick={() => {
                    setRegenTarget(detail)
                    setDetail(null)
                  }}
                >
                  按无课表重新生成
                </Button>
              )}
            {canManage && detail?.status === 'DRAFT' && (
              <Button
                danger
                onClick={() => {
                  setDeleteTarget(detail)
                  setDetail(null)
                }}
              >
                删除草稿
              </Button>
            )}
          </>
        }
      />
      {genOpen && (
        <FormSurface
          mode="drawer"
          description="设置活动范围与人员来源，先预览人员与时段，确认后才保存草稿。"
          title="从无课表生成活动排班"
          width={650}
          open={!preview}
          onCancel={() => setGenOpen(false)}
          confirmLoading={busy}
          okText="预览排班"
          onOk={() => genForm.submit()}
        >
          <Form
            form={genForm}
            preserve={false}
            layout="vertical"
            initialValues={{
              activityMode: 'existing',
              activityId,
              semester: params.get('semester') || semesters[0],
              deptIds: Number(params.get('deptId'))
                ? [Number(params.get('deptId'))]
                : undefined,
            }}
            onFinish={previewGeneration}
          >
            <FormSection title="活动范围">
              <Form.Item name="activityMode" label="活动方式">
                <Select
                  options={[
                    { value: 'existing', label: '选择现有活动' },
                    { value: 'custom', label: '自定义活动名称' },
                  ]}
                />
              </Form.Item>
              {genMode !== 'custom' ? (
                <Form.Item
                  name="activityId"
                  label="关联活动"
                  rules={[{ required: true }]}
                >
                  <Select
                    showSearch
                    optionFilterProp="label"
                    options={activities.map((a) => ({
                      value: a.id,
                      label: a.name,
                    }))}
                  />
                </Form.Item>
              ) : (
                <Form.Item
                  name="customActivityName"
                  label="自定义活动名称"
                  rules={[{ required: true, whitespace: true }]}
                >
                  <Input maxLength={100} />
                </Form.Item>
              )}
              <Form.Item
                name="dateRange"
                label="活动日期范围"
                rules={[{ required: true }]}
              >
                <DatePicker.RangePicker style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="timeRange"
                label="每天的活动时段"
                rules={[{ required: true }]}
              >
                <TimePicker.RangePicker
                  format="HH:mm"
                  style={{ width: '100%' }}
                />
              </Form.Item>
            </FormSection>
            <FormSection title="人员与无课表">
              <Form.Item
                name="deptIds"
                label="参与部门"
                rules={[{ required: true, type: 'array', min: 1 }]}
              >
                <Select
                  mode="multiple"
                  options={depts.map((d) => ({ value: d.id, label: d.name }))}
                />
              </Form.Item>
              <div className="routine-form-grid">
                <Form.Item name="semester" label="无课表学期">
                  <Select
                    allowClear
                    options={[
                      ...new Set(
                        [params.get('semester'), ...semesters].filter(
                          (s): s is string => !!s,
                        ),
                      ),
                    ].map((s) => ({ value: s, label: s }))}
                  />
                </Form.Item>
                <Form.Item name="semesterStartDate" label="第一教学周的周一">
                  <DatePicker style={{ width: '100%' }} />
                </Form.Item>
              </div>
              <Form.Item name="peoplePerSlot" label="每节次人数（留空不限）">
                <InputNumber min={1} precision={0} />
              </Form.Item>
              <RoutineNote>
                按每个覆盖节次分别匹配无课人员，在本次生成范围内尽量均衡安排。生成后先存为草稿。
              </RoutineNote>
              <RoutineNote warning>
                按教学周记录的无课表需要填写第一教学周的周一。周末请手动排班。
              </RoutineNote>
            </FormSection>
          </Form>
        </FormSurface>
      )}
      <FormSurface
        mode="dialog"
        title="复核活动排班"
        width={650}
        open={preview !== null}
        onCancel={() => { if (!busy) setPreview(null) }}
        footer={
          <Space wrap>
            <Button disabled={busy} onClick={() => setPreview(null)}>返回调整</Button>
            {previewStale ? (
              <Button type="primary" loading={busy} onClick={() => void refreshGeneration()}>重新预览</Button>
            ) : (
              <Button type="primary" loading={busy} disabled={!preview?.review.draftCount}
                onClick={() => void confirmGeneration()}>确认生成草稿</Button>
            )}
          </Space>
        }
      >
        <p className="routine-muted">
          预计生成 {preview?.review.draftCount ?? 0} 个草稿班次。清单版本 {preview?.review.fingerprint.slice(0, 8)}
        </p>
        {previewStale && <Alert type="warning" showIcon message="名单或已排班次有变化，请重新预览后确认。" />}
        {preview?.review.days.map((r) => (
          <div key={r.date}>
            <h4>{r.date}</h4>
            {r.slots?.length ? (
              r.slots.map((slot) => (
                <div className="routine-preview-row" key={slot.period}>
                  <span>
                    {slot.label}
                    <small>
                      {slot.startTime?.slice(0, 5)}–{slot.endTime?.slice(0, 5)}
                    </small>
                  </span>
                  <span>
                    {slot.persons.map((p) => p.name).join('、')}
                    <small>{slot.count} 人</small>
                  </span>
                  {!!slot.missingCount && <small>缺 {slot.missingCount} 人</small>}
                  {!!slot.unboundNames?.length && <small>未关联账号：{slot.unboundNames.join('、')}</small>}
                  {!!slot.conflictingNames?.length && <small>已有值班：{slot.conflictingNames.join('、')}</small>}
                  {!!slot.existingShiftIds?.length && <small>该活动已有重叠班次，本时段跳过</small>}
                </div>
              ))
            ) : (
              <RoutineNote warning>
                当天无可排班时段，请核对无课表，或人工安排周末班次。
              </RoutineNote>
            )}
          </div>
        ))}
      </FormSurface>
      <FormSurface
        mode="drawer"
        description="指定活动、时段与值班人员，保存后核对并发布。"
        title={manualEditing ? '编辑班次' : '人工创建活动排班'}
        className="routine-drawer"
        width="min(540px, 100vw)"
        open={manualOpen}
        onCancel={() => setManualOpen(false)}
        destroyOnHidden
        footer={
          <Space>
            <Button onClick={() => setManualOpen(false)}>取消</Button>
            <Button
              type="primary"
              loading={busy}
              onClick={() => manualForm.submit()}
            >
              {manualEditing ? '保存修改' : '保存草稿'}
            </Button>
          </Space>
        }
      >
        <Form
          form={manualForm}
          preserve={false}
          layout="vertical"
          key={manualEditing?.id ?? 'new'}
          initialValues={
            manualEditing
              ? {
                  activityId: manualEditing.activityId,
                  scheduleDate: manualEditing.scheduleDate
                    ? dayjs(manualEditing.scheduleDate)
                    : undefined,
                  timeRange:
                    manualEditing.startTime && manualEditing.endTime
                      ? [
                          dayjs(`2000-01-01T${manualEditing.startTime}`),
                          dayjs(`2000-01-01T${manualEditing.endTime}`),
                        ]
                      : undefined,
                  location: manualEditing.location,
                  note: manualEditing.notes,
                  persons: manualEditing.persons.map((p) => ({
                    userId: p.userId,
                    personName: p.personName,
                    isPrimary: p.isPrimary,
                  })),
                }
              : { activityId, persons: [{ personName: '', isPrimary: 1 }] }
          }
          onFinish={manual}
        >
          <Form.Item
            name="activityId"
            label="关联活动"
            rules={[{ required: true }]}
          >
            <Select
              disabled={!!manualEditing}
              showSearch
              optionFilterProp="label"
              options={activities.map((a) => ({ value: a.id, label: a.name }))}
            />
          </Form.Item>
          <Form.Item
            name="scheduleDate"
            label="排班日期"
            rules={[{ required: true }]}
          >
            <DatePicker style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            name="timeRange"
            label="班次时间"
            rules={[{ required: true }]}
          >
            <TimePicker.RangePicker format="HH:mm" style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="location" label="地点">
            <Input maxLength={100} />
          </Form.Item>
          <PeopleFields form={manualForm} users={users} />
          <Form.Item name="note" label="工作备注">
            <Input.TextArea maxLength={255} rows={3} />
          </Form.Item>
          <RoutineNote warning={manualEditing?.status === 'PUBLISHED'}>
            {manualEditing?.status === 'PUBLISHED'
              ? '修改已发布班次会同步刷新人员考勤，请核对人员变动。'
              : '保存为草稿，复核后发布。'}
          </RoutineNote>
        </Form>
      </FormSurface>
      <FormSurface
        mode="dialog"
        title="发布活动排班"
        open={!!publishTarget}
        onCancel={() => setPublishTarget(null)}
        confirmLoading={busy}
        okText="确认发布"
        onOk={() =>
          publishTarget &&
          mutate(
            () =>
              publishTarget.all
                ? publishAllActivitySchedules(publishTarget.activityId)
                : publishActivitySchedule(publishTarget.rows[0].id),
            '已发布班次并建立待签到考勤',
          )
        }
      >
        <p>
          {activities.find((a) => a.id === publishTarget?.activityId)?.name}
        </p>
        <RoutineNote>
          本次发布 {publishTarget?.rows.length} 个草稿班次，将建立{' '}
          {publishTarget?.rows.reduce((n, s) => n + s.persons.length, 0)}{' '}
          条待签到记录。
        </RoutineNote>
        {publishTarget?.rows.map((s) => (
          <div className="routine-preview-row" key={s.id}>
            <span>
              {s.scheduleDate} {timeRange(s)}
              <small>{s.persons.map((p) => p.personName).join('、')}</small>
            </span>
            <span>{s.persons.length} 人</span>
          </div>
        ))}
      </FormSurface>
      <FormSurface
        mode="dialog"
        title="取消已发布班次"
        open={!!cancelTarget}
        onCancel={() => setCancelTarget(null)}
        okText="确认取消班次"
        confirmLoading={busy}
        onOk={() =>
          cancelTarget &&
          mutate(
            () => cancelActivitySchedule(cancelTarget.id),
            '已取消班次，人工修正考勤保留',
          )
        }
      >
        <p>
          {cancelTarget?.scheduleDate} {cancelTarget && timeRange(cancelTarget)}
        </p>
        <RoutineNote warning>
          仍待签到的自动考勤会取消，已完成和人工修正的记录会保留。
        </RoutineNote>
      </FormSurface>
      <FormSurface
        mode="dialog"
        title="删除排班草稿"
        open={!!deleteTarget}
        onCancel={() => setDeleteTarget(null)}
        okText="删除草稿"
        okButtonProps={{ danger: true }}
        confirmLoading={busy}
        onOk={() =>
          deleteTarget &&
          mutate(() => deleteActivitySchedule(deleteTarget.id), '草稿已删除')
        }
      >
        <p>
          {deleteTarget?.scheduleDate} {deleteTarget && timeRange(deleteTarget)}
        </p>
        <p>{deleteTarget?.persons.map((p) => p.personName).join('、')}</p>
      </FormSurface>
      <FormSurface
        mode="dialog"
        title="重新生成班次人员"
        open={!!regenTarget}
        destroyOnHidden
        onCancel={() => setRegenTarget(null)}
        okText="确认重新生成"
        confirmLoading={busy}
        onOk={() => regenForm.submit()}
      >
        <Form
          form={regenForm}
          preserve={false}
          layout="vertical"
          initialValues={{ semester: params.get('semester') || semesters[0] }}
          onFinish={(v: RegenerateValues) =>
            regenTarget &&
            mutate(
              () =>
                regenerateActivitySchedule(
                  regenTarget.id,
                  v.deptId,
                  v.semester,
                  v.semesterStartDate?.format('YYYY-MM-DD'),
                ),
              '已重新生成班次人员',
            )
          }
        >
          <p>
            {regenTarget?.scheduleDate} {regenTarget && timeRange(regenTarget)}
          </p>
          <Form.Item name="deptId" label="参与部门（留空沿用班次部门）">
            <Select
              allowClear
              options={depts.map((d) => ({ value: d.id, label: d.name }))}
            />
          </Form.Item>
          <Form.Item name="semester" label="无课表学期">
            <Select
              allowClear
              options={semesters.map((s) => ({ value: s, label: s }))}
            />
          </Form.Item>
          <Form.Item name="semesterStartDate" label="第一教学周的周一">
            <DatePicker />
          </Form.Item>
          <RoutineNote warning>
            重新选人会同步刷新已发布班次的自动考勤，人工修正记录保留。按教学周记录的无课表需要填写第一教学周的周一。
          </RoutineNote>
        </Form>
      </FormSurface>
    </RoutineLayout>
  )
}
