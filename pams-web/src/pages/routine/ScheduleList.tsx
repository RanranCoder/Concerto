import { useCallback, useEffect, useMemo, useState, type CSSProperties } from 'react'
import { App, AutoComplete, Button, DatePicker, Form, Input, InputNumber, Popconfirm, Select, Space, Spin, Tag } from 'antd'
import { DeleteOutlined, DownloadOutlined, EditOutlined, MinusCircleOutlined, PlusOutlined } from '@ant-design/icons'
import dayjs, { type Dayjs } from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import GlassModal from '@/components/glass/GlassModal'
import PageHeader from '@/components/glass/PageHeader'
import {
  listSchedules,
  createSchedule,
  updateSchedule,
  deleteSchedule,
  exportSchedule,
  SCHEDULE_TYPE_MAP,
  SCHEDULE_TYPE_OPTIONS,
  WEEKDAY_OPTIONS,
  WEEKDAY_NAMES,
  type SchedulePersonItem,
  type ScheduleSave,
  type ScheduleVO,
} from '@/api/schedule'
import { listActivities, type ActivityVO } from '@/api/activity'
import { listUserOptions, type UserOptionVO } from '@/api/user'
import { useAuthStore } from '@/stores/auth'

/** 周一到周五 */
const WEEK_COLS = WEEKDAY_NAMES.slice(0, 5)

/** 节次行：第1-2 / 第3-4 / 第5-6 / 第7-8 节（第几节由 sessionName 中首个数字落入的范围决定） */
const PERIOD_ROWS: Array<{ key: string; label: string; range: [number, number] }> = [
  { key: 'p1', label: '第1-2节', range: [1, 2] },
  { key: 'p3', label: '第3-4节', range: [3, 4] },
  { key: 'p5', label: '第5-6节', range: [5, 6] },
  { key: 'p7', label: '第7-8节', range: [7, 8] },
]

/** 依据 sessionName 中的首个数字判定所属节次行；无法判定返回 -1 */
function periodRowIndex(sessionName?: string | null): number {
  const m = sessionName?.match(/(\d+)/)
  if (!m) return -1
  const p = Number(m[1])
  return PERIOD_ROWS.findIndex((r) => p >= r.range[0] && p <= r.range[1])
}

const cellHeader: CSSProperties = {
  padding: 10,
  textAlign: 'center',
  border: '1px solid var(--color-border)',
  background: 'var(--color-bg-2)',
  color: 'var(--color-text-secondary)',
  fontWeight: 600,
}
const cellBody: CSSProperties = {
  padding: 10,
  verticalAlign: 'top',
  border: '1px solid var(--color-border)',
  minWidth: 120,
  minHeight: 44,
}

interface FormValues {
  scheduleType: string
  weekNo?: number
  weekday?: number
  /** 排班日期，仅用于仪表盘「本周排班」统计；可为空（旧数据无日期不参与统计） */
  scheduleDate?: Dayjs
  sessionName?: string
  location?: string
  persons?: SchedulePersonItem[]
}

/** 按 (weekday 1..5) × 节次行 组装排班网格：key=`{weekday}-{rowIdx}` -> 该格的排班列表 */
function buildCellMap(list: ScheduleVO[]): Map<string, ScheduleVO[]> {
  const map = new Map<string, ScheduleVO[]>()
  list.forEach((s) => {
    // 优先用 weekday；活动排班常只有日期，则从 scheduleDate 反推星期
    let wd = s.weekday
    if (wd == null && s.scheduleDate) wd = new Date(s.scheduleDate).getDay() || 7
    if (wd == null || wd < 1 || wd > 5) return // 周六周日/无法确定则不在周网格中显示
    const row = periodRowIndex(s.sessionName)
    if (row < 0) return
    const key = `${wd}-${row}`
    const arr = map.get(key) ?? []
    arr.push(s)
    map.set(key, arr)
  })
  return map
}

