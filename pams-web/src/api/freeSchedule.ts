import { get, post, put, del } from './http'

export interface FreeScheduleVO {
  id: number
  userId: number | null
  personName: string
  className: string | null
  deptId: number | null
  /** JSON 文本，如 [1,3,5] 或 {start:1,end:18} */
  freeWeeks: string | null
  note: string | null
  createdAt: string
}

export interface FreeScheduleSave {
  userId?: number | null
  personName: string
  className?: string | null
  deptId?: number | null
  freeWeeks?: string | null
  note?: string | null
}

export const listFreeSchedules = (params?: { deptId?: number }) => get<FreeScheduleVO[]>('/free-schedules', params)
export const createFreeSchedule = (data: FreeScheduleSave) => post<number>('/free-schedules', data)
export const updateFreeSchedule = (id: number, data: FreeScheduleSave) => put<void>(`/free-schedules/${id}`, data)
export const deleteFreeSchedule = (id: number) => del<void>(`/free-schedules/${id}`)
