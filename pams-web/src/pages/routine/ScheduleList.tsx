import { useCallback, useEffect, useMemo, useState } from 'react'
import { Button, DatePicker, Form, Input, InputNumber, message, Popconfirm, Select, Space, Spin } from 'antd'
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
import { useAuthStore } from '@/stores/auth'

interface GridEntry {
  key: number
  /** 同 (weekday, sessionName) 下的全部排班，可能多条 */
  schedules: ScheduleVO[]
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

/** 按 weekday(1-7) × sessionName 组装排班网格（同一格可能有多条排班，聚合为数组） */
function buildGrid(list: ScheduleVO[]): { rows: { key: string; sessionName: string; cells: (GridEntry | null)[] }[] } {
  const cellsMap = new Map<string, GridEntry>()
  list.forEach((s) => {
    const key = `${s.weekday ?? 1}-${s.sessionName ?? ''}`
    const entry = cellsMap.get(key)
    if (entry) {
      entry.schedules.push(s)
    } else {
      cellsMap.set(key, { key: s.id, schedules: [s] })
    }
  })
  const rowKeys = Array.from(new Set(list.map((s) => s.sessionName ?? '')))
  const rows = rowKeys.map((sessionName) => ({
    key: `row-${sessionName}`,
    sessionName,
    cells: WEEKDAY_NAMES.map((_, idx) => cellsMap.get(`${idx + 1}-${sessionName}`) ?? null),
  }))
  return { rows }
}

export default function ScheduleList() {
  const user = useAuthStore((s) => s.user)
  const isMinisterOrAbove = (user?.roleLevel ?? 0) >= 3

  const [type, setType] = useState<string | undefined>()
  const [weekNo, setWeekNo] = useState<number | undefined>()
  const [list, setList] = useState<ScheduleVO[]>([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<ScheduleVO | null>(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<FormValues>()

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
      const res = await listSchedules({ type, weekNo })
      setList(res ?? [])
    } catch {
      /* http 拦截已提示 */
    } finally {
      setLoading(false)
    }
  }, [type, weekNo])

  useEffect(() => {
    fetchList()
  }, [fetchList])

  const grid = useMemo(() => buildGrid(list), [list])

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
          <Select
            placeholder="类型筛选"
            allowClear
            options={SCHEDULE_TYPE_OPTIONS}
            style={{ width: 160 }}
            value={type}
            onChange={setType}
          />
          <InputNumber
            placeholder="周次"
            min={1}
            max={30}
            style={{ width: 120 }}
            value={weekNo}
            onChange={(v) => setWeekNo(v ?? undefined)}
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
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                <th style={{ padding: 8, textAlign: 'left', borderBottom: '1px solid var(--color-border)', color: 'var(--color-text-secondary)' }}>
                  节次 / 时间段
                </th>
                {WEEKDAY_NAMES.map((name) => (
                  <th
                    key={name}
                    style={{ padding: 8, textAlign: 'center', borderBottom: '1px solid var(--color-border)', color: 'var(--color-text-secondary)' }}
                  >
                    {name}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {grid.rows.map((row) => (
                <tr key={row.key}>
                  <td
                    style={{
                      padding: 8,
                      fontWeight: 500,
                      verticalAlign: 'top',
                      borderBottom: '1px solid var(--color-border)',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {row.sessionName}
                  </td>
                  {row.cells.map((entry, idx) => (
                    <td
                      key={`${row.key}-${idx}`}
                      style={{
                        padding: 8,
                        verticalAlign: 'top',
                        borderBottom: '1px solid var(--color-border)',
                        minWidth: 120,
                        minHeight: 48,
                      }}
                    >
                      {entry && (
                        <div style={{ minHeight: 48 }}>
                          {entry.schedules.map((schedule) => {
                            const primaryNames = (schedule.persons ?? [])
                              .filter((p) => p.isPrimary !== 0)
                              .map((p) => p.personName)
                            const deputyNames = (schedule.persons ?? [])
                              .filter((p) => p.isPrimary === 0)
                              .map((p) => p.personName)
                            return (
                              <div key={schedule.id} style={{ marginBottom: 8, paddingBottom: 8, borderBottom: '1px dashed var(--color-border)' }}>
                                <div style={{ fontSize: 12, color: 'var(--color-red)', marginBottom: 2 }}>
                                  {SCHEDULE_TYPE_MAP[schedule.scheduleType] ?? schedule.scheduleType}
                                </div>
                                {schedule.scheduleDate && (
                                  <div style={{ fontSize: 11, color: 'var(--color-text-secondary)', marginBottom: 2 }}>
                                    {dayjs(schedule.scheduleDate).format('YYYY-MM-DD')}
                                  </div>
                                )}
                                {primaryNames.length > 0 && (
                                  <div style={{ fontWeight: 500, color: 'var(--color-text)' }}>{primaryNames.join('、')}</div>
                                )}
                                {deputyNames.length > 0 && (
                                  <div style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>{deputyNames.join('、')}</div>
                                )}
                                <Space size="small" style={{ marginTop: 4 }}>
                                  {isMinisterOrAbove && (
                                    <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(schedule)}>
                                      编辑
                                    </Button>
                                  )}
                                  {isMinisterOrAbove && (
                                    <Popconfirm
                                      title="确认删除该排班？"
                                      onConfirm={() => handleDelete(schedule.id)}
                                      okText="删除"
                                      cancelText="取消"
                                    >
                                      <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                                        删除
                                      </Button>
                                    </Popconfirm>
                                  )}
                                </Space>
                              </div>
                            )
                          })}
                        </div>
                      )}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        )}
        </Spin>
      </GlassCard>

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
                        rules={[{ required: true, message: '请输入姓名' }]}
                        style={{ marginBottom: 0 }}
                      >
                        <Input placeholder="人员姓名" style={{ width: 180 }} />
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
