import FormSurface from '@/components/form/FormSurface'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  App,
  Button,
  DatePicker,
  Form,
  Input,
  InputNumber,
  Select,
  Segmented,
  Space,
  Spin,
} from 'antd'
import {
  DownloadOutlined,
  PlusOutlined,
  SearchOutlined,
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import dayjs, { type Dayjs } from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import PageHeader from '@/components/glass/PageHeader'
import RoutineLayout, {
  RoutineNote,
  RoutineSummary,
} from '@/components/routine/RoutineLayout'
import PeopleFields from '@/components/routine/PeopleFields'
import {
  ScheduleDetail,
  ScheduleGrid,
  ScheduleTable,
  WeekControls,
} from '@/components/routine/ScheduleViews'
import {
  listSchedules,
  createSchedule,
  updateSchedule,
  deleteSchedule,
  exportSchedule,
  SCHEDULE_TYPE_OPTIONS,
  type ScheduleVO,
  type ScheduleSave,
} from '@/api/schedule'
import { listActivities, type ActivityVO } from '@/api/activity'
import { listUserOptions, type UserOptionVO } from '@/api/user'
import { getScheduleConfigs, type ScheduleConfigVO } from '@/api/courseSchedule'
import { useAuthStore } from '@/stores/auth'
import {
  FALLBACK_PERIODS,
  inCalendarWeek,
  monday,
  periodFor,
  weekday,
  WEEK_DAYS,
  downloadBlob,
} from '@/utils/routine'

interface FormValues extends Omit<ScheduleSave, 'scheduleDate'> {
  scheduleDate?: Dayjs
}
export default function ScheduleList() {
  const { message } = App.useApp(),
    navigate = useNavigate()
  const permissions = useAuthStore((s) => s.user?.permissions) ?? [],
    canManage = permissions.includes('schedule:manage')
  const [rows, setRows] = useState<ScheduleVO[]>([]),
    [loading, setLoading] = useState(false),
    [saving, setSaving] = useState(false)
  const [activities, setActivities] = useState<ActivityVO[]>([]),
    [users, setUsers] = useState<UserOptionVO[]>([]),
    [periods, setPeriods] = useState<ScheduleConfigVO[]>(FALLBACK_PERIODS)
  const [start, setStart] = useState(monday),
    [weekNo, setWeekNo] = useState<number>(),
    [type, setType] = useState<string>(),
    [query, setQuery] = useState(''),
    [mode, setMode] = useState('week')
  const [detail, setDetail] = useState<ScheduleVO | null>(null),
    [editing, setEditing] = useState<ScheduleVO | null>(null),
    [formOpen, setFormOpen] = useState(false),
    [defaults, setDefaults] = useState<FormValues>()
  const [form] = Form.useForm<FormValues>()
  const requestVersion = useRef(0)
  const load = useCallback(async () => {
    const version = ++requestVersion.current
    setLoading(true)
    try {
      const result = await listSchedules({ type, weekNo })
      if (version === requestVersion.current)
        setRows((result ?? []).filter((s) => s.scheduleType !== 'ACTIVITY'))
    } catch {
      if (version === requestVersion.current) setRows([])
    } finally {
      if (version === requestVersion.current) setLoading(false)
    }
  }, [type, weekNo])
  useEffect(() => {
    void load()
  }, [load])
  useEffect(() => {
    listActivities({ page: 1, size: 200 })
      .then((r) => setActivities(r.records ?? []))
      .catch(() => {})
    listUserOptions()
      .then(setUsers)
      .catch(() => {})
    getScheduleConfigs()
      .then((r) => {
        if (r.length) setPeriods(r)
      })
      .catch(() => {})
  }, [])
  const filtered = useMemo(
    () =>
      rows.filter((s) =>
        `${s.location ?? ''} ${s.persons?.map((p) => p.personName).join(' ') ?? ''}`.includes(
          query.trim(),
        ),
      ),
    [rows, query],
  )
  const weekRows = filtered.filter((s) => inCalendarWeek(s, start, weekNo)),
    unplaced = weekRows.filter(
      (s) => !weekday(s) || weekday(s)! > 5 || periodFor(s, periods) < 0,
    )
  const todayRows = filtered.filter(
    (s) => s.scheduleDate === dayjs().format('YYYY-MM-DD'),
  )
  const peopleCount = new Set(
    weekRows.flatMap(
      (s) =>
        s.persons?.map((p) =>
          p.userId ? `id:${p.userId}` : `name:${p.personName}`,
        ) ?? [],
    ),
  ).size
  const openCreate = (day?: number, period?: ScheduleConfigVO) => {
    setEditing(null)
    setDefaults({
      scheduleType: type ?? 'CLASS_DUTY',
      weekNo,
      weekday: day,
      scheduleDate: day ? start.add(day - 1, 'day') : undefined,
      sessionName: period?.label,
      persons: [{ personName: '', isPrimary: 1 }],
    })
    setFormOpen(true)
  }
  const openEdit = (s: ScheduleVO) => {
    setEditing(s)
    setDetail(null)
    setDefaults({
      scheduleType: s.scheduleType,
      activityId: s.activityId,
      weekNo: s.weekNo,
      weekday: weekday(s),
      scheduleDate: s.scheduleDate ? dayjs(s.scheduleDate) : undefined,
      sessionName: s.sessionName,
      location: s.location,
      notes: s.notes,
      persons: s.persons.map((p) => ({
        userId: p.userId,
        personName: p.personName,
        isPrimary: p.isPrimary,
      })),
    })
    setFormOpen(true)
  }
  const save = async (v: FormValues) => {
    setSaving(true)
    try {
      const payload: ScheduleSave = {
        ...v,
        activityId: editing?.activityId ?? v.activityId,
        scheduleDate: v.scheduleDate?.format('YYYY-MM-DD') ?? null,
        persons: v.persons?.map((p) => ({
          ...p,
          personName: p.personName.trim(),
        })),
      }
      if (editing) await updateSchedule(editing.id, payload)
      else await createSchedule(payload)
      setFormOpen(false)
      if (v.scheduleDate) setStart(monday(v.scheduleDate))
      setWeekNo(v.weekNo ?? undefined)
      setType(v.scheduleType)
      setQuery('')
      message.success('排班已保存')
      await load()
    } catch {
      /* 统一拦截器展示请求错误。 */
    } finally {
      setSaving(false)
    }
  }
  const remove = async (s: ScheduleVO) => {
    try {
      await deleteSchedule(s.id)
      setDetail(null)
      message.success('班次已删除')
      await load()
    } catch {}
  }
  const exportRows = async () => {
    try {
      const res = await exportSchedule({ type, weekNo })
      downloadBlob(
        res.data,
        `排班表-${weekNo ? `第${weekNo}周` : '全部教学周'}.xlsx`,
      )
    } catch {}
  }
  return (
    <RoutineLayout>
      <PageHeader
        title="排班"
        description="按教学周安排例行事务，明确每班责任人与协作人员。"
        extra={
          canManage && (
            <>
              <Button icon={<DownloadOutlined />} onClick={exportRows}>
                导出值班表
              </Button>
              <Button
                type="primary"
                icon={<PlusOutlined />}
                onClick={() => openCreate()}
              >
                新建排班
              </Button>
            </>
          )
        }
      />
      <RoutineSummary
        items={[
          { label: '本周班次', value: weekRows.length, note: '当前筛选' },
          { label: '参与成员', value: peopleCount, note: '去重人数' },
          {
            label: '今日班次',
            value: todayRows.length,
            note: dayjs().format('MM 月 DD 日'),
          },
          {
            label: '主班已指定',
            value: weekRows.filter((s) =>
              s.persons.some((p) => p.isPrimary === 1),
            ).length,
            note: '责任到人',
          },
        ]}
      />
      <div className="routine-workspace">
        <GlassCard>
          <WeekControls
            start={start}
            onChange={(d) => {
              setStart(d)
              setWeekNo(undefined)
            }}
            extra={
              <Segmented
                aria-label="排班视图"
                value={mode}
                onChange={(v) => setMode(String(v))}
                options={[
                  { value: 'week', label: '周视图' },
                  { value: 'list', label: '列表' },
                ]}
              />
            }
          />
          <div className="routine-toolbar">
            <label className="routine-filter">
              排班类型
              <Select
                aria-label="排班类型"
                placeholder="全部类型"
                allowClear
                options={SCHEDULE_TYPE_OPTIONS}
                value={type}
                onChange={setType}
              />
            </label>
            <label className="routine-filter">
              教学周
              <InputNumber
                aria-label="教学周"
                min={1}
                max={30}
                value={weekNo}
                placeholder="全部周次"
                onChange={(n) => {
                  setWeekNo(n ?? undefined)
                  const match = rows.find(
                    (s) => s.weekNo === n && s.scheduleDate,
                  )
                  if (match?.scheduleDate)
                    setStart(monday(dayjs(match.scheduleDate)))
                }}
              />
            </label>
            <label className="routine-filter wide">
              查找人员 / 地点
              <Input
                aria-label="查找排班"
                prefix={<SearchOutlined />}
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                allowClear
                placeholder="姓名或值班地点"
              />
            </label>
            <Button
              type="link"
              onClick={() => {
                setType(undefined)
                setWeekNo(undefined)
                setQuery('')
                setStart(monday())
              }}
            >
              重置
            </Button>
          </div>
          <div className="routine-caption">
            <div className="routine-legend">
              <span>例行值班</span>
              <span className="document">档案 / 盖章</span>
            </div>
            <span>
              {mode === 'week'
                ? '日期对应本周；无日期数据按所选教学周显示'
                : '当前筛选下的全部班次'}
            </span>
          </div>
          <Spin spinning={loading}>
            {mode === 'week' ? (
              <>
                <ScheduleGrid
                  rows={weekRows}
                  periods={periods}
                  start={start}
                  activities={activities}
                  onOpen={setDetail}
                  onCreate={canManage ? openCreate : undefined}
                />
                {unplaced.length > 0 && (
                  <>
                    <div className="routine-caption">
                      其他时段 / 周末班次（{unplaced.length}）
                    </div>
                    <ScheduleTable
                      rows={unplaced}
                      activities={activities}
                      onOpen={setDetail}
                      onEdit={canManage ? openEdit : undefined}
                    />
                  </>
                )}
              </>
            ) : (
              <ScheduleTable
                rows={filtered}
                activities={activities}
                onOpen={setDetail}
                onEdit={canManage ? openEdit : undefined}
              />
            )}
          </Spin>
          <div className="routine-table-foot">
            <span>
              {mode === 'week'
                ? `本周 ${weekRows.length} 个班次`
                : `共 ${filtered.length} 个班次`}
            </span>
            {mode === 'week' && (
              <Button type="link" onClick={() => setMode('list')}>
                查看全部 {filtered.length} 条记录
              </Button>
            )}
          </div>
        </GlassCard>
        <aside className="routine-side">
          <GlassCard className="routine-pad routine-today">
            <h3>
              今天的安排 <small>{dayjs().format('MM / DD')}</small>
            </h3>
            {todayRows.map((s) => (
              <div className="routine-agenda" key={s.id}>
                <span className="routine-agenda-time">
                  {s.startTime?.slice(0, 5) || s.sessionName || '待定'}
                </span>
                <div>
                  <strong>
                    {
                      SCHEDULE_TYPE_OPTIONS.find(
                        (o) => o.value === s.scheduleType,
                      )?.label
                    }
                  </strong>
                  <small>{s.location || '地点未设置'}</small>
                  <small>{s.persons.map((p) => p.personName).join('、')}</small>
                  <Button type="link" onClick={() => setDetail(s)}>
                    查看班次
                  </Button>
                </div>
              </div>
            ))}
            {!todayRows.length && <p>当前筛选下今天没有排班。</p>}
          </GlassCard>
          <GlassCard className="routine-pad">
            <h3>排班依据</h3>
            {permissions.includes('schedule:free_table') && (
              <div className="routine-side-link">
                <strong>核对无课周次</strong>
                <small>查看成员在对应教学周的可参与时段</small>
                <Button
                  type="link"
                  onClick={() => navigate('/routine/free-schedules')}
                >
                  查看无课表
                </Button>
              </div>
            )}
            <div className="routine-side-link">
              <strong>活动筹备岗位</strong>
              <small>活动排班的草稿、发布与签到独立管理</small>
              <Button
                type="link"
                onClick={() => navigate('/routine/activity-schedules')}
              >
                进入活动排班
              </Button>
            </div>
            <RoutineNote>
              导出按排班类型和教学周导出，不包含本页人员关键词筛选。
            </RoutineNote>
          </GlassCard>
        </aside>
      </div>
      <ScheduleDetail
        shift={detail}
        activities={activities}
        onClose={() => setDetail(null)}
        onEdit={canManage ? openEdit : undefined}
        onDelete={canManage ? remove : undefined}
      />
      <FormSurface
        mode="drawer"
        description="设置班次时间、地点和人员，保存后返回排班表。"
        title={editing ? '编辑班次' : '新建排班'}
        className="routine-drawer"
        width="min(540px, 100vw)"
        open={formOpen}
        onCancel={() => setFormOpen(false)}
        destroyOnHidden
        footer={
          <Space>
            <Button onClick={() => setFormOpen(false)}>取消</Button>
            <Button
              type="primary"
              loading={saving}
              onClick={() => form.submit()}
            >
              保存排班
            </Button>
          </Space>
        }
      >
        <Form
          form={form}
          layout="vertical"
          preserve={false}
          key={`${editing?.id ?? 'new'}-${defaults?.scheduleDate?.format('YYYY-MM-DD') ?? ''}-${defaults?.sessionName ?? ''}`}
          initialValues={defaults}
          onFinish={save}
        >
          <Form.Item
            name="scheduleType"
            label="排班类型"
            rules={[{ required: true }]}
          >
            <Select options={SCHEDULE_TYPE_OPTIONS} />
          </Form.Item>
          <div className="routine-form-grid">
            <Form.Item
              name="weekNo"
              label="教学周"
              rules={[{ required: true, message: '请输入教学周' }]}
            >
              <InputNumber min={1} max={30} />
            </Form.Item>
            <Form.Item name="weekday" label="星期" rules={[{ required: true }]}>
              <Select
                options={WEEK_DAYS.map((d, i) => ({ value: i + 1, label: d }))}
              />
            </Form.Item>
          </div>
          <Form.Item name="scheduleDate" label="排班日期">
            <DatePicker
              style={{ width: '100%' }}
              onChange={(d) => {
                if (d) form.setFieldValue('weekday', ((d.day() + 6) % 7) + 1)
              }}
            />
          </Form.Item>
          <Form.Item
            name="sessionName"
            label="节次 / 时间段"
            rules={[{ required: true }]}
          >
            <Input maxLength={50} placeholder="如 第1-2节 / 09:00-09:10" />
          </Form.Item>
          <Form.Item name="location" label="值班地点">
            <Input maxLength={100} />
          </Form.Item>
          <PeopleFields form={form} users={users} />
          <Form.Item name="notes" label="工作备注">
            <Input.TextArea rows={3} maxLength={255} />
          </Form.Item>
          <RoutineNote>
            日期用于日历与工作台统计；未设置日期时可按教学周查看排班。
          </RoutineNote>
        </Form>
      </FormSurface>
    </RoutineLayout>
  )
}
