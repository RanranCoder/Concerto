import { get, post, put } from './http'
import type { ActivityPlanVO } from './activity'

export interface PlanSave {
  activityId: number
  version?: number | null
  background?: string | null
  purpose?: string | null
  content?: string | null
  /** JSON 字符串，如 [{step,detail}] */
  flow?: string | null
  notice?: string | null
  emergency?: string | null
  /** JSON 字符串，如 [{item,quantity,unitPrice,totalPrice}] */
  budget?: string | null
  /** 只读章节可覆盖值（活动基本信息） */
  nameOverride?: string | null
  themeOverride?: string | null
  timeOverride?: string | null
  locationOverride?: string | null
  organizerOverride?: string | null
  targetOverride?: string | null
  /** 章节顺序 + 自定义节名 JSON */
  sectionOrder?: string | null
  /** 是否同步更新活动基本信息（用户弹窗确认） */
  syncActivity?: boolean
}

export const latestPlan = (activityId: number) =>
  get<ActivityPlanVO | null>('/plans', { activityId })
export const getPlan = (id: number) => get<ActivityPlanVO>(`/plans/${id}`)
export const createPlan = (data: PlanSave) => post<ActivityPlanVO>('/plans', data)
export const updatePlan = (id: number, data: PlanSave) => put<void>(`/plans/${id}`, data)
export const submitPlan = (id: number) => put<void>(`/plans/${id}/submit`)
export const reviewPlan = (id: number, approved: boolean, comment?: string) =>
  put<void>(`/plans/${id}/review`, { approved, comment })
