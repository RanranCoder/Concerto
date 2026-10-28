import dayjs from 'dayjs'
import { isScheduled, type TaskDates } from './gantt.utils'

export type GanttZoom = 'day' | 'week' | 'month' | 'fit' | 'custom'
export type TimelineUnit = 'day' | 'week' | 'month'
interface RangeOptions {
  tasks: TaskDates[]
  zoom: GanttZoom
  anchor?: string | null
  activityDate?: string | null
  today: string
  customPixelsPerDay?: number
}
export interface TimelineTick {
  date: string
  offset: number
  days: number
  label: string
  subLabel: string
}
// Only fit may stretch a time scale to the viewport. Named views retain distinct day widths.
export const TIMELINE_DAY_WIDTH = { day: 42, week: 16, month: 5 } as const
export const MIN_DAY_WIDTH = 0.5
export const MAX_DAY_WIDTH = 126
export function wheelDayWidth(current: number, delta: number): number {
  return Math.min(
    MAX_DAY_WIDTH,
    Math.max(
      MIN_DAY_WIDTH,
      current * 2 ** (-Math.max(-240, Math.min(240, delta)) / 300),
    ),
  )
}
const format = (date: dayjs.Dayjs) => date.format('YYYY-MM-DD')
const monday = (date: dayjs.Dayjs) => date.subtract((date.day() + 6) % 7, 'day')

function bounds({ tasks, activityDate, today }: RangeOptions) {
  const dates = tasks
    .filter(isScheduled)
    .flatMap((t) => [t.startDate!, t.endDate!])
  if (activityDate && dayjs(activityDate).isValid()) dates.push(activityDate)
  dates.sort()
  return {
    first: dayjs(dates[0] || today),
    last: dayjs(dates.at(-1) || today),
  }
}

export function timelineStart(options: RangeOptions): string {
  const { first } = bounds(options)
  if (options.zoom === 'fit') return format(first.subtract(1, 'day'))
  const base = options.anchor ? dayjs(options.anchor) : first
  if (options.zoom === 'week') return format(monday(base))
  if (options.zoom === 'month') return format(base.startOf('month'))
  return format(options.anchor ? base : base.subtract(1, 'day'))
}

export function timelineScale(
  options: RangeOptions & { availableWidth: number },
) {
  const start = dayjs(timelineStart(options))
  const { last } = bounds(options)
  const available = Math.max(1, options.availableWidth)
  const fit = options.zoom === 'fit'
  const pixelsPerDay =
    options.zoom === 'fit'
      ? 0
      : options.zoom === 'custom'
        ? Math.min(
            MAX_DAY_WIDTH,
            Math.max(MIN_DAY_WIDTH, options.customPixelsPerDay ?? 42),
          )
        : TIMELINE_DAY_WIDTH[options.zoom]
  const minimumDays = fit ? 7 : Math.max(1, Math.ceil(available / pixelsPerDay))
  let end = last.add(fit || options.zoom === 'day' ? 1 : 0, 'day')
  const viewportEnd = start.add(minimumDays - 1, 'day')
  if (end.isBefore(viewportEnd, 'day')) end = viewportEnd
  if (options.zoom === 'week') end = monday(end).add(6, 'day')
  if (options.zoom === 'month') end = end.endOf('month')
  const days = end.diff(start, 'day') + 1
  const px = fit ? available / days : pixelsPerDay
  const unit: TimelineUnit =
    fit || options.zoom === 'custom'
      ? px >= 28
        ? 'day'
        : px >= 10
          ? 'week'
          : 'month'
      : (options.zoom as TimelineUnit)
  return {
    start: format(start),
    end: format(end),
    days,
    pixelsPerDay: px,
    gridWidth: fit ? available : days * px,
    barInset: Math.min(3, px * 0.12),
    unit,
  }
}

/** Calendar buckets share the exact date-to-pixel transform used by rows, markers and arrows. */
export function timelineTicks(
  startDate: string,
  days: number,
  unit: TimelineUnit,
): TimelineTick[] {
  const start = dayjs(startDate),
    end = start.add(days - 1, 'day')
  let cursor =
    unit === 'week'
      ? monday(start)
      : unit === 'month'
        ? start.startOf('month')
        : start
  const ticks: TimelineTick[] = []
  const step = unit === 'day' ? Math.max(1, Math.ceil(days / 1000)) : 1
  while (!cursor.isAfter(end, 'day')) {
    const next = cursor.add(step, unit)
    const left = cursor.isBefore(start, 'day') ? start : cursor
    const right = next.subtract(1, 'day').isAfter(end, 'day')
      ? end
      : next.subtract(1, 'day')
    ticks.push({
      date: format(left),
      offset: left.diff(start, 'day'),
      days: right.diff(left, 'day') + 1,
      label:
        unit === 'day'
          ? String(left.date())
          : unit === 'week'
            ? `${left.format('M/D')} — ${right.format('M/D')}`
            : cursor.format('M月'),
      subLabel:
        unit === 'day'
          ? `周${'日一二三四五六'[left.day()]}`
          : unit === 'week'
            ? `周${'日一二三四五六'[left.day()]}至周${'日一二三四五六'[right.day()]}`
            : cursor.format('YYYY年'),
    })
    cursor = next
  }
  return ticks
}
