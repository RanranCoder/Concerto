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

/** 活动详情聚合：策划书 / 议程 / 座位表 / 评分 / 签到 / 任务（后端 ActivityDetailService） */
export interface ActivityDetail {
  activity: ActivityVO & { targetAudience?: string; description?: string }
  plan: { latest: ActivityPlanVO | null; status: string | null } | null
  agendas: ActivityAgendaVO[]
  seatZones: Record<string, SeatMapVO[]>
  score: { rules: ScoreRuleVO[]; records: ScoreRecordVO[] }
  signinCount: number
  tasks: unknown[]
}

export interface ActivityPlanVO {
  id: number
  activityId: number
  version: number | null
  background: string | null
  purpose: string | null
  content: string | null
  flow: string | null
  notice: string | null
  emergency: string | null
  budget: string | null
  nameOverride: string | null
  themeOverride: string | null
  timeOverride: string | null
  locationOverride: string | null
  organizerOverride: string | null
  targetOverride: string | null
  sectionOrder: string | null
  status: 'DRAFT' | 'PENDING' | 'APPROVED' | 'REJECTED'
  submitterId: number | null
  reviewerId: number | null
  reviewComment: string | null
  createdAt: string
  updatedAt: string
}

export interface ActivityAgendaVO {
  id: number
  activityId: number
  stepNo: number
  title: string
  remark: string | null
  createdAt: string
}

export interface SeatMapVO {
  id: number
  activityId: number
  roomName: string | null
  zone: string
  rowNo: number | null
  colNo: number | null
  personName: string | null
  seatType: string | null
  createdAt: string
}

export interface ScoreRuleVO {
  id: number
  activityId: number
  dimensionName: string
  fullMarks: number
  sortOrder: number | null
}

export interface ScoreRecordVO {
  id: number
  activityId: number
  teamName: string
  groupName: string | null
  /** JSON 字符串：{dimensionId: score} */
  dimensionScores: string | null
  total: number | null
  rankNo: number | null
  remark: string | null
  createdAt: string
}

export const getActivityDetail = (id: number) => get<ActivityDetail>(`/activities/${id}/detail`)
export const createActivity = (data: ActivitySave) => post<number>('/activities', data)
export const updateActivity = (id: number, data: ActivitySave) => put<void>(`/activities/${id}`, data)
export const changeActivityStatus = (id: number, status: string) => put<void>(`/activities/${id}/status`, { status })
export const deleteActivity = (id: number) => del<void>(`/activities/${id}`)
