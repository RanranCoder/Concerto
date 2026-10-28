import dayjs from 'dayjs'

export interface GanttTask {
  id: number
  name: string
  startDate: string
  endDate: string
  dependsOn: number | null
  deptName?: string
  deptId?: number | null
  isMilestone?: boolean
  progress?: number
  assignee?: string
}

export function dayRange(start: string, end: string): number {
  return dayjs(end).add(1, 'day').diff(dayjs(start), 'day')
}

/**
 * 任务条像素定位。left 为相对项目起点（range.start）的天数偏移 * pxPerDay，
 * 由调用方（GanttChart）用 dayjs(startDate).diff(dayjs(range.start), 'day') 计算 startOffsetDays。
 * 注：简报原始实现引用未定义的 projectStart(task)（恒等于 startDate 导致 left 恒为 0），此处内联修正。
 */
export function taskToPixels(task: GanttTask, pxPerDay: number, startOffsetDays: number) {
  const left = startOffsetDays * pxPerDay
  return { left, width: Math.max(dayRange(task.startDate, task.endDate), 1) * pxPerDay }
}

/**
 * 依赖边。参数放宽为「含 id/dependsOn 的最小形状」，便于测试与 GanttTask 共用。
 */
export function buildDeps<T extends { id: number; dependsOn: number | null }>(tasks: T[]): Array<{ from: number; to: number }> {
  const byId = new Map(tasks.map((t) => [t.id, t]))
  const edges: Array<{ from: number; to: number }> = []
  for (const t of tasks) {
    if (t.dependsOn != null && byId.has(t.dependsOn)) edges.push({ from: t.dependsOn, to: t.id })
  }
  return edges
}

export function todayStr(): string {
  return dayjs().format('YYYY-MM-DD')
}

export type TaskDates = { startDate: string | null; endDate: string | null; progress?: number | null }
export function isScheduled(task: TaskDates): boolean {
  return !!(task.startDate && task.endDate && dayjs(task.startDate).isValid() && dayjs(task.endDate).isValid() && task.startDate <= task.endDate)
}
export function isOverdue(task: TaskDates, today = todayStr()): boolean {
  return isScheduled(task) && task.endDate! < today && (task.progress ?? 0) < 100
}
export function hasDependencyConflict<T extends TaskDates & { id: number; dependsOn: number | null }>(task: T, tasks: T[]): boolean {
  const parent = tasks.find(t => t.id === task.dependsOn)
  return isScheduled(task) && !!parent && isScheduled(parent) && task.startDate! <= parent.endDate!
}
export function taskStatus(task: TaskDates, today = todayStr()): string {
  if ((task.progress ?? 0) >= 100) return '已完成'
  if (!isScheduled(task)) return '未排期'
  if (isOverdue(task, today)) return '已逾期'
  return task.startDate! > today && !(task.progress ?? 0) ? '待开始' : '进行中'
}
export function taskTone<T extends TaskDates & { id: number; dependsOn: number | null }>(task: T, tasks: T[], today = todayStr()): string {
  if ((task.progress ?? 0) >= 100) return 'done'
  if (hasDependencyConflict(task, tasks) || isOverdue(task, today)) return 'risk'
  return !isScheduled(task) || taskStatus(task, today) === '待开始' ? 'waiting' : 'active'
}
export function wouldCycle(tasks: Array<{ id: number; dependsOn: number | null }>, id: number | undefined, predecessor: number | null): boolean {
  const seen = new Set<number>(id === undefined ? [] : [id])
  let next = predecessor
  while (next !== null) {
    if (seen.has(next)) return true
    seen.add(next)
    next = tasks.find(t => t.id === next)?.dependsOn ?? null
  }
  return false
}

export function dateLabel(value?: string | null): string { return value ? dayjs(value).format('M/D') : '未排期' }
export function taskDateLabel(task: TaskDates): string {
  return isScheduled(task) ? `${dateLabel(task.startDate)} — ${dateLabel(task.endDate)}` : '未排期'
}
