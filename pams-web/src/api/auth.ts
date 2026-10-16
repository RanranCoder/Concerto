import { get, post } from './http'

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
    orgId: number | null
    orgName: string | null
    permissions: string[]
    businessPermissions: string[]
    managementCapabilities: string[]
    isOwner: boolean
    authzVersion: number
    roleId: number
  }
}

export const loginApi = (params: LoginParams) => post<LoginResponse>('/auth/login', params)
export const meApi = () => get<LoginResponse['user']>('/auth/me')
