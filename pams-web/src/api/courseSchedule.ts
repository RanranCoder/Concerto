import { get, post, put } from './http'

// ==================== F08 无课表 ====================

export interface ScheduleConfigVO {
  id: number
  period: number
  startTime: string | null
  endTime: string | null
  label: string
}

export interface CourseCell {
  dayOfWeek: number
  period: number
  courseName?: string
}

export interface MyScheduleVO {
  semester: string
  matrix: Record<number, Record<number, string>>
  count: number
}

export interface FreeTimeUserVO {
  id: number
  realName: string
  deptName: string
}

export interface OptimalSlotVO {
  dayOfWeek: number
  period: number
  label: string
  freeCount: number
  allFree: boolean
}

export interface FreeTimeAnalysisVO {
  semester: string
  periods: ScheduleConfigVO[]
  users: FreeTimeUserVO[]
  heatmap: Record<string, Record<number, number>>
  optimal: OptimalSlotVO[]
}

export const getScheduleConfigs = () => get<ScheduleConfigVO[]>('/course-schedules/configs')
export const saveScheduleConfigs = (configs: ScheduleConfigVO[]) =>
  put<void>('/course-schedules/configs', configs)

export const getMySchedule = (semester?: string) =>
  get<MyScheduleVO>('/course-schedules/mine', { semester })
export const saveMySchedule = (semester: string, cells: CourseCell[]) =>
  post<void>('/course-schedules/mine', { semester, cells })

export const analyzeFreeTime = (semester?: string, userIds?: number[]) =>
  get<FreeTimeAnalysisVO>('/course-schedules/analyze', { semester, userIds: userIds?.join(',') })
