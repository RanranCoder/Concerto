import { get, post, del } from './http'

export interface SigninVO {
  id: number
  activityId: number
  personId: number | null
  name: string
  studentNo: string | null
  className: string | null
  identityType: string | null
  signType: 'MANUAL' | 'SCAN' | null
  signTime: string | null
  location: string | null
  phone: string | null
  remark: string | null
  createdAt: string
}

export interface SigninSave {
  activityId: number
  personId?: number | null
  name: string
  studentNo?: string | null
  className?: string | null
  identityType?: string | null
  signType?: 'MANUAL' | 'SCAN'
  signTime?: string | null
  location?: string | null
  phone?: string | null
  remark?: string | null
}

export const listSignins = (activityId: number, keyword?: string) =>
  get<SigninVO[]>('/signins', { activityId, keyword })
export const createSignin = (data: SigninSave) => post<SigninVO>('/signins', data)
export const deleteSignin = (id: number) => del<void>(`/signins/${id}`)
export const countSignins = (activityId: number) =>
  get<number>(`/signins/${activityId}/count`)