export default function ScheduleList() {
  const { message } = App.useApp()
  const user = useAuthStore((s) => s.user)
  const isMinisterOrAbove = (user?.roleLevel ?? 0) >= 3

  const [type, setType] = useState<string | undefined>()
  const [activityId, setActivityId] = useState<number | undefined>()
  const [weekNo, setWeekNo] = useState<number | undefined>()
  const [list, setList] = useState<ScheduleVO[]>([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<ScheduleVO | null>(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<FormValues>()
  // F5 选人数据源：排班人员绑定 userId，消除同名混淆（接口登录即可用，最小字段）
  const [userOptions, setUserOptions] = useState<UserOptionVO[]>([])
  const [activities, setActivities] = useState<ActivityVO[]>([])
  /** 当前点击格子里的排班（详情操作弹窗） */
  const [cellSchedules, setCellSchedules] = useState<ScheduleVO[] | null>(null)

  useEffect(() => {
    listUserOptions()
      .then((res) => setUserOptions(res ?? []))
      .catch(() => {
        /* 加载失败时仍可手输姓名 */
      })
    listActivities({ page: 1, size: 200 })
      .then((r) => setActivities(r?.records ?? []))
      .catch(() => {})
  }, [])

  const personOptions = useMemo(
    () => userOptions.map((u) => ({ value: u.realName, label: `${u.realName}（${u.deptName ?? u.roleName ?? ''}）` })),
    [userOptions],
  )

  /**
   * GlassModal 用 destroyOnHidden：关闭后 Form 卸载，重开时重新挂载。
   * 因此用 initialValues + 随 record 变化的 key 让 Form 重挂载后拿到编辑值，
   * 比在 modal 打开后再 setFieldsValue 更可靠（后者对 Form.List persons 不生效）。
   */
  const modalInitialValues: FormValues | undefined = useMemo(() => {
    if (!editing) return undefined
    return {
      scheduleType: editing.scheduleType,
      weekNo: editing.weekNo ?? undefined,
      weekday: editing.weekday ?? undefined,
      scheduleDate: editing.scheduleDate ? dayjs(editing.scheduleDate) : undefined,
      sessionName: editing.sessionName ?? undefined,
      location: editing.location ?? undefined,
      persons: (editing.persons ?? []).map((p) => ({
        userId: p.userId,
        personName: p.personName,
        isPrimary: p.isPrimary,
      })),
    }
  }, [editing])

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const res = await listSchedules({ type, weekNo, activityId })
      setList(res ?? [])
    } catch {
      /* http 拦截已提示 */
    } finally {
      setLoading(false)
    }
  }, [type, weekNo, activityId])

  useEffect(() => {
    fetchList()
  }, [fetchList])

  const cellMap = useMemo(() => buildCellMap(list), [list])

  const openCell = (wd: number, rowIdx: number) => {
    setCellSchedules(cellMap.get(`${wd}-${rowIdx}`) ?? [])
  }

  const openCreate = () => {
    setEditing(null)
    setModalOpen(true)
  }

  const openEdit = (record: ScheduleVO) => {
    setEditing(record)
    setModalOpen(true)
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const payload: ScheduleSave = {
        scheduleType: values.scheduleType,
        weekNo: values.weekNo,
        weekday: values.weekday,
        scheduleDate: values.scheduleDate ? values.scheduleDate.format('YYYY-MM-DD') : null,
        sessionName: values.sessionName,
        location: values.location,
        persons: values.persons ?? [],
      }
      if (editing) {
        await updateSchedule(editing.id, payload)
        message.success('保存成功')
      } else {
        await createSchedule(payload)
        message.success('创建成功')
      }
      setModalOpen(false)
      fetchList()
    } catch {
      /* 校验失败或 http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteSchedule(id)
      message.success('已删除')
      fetchList()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const handleExport = async () => {
    try {
      // 拦截器对 blob 响应原样返回 AxiosResponse，data 为 Blob
      const res = (await exportSchedule({ type, weekNo })) as unknown as { data: Blob }
      const url = URL.createObjectURL(res.data)
      const a = document.createElement('a')
      a.href = url
      a.download = `排班表-${type ? `${type}-` : ''}${weekNo ? `第${weekNo}周` : '全部'}.xlsx`
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      URL.revokeObjectURL(url)
      message.success('导出成功')
    } catch {
      /* http 拦截已提示 */
    }
  }

  return (
    <div>
      <PageHeader
        title="排班管理"
        description="控烟 / 值班 / 摆摊 / 档案 / 盖章 / 教学楼检查排班"
        extra={
          isMinisterOrAbove && (
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新增排班
            </Button>
          )
        }
      />

      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space wrap>
          <span style={{ color: 'var(--color-text-secondary)' }}>活动名称</span>
          <Select
            placeholder="选择活动"
            allowClear
            showSearch
            optionFilterProp="label"
            style={{ width: 200 }}
            options={activities.map((a) => ({ value: a.id, label: a.name }))}
            value={activityId}
            onChange={setActivityId}
          />
          <span style={{ color: 'var(--color-text-secondary)' }}>第几周</span>
          <InputNumber
            placeholder="周次"
            min={1}
            max={30}
            style={{ width: 120 }}
            value={weekNo}
            onChange={(v) => setWeekNo(v ?? undefined)}
          />
          <Select
            placeholder="类型"
            allowClear
            options={SCHEDULE_TYPE_OPTIONS}
            style={{ width: 140 }}
            value={type}
            onChange={setType}
          />
          <Button icon={<DownloadOutlined />} onClick={handleExport}>
            导出值班表
          </Button>
        </Space>
      </GlassCard>

      <GlassCard style={{ padding: 16 }}>
        <Spin spinning={loading}>
          {list.length === 0 && !loading ? (
            <div style={{ textAlign: 'center', color: 'var(--color-text-secondary)', padding: 32 }}>
              当前筛选条件下暂无排班，点击右上角「新增排班」创建
            </div>
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>
                    <th style={{ ...cellHeader, width: 90 }}>节次</th>
                    {WEEK_COLS.map((name) => (
                      <th key={name} style={cellHeader}>
                        {name}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {PERIOD_ROWS.map((row, rowIdx) => (
                    <tr key={row.key}>
                      <td style={{ ...cellBody, fontWeight: 600, textAlign: 'center', width: 90 }}>{row.label}</td>
                      {WEEK_COLS.map((name, wIdx) => {
                        const wd = wIdx + 1
                        const schedules = cellMap.get(`${wd}-${rowIdx}`) ?? []
                        const names = schedules.flatMap((s) => (s.persons ?? []).map((p) => p.personName))
                        return (
                          <td
                            key={name}
                            style={{
                              ...cellBody,
                              cursor: schedules.length ? 'pointer' : 'default',
                              background: schedules.length ? 'var(--color-bg-2)' : 'transparent',
                            }}
                            onClick={() => schedules.length && openCell(wd, rowIdx)}
                          >
                            <div style={{ minHeight: 44 }}>
                              {names.length > 0 ? (
                                <div style={{ lineHeight: 1.6, wordBreak: 'break-word' }}>{names.join('、')}</div>
                              ) : (
                                <span style={{ color: 'var(--color-text-tertiary)' }}>—</span>
                              )}
                              {schedules.length > 0 && (
                                <div style={{ fontSize: 11, color: 'var(--color-text-secondary)', marginTop: 2 }}>
                                  {schedules.length} 条排班
                                </div>
                              )}
                            </div>
                          </td>
                        )
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Spin>
      </GlassCard>

      {/* 格子详情：点击节次格子后，展示该格排班并可编辑/删除 */}
      <GlassModal
        title="排班详情"
        open={!!cellSchedules}
        onCancel={() => setCellSchedules(null)}
        footer={<Button onClick={() => setCellSchedules(null)}>关闭</Button>}
      >
        {(cellSchedules ?? []).length === 0 ? (
          <div style={{ textAlign: 'center', color: 'var(--color-text-secondary)', padding: 24 }}>该格暂无排班</div>
        ) : (
          (cellSchedules ?? []).map((s) => (
            <div
              key={s.id}
              style={{ border: '1px solid var(--color-border)', borderRadius: 8, padding: 12, marginBottom: 10 }}
            >
              <Space wrap style={{ marginBottom: 6 }}>
                <Tag color="red">{SCHEDULE_TYPE_MAP[s.scheduleType] ?? s.scheduleType}</Tag>
                {s.weekday != null && <span>{WEEKDAY_NAMES[s.weekday - 1]}</span>}
                {s.weekNo != null && <Tag>第{s.weekNo}周</Tag>}
                {s.sessionName && <span>{s.sessionName}</span>}
              </Space>
              {s.location && <div style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>地点：{s.location}</div>}
              {s.scheduleDate && (
                <div style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>
                  日期：{dayjs(s.scheduleDate).format('YYYY-MM-DD')}
                </div>
              )}
              <div style={{ margin: '6px 0' }}>
                {(s.persons ?? []).map((p) => p.personName).join('、') || '无人员'}
              </div>
              <Space>
                {isMinisterOrAbove && (
                  <Button
                    size="small"
                    icon={<EditOutlined />}
                    onClick={() => {
                      openEdit(s)
                      setCellSchedules(null)
                    }}
                  >
                    编辑
                  </Button>
                )}
                {isMinisterOrAbove && (
                  <Popconfirm
                    title="确认删除该排班？"
                    onConfirm={() => {
                      handleDelete(s.id)
                      setCellSchedules(null)
                    }}
                    okText="删除"
                    cancelText="取消"
                  >
                    <Button size="small" danger icon={<DeleteOutlined />}>
                      删除
                    </Button>
                  </Popconfirm>
                )}
              </Space>
            </div>
          ))
        )}
      </GlassModal>

      <GlassModal
        title={editing ? '编辑排班' : '新增排班'}
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
        <Form form={form} layout="vertical" preserve={false} initialValues={modalInitialValues} key={editing ? `edit-${editing.id}` : 'create'}>
          <Form.Item
            name="scheduleType"
            label="排班类型"
            rules={[{ required: true, message: '请选择排班类型' }]}
          >
            <Select options={SCHEDULE_TYPE_OPTIONS} placeholder="请选择" />
          </Form.Item>
          <Space size="middle" wrap>
            <Form.Item name="weekNo" label="周次" rules={[{ required: true, message: '请输入周次' }]}>
              <InputNumber min={1} max={30} style={{ width: 120 }} placeholder="如 1" />
            </Form.Item>
            <Form.Item name="weekday" label="星期" rules={[{ required: true, message: '请选择星期' }]}>
              <Select options={WEEKDAY_OPTIONS} placeholder="请选择" style={{ width: 120 }} />
            </Form.Item>
          </Space>
          <Form.Item name="scheduleDate" label="排班日期">
            <DatePicker style={{ width: '100%' }} placeholder="用于仪表盘本周排班，可留空" />
          </Form.Item>
          <Form.Item
            name="sessionName"
            label="节次 / 时间段"
            rules={[{ required: true, message: '请输入节次或时间段' }]}
          >
            <Input maxLength={50} placeholder="如 上午第1-2节 / 9:00-9:10" />
          </Form.Item>
          <Form.Item name="location" label="地点">
            <Input maxLength={100} placeholder="如 教学楼一楼大厅" />
          </Form.Item>
          <Form.Item label="值班人员" required>
            <Form.List
              name="persons"
              rules={[
                {
                  validator: async (_, persons?: SchedulePersonItem[]) => {
                    if (!persons || persons.length === 0) throw new Error('至少添加一名值班人员')
                    if (persons.some((p) => !p?.personName || !p.personName.trim())) throw new Error('人员姓名不能为空')
                  },
                },
              ]}
            >
              {(fields, { add, remove }, { errors }) => (
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
                          style={{ width: 220 }}
                          filterOption={(input, option) =>
                            ((option?.value as string) ?? '').toLowerCase().includes(input.toLowerCase())
                          }
                          onSelect={(value: string) => {
                            // F5：选中系统用户时绑定 userId；手动输入则清空绑定（宁缺勿错）
                            const matched = userOptions.find((u) => u.realName === value)
                            form.setFieldValue(['persons', field.name, 'userId'], matched?.id ?? undefined)
                          }}
                          onChange={(value: string) => {
                            const matched = userOptions.find((u) => u.realName === value)
                            if (!matched) {
                              form.setFieldValue(['persons', field.name, 'userId'], undefined)
                            }
                          }}
                        />
                      </Form.Item>
                      <Form.Item name={[field.name, 'isPrimary']} style={{ marginBottom: 0 }}>
                        <Select
                          style={{ width: 110 }}
                          options={[
                            { value: 1, label: '主班' },
                            { value: 0, label: '副班' },
                          ]}
                          placeholder="主班"
                        />
                      </Form.Item>
                      <MinusCircleOutlined onClick={() => remove(field.name)} style={{ color: 'var(--color-text-secondary)' }} />
                    </Space>
                  ))}
                  <Form.Item>
                    <Button type="dashed" onClick={() => add()} block icon={<PlusOutlined />}>
                      添加人员
                    </Button>
                    <Form.ErrorList errors={errors} />
                  </Form.Item>
                </>
              )}
            </Form.List>
          </Form.Item>
        </Form>
      </GlassModal>
    </div>
  )
}
