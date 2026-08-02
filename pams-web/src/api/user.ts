import { get, post, put, del } from './http'
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

/** 新增/编辑用户请求体（对应后端 UserSaveRequest：username/realName 必填，roleId 必填，deptId 可为 null） */
export interface UserSave {
  username: string
  password?: string
  realName: string
  studentNo?: string | null
  phone?: string | null
  deptId?: number | null
  roleId: number
  status?: number
}

/** 角色（后端 sys_role） */
export interface RoleVO {
  id: number
  code: string
  name: string
  level: number
  dataScope: string
}

export const listUsers = (params: { keyword?: string; deptId?: number; page?: number; size?: number }) =>
  get<PageResult<UserVO>>('/users', params)

export const createUser = (data: UserSave) => post<number>('/users', data)
export const updateUser = (id: number, data: UserSave) => put<void>(`/users/${id}`, data)
export const deleteUser = (id: number) => del<void>(`/users/${id}`)
export const resetPassword = (id: number) => post<void>(`/users/${id}/reset-password`)

/** 角色下拉（角色-部门联动需要 role.code） */
export const listRoles = () => get<RoleVO[]>('/roles')
