import { get, post, put, del } from './http'
import type { PageResult } from './types'

export interface CreditVO {
  id: number
  userId: number | null
  personName: string
  studentNo: string
  activityId: number | null
  project: string
  /** 分值（0 ~ 99.99，两位小数） */
  credit: number
  basis: string
  remark: string
  recordBy: number | null
  createdAt: string
}

export interface CreditSave {
  userId?: number | null
  personName: string
  studentNo?: string | null
  activityId?: number | null
  project: string
  credit: number
  basis?: string | null
  remark?: string | null
}

/** 加分依据 */
export const CREDIT_BASIS_OPTIONS = [
  { value: 'PARTICIPATE', label: '参与' },
  { value: 'ANSWER', label: '答题' },
]

export const listCredits = (params: { keyword?: string; page?: number; size?: number }) =>
  get<PageResult<CreditVO>>('/credits', params)
export const createCredit = (data: CreditSave) => post<CreditVO>('/credits', data)
export const updateCredit = (id: number, data: CreditSave) => put<void>(`/credits/${id}`, data)
export const deleteCredit = (id: number) => del<void>(`/credits/${id}`)
