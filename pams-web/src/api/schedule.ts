import { get, post, put, del, http } from './http'

/** 排班类型：SMOKING_CURB 控烟/CLASS_DUTY 值班/BOOTH 摆摊/ARCHIVE 档案整理/STAMP 盖章/CLASS_CHECK 教学楼检查 */
export const SCHEDULE_TYPES = ['SMOKING_CURB', 'CLASS_DUTY', 'BOOTH', 'ARCHIVE', 'STAMP', 'CLASS_CHECK'] as const
export type ScheduleType = (typeof SCHEDULE_TYPES)[number]

export const SCHEDULE_TYPE_MAP: Record<string, string> = {
  SMOKING_CURB: '控烟',
  CLASS_DUTY: '办公室值班',
  BOOTH: '摆摊',
  ARCHIVE: '档案整理',
  STAMP: '盖章',
  CLASS_CHECK: '教学楼检查',
}

export const SCHEDULE_TYPE_OPTIONS = SCHEDULE_TYPES.map((v) => ({ value: v, label: SCHEDULE_TYPE_MAP[v] }))

export const WEEKDAY_OPTIONS = [
  { value: 1, label: '周一' },
  { value: 2, label: '周二' },
  { value: 3, label: '周三' },
  { value: 4, label: '周四' },
  { value: 5, label: '周五' },
  { value: 6, label: '周六' },
  { value: 7, label: '周日' },
]

export const WEEKDAY_NAMES = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

export interface SchedulePersonVO {
  id: number
  scheduleId: number
  userId: number | null
  personName: string
  /** 1 主班 / 0 副班 */
  isPrimary: number | null
  createdAt: string
}

export interface ScheduleVO {
  id: number
  scheduleType: string
  activityId: number | null
  weekNo: number | null
  /** 1-7 周一~周日 */
  weekday: number | null
  /** 节次或时间段，如 上午第1-2节 / 9:00-9:10 */
  sessionName: string | null
  location: string | null
  scheduleDate: string | null
  notes: string | null
  createdAt: string
  updatedAt: string
  persons: SchedulePersonVO[]
}

export interface SchedulePersonItem {
  userId?: number | null
  personName: string
  isPrimary?: number | null
}

export interface ScheduleSave {
  scheduleType: string
  activityId?: number | null
  weekNo?: number | null
  weekday?: number | null
  sessionName?: string | null
  location?: string | null
  scheduleDate?: string | null
  notes?: string | null
  persons?: SchedulePersonItem[]
}

export const listSchedules = (params: { type?: string; weekNo?: number; weekday?: number; activityId?: number }) =>
  get<ScheduleVO[]>('/schedules', params)
export const createSchedule = (data: ScheduleSave) => post<number>('/schedules', data)
export const updateSchedule = (id: number, data: ScheduleSave) => put<void>(`/schedules/${id}`, data)
export const deleteSchedule = (id: number) => del<void>(`/schedules/${id}`)

/** 导出值班表 xlsx：responseType blob 下载，走原生 http（跳过 Result 解包） */
export const exportSchedule = (params: { type?: string; weekNo?: number }) =>
  http.get('/schedules/export', { params, responseType: 'blob' })
