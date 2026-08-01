import { get, post, put, del } from './http'
import type { PageResult } from './types'

export interface ActivityVO {
  id: number
  name: string
  theme: string
  type: string
  status: string
  startDate: string | null
  endDate: string | null
  location: string
  organizer: string
  host: string
  leader: string
  createdAt: string
}

export interface ActivitySave {
  name: string
  theme?: string
  type?: string
  startDate?: string | null
  endDate?: string | null
  location?: string
  organizer?: string
  targetAudience?: string
  host?: string
  leader?: string
  description?: string
}

export const listActivities = (params: { keyword?: string; status?: string; type?: string; page?: number; size?: number }) =>
  get<PageResult<ActivityVO>>('/activities', params)
export const getActivity = (id: number) => get<ActivityVO>(`/activities/${id}`)
export const createActivity = (data: ActivitySave) => post<number>('/activities', data)
export const updateActivity = (id: number, data: ActivitySave) => put<void>(`/activities/${id}`, data)
export const changeActivityStatus = (id: number, status: string) => put<void>(`/activities/${id}/status`, { status })
export const deleteActivity = (id: number) => del<void>(`/activities/${id}`)
