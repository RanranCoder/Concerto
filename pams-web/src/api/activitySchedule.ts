import { get, post, put, del } from './http'
import type { ScheduleVO } from './schedule'

export interface SchedulePersonItem {
  userId?: number | null
  personName: string
  isPrimary?: number
}

export interface ActivityScheduleSave {
  activityId: number
  scheduleDate: string
  startTime: string
  endTime: string
  location?: string | null
  note?: string | null
  persons?: SchedulePersonItem[]
}

export interface GenerateSlot {
  period: number
  label: string | null
  startTime: string | null
  endTime: string | null
  count: number
  persons: Array<{ name: string; userId: number }>
}

export interface GenerateResult {
  date: string
  count: number
  persons: Array<{ name: string; userId: number }>
  slots: GenerateSlot[]
}

export const listActivitySchedules = (params: { activityId?: number; status?: string; date?: string }) =>
  get<ScheduleVO[]>('/activity-schedules', params)

/** 已上传无课表的学期列表 */
export const listSemesters = () => get<string[]>('/activity-schedules/semesters')

export const generateActivitySchedules = (data: {
  activityId?: number
  customActivityName?: string
  startDate: string
  endDate: string
  startTime: string
  endTime: string
  deptIds?: number[]
  peoplePerSlot?: number
  semester?: string
  semesterStartDate?: string | null
}) => post<GenerateResult[]>('/activity-schedules/generate', data)

export const createActivitySchedule = (data: ActivityScheduleSave) => post<number>('/activity-schedules', data)
export const updateActivitySchedule = (id: number, data: ActivityScheduleSave) => put<void>(`/activity-schedules/${id}`, data)
export const deleteActivitySchedule = (id: number) => del<void>(`/activity-schedules/${id}`)
export const publishActivitySchedule = (id: number) => post<void>(`/activity-schedules/${id}/publish`)
export const publishAllActivitySchedules = (activityId?: number) =>
  post<number>('/activity-schedules/publish-all', undefined, { params: { activityId } })
export const cancelActivitySchedule = (id: number) => post<void>(`/activity-schedules/${id}/cancel`)
export const regenerateActivitySchedule = (id: number, deptId?: number, semester?: string) =>
  post<number>(`/activity-schedules/${id}/re-generate`, undefined, { params: { deptId, semester } })