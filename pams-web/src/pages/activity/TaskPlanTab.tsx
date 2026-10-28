import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Alert,
  App,
  Button,
  Empty,
  Input,
  Radio,
  Select,
  Segmented,
  Slider,
  Space,
  Spin,
} from 'antd'
import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import type { FormInstance } from 'antd'
import TaskEditorForm, {
  type TaskEditorValues as Values,
} from './TaskEditorForm'
import FormSurface from '@/components/form/FormSurface'
import GanttChart, { type GanttZoom } from '@/components/gantt/GanttChart'
import { timelineStart } from '@/components/gantt/gantt.scale'
import {
  dateLabel,
  hasDependencyConflict,
  isOverdue,
  isScheduled,
  taskDateLabel,
  taskStatus,
  taskTone,
  todayStr,
  wouldCycle,
} from '@/components/gantt/gantt.utils'
import { useHasPerm } from '@/components/glass/RequireRole'
import { listDepts, type DeptVO } from '@/api/dept'
import {
  deleteTask,
  listTasks,
  previewTaskPlan,
  saveTaskPlan,
  taskSave,
  toGanttTask,
  undoTaskPlan,
  updateTaskProgress,
  type TaskPlanPreview,
  type TaskPlanRequest,
  type TaskPlanUndo,
  type TaskSave,
  type TaskVO,
} from '@/api/task'
import type { ActivityVO } from '@/api/activity'
import '@/styles/task-plan.css'

interface Props {
  activity: ActivityVO
  active: boolean
  onChanged: (tasks: TaskVO[]) => void
  onTab: (key: string) => void
  focusTaskId?: number
  onTaskOpened?: () => void
}

type Filter = 'all' | 'late' | 'conflict' | 'unscheduled' | 'risk'
type Undo =
  | { kind: 'plan'; request: TaskPlanUndo }
  | { kind: 'progress'; id: number; before: number; revision: number }
const related = [
  ['basic', '基本信息', ''],
  ['plan', '策划书', 'plan:view'],
  ['agenda', '议程', 'agenda:view'],
  ['signin', '签到', 'checkin:view'],
  ['materials', '材料', 'material:view'],
  ['articles', '推文', 'content:view'],
]
function errorText(error: unknown): string {
  const response = (error as { response?: { data?: { message?: string } } })
    ?.response
  return (
    response?.data?.message ||
    (error instanceof Error ? error.message : '操作失败，请重试')
  )
}

