import {
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
} from 'react'
import dayjs from 'dayjs'
import {
  dateLabel,
  hasDependencyConflict,
  isScheduled,
  taskDateLabel,
  taskStatus,
  taskTone,
  todayStr,
  type GanttTask,
} from './gantt.utils'
import {
  timelineScale,
  timelineTicks,
  wheelDayWidth,
  type GanttZoom,
} from './gantt.scale'

export type { GanttZoom } from './gantt.scale'
interface Props {
  tasks: GanttTask[]
  allTasks?: GanttTask[]
  zoom: GanttZoom
  customPixelsPerDay?: number
  onWheelZoom?: (pixelsPerDay: number, startDate: string) => void
  anchor?: string | null
  activityDate?: string | null
  selectedId?: number
  onSelect: (task: GanttTask) => void
  onMove?: (task: GanttTask, startDate: string, endDate: string) => void
  onSchedule?: (task: GanttTask) => void
  today?: string
}
const HEADER = 64,
  ROW = 58,
  GROUP = 38

/** A fixed task column and a shared scroll surface keep labels, dates and dependencies aligned. */
export default function GanttChart({
  tasks,
  allTasks = tasks,
  zoom,
  customPixelsPerDay,
  onWheelZoom,
  anchor,
  activityDate,
  selectedId,
  onSelect,
  onMove,
  onSchedule,
  today = todayStr(),
}: Props) {
  const scroller = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(1000)
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set())
  const [moving, setMoving] = useState<{ id: number; delta: number } | null>(
    null,
  )
  const drag = useRef<{
    id: number
    x: number
    delta: number
    pointerId: number
  } | null>(null)
  const suppressClick = useRef(false)
  const marker = useId().replace(/:/g, '')
  useEffect(() => {
    const element = scroller.current
    if (!element) return
    const observer = new ResizeObserver((entries) =>
      setWidth(entries[0].contentRect.width),
    )
    observer.observe(element)
    return () => observer.disconnect()
  }, [])
  const labelWidth = width < 650 ? 190 : width < 950 ? 275 : 310
  const available = Math.max(width - labelWidth, 100)
  const {
    start,
    end,
    days,
    pixelsPerDay: px,
    gridWidth,
    barInset,
    unit,
  } = timelineScale({
    tasks: allTasks,
    zoom,
    anchor,
    activityDate,
    today,
    customPixelsPerDay,
    availableWidth: available,
  })
  const pendingZoom = useRef<{
    start: string
    day: number
    x: number
    px: number
  } | null>(null)
  const navigation = useRef({ anchor, zoom })
  useLayoutEffect(() => {
    const element = scroller.current
    if (!element) return
    const pending = pendingZoom.current
    if (pending) {
      // Preserve the date under the pointer, subject to the scroll range boundaries.
      element.scrollLeft =
        (dayjs(pending.start).diff(dayjs(start), 'day') + pending.day) * px -
        pending.x
      pendingZoom.current = null
    } else if (
      navigation.current.anchor !== anchor ||
      navigation.current.zoom !== zoom
    ) {
      element.scrollLeft = 0
    }
    navigation.current = { anchor, zoom }
  }, [anchor, zoom, start, px])
  useEffect(() => {
    const element = scroller.current
    if (!element || !onWheelZoom) return
    const wheel = (event: WheelEvent) => {
      if (!event.ctrlKey || event.deltaY === 0) return
      event.preventDefault()
      if (drag.current) return
      const x = Math.max(
        0,
        Math.min(
          element.clientWidth - labelWidth,
          event.clientX -
            element.getBoundingClientRect().left -
            element.clientLeft -
            labelWidth,
        ),
      )
      const delta =
        event.deltaY *
        (event.deltaMode === 1
          ? 16
          : event.deltaMode === 2
            ? element.clientHeight
            : 1)
      const next = wheelDayWidth(pendingZoom.current?.px ?? px, delta)
      if (next === px && !pendingZoom.current) return
      pendingZoom.current = {
        start,
        day: (element.scrollLeft + x) / px,
        x,
        px: next,
      }
      onWheelZoom(next, start)
    }
    // React's delegated wheel listener is passive; this local listener prevents browser page zoom.
    element.addEventListener('wheel', wheel, { passive: false })
    return () => element.removeEventListener('wheel', wheel)
  }, [labelWidth, onWheelZoom, px, start])
  const at = (date: string) => dayjs(date).diff(dayjs(start), 'day') * px
  const groups = useMemo(() => {
    const result = new Map<string, { name: string; tasks: GanttTask[] }>()
    tasks.forEach((t) => {
      const key =
        t.deptId != null ? String(t.deptId) : t.deptName || 'unassigned'
      if (!result.has(key))
        result.set(key, {
          name:
            t.deptName || (t.deptId != null ? '部门信息待补充' : '未分配部门'),
          tasks: [],
        })
      result.get(key)!.tasks.push(t)
    })
    return [...result].sort(([a], [b]) =>
      a === 'unassigned' ? 1 : b === 'unassigned' ? -1 : 0,
    )
  }, [tasks])
  const rows = new Map<number, { task: GanttTask; y: number }>()
  let height = HEADER
  groups.forEach(([key, group]) => {
    height += GROUP
    if (!collapsed.has(key))
      group.tasks.forEach((task) => {
        rows.set(task.id, { task, y: height + ROW / 2 })
        height += ROW
      })
  })
  const ticks = timelineTicks(start, days, unit)
  const weekends =
    days <= 366 && px >= 10
      ? Array.from({ length: days }, (_, i) => i).filter((i) =>
          [0, 6].includes(dayjs(start).add(i, 'day').day()),
        )
      : []
  const line = (
    date: string | null | undefined,
    label: string,
    cls: string,
  ) => {
    if (!date) return null
    const position = at(date) + px / 2
    return position < 0 || position >= gridWidth ? null : (
      <div
        className={`task-timeline-marker ${cls}`}
        style={{ left: labelWidth + position }}
      >
        <span
          style={
            gridWidth - position < 70
              ? { marginLeft: -4, transform: 'translateX(-100%)' }
              : undefined
          }
        >
          {label}
        </span>
      </div>
    )
  }
  const select = (task: GanttTask) => {
    if (!suppressClick.current) onSelect(task)
  }
  return (
    <div
      className="task-timeline"
      ref={scroller}
      tabIndex={0}
      aria-label="任务时间轴，可横向和纵向滚动，Ctrl 加滚轮缩放"
    >
      <div
        className="task-timeline-canvas"
        data-start-date={start}
        data-end-date={end}
        style={
          {
            width: labelWidth + gridWidth,
            '--task-label-width': `${labelWidth}px`,
            '--task-day-width': `${px}px`,
          } as CSSProperties
        }
      >
        <div
          className="task-timeline-shading"
          style={{
            left: labelWidth,
            top: HEADER,
            height: height - HEADER,
            width: gridWidth,
          }}
          aria-hidden="true"
        >
          {weekends.map((i) => (
            <i key={i} style={{ left: i * px, width: px }} />
          ))}
          {at(today) >= 0 && at(today) < gridWidth && (
            <b style={{ left: at(today), width: px }} />
          )}
        </div>
        <div
          className="task-timeline-grid"
          style={{
            left: labelWidth,
            top: HEADER,
            width: gridWidth,
            height: height - HEADER,
          }}
          aria-hidden="true"
        >
          {ticks.map((tick) => (
            <i key={tick.date} style={{ left: tick.offset * px }} />
          ))}
        </div>
        <div className="task-timeline-row task-timeline-head">
          <div className="task-timeline-label">任务 / 负责人 / 状态</div>
          <div className="task-timeline-ruler" data-unit={unit}>
            <span className="task-timeline-month">
              {dayjs(start).format('YYYY 年 M 月 D 日')} —{' '}
              {dayjs(end).format(
                dayjs(start).year() === dayjs(end).year()
                  ? 'M 月 D 日'
                  : 'YYYY 年 M 月 D 日',
              )}
            </span>
            {ticks.map((tick) => {
              const isCurrent =
                today >= tick.date &&
                today <=
                  dayjs(tick.date)
                    .add(tick.days - 1, 'day')
                    .format('YYYY-MM-DD')
              return (
                <div
                  key={tick.date}
                  className={`task-timeline-date ${isCurrent ? 'today' : ''}`}
                  style={{ left: tick.offset * px, width: tick.days * px }}
                  title={`${tick.label} ${tick.subLabel}`}
                >
                  <span>{tick.label}</span>
                  <small>{tick.subLabel}</small>
                </div>
              )
            })}
          </div>
        </div>
        {groups.map(([key, group]) => (
          <div key={key}>
            <div className="task-timeline-row task-timeline-group">
              <div className="task-timeline-label">
                <button
                  aria-expanded={!collapsed.has(key)}
                  onClick={() =>
                    setCollapsed((previous) => {
                      const next = new Set(previous)
                      next.has(key) ? next.delete(key) : next.add(key)
                      return next
                    })
                  }
                >
                  <span aria-hidden>{collapsed.has(key) ? '▸' : '▾'}</span>
                  {group.name}
                  <small>
                    {group.tasks.filter((t) => (t.progress ?? 0) >= 100).length}{' '}
                    / {group.tasks.length} 完成
                  </small>
                </button>
              </div>
              <div />
            </div>
            {!collapsed.has(key) &&
              group.tasks.map((task) => {
                const tone = taskTone(task, allTasks, today),
                  conflict = hasDependencyConflict(task, allTasks)
                const milestone = !!task.isMilestone,
                  scheduled = isScheduled(task)
                const left = scheduled ? at(task.startDate) : 0
                const barWidth = scheduled
                  ? (dayjs(task.endDate).diff(dayjs(task.startDate), 'day') +
                      1) *
                    px
                  : 0
                return (
                  <div
                    key={task.id}
                    data-task-row={task.id}
                    className={`task-timeline-row ${selectedId === task.id ? 'selected' : ''}`}
                  >
                    <div className="task-timeline-label">
                      <span className="task-timeline-number">{task.id}</span>
                      <div>
                        <button
                          className="task-name"
                          onClick={() => select(task)}
                          title={task.name}
                        >
                          {milestone && <span aria-hidden>◇ </span>}
                          {task.name}
                        </button>
                        <small>
                          {task.assignee || '待分配'}{' '}
                          <span className={`task-state ${tone}`}>
                            {taskStatus(task, today)}
                            {conflict ? ' · 前置冲突' : ''}
                          </span>
                        </small>
                      </div>
                      <span className="task-timeline-avatar" aria-hidden>
                        {task.assignee?.slice(0, 1) || '待'}
                      </span>
                    </div>
                    <div className="task-timeline-lane">
                      {scheduled ? (
                        <button
                          className={`task-bar ${tone} ${milestone ? 'milestone' : ''}`}
                          style={
                            {
                              left: milestone
                                ? left + px / 2 - 10
                                : left + barInset,
                              width: milestone
                                ? 160
                                : Math.max(2, barWidth - 2 * barInset),
                              paddingInline:
                                !milestone && barWidth < 24 ? 0 : undefined,
                              '--task-progress': `${task.progress ?? 0}%`,
                              transform:
                                moving?.id === task.id
                                  ? `translateX(${moving.delta * px}px)`
                                  : undefined,
                            } as CSSProperties
                          }
                          aria-label={`${task.name}，${taskDateLabel(task)}，${taskStatus(task, today)}，进度 ${task.progress ?? 0}%`}
                          title={`${task.name} · ${taskDateLabel(task)} · ${task.progress ?? 0}%`}
                          onClick={() => select(task)}
                          onPointerDown={(e) => {
                            if (!onMove || milestone || e.button !== 0) return
                            drag.current = {
                              id: task.id,
                              x: e.clientX,
                              delta: 0,
                              pointerId: e.pointerId,
                            }
                            e.currentTarget.setPointerCapture(e.pointerId)
                          }}
                          onPointerMove={(e) => {
                            const d = drag.current
                            if (!d || d.id !== task.id) return
                            d.delta = Math.round((e.clientX - d.x) / px)
                            setMoving({ id: d.id, delta: d.delta })
                          }}
                          onPointerUp={(e) => {
                            const d = drag.current
                            if (!d || d.id !== task.id) return
                            drag.current = null
                            setMoving(null)
                            if (e.currentTarget.hasPointerCapture(d.pointerId))
                              e.currentTarget.releasePointerCapture(d.pointerId)
                            if (d.delta) {
                              suppressClick.current = true
                              setTimeout(() => {
                                suppressClick.current = false
                              }, 300)
                              onMove?.(
                                task,
                                dayjs(task.startDate)
                                  .add(d.delta, 'day')
                                  .format('YYYY-MM-DD'),
                                dayjs(task.endDate)
                                  .add(d.delta, 'day')
                                  .format('YYYY-MM-DD'),
                              )
                            }
                          }}
                          onPointerCancel={() => {
                            drag.current = null
                            setMoving(null)
                          }}
                        >
                          {milestone ? (
                            <>
                              <i className="task-diamond" />
                              <span>
                                {task.name} · {dateLabel(task.startDate)}
                              </span>
                            </>
                          ) : (
                            <>
                              <span>{barWidth > 115 ? task.name : ''}</span>
                              <b>
                                {barWidth > 32 ? `${task.progress ?? 0}%` : ''}
                              </b>
                            </>
                          )}
                        </button>
                      ) : (
                        <div className="task-unplanned">
                          尚未安排日期{' '}
                          {onSchedule && (
                            <button onClick={() => onSchedule(task)}>
                              安排日期
                            </button>
                          )}
                        </div>
                      )}
                    </div>
                  </div>
                )
              })}
          </div>
        ))}
        {line(
          today,
          activityDate === today ? '今天 · 活动日' : '今天',
          'today',
        )}
        {activityDate !== today && line(activityDate, '活动日', 'event')}
        <svg
          className="task-dependencies"
          aria-hidden="true"
          style={{ left: labelWidth, width: gridWidth, height }}
          viewBox={`0 0 ${gridWidth} ${height}`}
        >
          <defs>
            <marker
              id={marker}
              markerWidth="7"
              markerHeight="7"
              refX="6"
              refY="3.5"
              orient="auto"
            >
              <path d="M0 0 7 3.5 0 7z" />
            </marker>
          </defs>
          {[...rows.values()].map(({ task, y }) => {
            const from = rows.get(task.dependsOn ?? -1)
            if (!from || !isScheduled(from.task) || !isScheduled(task))
              return null
            const x1 = from.task.isMilestone
                ? at(from.task.startDate) + px / 2
                : at(from.task.endDate) + px - barInset,
              x2 = at(task.startDate) + (task.isMilestone ? px / 2 : barInset)
            if (Math.max(x1, x2) < 0 || Math.min(x1, x2) > gridWidth)
              return null
            return (
              <path
                key={task.id}
                className={hasDependencyConflict(task, allTasks) ? 'risk' : ''}
                markerEnd={`url(#${marker})`}
                d={`M${x1} ${from.y} H${x1 + 9} V${y} H${x2}`}
              />
            )
          })}
        </svg>
      </div>
    </div>
  )
}
