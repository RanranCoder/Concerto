import { get } from './http'
import type { PageResult } from './types'

export interface UserVO {
  id: number
  username: string
  realName: string
  studentNo: string
  phone: string
  deptId: number | null
  deptName: string | null
  roleCode: string | null
  roleName: string | null
  status: number | null
}

export const listUsers = (params: { keyword?: string; deptId?: number; page?: number; size?: number }) =>
  get<PageResult<UserVO>>('/users', params)