export default function TaskPlanTab({
  activity,
  active,
  onChanged,
  onTab,
  focusTaskId,
  onTaskOpened,
}: Props) {
  const { message, modal } = App.useApp(),
    hasPerm = useHasPerm()
  const archived = activity.status === 'ARCHIVED'
  const canManage = hasPerm('task:manage') && !archived
  const canProgress =
    (hasPerm('task:progress') || hasPerm('task:manage')) && !archived
  const [tasks, setTasks] = useState<TaskVO[]>([]),
    [depts, setDepts] = useState<DeptVO[]>([])
  const [loading, setLoading] = useState(true),
    [failed, setFailed] = useState(false),
    [deptFailed, setDeptFailed] = useState(false)
  const [query, setQuery] = useState(''),
    [department, setDepartment] = useState<string | number>('all'),
    [filter, setFilter] = useState<Filter>('all')
  const [view, setView] = useState<'list' | 'gantt'>(() =>
    window.matchMedia('(max-width:700px)').matches ? 'list' : 'gantt',
  )
  const [zoom, setZoom] = useState<GanttZoom>('day'),
    [customPixelsPerDay, setCustomPixelsPerDay] = useState(42),
    [anchor, setAnchor] = useState<string | null>(null)
  const [selected, setSelected] = useState<TaskVO | null>(null),
    [drawer, setDrawer] = useState<'detail' | 'edit' | null>(null)
  const [initial, setInitial] = useState<Values>(),
    [formKey, setFormKey] = useState(0),
    [dirty, setDirty] = useState(false),
    [progress, setProgress] = useState(0)
  const [busy, setBusy] = useState(false),
    [formError, setFormError] = useState(''),
    [saved, setSaved] = useState(''),
    [undo, setUndo] = useState<Undo | null>(null)
  const [pending, setPending] = useState<{
      request: TaskPlanRequest
      preview: TaskPlanPreview
    } | null>(null),
    [cascade, setCascade] = useState(false)
  const form = useRef<FormInstance<Values>>(null)
  const request = useRef(0),
    mounted = useRef(true),
    changed = useRef(onChanged),
    opened = useRef(onTaskOpened),
    mutation = useRef(false)
  changed.current = onChanged
  opened.current = onTaskOpened
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      request.current++
    }
  }, [])
  const accept = useCallback((rows: TaskVO[]) => {
    request.current++
    setTasks(rows)
    setLoading(false)
    setFailed(false)
    changed.current(rows)
  }, [])
  const refresh = useCallback(async () => {
    const current = ++request.current
    setLoading(true)
    try {
      const rows = await listTasks(activity.id)
      if (mounted.current && current === request.current) {
        setTasks(rows)
        setFailed(false)
        changed.current(rows)
      }
    } catch {
      if (mounted.current && current === request.current) setFailed(true)
    } finally {
      if (mounted.current && current === request.current) setLoading(false)
    }
  }, [activity.id])
  const refreshDepts = useCallback(async () => {
    try {
      const rows = await listDepts()
      if (mounted.current) {
        setDepts(rows)
        setDeptFailed(false)
      }
    } catch {
      if (mounted.current) setDeptFailed(true)
    }
  }, [])
  useEffect(() => {
    void refreshDepts()
  }, [refreshDepts])
  useEffect(() => {
    if (active) void refresh()
  }, [active, refresh])
  useEffect(() => {
    if (!active || !focusTaskId || loading) return
    const task = tasks.find((t) => t.id === focusTaskId)
    if (task) {
      setSelected(task)
      setProgress(task.progress ?? 0)
      setDrawer('detail')
      setDirty(false)
      setFormError('')
      opened.current?.()
    }
  }, [active, focusTaskId, loading, tasks])
  useEffect(() => {
    if (!drawer || !dirty) return
    const warn = (e: BeforeUnloadEvent) => {
      e.preventDefault()
      e.returnValue = ''
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [drawer, dirty])

  const deptName = (task: TaskVO) =>
    task.deptId == null
      ? '未分配部门'
      : depts.find((d) => d.id === task.deptId)?.name || '部门信息待补充'
  const ganttTasks = useMemo(
    () =>
      tasks.map((t) =>
        toGanttTask(
          t,
          t.deptId == null
            ? undefined
            : depts.find((d) => d.id === t.deptId)?.name,
        ),
      ),
    [tasks, depts],
  )
  const today = todayStr(),
    done = tasks.filter((t) => (t.progress ?? 0) >= 100).length
  const counts = {
    all: tasks.length,
    late: tasks.filter((t) => isOverdue(t, today)).length,
    conflict: tasks.filter((t) => hasDependencyConflict(t, tasks)).length,
    unscheduled: tasks.filter((t) => !isScheduled(t)).length,
  }
  const visible = tasks.filter(
    (t) =>
      (department === 'all' ||
        (department === 'none' && t.deptId == null) ||
        t.deptId === department) &&
      `${t.name} ${t.assignee ?? ''}`
        .toLowerCase()
        .includes(query.trim().toLowerCase()) &&
      {
        all: true,
        late: isOverdue(t, today),
        conflict: hasDependencyConflict(t, tasks),
        unscheduled: !isScheduled(t),
        risk:
          isOverdue(t, today) ||
          hasDependencyConflict(t, tasks) ||
          !isScheduled(t),
      }[filter],
  )
  const hasFilter = query || department !== 'all' || filter !== 'all'
  const reset = () => {
    setQuery('')
    setDepartment('all')
    setFilter('all')
  }
  const stateLabel = (task: TaskVO) => (
    <span className={`task-state ${taskTone(task, tasks, today)}`}>
      {taskStatus(task, today)}
      {hasDependencyConflict(task, tasks) ? ' · 前置冲突' : ''}
    </span>
  )
  const guard = (action: () => void) => {
    if (busy) return
    if (dirty)
      modal.confirm({
        title: '放弃未保存的修改？',
        content: '离开会丢失本次输入，已保存的任务不受影响。',
        okText: '放弃修改',
        cancelText: '继续编辑',
        onOk: action,
      })
    else action()
  }
  const close = () =>
    guard(() => {
      setDrawer(null)
      setDirty(false)
      setFormError('')
    })
  const showDetail = (task: TaskVO) =>
    guard(() => {
      setSelected(task)
      setProgress(task.progress ?? 0)
      setDrawer('detail')
      setDirty(false)
      setFormError('')
    })
  const edit = (task: TaskVO | null) =>
    guard(() => {
      if (!canManage) return
      setSelected(task)
      setInitial({
        name: task?.name || '',
        deptId:
          task?.deptId ??
          (typeof department === 'number' ? department : undefined),
        assignee: task?.assignee ?? '',
        assigneeUserId: task?.assigneeUserId ?? undefined,
        startDate: task?.startDate ? dayjs(task.startDate) : undefined,
        endDate: task?.endDate ? dayjs(task.endDate) : undefined,
        dependsOn: task?.dependsOn ?? undefined,
        isMilestone: task?.isMilestone === 1,
        progress: task?.progress ?? 0,
        description: task?.description ?? '',
        priority: task?.priority ?? 0,
      })
      setFormKey((k) => k + 1)
      setDrawer('edit')
      setDirty(false)
      setFormError('')
    })
  const navigateRelated = (key: string) =>
    guard(() => {
      setDrawer(null)
      setDirty(false)
      onTab(key)
    })
  const finish = (rows: TaskVO[], undoValue: Undo, text: string) => {
    accept(rows)
    setDrawer(null)
    setDirty(false)
    setPending(null)
    setUndo(undoValue)
    setSaved(text)
    setFormError('')
  }
  const save = async (proposal: TaskPlanRequest) => {
    if (mutation.current || !canManage) return
    mutation.current = true
    setBusy(true)
    setFormError('')
    try {
      const result = await saveTaskPlan(proposal)
      if (mounted.current)
        finish(
          result.tasks,
          { kind: 'plan', request: result.undo },
          proposal.id ? '任务安排已保存' : '任务已创建',
        )
    } catch (error) {
      if (mounted.current) {
        setFormError(errorText(error))
        setPending(null)
        if (!drawer) message.error(errorText(error))
      }
    } finally {
      mutation.current = false
      if (mounted.current) setBusy(false)
    }
  }
  const propose = async (proposal: TaskPlanRequest, alwaysConfirm = false) => {
    if (mutation.current || !canManage) return
    mutation.current = true
    setBusy(true)
    setFormError('')
    try {
      const preview = await previewTaskPlan(proposal)
      if (!mounted.current) return
      const withToken = { ...proposal, previewToken: preview.token }
      if (
        alwaysConfirm ||
        preview.impacts.length ||
        hasDependencyConflict(preview.candidate, tasks)
      ) {
        setCascade(false)
        setPending({ request: withToken, preview })
      } else {
        mutation.current = false
        await save(withToken)
      }
    } catch (error) {
      if (mounted.current) {
        const text = errorText(error)
        setFormError(text)
        if (!drawer) message.error(text)
      }
    } finally {
      mutation.current = false
      if (mounted.current) setBusy(false)
    }
  }
  const submitEdit = async () => {
    try {
      if (!form.current) return
      const values = await form.current.validateFields()
      if (
        !!values.startDate !==
        !!(values.isMilestone ? values.startDate : values.endDate)
      ) {
        setFormError('开始和结束日期需同时填写，或同时留空。')
        return
      }
      if (
        values.startDate &&
        values.endDate &&
        !values.isMilestone &&
        values.endDate.isBefore(values.startDate, 'day')
      ) {
        setFormError('结束日期不能早于开始日期。')
        return
      }
      if (wouldCycle(tasks, selected?.id, values.dependsOn ?? null)) {
        setFormError('前置关系形成循环，请选择其他任务。')
        return
      }
      const payload: TaskSave = {
        activityId: activity.id,
        name: values.name.trim(),
        deptId: values.deptId ?? null,
        assignee: values.assignee?.trim() || null,
        assigneeUserId: values.assigneeUserId ?? null,
        startDate: values.startDate?.format('YYYY-MM-DD') ?? null,
        endDate:
          (values.isMilestone ? values.startDate : values.endDate)?.format(
            'YYYY-MM-DD',
          ) ?? null,
        dependsOn: values.dependsOn ?? null,
        isMilestone: values.isMilestone ? 1 : 0,
        progress: values.progress,
        description: values.description || null,
        priority: values.priority ?? 0,
        expectedRevision: selected?.revision,
      }
      await propose({ id: selected?.id, task: payload })
    } catch {
      /* Form validation stays next to the invalid field. */
    }
  }
  const saveProgress = async () => {
    if (!selected || !canProgress || mutation.current) return
    mutation.current = true
    setBusy(true)
    setFormError('')
    try {
      const result = await updateTaskProgress(
        selected.id,
        progress,
        selected.revision,
      )
      if (mounted.current)
        finish(
          tasks.map((t) => (t.id === result.id ? result : t)),
          {
            kind: 'progress',
            id: result.id,
            before: selected.progress ?? 0,
            revision: result.revision,
          },
          '执行进度已保存',
        )
    } catch (error) {
      if (mounted.current) setFormError(errorText(error))
    } finally {
      mutation.current = false
      if (mounted.current) setBusy(false)
    }
  }
  const remove = () => {
    if (!selected || !canManage) return
    const dependent = tasks.filter((t) => t.dependsOn === selected.id)
    if (dependent.length) {
      setFormError(
        `无法删除：${dependent.map((t) => t.name).join('、')} 仍依赖此任务，请先解除前置关系。`,
      )
      return
    }
    modal.confirm({
      title: '删除任务',
      content: `确认删除「${selected.name}」？删除后可撤销。`,
      okText: '删除任务',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        if (mutation.current) return
        mutation.current = true
        setBusy(true)
        try {
          const result = await deleteTask(selected.id, selected.revision)
          if (mounted.current)
            finish(
              result.tasks,
              { kind: 'plan', request: result.undo },
              '任务已删除',
            )
        } catch (error) {
          if (mounted.current) setFormError(errorText(error))
          throw error
        } finally {
          mutation.current = false
          if (mounted.current) setBusy(false)
        }
      },
    })
  }
  const undoLast = async () => {
    if (!undo || mutation.current) return
    mutation.current = true
    setBusy(true)
    try {
      if (undo.kind === 'plan') {
        const result = await undoTaskPlan(undo.request)
        if (mounted.current) accept(result.tasks)
      } else {
        const result = await updateTaskProgress(
          undo.id,
          undo.before,
          undo.revision,
        )
        if (mounted.current)
          accept(tasks.map((t) => (t.id === result.id ? result : t)))
      }
      if (mounted.current) {
        setUndo(null)
        setSaved('已撤销上次任务修改')
      }
    } catch (error) {
      if (mounted.current) message.error(errorText(error))
    } finally {
      mutation.current = false
      if (mounted.current) setBusy(false)
    }
  }
  const initialDate = timelineStart({
    tasks,
    zoom,
    anchor,
    activityDate: activity.startDate,
    today,
  })
  const parent = selected
    ? tasks.find((t) => t.id === selected.dependsOn)
    : undefined
  const children = selected
    ? tasks.filter((t) => t.dependsOn === selected.id)
    : []
  return (
    <section className="task-plan" aria-label="活动任务计划">
      <header className="task-plan-actions">
        <Button
          aria-label="刷新任务"
          icon={<ReloadOutlined aria-hidden />}
          onClick={() => void refresh()}
          loading={loading}
          disabled={busy}
        >
          刷新任务
        </Button>
        {canManage && (
          <Button
            type="primary"
            aria-label="新建任务"
            icon={<PlusOutlined aria-hidden />}
            disabled={busy || loading || failed}
            onClick={() => edit(null)}
          >
            新建任务
          </Button>
        )}
      </header>
      <div className="task-plan-summary">
        <div className="task-plan-completion">
          <strong>
            {done}
            <small> / {tasks.length} 项完成</small>
          </strong>
          <div className="task-mini-progress">
            <i
              style={{
                width: `${tasks.length ? (done / tasks.length) * 100 : 0}%`,
              }}
            />
          </div>
          <span>
            {tasks.length ? Math.round((done / tasks.length) * 100) : 0}%
          </span>
        </div>
        <div className="task-plan-filters" aria-label="按任务情况筛选">
          {(
            [
              ['all', '全部'],
              ['late', '逾期'],
              ['conflict', '前置冲突'],
              ['unscheduled', '未排期'],
            ] as const
          ).map(([key, label]) => (
            <button
              key={key}
              aria-pressed={filter === key}
              onClick={() => setFilter(key)}
            >
              {label}
              <b>{counts[key]}</b>
            </button>
          ))}
        </div>
      </div>
      <Alert
        className="task-plan-notice"
        showIcon
        type={archived ? 'info' : 'warning'}
        message={
          archived
            ? '活动已归档，任务计划以只读方式保留。'
            : !canManage
              ? canProgress
                ? '你可以更新本组织任务进度。任务安排由管理员维护。'
                : '当前为只读查看，可以查看任务安排、依赖和进度。'
              : `${counts.late} 项任务已逾期，${counts.conflict} 项存在前置冲突，${counts.unscheduled} 项尚未排期。`
        }
        action={
          <Button type="link" size="small" onClick={() => setFilter('risk')}>
            查看风险
          </Button>
        }
      />
      {saved && (
        <div role="status" className="task-plan-saved">
          <span>{saved}</span>
          {undo && (
            <Button
              type="link"
              disabled={
                busy ||
                archived ||
                (undo.kind === 'plan' ? !canManage : !canProgress)
              }
              onClick={() => void undoLast()}
            >
              撤销上次修改
            </Button>
          )}
          <Button
            type="text"
            onClick={() => {
              setSaved('')
              setUndo(null)
            }}
          >
            收起
          </Button>
        </div>
      )}
      {deptFailed && (
        <Alert
          type="warning"
          message="部门信息加载失败，已有任务保留原部门编号。"
          action={
            <Button type="link" onClick={() => void refreshDepts()}>
              重试
            </Button>
          }
        />
      )}
      <section className="task-plan-surface">
        <div className="task-plan-toolbar">
          <Input
            className="task-plan-search"
            prefix={<SearchOutlined />}
            placeholder="搜索任务、负责人"
            aria-label="搜索任务、负责人"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            allowClear
          />
          <Select
            aria-label="负责部门筛选"
            className="task-plan-department"
            value={department}
            onChange={setDepartment}
            options={[
              { value: 'all', label: '全部部门' },
              ...depts.map((d) => ({ value: d.id, label: d.name })),
              { value: 'none', label: '未分配部门' },
            ]}
          />
          <div className="task-plan-tools">
            <Segmented
              aria-label="任务视图"
              value={view}
              options={[
                { label: '列表', value: 'list' },
                { label: '甘特图', value: 'gantt' },
              ]}
              onChange={(value) => setView(value as 'list' | 'gantt')}
            />
            {view === 'gantt' && (
              <>
                <Select
                  aria-label="时间尺度"
                  value={zoom}
                  onChange={(value: GanttZoom) => {
                    setZoom(value)
                    if (value === 'fit') setAnchor(null)
                  }}
                  options={[
                    { value: 'day', label: '日视图' },
                    { value: 'week', label: '周视图' },
                    { value: 'month', label: '月视图' },
                    { value: 'fit', label: '全览' },
                    ...(zoom === 'custom'
                      ? [{ value: 'custom', label: '自定义', disabled: true }]
                      : []),
                  ]}
                />
                <Button
                  onClick={() => {
                    if (zoom === 'fit') setZoom('day')
                    setAnchor(
                      dayjs(today)
                        .subtract(
                          zoom === 'day' || zoom === 'fit' ? 7 : 0,
                          'day',
                        )
                        .format('YYYY-MM-DD'),
                    )
                  }}
                >
                  定位今日
                </Button>
                <Button
                  onClick={() => {
                    setZoom('fit')
                    setAnchor(null)
                  }}
                >
                  适合全部
                </Button>
              </>
            )}
          </div>
        </div>
        {view === 'gantt' && (
          <div className="task-plan-range">
            <span>{dateLabel(initialDate)} 起 · 前置完成后的次日开始</span>
            <Space>
              <Button
                type="text"
                size="small"
                disabled={zoom === 'fit'}
                onClick={() =>
                  setAnchor(
                    dayjs(initialDate)
                      .subtract(1, zoom === 'month' ? 'month' : 'week')
                      .format('YYYY-MM-DD'),
                  )
                }
              >
                ‹ {zoom === 'month' ? '前一月' : '前一周'}
              </Button>
              <Button
                type="text"
                size="small"
                disabled={zoom === 'fit'}
                onClick={() =>
                  setAnchor(
                    dayjs(initialDate)
                      .add(1, zoom === 'month' ? 'month' : 'week')
                      .format('YYYY-MM-DD'),
                  )
                }
              >
                {zoom === 'month' ? '后一月' : '后一周'} ›
              </Button>
            </Space>
          </div>
        )}
        {loading && !tasks.length ? (
          <div className="task-plan-empty">
            <Spin tip="正在加载任务计划">
              <div />
            </Spin>
          </div>
        ) : failed ? (
          <div className="task-plan-empty">
            <Alert
              type="error"
              showIcon
              message="任务计划加载失败"
              description="重试会保留当前筛选条件。"
              action={<Button onClick={() => void refresh()}>重新加载</Button>}
            />
          </div>
        ) : !tasks.length ? (
          <div className="task-plan-empty">
            <Empty description="从第一项准备任务开始">
              {canManage ? (
                <Button type="primary" onClick={() => edit(null)}>
                  新建第一项任务
                </Button>
              ) : (
                <p>管理员尚未安排活动任务。</p>
              )}
            </Empty>
          </div>
        ) : !visible.length ? (
          <div className="task-plan-empty">
            <Empty description="没有符合条件的任务">
              <Button onClick={reset}>清除筛选</Button>
            </Empty>
          </div>
        ) : view === 'gantt' ? (
          <GanttChart
            tasks={ganttTasks.filter((t) => visible.some((v) => v.id === t.id))}
            allTasks={ganttTasks}
            zoom={zoom}
            customPixelsPerDay={customPixelsPerDay}
            onWheelZoom={(pixelsPerDay, startDate) => {
              setCustomPixelsPerDay(pixelsPerDay)
              setAnchor(startDate)
              setZoom('custom')
            }}
            anchor={anchor}
            activityDate={activity.startDate}
            selectedId={selected?.id}
            onSelect={(task) => {
              const full = tasks.find((t) => t.id === task.id)
              if (full) showDetail(full)
            }}
            onSchedule={
              canManage && !busy
                ? (task) => edit(tasks.find((t) => t.id === task.id)!)
                : undefined
            }
            onMove={
              canManage && !busy && !loading
                ? (task, startDate, endDate) => {
                    const full = tasks.find((t) => t.id === task.id)
                    if (full)
                      void propose(
                        {
                          id: full.id,
                          task: { ...taskSave(full), startDate, endDate },
                        },
                        true,
                      )
                  }
                : undefined
            }
          />
        ) : (
          <div className="task-plan-list">
            <table aria-label="活动任务列表">
              <thead>
                <tr>
                  {[
                    '任务 / 负责人',
                    '负责部门',
                    '起止日期',
                    '状态',
                    '进度',
                    '前置任务',
                  ].map((label) => (
                    <th key={label} scope="col">
                      {label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {visible.map((task) => (
                  <tr key={task.id}>
                    <td>
                      <button
                        className="task-name"
                        onClick={() => showDetail(task)}
                      >
                        {task.isMilestone === 1 ? '◇ ' : ''}
                        {task.name}
                      </button>
                      <small className="task-list-owner">
                        {task.assignee || '待分配'}
                      </small>
                      <small className="task-list-mobile-owner">
                        {deptName(task)} / {task.assignee || '待分配'}
                      </small>
                    </td>
                    <td data-cell="department">{deptName(task)}</td>
                    <td>{taskDateLabel(task)}</td>
                    <td>{stateLabel(task)}</td>
                    <td data-cell="progress">{task.progress ?? 0}%</td>
                    <td data-cell="dependency">
                      {tasks.find((t) => t.id === task.dependsOn)?.name ||
                        (task.dependsOn ? '前置任务不可用' : '无前置任务')}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <footer className="task-plan-footer">
          <div className="task-plan-legend">
            <span>
              <i className="done" />
              已完成
            </span>
            <span>
              <i className="active" />
              进行中
            </span>
            <span>
              <i className="risk" />
              逾期 / 冲突
            </span>
            <span>
              <i className="waiting" />
              待开始
            </span>
            <span>◇ 里程碑</span>
          </div>
          <span aria-live="polite">
            显示 {visible.length} / {tasks.length} 项任务
            {hasFilter && (
              <Button type="link" size="small" onClick={reset}>
                清除筛选
              </Button>
            )}
          </span>
        </footer>
      </section>
      <p className="task-plan-caption">
        今天 {today} · 活动日 {activity.startDate || '待定'} · 点击任务查看详情
        {canManage ? '，拖动普通任务条可预览改期' : ''}
        。甘特图内按住
        Ctrl＋滚轮缩放（向上放大、向下缩小）。任务完成不会自动推进活动阶段或完成业务审核。
      </p>
      <FormSurface
        mode="drawer"
        width={510}
        className="task-plan-drawer"
        open={drawer !== null}
        title={
          drawer === 'edit'
            ? selected
              ? '编辑任务安排'
              : '新建任务'
            : '任务详情'
        }
        description={
          drawer === 'edit'
            ? '先明确分工，再安排日期；未排期任务也可以保存。'
            : activity.name
        }
        onCancel={close}
        keyboard={!busy}
        closable={!busy}
        footer={
          <div className="task-drawer-footer">
            {drawer === 'edit' && selected && canManage && (
              <Button danger onClick={remove} disabled={busy}>
                删除任务
              </Button>
            )}
            <div className="task-drawer-spacer" />
            {drawer === 'detail' && canManage && (
              <Button disabled={busy} onClick={() => edit(selected)}>
                编辑安排
              </Button>
            )}
            <Button
              aria-label={drawer === 'edit' ? '取消' : '关闭'}
              autoInsertSpace={false}
              onClick={close}
              disabled={busy}
            >
              {drawer === 'edit' ? '取消' : '关闭'}
            </Button>
            {drawer === 'edit' ? (
              <Button
                type="primary"
                loading={busy}
                disabled={!canManage}
                onClick={() => void submitEdit()}
              >
                {selected ? '保存安排' : '创建任务'}
              </Button>
            ) : (
              canProgress && (
                <Button
                  type="primary"
                  loading={busy}
                  disabled={!dirty}
                  onClick={() => void saveProgress()}
                >
                  保存进度
                </Button>
              )
            )}
          </div>
        }
      >
        {formError && (
          <Alert
            role="alert"
            className="task-drawer-error"
            type="error"
            showIcon
            message={formError}
            action={
              <Button
                type="link"
                onClick={() =>
                  guard(() => {
                    setDrawer(null)
                    setDirty(false)
                    void refresh()
                  })
                }
              >
                重新载入任务
              </Button>
            }
          />
        )}
        {drawer === 'edit' ? (
          <TaskEditorForm
            ref={form}
            key={formKey}
            initial={initial}
            disabled={busy || !canManage}
            tasks={tasks}
            depts={depts}
            selectedId={selected?.id}
            onDirty={() => {
              setDirty(true)
              setFormError('')
            }}
            onFinish={() => void submitEdit()}
          />
        ) : (
          selected && (
            <>
              <div>{stateLabel(selected)}</div>
              <h2 className="task-detail-name">{selected.name}</h2>
              <dl className="task-detail-info">
                <div>
                  <dt>负责部门</dt>
                  <dd>{deptName(selected)}</dd>
                </div>
                <div>
                  <dt>负责人</dt>
                  <dd>{selected.assignee || '待分配'}</dd>
                </div>
                <div>
                  <dt>{selected.isMilestone ? '里程碑日期' : '起止日期'}</dt>
                  <dd>
                    {selected.isMilestone
                      ? dateLabel(selected.startDate)
                      : taskDateLabel(selected)}
                  </dd>
                </div>
                <div>
                  <dt>任务类型</dt>
                  <dd>{selected.isMilestone ? '里程碑' : '普通任务'}</dd>
                </div>
              </dl>
              {(isOverdue(selected) ||
                hasDependencyConflict(selected, tasks)) && (
                <Alert
                  type="warning"
                  showIcon
                  message={`${isOverdue(selected) ? `已逾期 ${dayjs(today).diff(dayjs(selected.endDate), 'day')} 天。` : ''}${hasDependencyConflict(selected, tasks) ? '开始日期早于前置完成后的次日，请调整安排。' : ''}`}
                />
              )}
              <div className="task-context">
                <h3>前后置关系</h3>
                <p>
                  前置任务：
                  {parent ? (
                    <Button type="link" onClick={() => showDetail(parent)}>
                      {parent.name}
                    </Button>
                  ) : selected.dependsOn ? (
                    '前置任务不可用'
                  ) : (
                    '无'
                  )}
                </p>
                <p>
                  后续任务：
                  {children.length
                    ? children.map((t) => (
                        <Button
                          type="link"
                          key={t.id}
                          onClick={() => showDetail(t)}
                        >
                          {t.name}
                        </Button>
                      ))
                    : '无'}
                </p>
              </div>
              <div className="task-progress-editor">
                <label id="task-progress-label">
                  {canProgress ? '更新执行进度' : '执行进度'}
                  <output>{progress}%</output>
                </label>
                <Slider
                  aria-labelledby="task-progress-label"
                  value={progress}
                  disabled={!canProgress || busy}
                  onChange={(value) => {
                    setProgress(value)
                    setDirty(value !== (selected.progress ?? 0))
                  }}
                />
                {canProgress && (
                  <div className="task-progress-presets">
                    {[0, 25, 50, 75, 100].map((value) => (
                      <Button
                        key={value}
                        size="small"
                        disabled={busy}
                        onClick={() => {
                          setProgress(value)
                          setDirty(value !== (selected.progress ?? 0))
                        }}
                      >
                        {value === 100 ? '已完成' : `${value}%`}
                      </Button>
                    ))}
                  </div>
                )}
              </div>
              <div className="task-context">
                <h3>任务说明</h3>
                <p className="task-description">
                  {selected.description || '暂无说明'}
                </p>
              </div>
              <div className="task-context">
                <h3>活动相关工作</h3>
                <div className="task-related">
                  {related
                    .filter(
                      ([, , permission]) => !permission || hasPerm(permission),
                    )
                    .map(([key, label]) => (
                      <Button
                        key={key}
                        type="link"
                        onClick={() => navigateRelated(key)}
                      >
                        {label}
                      </Button>
                    ))}
                </div>
                <p>
                  任务进度与业务状态分别维护，标记完成不会自动审核策划书、发布推文或推进活动阶段。
                </p>
              </div>
            </>
          )
        )}
      </FormSurface>
      <FormSurface
        mode="dialog"
        width={560}
        className="task-plan-impact"
        open={pending !== null}
        title="确认任务安排"
        onCancel={() => {
          if (!busy) setPending(null)
        }}
        keyboard={!busy}
        closable={!busy}
        confirmLoading={busy}
        onOk={() => {
          if (pending) void save({ ...pending.request, cascade })
        }}
        okText="确认保存"
      >
        {pending && (
          <>
            <h3>{pending.preview.candidate.name}</h3>
            <div className="task-impact-row">
              <small>当前任务</small>
              <p>
                {pending.request.id
                  ? `${taskDateLabel(tasks.find((t) => t.id === pending.request.id)!)} → `
                  : ''}
                {taskDateLabel(pending.preview.candidate)}
              </p>
            </div>
            {hasDependencyConflict(pending.preview.candidate, tasks) && (
              <Alert
                type="warning"
                message="当前任务仍有前置日期冲突。保存后会保留风险提醒。"
              />
            )}
            <h3 className="task-impact-heading">
              {pending.preview.impacts.length
                ? `${pending.preview.impacts.length} 项后续任务需要顺延`
                : '没有需要顺延的后续任务'}
            </h3>
            {pending.preview.impacts.map(({ before, after }) => (
              <div key={before.id} className="task-impact-row">
                <strong>
                  {before.name}
                  {(before.progress ?? 0) >= 100 ? '（已完成）' : ''}
                </strong>
                <small>
                  {taskDateLabel(before)} → {taskDateLabel(after)}
                </small>
              </div>
            ))}
            {pending.preview.impacts.length > 0 && (
              <Radio.Group
                value={cascade}
                onChange={(e) => setCascade(e.target.value)}
                disabled={busy}
              >
                <Radio value={false}>
                  仅保存当前任务
                  <small>保留后续任务日期，继续显示冲突提醒。</small>
                </Radio>
                <Radio value={true}>
                  同时顺延 {pending.preview.impacts.length} 项后续任务
                  <small>
                    保持原工期与进度；
                    {pending.preview.impacts.some(
                      (i) => (i.before.progress ?? 0) >= 100,
                    )
                      ? '包含已完成任务，请核实历史日期。'
                      : '不会静默标记任务完成。'}
                  </small>
                </Radio>
              </Radio.Group>
            )}
            {activity.startDate &&
              pending.preview.impacts.some(
                (i) => i.after.endDate && i.after.endDate > activity.startDate!,
              ) && (
                <p className="task-impact-warning">
                  顺延将使部分任务晚于活动日，请确认是否影响现场准备。
                </p>
              )}
          </>
        )}
      </FormSurface>
    </section>
  )
}
