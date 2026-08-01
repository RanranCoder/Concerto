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
}

export const latestPlan = (activityId: number) =>
  get<ActivityPlanVO | null>('/plans', { activityId })
export const getPlan = (id: number) => get<ActivityPlanVO>(`/plans/${id}`)
export const createPlan = (data: PlanSave) => post<ActivityPlanVO>('/plans', data)
export const updatePlan = (id: number, data: PlanSave) => put<void>(`/plans/${id}`, data)
export const submitPlan = (id: number) => put<void>(`/plans/${id}/submit`)
export const reviewPlan = (id: number, approved: boolean, comment?: string) =>
  put<void>(`/plans/${id}/review`, { approved, comment })
