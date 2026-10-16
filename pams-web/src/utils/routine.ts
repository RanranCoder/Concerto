import dayjs, { type Dayjs } from 'dayjs'
import type { ScheduleVO } from '@/api/schedule'
import type { ScheduleConfigVO } from '@/api/courseSchedule'

export const WEEK_DAYS = [
  '周一',
  '周二',
  '周三',
  '周四',
  '周五',
  '周六',
  '周日',
]
export const FALLBACK_PERIODS: ScheduleConfigVO[] = Array.from(
  { length: 5 },
  (_, i) => ({
    id: i + 1,
    period: i + 1,
    label: `第${i * 2 + 1}-${i * 2 + 2}节`,
    startTime: null,
    endTime: null,
  }),
)
export function monday(date = dayjs()) {
  return date.subtract((date.day() + 6) % 7, 'day').startOf('day')
}
export function weekday(s: ScheduleVO) {
  return s.scheduleDate
    ? ((dayjs(s.scheduleDate).day() + 6) % 7) + 1
    : s.weekday
}
export function timeRange(s: ScheduleVO, periods?: ScheduleConfigVO[]) {
  const period = periods?.[periodFor(s, periods)]
  return s.startTime
    ? `${s.startTime.slice(0, 5)}–${s.endTime?.slice(0, 5) ?? '待定'}`
    : period?.startTime
      ? `${period.startTime.slice(0, 5)}–${period.endTime?.slice(0, 5) ?? '待定'}`
      : s.sessionName || '未设置时段'
}
export function periodFor(s: ScheduleVO, periods: ScheduleConfigVO[]) {
  if (s.startTime) {
    const match = periods.findIndex(
      (p) =>
        p.startTime &&
        p.endTime &&
        s.startTime!.slice(0, 5) >= p.startTime.slice(0, 5) &&
        s.startTime!.slice(0, 5) < p.endTime.slice(0, 5),
    )
    if (match >= 0) return match
  }
  // Only an explicit lesson marker is a lesson number; “9:00” is a time, not lesson 9.
  const n = s.sessionName?.match(/第?\s*(\d+)\s*(?:[-–—~至]\s*\d+\s*)?节/)
  if (!n) return -1
  return periods.findIndex((p) => {
    const bounds = p.label.match(/(\d+)\s*[-–—~至]\s*(\d+)/)
    return bounds
      ? Number(n[1]) >= Number(bounds[1]) && Number(n[1]) <= Number(bounds[2])
      : p.label === s.sessionName
  })
}
export function inCalendarWeek(s: ScheduleVO, start: Dayjs, weekNo?: number) {
  if (s.scheduleDate)
    return (
      s.scheduleDate >= start.format('YYYY-MM-DD') &&
      s.scheduleDate <= start.add(6, 'day').format('YYYY-MM-DD')
    )
  return weekNo !== undefined && s.weekNo === weekNo
}
export function freeInWeek(
  value: string | null | undefined,
  week: number,
): boolean {
  if (!value?.trim()) return true
  const normalized = value
    .replace(/[（]/g, '(')
    .replace(/[）]/g, ')')
    .replace(/[，、；;]/g, ',')
    .replace(/[–—~～至]/g, '-')
    .replace(/\s/g, '')
  return normalized.split(',').some((part) => {
    const parity = part.includes('单') ? 1 : part.includes('双') ? 0 : undefined
    if (parity !== undefined && week % 2 !== parity) return false
    const range = part.match(/(\d+)-(\d+)/)
    if (range)
      return (
        week >= Math.min(Number(range[1]), Number(range[2])) &&
        week <= Math.max(Number(range[1]), Number(range[2]))
      )
    const n = part.match(/\d+/)
    return n
      ? Number(n[0]) === week
      : part.includes('全') || parity !== undefined
  })
}
export function downloadBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
export function downloadCsv(
  name: string,
  rows: Array<Array<string | number | null | undefined>>,
) {
  const csv = rows
    .map((row) =>
      row
        .map((v) => {
          const text = String(v ?? '')
          // Treat text as a spreadsheet value, never as a formula on opening the export.
          const safe = /^[=+\-@\t\r]/.test(text) ? `'${text}` : text
          return `"${safe.replaceAll('"', '""')}"`
        })
        .join(','),
    )
    .join('\r\n')
  downloadBlob(
    new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8' }),
    name + '.csv',
  )
}
