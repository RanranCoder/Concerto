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
  missingCount?: number
  unboundNames?: string[]
  conflictingNames?: string[]
  existingShiftIds?: number[]
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

export interface GenerateScheduleRequest {
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
}

export interface GenerationReview {
  fingerprint: string
  days: GenerateResult[]
  draftCount: number
}

export const previewActivitySchedules = (request: GenerateScheduleRequest) =>
  post<GenerationReview>('/activity-schedules/generate/preview', request)

export const confirmActivitySchedules = (request: GenerateScheduleRequest, fingerprint: string) =>
  post<GenerationReview>('/activity-schedules/generate/confirm', { request, fingerprint })

export const createActivitySchedule = (data: ActivityScheduleSave) => post<number>('/activity-schedules', data)
export const updateActivitySchedule = (id: number, data: ActivityScheduleSave) => put<void>(`/activity-schedules/${id}`, data)
export const deleteActivitySchedule = (id: number) => del<void>(`/activity-schedules/${id}`)
export const publishActivitySchedule = (id: number) => post<void>(`/activity-schedules/${id}/publish`)
export const publishAllActivitySchedules = (activityId?: number) =>
  post<number>('/activity-schedules/publish-all', undefined, { params: { activityId } })
export const cancelActivitySchedule = (id: number) => post<void>(`/activity-schedules/${id}/cancel`)
export const regenerateActivitySchedule = (id: number, deptId?: number, semester?: string, semesterStartDate?: string) =>
  post<number>(`/activity-schedules/${id}/re-generate`, undefined, { params: { deptId, semester, semesterStartDate } })
