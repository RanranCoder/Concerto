import { post } from './http'

export interface LoginParams { username: string; password: string }
export interface LoginResponse {
  token: string
  user: {
    id: number
    username: string
    realName: string
    roleCode: string
    roleLevel: number
    deptId: number | null
    deptName: string | null
    permissions: string[]
  }
}

export const loginApi = (params: LoginParams) => post<LoginResponse>('/auth/login', params)
