import { useCallback, useEffect, useMemo, useState, type CSSProperties } from 'react'
import { App, AutoComplete, Button, DatePicker, Form, Input, InputNumber, Pagination, Popconfirm, Radio, Select, Space, Spin, Tag, TimePicker } from 'antd'
import { DeleteOutlined, PlayCircleOutlined, PlusOutlined, ReloadOutlined, StopOutlined, QrcodeOutlined, ThunderboltOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { QRCodeSVG } from 'qrcode.react'
import dayjs, { type Dayjs } from 'dayjs'
import { closeActivitySignin, generateActivitySigninToken, getSigninFields, listSignins, type ActivitySigninToken, type SigninFieldConfigVO, type SigninVO } from '@/api/signin'
import SigninFieldsConfig from '@/components/signin/SigninFieldsConfig'
import GlassCard from '@/components/glass/GlassCard'
import GlassModal from '@/components/glass/GlassModal'
import PageHeader from '@/components/glass/PageHeader'
import {
  cancelActivitySchedule,
  deleteActivitySchedule,
  generateActivitySchedules,
  listActivitySchedules,
  listSemesters,
  publishActivitySchedule,
  publishAllActivitySchedules,
  regenerateActivitySchedule,
  updateActivitySchedule,
  type ActivityScheduleSave,
  type GenerateResult,
} from '@/api/activitySchedule'
import { listActivities, type ActivityVO } from '@/api/activity'
import { listDepts } from '@/api/dept'
import { listUserOptions, type UserOptionVO } from '@/api/user'
import type { ScheduleVO } from '@/api/schedule'

const STATUS_TEXT: Record<string, { text: string; color: string }> = {
  DRAFT: { text: '草稿', color: 'default' },
  PUBLISHED: { text: '已发布', color: 'green' },
  ENDED: { text: '已结束', color: 'blue' },
  CANCELLED: { text: '已取消', color: 'red' },
}

const thStyle: CSSProperties = {
  textAlign: 'left',
  padding: '6px 8px',
  borderBottom: '1px solid var(--border)',
  color: 'var(--text-secondary)',
  fontWeight: 600,
}
const tdStyle: CSSProperties = { padding: '6px 8px', borderBottom: '1px solid var(--border)' }

const SIGN_PAGE_SIZE = 8

interface GenerateFormValues {
  activityMode?: 'existing' | 'custom'
  activityId?: number
  customActivityName?: string
  dateRange: [Dayjs, Dayjs]
  timeRange: [Dayjs, Dayjs]
  deptIds?: number[]
  peoplePerSlot?: number
  semester?: string
  semesterStartDate?: Dayjs
}

interface ManualFormValues {
  scheduleDate?: Dayjs
  timeRange?: [Dayjs, Dayjs]
  location?: string
  persons?: Array<{ userId?: number; personName?: string }>
}

export default function ActivityScheduleList() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [list, setList] = useState<ScheduleVO[]>([])
  const [loading, setLoading] = useState(false)
  const [statusFilter, setStatusFilter] = useState<string | undefined>()

  const [activities, setActivities] = useState<ActivityVO[]>([])
  const [depts, setDepts] = useState<Array<{ id: number; name: string }>>([])
  const [userOptions, setUserOptions] = useState<UserOptionVO[]>([])
  const [semesters, setSemesters] = useState<string[]>([])

  const [generating, setGenerating] = useState(false)
  const [preview, setPreview] = useState<GenerateResult[] | null>(null)

  const [genForm] = Form.useForm<GenerateFormValues>()
  const genMode = Form.useWatch('activityMode', genForm) || 'existing'
  const [genOpen, setGenOpen] = useState(false)

  const [manualOpen, setManualOpen] = useState(false)
  const [manualEditing, setManualEditing] = useState<ScheduleVO | null>(null)
  const [manualForm] = Form.useForm<ManualFormValues>()

  // —— 活动级单码签到（拆合容器）——
  const [selActivityId, setSelActivityId] = useState<number>()
  const [qr, setQr] = useState<ActivitySigninToken | null>(null)
  const [closed, setClosed] = useState(false)
  const [qrLoading, setQrLoading] = useState(false)
  const [signRows, setSignRows] = useState<SigninVO[]>([])
  const [fieldConfigs, setFieldConfigs] = useState<SigninFieldConfigVO[]>([])
  const [signLoading, setSignLoading] = useState(false)
  const [signPage, setSignPage] = useState(1)

  const refreshQr = useCallback(async (id: number) => {
    setQrLoading(true)
    try {
      const t = await generateActivitySigninToken(id)
      setQr(t)
      setClosed(false)
    } catch {
      /* http 拦截已提示 */
    } finally {
      setQrLoading(false)
    }
  }, [])

  const doCloseSignin = async (id: number) => {
    try {
      await closeActivitySignin(id)
      setQr(null)
      setClosed(true)
      message.success('签到已关闭，当前码已作废')
    } catch {
      /* http 拦截已提示 */
    }
  }

  // 加载所选活动的签到记录 + 自定义字段配置
  const refreshSignups = useCallback(async (id: number) => {
    setSignLoading(true)
    try {
      const [rows, fields] = await Promise.all([listSignins(id), getSigninFields(id)])
      setSignRows(rows ?? [])
      setFieldConfigs(fields ?? [])
    } catch {
      /* http 拦截已提示 */
    } finally {
      setSignLoading(false)
    }
  }, [])

  // 选择活动：自动拉取该活动签到码与签到记录
  useEffect(() => {
    if (!selActivityId) {
      setQr(null)
      setSignRows([])
      setFieldConfigs([])
      setSignPage(1)
      return
    }
    setSignPage(1)
    refreshQr(selActivityId)
    refreshSignups(selActivityId)
  }, [selActivityId, refreshQr, refreshSignups])

  const pageSignRows = useMemo(
    () => signRows.slice((signPage - 1) * SIGN_PAGE_SIZE, signPage * SIGN_PAGE_SIZE),
    [signRows, signPage],
  )

  // 轮询：签到记录随扫码实时变化
  useEffect(() => {
    if (!selActivityId) return
    const timer = setInterval(() => refreshSignups(selActivityId), 6000)
    return () => clearInterval(timer)
  }, [selActivityId, refreshSignups])

  const fetchList = useCallback(() => {
    setLoading(true)
    listActivitySchedules({ status: statusFilter, activityId: selActivityId })
      .then((res) => setList(res ?? []))
      .catch(() => {
        /* http 拦截已提示 */
      })
      .finally(() => setLoading(false))
  }, [statusFilter, selActivityId])

  useEffect(() => {
    fetchList()
  }, [fetchList])

  useEffect(() => {
    listActivities({ page: 1, size: 200 })
      .then((r) => {
        const rows = r?.records ?? []
        setActivities(rows)
        // 默认选中最近一次创建的活动（id 最大）
        if (rows.length > 0) {
          const recent = rows.reduce((a, b) => (b.id > a.id ? b : a))
          setSelActivityId(recent.id)
        }
      })
      .catch(() => {})
    listDepts().then((r) => setDepts(r ?? [])).catch(() => {})
    listUserOptions().then((r) => setUserOptions(r ?? [])).catch(() => {})
    listSemesters().then((r) => setSemesters(r ?? [])).catch(() => {})
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 生成后刷新活动列表（含新建/复用的自定义活动）
  const reloadActivities = useCallback(() => {
    listActivities({ page: 1, size: 200 }).then((r) => setActivities(r?.records ?? [])).catch(() => {})
  }, [])

  const personOptions = useMemo(
    () => userOptions.map((u) => ({ value: u.realName, label: `${u.realName}（${u.deptName ?? ''}）` })),
    [userOptions],
  )

  const activityOptions = activities.map((a) => ({ value: a.id, label: a.name }))

  const handleGenerate = async () => {
    const v = await genForm.validateFields()
    setGenerating(true)
    try {
      const res = await generateActivitySchedules({
        activityId: v.activityMode === 'existing' ? v.activityId : undefined,
        customActivityName: v.activityMode === 'custom' ? v.customActivityName : undefined,
        startDate: v.dateRange[0].format('YYYY-MM-DD'),
        endDate: v.dateRange[1].format('YYYY-MM-DD'),
        startTime: v.timeRange[0].format('HH:mm:ss'),
        endTime: v.timeRange[1].format('HH:mm:ss'),
        deptIds: v.deptIds ?? [],
        peoplePerSlot: v.peoplePerSlot,
        semester: v.semester,
        semesterStartDate: v.semesterStartDate ? v.semesterStartDate.format('YYYY-MM-DD') : null,
      })
      setPreview(res ?? [])
      message.success('已按无课表生成草稿排班')
      reloadActivities()
      fetchList()
    } catch {
      /* http 拦截已提示 */
    } finally {
      setGenerating(false)
    }
  }

  const doPublish = async (id: number) => {
    await publishActivitySchedule(id)
    message.success('已发布，进入签到考勤')
    fetchList()
  }
  const doPublishAll = async () => {
    try {
      const n = await publishAllActivitySchedules(selActivityId)
      message.success(`已一键发布 ${n} 条草稿排班`)
      fetchList()
    } catch {
      /* http 拦截已提示 */
    }
  }
  const doCancel = async (id: number) => {
    await cancelActivitySchedule(id)
    message.success('已取消')
    fetchList()
  }
  const doRegenerate = async (id: number) => {
    const n = await regenerateActivitySchedule(id)
    message.success(`重新生成，命中 ${n} 人`)
    fetchList()
  }
  const doDelete = async (id: number) => {
    await deleteActivitySchedule(id)
    message.success('已删除')
    fetchList()
  }

  const openManualEdit = (s: ScheduleVO) => {
    setManualEditing(s)
    const persons = (s.persons ?? []).map((p) => ({ userId: p.userId ?? undefined, personName: p.personName }))
    manualForm.setFieldsValue({
      scheduleDate: s.scheduleDate ? dayjs(s.scheduleDate) : undefined,
      timeRange: s.startTime && s.endTime ? [dayjs(s.startTime, 'HH:mm:ss'), dayjs(s.endTime, 'HH:mm:ss')] : undefined,
      location: s.location ?? undefined,
      persons,
    })
    setManualOpen(true)
  }

  const handleManualSave = async () => {
    const v = await manualForm.validateFields()
    if (!manualEditing) return
    const data: ActivityScheduleSave = {
      activityId: manualEditing.activityId ?? 0,
      scheduleDate: v.scheduleDate ? v.scheduleDate.format('YYYY-MM-DD') : '',
      startTime: v.timeRange?.[0] ? v.timeRange[0].format('HH:mm:ss') : '',
      endTime: v.timeRange?.[1] ? v.timeRange[1].format('HH:mm:ss') : '',
      location: v.location ?? null,
      persons: (v.persons ?? [])
        .filter((p): p is { userId?: number; personName: string } => !!p.personName?.trim())
        .map((p) => ({ userId: p.userId ?? null, personName: p.personName.trim() })),
    }
    try {
      await updateActivitySchedule(manualEditing.id, data)
      message.success('保存成功')
      setManualOpen(false)
      fetchList()
    } catch {
      /* http 拦截已提示 */
    }
  }

  return (
    <div>
      <PageHeader
        title="活动排班"
        description="基于无课表一键生成活动排班，发布后作为考勤判断依据"
        extra={
          <Space>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setGenOpen(true)}>
              一键生成排班
            </Button>
          </Space>
        }
      />

      {/* —— 活动签到：签到码 + 签到记录 两个独立容器 —— */}
      <div style={{ display: 'flex', gap: 16, marginBottom: 16, flexWrap: 'wrap' }}>
        {/* 容器A：签到码（一个活动一个码） */}
        <GlassCard style={{ padding: 16, flex: 1, minWidth: 300 }}>
          <div style={{ fontWeight: 600, marginBottom: 12, display: 'flex', alignItems: 'center', gap: 6, justifyContent: 'space-between' }}>
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
              <QrcodeOutlined /> 签到码
            </span>
            {selActivityId && <SigninFieldsConfig activityId={selActivityId} />}
          </div>
          <Select
            showSearch
            optionFilterProp="label"
            placeholder="选择活动后自动生成该活动唯一签到码"
            style={{ width: '100%', marginBottom: 12 }}
            options={activityOptions}
            value={selActivityId}
            onChange={setSelActivityId}
          />
          {!selActivityId ? (
            <div style={{ textAlign: 'center', color: 'var(--text-secondary)', padding: 20 }}>请先选择活动</div>
          ) : closed ? (
            <div style={{ textAlign: 'center', padding: 16 }}>
              <div style={{ color: 'var(--brand)', marginBottom: 12 }}>签到已关闭，扫码已停止</div>
              <Button type="primary" icon={<PlayCircleOutlined />} loading={qrLoading} onClick={() => refreshQr(selActivityId)}>
                重新开启签到
              </Button>
            </div>
          ) : qr ? (
            <div style={{ textAlign: 'center' }}>
              <div style={{ display: 'inline-block', padding: 12, background: '#fff', borderRadius: 8 }}>
                {/* 用前端 origin 拼扫码链接，避免后端拼出的 host（vite changeOrigin）指向 8080 而非 SPA */}
                <QRCodeSVG value={`${window.location.origin}/signin/${qr.token}`} size={170} level="M" />
              </div>
              <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginTop: 8 }}>
                有效期至 {qr.expiresAt ? new Date(qr.expiresAt).toLocaleTimeString() : '-'}
              </div>
              <Space style={{ marginTop: 10 }} wrap>
                <Button size="small" icon={<ReloadOutlined />} loading={qrLoading} onClick={() => refreshQr(selActivityId)}>
                  刷新
                </Button>
                <Button size="small" danger icon={<StopOutlined />} onClick={() => doCloseSignin(selActivityId)}>
                  关闭签到
                </Button>
              </Space>
            </div>
          ) : (
            <div style={{ textAlign: 'center', padding: 20 }}>
              <Spin />
            </div>
          )}
        </GlassCard>

        {/* 容器B：签到记录（含自定义表单字段回显） */}
        <GlassCard style={{ padding: 16, flex: 1.6, minWidth: 380 }}>
          <div style={{ fontWeight: 600, marginBottom: 12 }}>签到记录</div>
          {!selActivityId ? (
            <div style={{ textAlign: 'center', color: 'var(--text-secondary)', padding: 20 }}>
              选择左侧活动后显示其签到记录
            </div>
          ) : signLoading && signRows.length === 0 ? (
            <div style={{ textAlign: 'center', padding: 20 }}>
              <Spin />
            </div>
          ) : signRows.length === 0 ? (
            <div style={{ textAlign: 'center', color: 'var(--text-secondary)', padding: 20 }}>该活动暂无签到记录</div>
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 14, whiteSpace: 'nowrap' }}>
                <thead>
                  <tr>
                    <th style={thStyle}>姓名</th>
                    <th style={thStyle}>学号</th>
                    <th style={thStyle}>签到时间</th>
                    {fieldConfigs
                      .filter((f) => f.fieldName !== '姓名' && f.fieldName !== '学号')
                      .map((f) => (
                        <th key={f.id} style={thStyle}>
                          {f.fieldName}
                        </th>
                      ))}
                  </tr>
                </thead>
                <tbody>
                  {pageSignRows.map((row) => {
                    let fv: Record<string, string> = {}
                    if (row.fieldsJson) {
                      try {
                        fv = JSON.parse(row.fieldsJson)
                      } catch {
                        fv = {}
                      }
                    }
                    return (
                      <tr key={row.id}>
                        <td style={tdStyle}>{row.name}</td>
                        <td style={tdStyle}>{row.studentNo || '-'}</td>
                        <td style={tdStyle}>{row.signTime ? dayjs(row.signTime).format('MM-DD HH:mm') : '-'}</td>
                        {fieldConfigs
                          .filter((f) => f.fieldName !== '姓名' && f.fieldName !== '学号')
                          .map((f) => (
                            <td key={f.id} style={tdStyle}>
                              {fv[f.fieldName] || '-'}
                            </td>
                          ))}
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}
          {signRows.length > SIGN_PAGE_SIZE && (
            <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 12 }}>
              <Pagination
                current={signPage}
                pageSize={SIGN_PAGE_SIZE}
                total={signRows.length}
                showSizeChanger={false}
                showTotal={(t) => `共 ${t} 条`}
                onChange={(p) => setSignPage(p)}
              />
            </div>
          )}
        </GlassCard>
      </div>

      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space wrap>
          <span style={{ color: 'var(--text-secondary)' }}>状态</span>
          <Select
            placeholder="全部状态"
            allowClear
            style={{ width: 140 }}
            value={statusFilter}
            onChange={setStatusFilter}
            options={Object.entries(STATUS_TEXT).map(([value, s]) => ({ value, label: s.text }))}
          />
          <Button type="primary" icon={<ThunderboltOutlined />} onClick={doPublishAll}>
            一键发布
          </Button>
          <span style={{ color: 'var(--text-secondary)', fontSize: 13 }}>
            {selActivityId ? '将发布所选活动的全部草稿排班' : '将发布全部活动下的草稿排班'}
          </span>
        </Space>
      </GlassCard>

      <GlassCard style={{ padding: 16 }}>
        {list.length === 0 && !loading ? (
          <div style={{ textAlign: 'center', color: 'var(--text-secondary)', padding: 32 }}>
            暂无活动排班，点击右上角「一键生成排班」
          </div>
        ) : (
          <>
            {list.map((s) => {
              const st = STATUS_TEXT[s.status ?? ''] ?? { text: s.status ?? '-', color: 'default' }
              const names = (s.persons ?? []).map((p) => p.personName).join('、')
              return (
                <div
                  key={s.id}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 12,
                    padding: 12,
                    borderBottom: '1px solid var(--border)',
                    flexWrap: 'wrap',
                  }}
                >
                  <span style={{ fontWeight: 500, minWidth: 110 }}>{s.scheduleDate ?? '-'}</span>
                  <span style={{ color: 'var(--text-primary)', width: 130 }}>
                    {s.startTime ? s.startTime.slice(0, 5) : ''}-{s.endTime ? s.endTime.slice(0, 5) : ''}
                  </span>
                  <Tag color={st.color}>{st.text}</Tag>
                  <span style={{ color: 'var(--text-secondary)', flex: 1 }}>{names || '无人员'}</span>
                  <Space>
                    {s.activityId && (
                      <Button
                        size="small"
                        type="link"
                        onClick={() => navigate(`/routine/attendance?activityId=${s.activityId}`)}
                      >
                        查看考勤
                      </Button>
                    )}
                    {s.status === 'DRAFT' && (
                      <Button size="small" type="primary" icon={<PlayCircleOutlined />} onClick={() => doPublish(s.id)}>
                        发布
                      </Button>
                    )}
                    {s.status === 'PUBLISHED' && (
                      <Button size="small" icon={<StopOutlined />} onClick={() => doCancel(s.id)}>
                        取消
                      </Button>
                    )}
                    {s.source === 'AUTO' && s.status !== 'CANCELLED' && (
                      <Button size="small" icon={<ReloadOutlined />} onClick={() => doRegenerate(s.id)}>
                        重新生成
                      </Button>
                    )}
                    <Button size="small" onClick={() => openManualEdit(s)}>
                      编辑
                    </Button>
                    <Popconfirm title="确认删除该排班？" onConfirm={() => doDelete(s.id)}>
                      <Button size="small" danger icon={<DeleteOutlined />} />
                    </Popconfirm>
                  </Space>
                </div>
              )
            })}
          </>
        )}
      </GlassCard>

      {/* 一键生成弹窗 */}
      <GlassModal title="一键生成排班" open={genOpen} onCancel={() => setGenOpen(false)} width={560}>
        <Form form={genForm} layout="vertical" initialValues={{ activityMode: 'existing' }}>
          <Form.Item name="activityMode" label="活动" rules={[{ required: true, message: '请选择活动方式' }]}>
            <Radio.Group
              options={[
                { value: 'existing', label: '选择现有活动' },
                { value: 'custom', label: '自定义活动' },
              ]}
              optionType="button"
              buttonStyle="solid"
            />
          </Form.Item>
          {genMode === 'existing' ? (
            <Form.Item
              name="activityId"
              label="选择活动"
              rules={[{ required: true, message: '请选择活动' }]}
              dependencies={['activityMode']}
            >
              <Select showSearch optionFilterProp="label" options={activityOptions} placeholder="选择活动" />
            </Form.Item>
          ) : (
            <Form.Item
              name="customActivityName"
              label="自定义活动名称"
              rules={[{ required: true, message: '请输入自定义活动名称' }]}
              dependencies={['activityMode']}
            >
              <Input maxLength={100} placeholder="如 秋季招新摆摊" />
            </Form.Item>
          )}
          <Form.Item name="dateRange" label="活动日期范围" rules={[{ required: true, message: '请选择日期范围' }]}>
            <DatePicker.RangePicker style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="timeRange" label="活动时间（开始-结束）" rules={[{ required: true, message: '请设置时间段' }]}>
            <TimePicker.RangePicker format="HH:mm" style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="deptIds" label="参与部门（多选，用于读取无课表）" rules={[{ required: true, message: '请选择参与部门' }]}>
            <Select
              mode="multiple"
              allowClear
              options={depts.map((d) => ({ value: d.id, label: d.name }))}
              placeholder="可选择多个部门"
            />
          </Form.Item>
          <Form.Item name="peoplePerSlot" label="每个时间段人数（留空=不限制）">
            <InputNumber min={1} precision={0} style={{ width: 160 }} placeholder="如 10" />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="semester" label="学期" style={{ flex: 1 }}>
              <Select
                allowClear
                showSearch
                options={semesters.map((s) => ({ value: s, label: s }))}
                placeholder="选择学期（来自已上传无课表）"
              />
            </Form.Item>
            <Form.Item name="semesterStartDate" label="开学日期（用于周次匹配）" style={{ flex: 1 }}>
              <DatePicker style={{ width: '100%' }} placeholder="周一为第1周" />
            </Form.Item>
          </Space>
          <Button type="primary" block loading={generating} onClick={handleGenerate}>
            按无课表生成草稿排班
          </Button>
        </Form>
        {preview && (
          <div style={{ marginTop: 16, maxHeight: 220, overflowY: 'auto', border: '1px solid var(--border)', borderRadius: 8, padding: 12 }}>
            <div style={{ fontWeight: 600, marginBottom: 8 }}>生成结果预览</div>
            {preview.length === 0 && <div style={{ color: 'var(--text-secondary)' }}>无课表无匹配或未上传，未生成任何排班</div>}
            {preview.map((p) => (
              <div key={p.date} style={{ marginBottom: 8 }}>
                <div style={{ fontWeight: 500, fontSize: 14 }}>{p.date}：共命中 {p.count} 人</div>
                {(p.slots ?? []).map((slot, i) => (
                  <div key={`${p.date}-${i}`} style={{ marginLeft: 12, fontSize: 14, marginBottom: 4 }}>
                    <span style={{ color: 'var(--brand)', fontWeight: 500 }}>
                      {slot.label || `第${slot.period}节`}
                    </span>
                    {slot.startTime ? ` ${(slot.startTime ?? '').slice(0, 5)}-${(slot.endTime ?? '').slice(0, 5)}` : ''}
                    <span style={{ marginLeft: 8, color: 'var(--text-secondary)' }}>
                      {slot.count} 人：{slot.persons.map((x) => x.name).join('、')}
                    </span>
                  </div>
                ))}
              </div>
            ))}
          </div>
        )}
      </GlassModal>

      {/* 手动新增/编辑排班弹窗 */}
      <GlassModal
        title={manualEditing ? '编辑排班' : '新增排班'}
        open={manualOpen}
        onCancel={() => setManualOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setManualOpen(false)}>取消</Button>
            <Button type="primary" onClick={handleManualSave}>
              保存
            </Button>
          </Space>
        }
      >
        <Form form={manualForm} layout="vertical">
          {!manualEditing && (
            <div style={{ color: 'var(--text-secondary)', marginBottom: 12 }}>
              手动新增建议从活动详情页创建场次；此处仅按所选活动新增草稿排班。
            </div>
          )}
          <Form.Item name="scheduleDate" label="排班日期" rules={[{ required: true, message: '请选择日期' }]}>
            <DatePicker style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="timeRange" label="时间段" rules={[{ required: true, message: '请设置时间段' }]}>
            <TimePicker.RangePicker format="HH:mm" style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="location" label="地点">
            <Input maxLength={100} placeholder="如 学院楼 A301" />
          </Form.Item>
          <Form.Item label="排班人员" required>
            <Form.List name="persons">
              {(fields, { add, remove }) => (
                <>
                  {fields.map((field) => (
                    <Space key={field.key} align="baseline" style={{ display: 'flex', marginBottom: 8 }}>
                      <Form.Item
                        name={[field.name, 'personName']}
                        rules={[{ required: true, message: '请输入或选择姓名' }]}
                        style={{ marginBottom: 0 }}
                      >
                        <AutoComplete
                          options={personOptions}
                          placeholder="输入搜索或选择人员"
                          style={{ width: 240 }}
                          filterOption={(input, option) =>
                            ((option?.value as string) ?? '').toLowerCase().includes(input.toLowerCase())
                          }
                          onSelect={(value: string) => {
                            const matched = userOptions.find((u) => u.realName === value)
                            manualForm.setFieldValue(['persons', field.name, 'userId'], matched?.id ?? undefined)
                          }}
                        />
                      </Form.Item>
                      <Button type="text" size="small" danger onClick={() => remove(field.name)}>
                        删除
                      </Button>
                    </Space>
                  ))}
                  <Button type="dashed" block icon={<PlusOutlined />} onClick={() => add()}>
                    添加人员
                  </Button>
                </>
              )}
            </Form.List>
          </Form.Item>
        </Form>
      </GlassModal>
    </div>
  )
}