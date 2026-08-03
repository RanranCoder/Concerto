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

export interface SigninToken {
  token: string
  activityId: number
  expiresAt: string
  /** 扫码 URL：{origin}/signin/{token}，由后端依据请求 origin 拼好 */
  qrContent: string
}

export const listSignins = (activityId: number, keyword?: string) =>
  get<SigninVO[]>('/signins', { activityId, keyword })
export const createSignin = (data: SigninSave) => post<SigninVO>('/signins', data)
export const deleteSignin = (id: number) => del<void>(`/signins/${id}`)
export const countSignins = (activityId: number) =>
  get<number>(`/signins/${activityId}/count`)
export const generateSigninToken = (activityId: number) =>
  post<SigninToken>('/signins/token', { activityId })
