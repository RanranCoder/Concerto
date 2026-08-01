import dayjs from 'dayjs'

export interface GanttTask {
  id: number
  name: string
  startDate: string
  endDate: string
  dependsOn: number | null
  deptName?: string
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
