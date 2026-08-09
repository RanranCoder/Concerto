import { get, post, put, http } from './http'
import type { AxiosResponse } from 'axios'

// ==================== F08 无课表 ====================

export interface NoClassScheduleCellVO {
  name: string
  freeWeeks: string
}
export interface NoClassScheduleRowVO {
  period: number
  label: string
  halfDay: string
  /** day "1".."5" -> 该列人员 */
  days: Record<string, NoClassScheduleCellVO[]>
}
export interface ImportFileFailureVO {
  fileName: string
  reason: string
}
export interface NoClassScheduleImportVO {
  deptName: string
  semester: string
  rows: NoClassScheduleRowVO[]
  markdown: string
  downloadUrl: string
  totalFiles: number
  successCount: number
  failed: ImportFileFailureVO[]
  warnings: string[]
}

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

// ==================== F09 批量导入无课表 ====================

/** 批量上传课表生成无课表（FormData: files[] + deptId + semester + maxWeek） */
export const importNoClassSchedules = (formData: FormData) =>
  post<NoClassScheduleImportVO>('/course-schedules/import', formData)

/** 下载生成的 xlsx（responseType blob，拦截器对 blob 原样返回） */
export const downloadNoClassScheduleXlsx = (downloadUrl: string) =>
  http.get('/course-schedules/import/download', {
    params: { path: downloadUrl },
    responseType: 'blob',
  }) as unknown as Promise<AxiosResponse<Blob>>
